package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wave-U1 seam test: with {@code artha.marketdata.source.optionchain=upstox} (an {@link
 * OptionChainQuoteSource} present), the snapshot capture builds rows from the Upstox per-strike
 * LTP+OI+spot and {@code oi_change = oi − prev_oi} (Upstox's own {@code prev_oi}); with the DEFAULT
 * (no source) the existing Kite-{@code QuoteGateway} path is untouched and {@code oi_change} comes
 * from the previous-pass diff. Asserts the source choice steers ONLY the raw quote fetch — the chain
 * computation, persistence shape, and (Kite) default behaviour are otherwise unchanged.
 */
class OptionsSnapshotSourceSeamTest {

  // Mon 2026-06-15 11:00 IST, one day before the fixture expiry.
  private static final Clock CLOCK =
      Clock.fixed(OffsetDateTime.parse("2026-06-15T11:00:00+05:30").toInstant(), ZoneOffset.UTC);
  private static final LocalDate EXPIRY = LocalDate.parse("2026-06-16");
  // the instrument-master strike (scale 0); the Upstox source keys its maps by the scale-2 form,
  // mirroring UpstoxOptionChainClient — so the chain's scale-insensitive lookup must still match.
  private static final BigDecimal STRIKE = new BigDecimal("23800");
  private static final BigDecimal STRIKE_KEY = STRIKE.setScale(2, java.math.RoundingMode.HALF_UP);
  // covers 2026 so chain()'s isOpenSafe never trips the uncovered-year guard
  private static final MarketCalendar CAL = MarketCalendar.of(List.of(LocalDate.parse("2026-01-26")));

  /** One ATM CE+PE pair so the chain has a strike to fill. */
  private static InstrumentRepository instrumentStub() {
    Instrument ce =
        new Instrument(
            "NFO", "NIFTY26JUN23800CE", 1L, "NIFTY", "NFO-OPT", "CE", "NSE", "NIFTY 50", EXPIRY,
            STRIKE, new BigDecimal("0.05"), 75, true);
    Instrument pe =
        new Instrument(
            "NFO", "NIFTY26JUN23800PE", 2L, "NIFTY", "NFO-OPT", "PE", "NSE", "NIFTY 50", EXPIRY,
            STRIKE, new BigDecimal("0.05"), 75, true);
    return new InstrumentRepository(null) {
      @Override
      public List<LocalDate> expiries(String underlying) {
        return List.of(EXPIRY);
      }

      @Override
      public List<Instrument> optionChain(String underlying, LocalDate expiry) {
        return List.of(ce, pe);
      }
    };
  }

  /** A capturing repository — records every persisted row instead of hitting the DB. */
  private static final class CapturingRepo extends OptionsSnapshotRepository {
    final List<SnapshotRow> rows = new ArrayList<>();

    CapturingRepo() {
      super(null);
    }

    @Override
    public void insertAll(List<SnapshotRow> rows) {
      this.rows.addAll(rows);
    }
  }

  private static OptionsSnapshotService snapshotService(
      OptionsChainService chainService, CapturingRepo repo) {
    return new OptionsSnapshotService(
        chainService,
        repo,
        new StringRedisTemplate(), // publish() swallows the no-connection failure
        new ObjectMapper(),
        CAL, // snapshotNow ignores it; present so the ctor's non-empty-cover guard is satisfied
        CLOCK,
        List.of("NIFTY 50"),
        90,
        new SimpleMeterRegistry(),
        // fail-soft ledger: the capture-session write no-ops on the null template (swallowed)
        new in.arthayantra.marketdata.ingest.IngestRunLedger(null));
  }

  @Test
  void upstoxSourceDrivesCaptureAndOiChangeFromPrevOi() {
    // Upstox gives oi + prev_oi directly: oi_change must be oi − prev_oi on the FIRST pass.
    OptionChainQuoteSource upstox =
        (underlying, expiry) ->
            Optional.of(
                new OptionChainQuoteSource.ChainQuotes(
                    new BigDecimal("23845.85"),
                    Map.of(
                        STRIKE_KEY,
                        new OptionChainQuoteSource.Leg(
                            new BigDecimal("120.5"),
                            new BigDecimal("120.0"),
                            new BigDecimal("121.0"),
                            345600L,
                            1820075L,
                            1750000L)),
                    Map.of(
                        STRIKE_KEY,
                        new OptionChainQuoteSource.Leg(
                            new BigDecimal("255.75"),
                            new BigDecimal("255.0"),
                            new BigDecimal("256.5"),
                            290100L,
                            2105500L,
                            2200000L))));

    OptionsChainService chainService =
        new OptionsChainService(
            instrumentStub(),
            null, // QuoteGateway must NOT be consulted on the Upstox path
            CAL,
            CLOCK,
            new BigDecimal("0.065"),
            true,
            Optional.of(upstox));
    CapturingRepo repo = new CapturingRepo();

    snapshotService(chainService, repo).snapshotNow("NIFTY 50", EXPIRY);

    OptionsSnapshotRepository.SnapshotRow ce =
        repo.rows.stream().filter(r -> r.optionType().equals("CE")).findFirst().orElseThrow();
    assertThat(ce.spotPrice()).isEqualByComparingTo("23845.85");
    assertThat(ce.ltp()).isEqualByComparingTo("120.5");
    assertThat(ce.oi()).isEqualTo(1820075L);
    assertThat(ce.oiChange()).isEqualTo(1820075L - 1750000L); // oi − prev_oi, from Upstox directly

    OptionsSnapshotRepository.SnapshotRow pe =
        repo.rows.stream().filter(r -> r.optionType().equals("PE")).findFirst().orElseThrow();
    assertThat(pe.oi()).isEqualTo(2105500L);
    assertThat(pe.oiChange()).isEqualTo(2105500L - 2200000L); // negative ΔOI, faithfully signed
  }

  @Test
  void defaultPathIsUntouchedWhenNoUpstoxSource() {
    // No OptionChainQuoteSource -> the Kite QuoteGateway path. A real gateway stub serves the strike.
    in.arthayantra.marketdata.kite.QuoteGateway gateway =
        keys -> {
          Map<in.arthayantra.marketdata.kite.InstrumentKey, in.arthayantra.marketdata.kite.QuoteGateway.Quote>
              out = new java.util.HashMap<>();
          for (in.arthayantra.marketdata.kite.InstrumentKey k : keys) {
            if (k.tradingsymbol().equals("NIFTY 50")) {
              out.put(
                  k,
                  new in.arthayantra.marketdata.kite.QuoteGateway.Quote(
                      k, new BigDecimal("23845.85"), OffsetDateTime.now(CLOCK)));
            } else {
              out.put(
                  k,
                  new in.arthayantra.marketdata.kite.QuoteGateway.Quote(
                      k,
                      k.tradingsymbol().endsWith("CE") ? new BigDecimal("120.5") : new BigDecimal("255.75"),
                      new BigDecimal("120.0"),
                      new BigDecimal("121.0"),
                      1000L,
                      k.tradingsymbol().endsWith("CE") ? 1820075L : 2105500L,
                      OffsetDateTime.now(CLOCK)));
            }
          }
          return out;
        };

    OptionsChainService chainService =
        new OptionsChainService(
            instrumentStub(),
            gateway,
            CAL,
            CLOCK,
            new BigDecimal("0.065"),
            true,
            Optional.empty()); // DEFAULT: no Upstox source
    CapturingRepo repo = new CapturingRepo();

    snapshotService(chainService, repo).snapshotNow("NIFTY 50", EXPIRY);

    OptionsSnapshotRepository.SnapshotRow ce =
        repo.rows.stream().filter(r -> r.optionType().equals("CE")).findFirst().orElseThrow();
    assertThat(ce.oi()).isEqualTo(1820075L);
    // first pass, no Upstox prev_oi and no prior snapshot -> oi_change is null (unchanged behaviour)
    assertThat(ce.oiChange()).isNull();
  }
}
