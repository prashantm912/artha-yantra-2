package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Read-path degradation (task_e2e01e): the LIVE chain degrades to the last CAPTURED chain instead
 * of 503-ing every evening (the cockpit's Option-chain panel blanked after every close), while the
 * WRITE path keeps refusing — a capture that degraded would persist the stale book back as a fresh
 * row and freeze OI.
 *
 * <p>Doctrine: entries need fresh truth (you can always NOT enter), reads need the best available
 * truth (you cannot refuse to read forever). Degrading is the goal, lying is not — so the served
 * chain is explicitly marked {@code lastCaptured} with the CAPTURE {@code asOf}, and with nothing
 * captured at all the 503 stands.
 */
class OptionsChainLastCapturedTest {

  // Mon 2026-06-15, one day before the expiry under test. 11:00 IST = open, 18:00 IST = closed.
  private static final Clock OPEN =
      Clock.fixed(OffsetDateTime.parse("2026-06-15T11:00:00+05:30").toInstant(), ZoneOffset.UTC);
  private static final Clock CLOSED =
      Clock.fixed(OffsetDateTime.parse("2026-06-15T18:00:00+05:30").toInstant(), ZoneOffset.UTC);
  private static final LocalDate EXPIRY = LocalDate.parse("2026-06-16");
  // the instrument master carries the scale-0 strike; the snapshot table's NUMERIC comes back at
  // scale 2 — the (strike, side) match must be scale-insensitive or every leg silently misses.
  private static final BigDecimal STRIKE = new BigDecimal("23800");
  private static final BigDecimal SNAPSHOT_STRIKE = new BigDecimal("23800.00");
  private static final OffsetDateTime CAPTURED_AT =
      OffsetDateTime.parse("2026-06-15T15:30:00+05:30");
  private static final MarketCalendar CAL =
      MarketCalendar.of(List.of(LocalDate.parse("2026-01-26")));

  private static final Instrument CE =
      new Instrument(
          "NFO", "NIFTY26JUN23800CE", 1L, "NIFTY", "NFO-OPT", "CE", "NSE", "NIFTY 50", EXPIRY,
          STRIKE, new BigDecimal("0.05"), 75, true);
  private static final Instrument PE =
      new Instrument(
          "NFO", "NIFTY26JUN23800PE", 2L, "NIFTY", "NFO-OPT", "PE", "NSE", "NIFTY 50", EXPIRY,
          STRIKE, new BigDecimal("0.05"), 75, true);

  private static InstrumentRepository instrumentStub() {
    return new InstrumentRepository(null) {
      @Override
      public List<LocalDate> expiries(String underlying) {
        return List.of(EXPIRY);
      }

      @Override
      public List<Instrument> optionChain(String underlying, LocalDate expiry) {
        return List.of(CE, PE);
      }
    };
  }

  /** Post-close reality: the feed answers nothing at all, so there is no live spot. */
  private static final QuoteGateway NO_QUOTES = keys -> Map.of();

  /** In-session: the underlying and both legs quote normally. */
  private static final QuoteGateway LIVE_QUOTES =
      keys -> {
        Map<InstrumentKey, QuoteGateway.Quote> out = new HashMap<>();
        for (InstrumentKey k : keys) {
          if (k.tradingsymbol().equals("NIFTY 50")) {
            out.put(
                k,
                new QuoteGateway.Quote(
                    k, new BigDecimal("23845.85"), OffsetDateTime.now(OPEN)));
          } else {
            out.put(
                k,
                new QuoteGateway.Quote(
                    k,
                    new BigDecimal("120.50"),
                    new BigDecimal("120.00"),
                    new BigDecimal("121.00"),
                    1000L,
                    500_000L,
                    OffsetDateTime.now(OPEN)));
          }
        }
        return out;
      };

  /** One captured CE+PE pair at 15:30, provenance-complete exactly as the capture pass writes it. */
  private static OptionsSnapshotRepository capturedStub() {
    return new OptionsSnapshotRepository(null) {
      @Override
      public Optional<OffsetDateTime> latestSnapshotTs(String underlying, LocalDate expiry) {
        return Optional.of(CAPTURED_AT);
      }

      @Override
      public List<SnapshotRow> rowsAt(String underlying, LocalDate expiry, OffsetDateTime ts) {
        return List.of(
            capturedRow("CE", "NIFTY26JUN23800CE", "133.45", 1_820_075L, "0.181500"),
            capturedRow("PE", "NIFTY26JUN23800PE", "255.75", 2_105_500L, "0.194200"));
      }
    };
  }

  private static OptionsSnapshotRepository.SnapshotRow capturedRow(
      String optionType, String tradingsymbol, String ltp, long oi, String iv) {
    return new OptionsSnapshotRepository.SnapshotRow(
        CAPTURED_AT,
        "NIFTY 50",
        EXPIRY,
        SNAPSHOT_STRIKE,
        optionType,
        tradingsymbol,
        new BigDecimal(ltp),
        new BigDecimal("133.00"),
        new BigDecimal("133.90"),
        345_600L,
        oi,
        12_345L,
        new BigDecimal("23901.20"),
        new BigDecimal(iv),
        new BigDecimal("0.512300"),
        new BigDecimal("0.000210"),
        new BigDecimal("-8.441000"),
        new BigDecimal("12.330000"),
        new BigDecimal("3.100000"),
        "SOLVED",
        "MID",
        new BigDecimal("23917.44"),
        new BigDecimal("0.065000"));
  }

  /** Nothing was ever captured for this (underlying, expiry). */
  private static OptionsSnapshotRepository noCaptureStub() {
    return new OptionsSnapshotRepository(null) {
      @Override
      public Optional<OffsetDateTime> latestSnapshotTs(String underlying, LocalDate expiry) {
        return Optional.empty();
      }

      @Override
      public List<SnapshotRow> rowsAt(String underlying, LocalDate expiry, OffsetDateTime ts) {
        throw new AssertionError("rowsAt must not be reached with no capture anchor");
      }
    };
  }

  /** Proves the capture store is not touched at all while a live spot exists. */
  private static OptionsSnapshotRepository forbiddenStub() {
    return new OptionsSnapshotRepository(null) {
      @Override
      public Optional<OffsetDateTime> latestSnapshotTs(String underlying, LocalDate expiry) {
        throw new AssertionError("the live path must never consult the capture store");
      }

      @Override
      public List<SnapshotRow> rowsAt(String underlying, LocalDate expiry, OffsetDateTime ts) {
        throw new AssertionError("the live path must never consult the capture store");
      }
    };
  }

  private static OptionsChainService service(
      QuoteGateway gateway, Clock clock, OptionsSnapshotRepository snapshots) {
    return new OptionsChainService(
        instrumentStub(),
        gateway,
        CAL,
        clock,
        new BigDecimal("0.065"),
        true,
        Optional.empty(),
        snapshots);
  }

  /** (a) No live spot + a captured chain ⇒ 200 with the staleness marker and the CAPTURE asOf. */
  @Test
  void withoutLiveSpotTheReadPathServesTheLastCapturedChain() {
    OptionsChainService.Chain chain =
        service(NO_QUOTES, CLOSED, capturedStub()).chainOrLastCaptured("NIFTY 50", EXPIRY);

    assertThat(chain.lastCaptured()).as("the staleness marker is explicit, never implied").isTrue();
    assertThat(chain.asOf())
        .as("asOf is the CAPTURE time, not now — the marker would otherwise be unreadable")
        .isEqualTo(CAPTURED_AT);
    assertThat(chain.spot()).isEqualByComparingTo("23901.20");
    assertThat(chain.forward()).as("the captured forward VALUE is real").isEqualByComparingTo("23917.44");
    assertThat(chain.forwardSource())
        .as("the forward's precedence rule is not persisted, so it is not claimed")
        .isEqualTo("CAPTURED");
    // ΣPE OI / ΣCE OI over the captured book
    assertThat(chain.pcr()).isEqualByComparingTo("1.1568");

    assertThat(chain.rows()).hasSize(1);
    OptionsChainService.Leg ce = chain.rows().get(0).ce();
    assertThat(ce).as("scale-0 master strike must match the scale-2 snapshot strike").isNotNull();
    assertThat(ce.exchange()).as("the canonical instrument key survives the degrade").isEqualTo("NFO");
    assertThat(ce.tradingsymbol()).isEqualTo("NIFTY26JUN23800CE");
    assertThat(ce.ltp()).isEqualByComparingTo("133.45");
    assertThat(ce.bid()).isEqualByComparingTo("133.00");
    assertThat(ce.oi()).isEqualTo(1_820_075L);
    assertThat(ce.iv()).as("the captured solved IV is served, not recomputed").isEqualByComparingTo("0.181500");
    assertThat(ce.delta()).isEqualByComparingTo("0.512300");
    assertThat(ce.ivReason()).isEqualTo("SOLVED");
    assertThat(ce.priceSource()).isEqualTo("MID");
    assertThat(ce.vanna()).as("second-order greeks are live-only, never fabricated").isNull();
    assertThat(ce.prevOi()).as("prevOi is an Upstox live field, never persisted").isNull();
    assertThat(chain.rows().get(0).pe().oi()).isEqualTo(2_105_500L);
  }

  /** (b) No live spot + NO captured chain ⇒ the genuine 503 DATA_STALE stands. */
  @Test
  void withoutLiveSpotAndWithoutAnyCaptureTheReadPathStillRefuses() {
    OptionsChainService service = service(NO_QUOTES, CLOSED, noCaptureStub());

    assertThatThrownBy(() -> service.chainOrLastCaptured("NIFTY 50", EXPIRY))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(503);
              assertThat(e.code()).isEqualTo(ErrorCodes.DATA_STALE);
              assertThat(e.getMessage()).isEqualTo("no spot quote for NIFTY 50");
            });
  }

  /** (c) A live spot present ⇒ behaviour unchanged, marker not set, capture store never read. */
  @Test
  void withLiveSpotTheReadPathIsUnchangedAndUnmarked() {
    OptionsChainService.Chain chain =
        service(LIVE_QUOTES, OPEN, forbiddenStub()).chainOrLastCaptured("NIFTY 50", EXPIRY);

    assertThat(chain.lastCaptured()).isFalse();
    assertThat(chain.stale()).isFalse();
    assertThat(chain.asOf()).isEqualTo(OffsetDateTime.now(OPEN));
    assertThat(chain.spot()).isEqualByComparingTo("23845.85");
    assertThat(chain.forwardSource()).isNotEqualTo("CAPTURED");
    assertThat(chain.rows().get(0).ce().oi()).isEqualTo(500_000L);
  }

  /**
   * The WRITE path never degrades even though a capture exists — otherwise the 2-min capture pass
   * would re-persist the last captured book as a fresh row and freeze OI for the rest of the day.
   */
  @Test
  void theStrictChainStillRefusesSoCaptureNeverFeedsOnItsOwnOutput() {
    OptionsChainService service = service(NO_QUOTES, CLOSED, capturedStub());

    assertThatThrownBy(() -> service.chain("NIFTY 50", EXPIRY))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(503);
              assertThat(e.code()).isEqualTo(ErrorCodes.DATA_STALE);
            });
  }

  /**
   * {@code stale} (market not open) and {@code lastCaptured} (rows are the captured book) are
   * orthogonal: an in-session feed gap degrades with {@code stale=false}, which is exactly why one
   * boolean cannot carry both meanings.
   */
  @Test
  void anInSessionFeedGapDegradesWithoutClaimingTheMarketIsClosed() {
    OptionsChainService.Chain chain =
        service(NO_QUOTES, OPEN, capturedStub()).chainOrLastCaptured("NIFTY 50", EXPIRY);

    assertThat(chain.stale()).as("the market IS open").isFalse();
    assertThat(chain.lastCaptured()).as("the data is not").isTrue();
    assertThat(chain.asOf()).isEqualTo(CAPTURED_AT);
  }

  // ── call-site pins ────────────────────────────────────────────────────────────────────────────
  // The two entry points are one identifier apart, so a one-line edit either silently undoes the
  // whole feature (read path back on strict chain() ⇒ 503 every evening again) or silently breaks
  // capture (write path on the degrading entry ⇒ the 2-min pass re-persists the stale book as a
  // fresh row and freezes OI). Neither is reachable from a service-level unit test — both sides
  // behave correctly in isolation — so the wiring itself is pinned here, the same pure-file way
  // MapReturnRatchetTest pins the contract surface. No containers, no Spring context.

  private static final Path READ_PATH_CONTROLLERS_ROOT =
      Path.of("src/main/java/in/arthayantra/marketdata/options");

  @Test
  void everyReadEndpointUsesTheDegradingEntryPoint() throws IOException {
    for (String controller :
        List.of("OptionsChainController.java", "analytics/OptionsAnalyticsController.java")) {
      String source = Files.readString(READ_PATH_CONTROLLERS_ROOT.resolve(controller));
      assertThat(source)
          .as("%s must serve reads through chainOrLastCaptured", controller)
          .contains("chainService.chainOrLastCaptured(");
      assertThat(source)
          .as(
              "%s calls the strict chainService.chain(...) — that is the WRITE-path entry and it"
                  + " 503s whenever there is no live spot, which blanks the panel every evening."
                  + " Read paths must call chainOrLastCaptured(...).",
              controller)
          .doesNotContain("chainService.chain(");
    }
  }

  @Test
  void theCaptureAndBroadcastPassStayOnTheStrictEntryPoint() throws IOException {
    String source = Files.readString(READ_PATH_CONTROLLERS_ROOT.resolve("OptionsSnapshotService.java"));
    assertThat(source)
        .as("the capture/broadcast pass must keep refusing when there is no live spot")
        .contains("chainService.chain(");
    assertThat(source)
        .as(
            "OptionsSnapshotService must NEVER degrade to the captured chain — snapshotNow persists"
                + " what chain() returns, so it would re-insert the last captured book as a fresh"
                + " row and freeze OI for the rest of the day.")
        .doesNotContain("chainOrLastCaptured");
  }
}
