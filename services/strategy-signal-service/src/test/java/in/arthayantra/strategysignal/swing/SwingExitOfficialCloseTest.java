package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalExited;
import in.arthayantra.strategysignal.signals.SignalPublisher;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ledger H9 — the swing exit FILL prices at the OFFICIAL NSE close, and the exit DECISION does not.
 *
 * <p>Kite's daily bar covers the CONTINUOUS session only and excludes the 15:15–15:30 closing auction
 * that sets NSE's official close. Measured on the 2026-08-13 settle: <b>0 of 22</b> {@code
 * source='KITE'} NSE 1d rows closed at {@code nse_eod_bhavcopy.close_price} and <b>22 of 22</b> were
 * short on volume. Real money — minervini PRECOT #23 realized −778.78, sibling manas #26 −1168.17.
 *
 * <p><b>The line this class exists to pin</b> is the owner's 2026-08-14 ruling: <i>"the exit DECISION
 * stays off the Kite bar (unchanged exit doctrine), the FILL is re-priced to NSE close_price"</i>.
 * Two tests assert that invariance in OPPOSITE directions, because a one-directional proof would
 * pass against an implementation that had leaked the official close into the decision.
 *
 * <p>Family-neutral on purpose: {@link SwingDoctrine} is mocked rather than instantiated as Manas or
 * Minervini. The reprice lives in the shared engine, so proving it through one family's adapter would
 * silently under-report which books it covers.
 */
class SwingExitOfficialCloseTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String SYM = "TESTCO";
  private static final String BATCH = "h9-batch";
  private static final BigDecimal ENTRY = new BigDecimal("152");

  // ---- the fill is the official close -------------------------------------------------------

  @Test
  void theExitFillsAtTheOfficialCloseAtEveryMoneySurface() {
    Harness h = new Harness(decliningSeriesThatStopsOut());
    h.officialClose(SYM, new BigDecimal("118.4500"));

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine, null, false);

    assertThat(run.exits()).as("the stop really does fire on this series").isEqualTo(1);
    BigDecimal candleClose = h.lastBar().close();
    assertThat(candleClose)
        .as("guard: the candle close and the official close must actually differ, or this proves nothing")
        .isNotEqualByComparingTo(new BigDecimal("118.4500"));

    // 1. the DURABLE swing_paper_effects decision row — the crash-retry path REPLAYS this one, so a
    //    candle price left here would silently re-price the retry back onto the wrong plane.
    assertThat((BigDecimal) h.argsOf(h.paperEffects, "expectExit")[4])
        .isEqualByComparingTo(new BigDecimal("118.4500"));
    // 2. the signals row
    assertThat((BigDecimal) h.argsOf(h.signals, "insert")[6])
        .isEqualByComparingTo(new BigDecimal("118.4500"));
    // 3. the publisher fan-out
    assertThat((BigDecimal) h.argsOf(h.publisher, "publish")[12])
        .isEqualByComparingTo(new BigDecimal("118.4500"));
    // 4. the SignalExited money event the paper module settles on
    assertThat(h.exitedEvents()).singleElement().satisfies(
        e -> assertThat(e.price()).isEqualByComparingTo(new BigDecimal("118.4500")));
  }

  @Test
  void theEquityMarkIsTheSameResolvedPriceAsTheFill() {
    // Correcting the fill but not the mark would leave realized P&L and book equity disagreeing about
    // the same symbol on the same night by exactly the closing-auction delta. exitPass is the ONLY
    // producer of EquityMarkCache, so one resolution must feed both.
    Harness h = new Harness(decliningSeriesThatStopsOut());
    h.officialClose(SYM, new BigDecimal("118.4500"));

    h.engine().runDaily(h.doctrine, null, false);

    ArgumentCaptor<BigDecimal> mark = ArgumentCaptor.forClass(BigDecimal.class);
    verify(h.guard).cacheEquityMark(eq("NSE"), eq(SYM), mark.capture(), any());
    assertThat(mark.getValue()).isEqualByComparingTo(new BigDecimal("118.4500"));
    assertThat(mark.getValue())
        .isEqualByComparingTo((BigDecimal) h.argsOf(h.signals, "insert")[6]);
  }

  // ---- decision invariance, BOTH directions --------------------------------------------------

  @Test
  void aStopThatFiresOnTheCandleStillFiresWhenTheOfficialCloseIsFarAboveTheStopLevel() {
    // DIRECTION A. The official close is 200 — above the ENTRY price of 152, therefore unambiguously
    // above any long stop or trail level this config can compute, without this test having to know
    // what that level is. If the reprice had leaked into ExitEvaluator, 200 would suppress the exit
    // entirely. It must fire anyway (the decision is off the candle close of 120) and fill at 200.
    Harness h = new Harness(decliningSeriesThatStopsOut());
    h.officialClose(SYM, new BigDecimal("200.0000"));

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine, null, false);

    assertThat(run.exits()).as("the DECISION is off the candle series and cannot see the reprice").isEqualTo(1);
    assertThat((BigDecimal) h.argsOf(h.signals, "insert")[6])
        .as("and it still fills at the official close, even one above the entry price")
        .isEqualByComparingTo(new BigDecimal("200.0000"));
  }

  @Test
  void aBarThatDoesNotExitStaysUnexitedWhenTheOfficialCloseIsFarBelowTheStopLevel() {
    // DIRECTION B, and it is the half a one-sided proof misses. An implementation that fed the
    // official close into the decision would exit here — 1.00 is below every conceivable stop. The
    // position must stay open, while the MARK still moves to the official close (accounting follows
    // the plane even when nothing is realized).
    Harness h = new Harness(risingSeriesThatDoesNotExit());
    h.officialClose(SYM, new BigDecimal("1.0000"));

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine, null, false);

    assertThat(run.exits()).as("the reprice must not be able to CREATE an exit").isZero();
    verify(h.signals, never()).insert(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    ArgumentCaptor<BigDecimal> mark = ArgumentCaptor.forClass(BigDecimal.class);
    verify(h.guard).cacheEquityMark(eq("NSE"), eq(SYM), mark.capture(), any());
    assertThat(mark.getValue()).isEqualByComparingTo(new BigDecimal("1.0000"));
  }

  // ---- fallback doctrine: never refuse, never silent -----------------------------------------

  @Test
  void anAbsentOfficialCloseFallsBackToTheCandleCloseCountedAndAlerted() {
    Harness h = new Harness(decliningSeriesThatStopsOut()); // no official close stubbed

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine, null, false);

    assertThat(run.exits()).as("an exit may never refuse — it settles tonight either way").isEqualTo(1);
    assertThat((BigDecimal) h.argsOf(h.signals, "insert")[6])
        .isEqualByComparingTo(h.lastBar().close());
    assertThat(h.counter("ay_swing_exit_official_close_fallback_total", "not_published"))
        .isEqualTo(1.0);
    assertThat(h.counter("ay_swing_exit_official_close_fallback_fill_total", "not_published"))
        .as("this one really did exit, so the fallback price became real money")
        .isEqualTo(1.0);
    assertThat(h.counter("ay_swing_exit_official_close_resolved_total"))
        .as("the resolved counter is what makes a zero fallback count READABLE")
        .isEqualTo(0.0);
    assertThat(h.fallbackAlerts()).singleElement().satisfies(
        a -> {
          assertThat(a.message())
              .contains(SYM)
              .contains("closing auction")
              .contains("1 of these became a REAL EXIT FILL")
              .contains("18:45 bhavcopy ingest");
          // The mirror of the mark-only title assertion: here an exit DID fill, so the title says
          // so. Asserting both directions is what stops the branch being a constant.
          assertThat(a.title())
              .contains("EXIT FILLED off the candle close")
              .doesNotContain("no exit");
        });
  }

  @Test
  void aResolvedCloseIncrementsTheResolvedCounterAndPagesNobody() {
    Harness h = new Harness(decliningSeriesThatStopsOut());
    h.officialClose(SYM, new BigDecimal("118.4500"));

    h.engine().runDaily(h.doctrine, null, false);

    assertThat(h.counter("ay_swing_exit_official_close_resolved_total")).isEqualTo(1.0);
    assertThat(h.counter("ay_swing_exit_official_close_fallback_total", "not_published")).isZero();
    assertThat(h.counter("ay_swing_exit_official_close_fallback_total", "stale_bar")).isZero();
    assertThat(h.fallbackAlerts()).isEmpty();
  }

  @Test
  void twoFallingBackSymbolsProduceExactlyONEAggregatedPage() {
    // Owner ruling: one aggregated page per run, no threshold. The measured worst case is NSE
    // publishing at 19:31, when EVERY held symbol falls back at once — as per-symbol pages that is a
    // burst nobody reads.
    Harness h = new Harness(decliningSeriesThatStopsOut());
    h.alsoHold("OTHERCO");

    h.engine().runDaily(h.doctrine, null, false);

    assertThat(h.counter("ay_swing_exit_official_close_fallback_total", "not_published"))
        .isEqualTo(2.0);
    assertThat(h.fallbackAlerts()).singleElement().satisfies(
        a -> assertThat(a.message()).contains(SYM).contains("OTHERCO").contains("2 held symbol(s)"));
  }

  @Test
  void aMarkOnlyFallbackIsNotReportedAsAFillAndDoesNotClaimAnExitSettled() {
    // ⚠️ The resolution runs BEFORE the exit rules, so on an ordinary night most fallbacks move only
    // the equity mark. An earlier shape of this page said "the exits still settled" for symbols that
    // never exited — a page that misattributes what happened teaches its reader to distrust every
    // page it sends. This holding does not exit; the telemetry must say so.
    Harness h = new Harness(risingSeriesThatDoesNotExit());

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine, null, false);

    assertThat(run.exits()).isZero();
    assertThat(h.counter("ay_swing_exit_official_close_fallback_total", "not_published"))
        .as("the mark really was priced off the candle")
        .isEqualTo(1.0);
    assertThat(h.counter("ay_swing_exit_official_close_fallback_fill_total", "not_published"))
        .as("but no money moved — the fill counter is a STRICT SUBSET, never an alias")
        .isZero();
    assertThat(h.fallbackAlerts()).singleElement().satisfies(
        a -> {
          assertThat(a.message())
              .contains("0 of these became a REAL EXIT FILL")
              .contains("moved the equity MARK only")
              .as("nothing exited, so the page may not claim an exit settled")
              .doesNotContain("exits that fired still settled");
          // ⚠️ THE TITLE, not just the body. Notification clients surface the title on its own — a
          // lock screen, a list row, a push preview — so an operator can read it without ever
          // opening the body that would correct it. A correct body does not repair a false title.
          assertThat(a.title())
              .as("the title is the copy of this message that reaches the most eyes")
              .contains("equity MARK only, no exit")
              .doesNotContain("EXIT FILLED")
              .doesNotContain("exit priced off");
        });
  }

  @Test
  void theTwoFallbackReasonsNeverShareACounterOrAnAlertSentence() {
    // One run, both reasons: TESTCO has no official close for the session (NOT_PUBLISHED), while
    // STALECO's newest daily bar is an earlier session (STALE_BAR) — for which an official close DOES
    // exist and is deliberately not used. The two point at different systems: the first at the 18:45
    // bhavcopy ingest, the second at a halt or gap on the SYMBOL. Folding them into one number, or one
    // sentence, makes the telemetry unable to answer the only question anyone asks it.
    Harness h = new Harness(decliningSeriesThatStopsOut());
    h.alsoHoldWithSeries("STALECO", staleDecliningSeries());
    h.officialClose("STALECO", new BigDecimal("118.4500"));

    h.engine().runDaily(h.doctrine, null, false);

    assertThat(h.counter("ay_swing_exit_official_close_fallback_total", "not_published")).isEqualTo(1.0);
    assertThat(h.counter("ay_swing_exit_official_close_fallback_total", "stale_bar")).isEqualTo(1.0);
    assertThat(h.fallbackAlerts()).singleElement().satisfies(
        a -> {
          String m = a.message();
          assertThat(m).contains("2 held symbol(s)");
          // The ingest advice is attached to the NOT_PUBLISHED group only. Asserting the ORDER of the
          // two substrings is what proves the advice sits on the right group rather than merely being
          // present somewhere in a message that also happens to mention both symbols.
          assertThat(m.indexOf(SYM)).isLessThan(m.indexOf("18:45 bhavcopy ingest"));
          assertThat(m.indexOf("18:45 bhavcopy ingest")).isLessThan(m.indexOf("STALECO"));
          assertThat(m).contains("may well have run normally");
        });
  }

  @Test
  void aHaltedSymbolWhoseLastBarPredatesTheSessionTakesTheFallback() {
    // The official close IS published for the effect session — but this symbol's newest daily bar is
    // from an earlier session, so that close belongs to a day the position is not being settled
    // against. Pricing the fill off it would make a wrong number look authoritative, so the bar's own
    // close wins and the mismatch is counted and paged. Owner ruling: straight fallback, no second
    // per-date lookup.
    Harness h = new Harness(decliningSeriesThatStopsOut());
    h.officialClose(SYM, new BigDecimal("118.4500"));
    h.clockDaysAfterLastBar(1);

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine, null, false);

    assertThat(run.exits()).isEqualTo(1);
    assertThat((BigDecimal) h.argsOf(h.signals, "insert")[6])
        .isEqualByComparingTo(h.lastBar().close());
    assertThat(h.counter("ay_swing_exit_official_close_fallback_total", "stale_bar"))
        .as("a stale bar is NOT the same failure as an unpublished close and must not share a counter")
        .isEqualTo(1.0);
    assertThat(h.counter("ay_swing_exit_official_close_fallback_total", "not_published")).isZero();
    assertThat(h.fallbackAlerts()).singleElement().satisfies(
        a -> assertThat(a.message())
                 .contains("not the effect session")
                 .as("blaming the ingest here would send an operator to check a job that ran fine")
                 .doesNotContain("18:45 bhavcopy ingest"));
  }

  @Test
  void anUnwiredClientBehavesExactlyLikeAFailedLookup() {
    // The seam constructors leave the client null, and that must be the DEGRADED state rather than a
    // silent no-op: a seam whose default was the repriced path would let a test claim behaviour the
    // live stack does not have. Null therefore prices off the candle, counts, and pages.
    Harness h = new Harness(decliningSeriesThatStopsOut());

    SwingBatchEngine.SwingRun run = h.engineWithoutClient().runDaily(h.doctrine, null, false);

    assertThat(run.exits()).isEqualTo(1);
    assertThat((BigDecimal) h.argsOf(h.signals, "insert")[6])
        .isEqualByComparingTo(h.lastBar().close());
    assertThat(h.fallbackAlerts()).hasSize(1);
  }

  // ---- harness -------------------------------------------------------------------------------

  private final class Harness {
    final StrategyRepository registry = mock(StrategyRepository.class);
    final SignalRepository signals = mock(SignalRepository.class);
    final MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    final SignalPublisher publisher = mock(SignalPublisher.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final EmissionGuard guard = mock(EmissionGuard.class);
    final SwingPaperEffectRepository paperEffects = mock(SwingPaperEffectRepository.class);
    final SwingBatchRefusalRepository refusals = mock(SwingBatchRefusalRepository.class);
    final OfficialCloseClient officialCloses = mock(OfficialCloseClient.class);
    final SwingDoctrine doctrine = mock(SwingDoctrine.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final List<EngineCandle> series;
    final UUID publishedVersion = UUID.randomUUID();
    final Map<String, BigDecimal> closes = new java.util.HashMap<>();
    final List<SignalRepository.SignalRow> anchors = new ArrayList<>();
    Clock clock;

    Harness(List<EngineCandle> bars) {
      this.series = bars;
      // 18:00 IST on the last bar's own session — the shape the 18:52 settle actually runs in.
      this.clock = Clock.fixed(bars.get(bars.size() - 1).bucketStart().plusHours(18).toInstant(), ZoneOffset.UTC);
      UUID strategyId = UUID.randomUUID();
      JsonNode config = swingConfig();
      when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, publishedVersion)));
      when(registry.findVersionById(publishedVersion))
          .thenReturn(Optional.of(version(publishedVersion, strategyId, config)));
      anchors.add(anchor(42L, publishedVersion, SYM, series.get(24).bucketStart()));
      when(signals.activeEntries()).thenAnswer(i -> List.copyOf(anchors));
      when(signals.insert(
              any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
              any(), any()))
          .thenReturn(43L);
      when(candles.fetch(any(), any(), any(), any(), any())).thenReturn(series);
      when(paperEffects.openPositionIdsForSignals(any())).thenReturn(List.of(7L));
      when(paperEffects.expectExit(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
      when(officialCloses.closesOn(any(), any(), any())).thenAnswer(i -> Map.copyOf(closes));
      when(doctrine.enabled()).thenReturn(true);
      when(doctrine.batchName()).thenReturn(BATCH);
      when(doctrine.alertLabel()).thenReturn("H9 Test");
      when(doctrine.book()).thenReturn("h9-book");
      when(doctrine.universeMode()).thenReturn("manas_arora_funnel");
      when(doctrine.warmupDays()).thenReturn(520);
      when(doctrine.ttlMinutes()).thenReturn(1440L);
      // The fixture config declares ONE indicator-level instrument override (VCP_PIVOT over
      // MANAS_BREAKOUT_PIVOT), and IndicatorRegistry REFUSES to build it without a context series.
      // Seeded neutral at 0, exactly as ManasDoctrine#neutralContextSeeds does: the bank still builds
      // every declared indicator, and none of the three exit rules (stop_loss / trailing_stop /
      // square_off) ever reads the pivot's value.
      when(doctrine.neutralContextSeeds())
          .thenReturn(Map.of("MANAS_BREAKOUT_PIVOT", BigDecimal.ZERO));
    }

    void officialClose(String symbol, BigDecimal close) {
      closes.put(symbol, close);
    }

    /** A second held symbol on the SAME series — the aggregation proof needs two fallbacks in one run. */
    void alsoHold(String symbol) {
      anchors.add(anchor(44L, publishedVersion, symbol, series.get(24).bucketStart()));
    }

    /**
     * A second held symbol with its OWN series — the only way one run can carry both fallback
     * reasons, which is what proves the two never share a counter or an alert sentence.
     */
    void alsoHoldWithSeries(String symbol, List<EngineCandle> bars) {
      anchors.add(anchor(45L, publishedVersion, symbol, bars.get(24).bucketStart()));
      when(candles.fetch(any(), eq(symbol), any(), any(), any())).thenReturn(bars);
    }

    /** Moves the run's clock forward so the newest daily bar is no longer the effect session. */
    void clockDaysAfterLastBar(int days) {
      this.clock = Clock.fixed(clock.instant().plus(java.time.Duration.ofDays(days)), ZoneOffset.UTC);
    }

    EngineCandle lastBar() {
      return series.get(series.size() - 1);
    }

    SwingBatchEngine engine() {
      return engine(officialCloses);
    }

    SwingBatchEngine engineWithoutClient() {
      return engine(null);
    }

    private SwingBatchEngine engine(OfficialCloseClient client) {
      return new SwingBatchEngine(
          registry, candles, signals, publisher, events, Optional.of(guard), passthroughTx(),
          new ObjectMapper(), clock, paperEffects, refusals, "OBSERVE_ONLY", client, meters);
    }

    /** A counter carrying only the batch tag (the resolved counter). */
    double counter(String name) {
      io.micrometer.core.instrument.Counter c = meters.find(name).tag("batch", BATCH).counter();
      return c == null ? 0.0 : c.count();
    }

    /** A reason-tagged counter. Absent (never incremented) reads 0, which is what a caller means. */
    double counter(String name, String reason) {
      io.micrometer.core.instrument.Counter c =
          meters.find(name).tag("batch", BATCH).tag("reason", reason).counter();
      return c == null ? 0.0 : c.count();
    }

    List<SignalExited> exitedEvents() {
      return publishedEvents(SignalExited.class);
    }

    /**
     * Only the H9 fallback page — the coverage gate publishes SwingBatchAlerts of its own. Keys on
     * the STABLE half of the title: the other half is branched on whether anything actually filled,
     * and a filter that matched only one branch would silently stop seeing the other.
     */
    List<SwingBatchAlert> fallbackAlerts() {
      return publishedEvents(SwingBatchAlert.class).stream()
          .filter(a -> a.title().contains("official-close fallback"))
          .toList();
    }

    private <T> List<T> publishedEvents(Class<T> type) {
      List<T> out = new ArrayList<>();
      mockingDetails(events)
          .getInvocations()
          .forEach(
              i -> {
                Object arg = i.getArguments()[0];
                if (type.isInstance(arg)) {
                  out.add(type.cast(arg));
                }
              });
      return out;
    }

    /** The first invocation's raw arguments — cheaper and clearer than 18 positional matchers. */
    Object[] argsOf(Object target, String method) {
      return mockingDetails(target).getInvocations().stream()
          .filter(i -> i.getMethod().getName().equals(method))
          .findFirst()
          .orElseThrow(() -> new AssertionError(method + " was never called"))
          .getArguments();
    }
  }

  // ---- fixtures ------------------------------------------------------------------------------

  /**
   * Flat at 150 for 26 sessions then 140, 120 — with the anchor placed at bar 24 (entry ₹152) the
   * ATR(20) stop is warmed and the final close is far through it, so the stop fires. Same shape as
   * {@code ManasAroraSwingEngineTest#craftDecline}, which already pins that this series exits.
   */
  private static List<EngineCandle> decliningSeriesThatStopsOut() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 25; d++) {
      bars.add(bar(d, 150.0));
    }
    bars.add(bar(26, 140.0));
    bars.add(bar(27, 120.0));
    return bars;
  }

  /**
   * Flat at 152 through the ATR warmup then a slow rise to ~₹170 — armed trail, nothing breached, no
   * exit. Same shape as {@code ManasAroraSwingEngineTest#craftArmedTrail}, which pins the no-exit
   * outcome; the anchor sits at bar 24 here too, so the entry is inside the flat stretch.
   */
  private static List<EngineCandle> risingSeriesThatDoesNotExit() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 24; d++) {
      bars.add(bar(d, 152.0));
    }
    for (int d = 25; d <= 40; d++) {
      bars.add(bar(d, 152.0 + 1.125 * (d - 24)));
    }
    return bars;
  }

  /**
   * {@link #decliningSeriesThatStopsOut} minus its final bar, so this symbol's newest daily bar is
   * the session BEFORE the one the run settles against — the halted-symbol / data-gap shape. It still
   * stops out, so the run emits an exit for it and the two reasons coexist in one aggregate.
   */
  private static List<EngineCandle> staleDecliningSeries() {
    List<EngineCandle> bars = decliningSeriesThatStopsOut();
    return List.copyOf(bars.subList(0, bars.size() - 1));
  }

  private static EngineCandle bar(int day, double price) {
    OffsetDateTime bucket = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, IST).plusDays(day);
    BigDecimal c = BigDecimal.valueOf(price);
    return new EngineCandle(
        bucket, c, BigDecimal.valueOf(price + 1), BigDecimal.valueOf(price - 1), c, 1_000L, null);
  }

  private static TransactionTemplate passthroughTx() {
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any()))
        .thenAnswer(inv -> inv.<TransactionCallback<Long>>getArgument(0).doInTransaction(null));
    return tx;
  }

  private static StrategyRepository.StrategyRow strategyRow(UUID strategyId, UUID publishedVersion) {
    return new StrategyRepository.StrategyRow(
        strategyId, "h9-swing", "H9 Swing", null, null, List.of("manas-arora"), true,
        publishedVersion, null, null, false, null);
  }

  private static StrategyRepository.VersionRow version(UUID id, UUID strategyId, JsonNode config) {
    return new StrategyRepository.VersionRow(
        id, strategyId, "1", null, config, "1", "chk", "published", null, null, null, null);
  }

  private static SignalRepository.SignalRow anchor(
      long id, UUID versionId, String symbol, OffsetDateTime at) {
    return new SignalRepository.SignalRow(
        id, versionId, "NSE", symbol, "1d", "ENTRY", "BUY", ENTRY, null, null, BigDecimal.ONE,
        new ObjectMapper().createObjectNode(), "TAKEN", at, at.plusDays(1), null, null, null, null,
        null, null, null);
  }

  private static JsonNode swingConfig() {
    try (InputStream in =
        SwingExitOfficialCloseTest.class.getResourceAsStream(
            "/manas-arora-strategies/manas-arora-breakout.yaml")) {
      assertThat(in).isNotNull();
      return StrategyDocuments.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).config();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
