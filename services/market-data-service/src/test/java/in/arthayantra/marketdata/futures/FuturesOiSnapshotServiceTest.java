package in.arthayantra.marketdata.futures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.FuturesContractSource.FutContract;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase-F: futures OI snapshot maps quotes to rows and computes oi_change across passes. */
class FuturesOiSnapshotServiceTest {

  // Tue 2026-06-16 11:00 IST — inside the session
  private static final Clock CLOCK =
      Clock.fixed(OffsetDateTime.parse("2026-06-16T11:00:00+05:30").toInstant(), ZoneOffset.UTC);
  private static final InstrumentKey NIFTY_FUT = new InstrumentKey("NFO", "NIFTY26JUNFUT");

  @Test
  void snapshotMapsQuotesToRowsAndComputesOiChangeAcrossPasses() {
    List<FuturesOiSnapshotRepository.Row> captured = new ArrayList<>();
    FuturesOiSnapshotRepository repo =
        new FuturesOiSnapshotRepository(null) {
          @Override
          public void insertAll(List<Row> rows) {
            captured.addAll(rows);
          }
        };
    FuturesContractSource contracts =
        (underlying, onOrAfter) ->
            List.of(new FutContract(NIFTY_FUT, LocalDate.parse("2026-06-25")));
    Map<InstrumentKey, Long> oiByPass = new HashMap<>();
    QuoteGateway quotes =
        keys ->
            Map.of(
                NIFTY_FUT,
                new QuoteGateway.Quote(
                    NIFTY_FUT,
                    new BigDecimal("23950"),
                    null,
                    null,
                    1000L,
                    oiByPass.get(NIFTY_FUT),
                    OffsetDateTime.now(CLOCK)));

    FuturesOiSnapshotService svc =
        new FuturesOiSnapshotService(
            contracts,
            quotes,
            repo,
            MarketCalendar.nse(),
            CLOCK,
            List.of("NIFTY 50"),
            new SimpleMeterRegistry());

    oiByPass.put(NIFTY_FUT, 5_000L);
    svc.snapshotNow();
    assertThat(captured).hasSize(1);
    assertThat(captured.get(0).underlying()).isEqualTo("NIFTY 50");
    assertThat(captured.get(0).tradingsymbol()).isEqualTo("NIFTY26JUNFUT");
    assertThat(captured.get(0).oi()).isEqualTo(5_000L);
    assertThat(captured.get(0).oiChange()).isNull(); // first pass — no previous

    captured.clear();
    oiByPass.put(NIFTY_FUT, 5_300L);
    svc.snapshotNow();
    assertThat(captured.get(0).oi()).isEqualTo(5_300L);
    assertThat(captured.get(0).oiChange()).isEqualTo(300L); // 5300 - 5000
  }

  @Test
  void batchesEveryUnderlyingIntoOneQuoteCall() {
    // Two underlyings, one contract each. The kite-quote limiter is 1 call/s with a 5s
    // timeout, so a call-per-underlying loop 429s past ~5-6 underlyings — the whole pass
    // must batch into ONE quotes() call (Kite caps at 250 instruments).
    InstrumentKey niftyFut = new InstrumentKey("NFO", "NIFTY26JUNFUT");
    InstrumentKey hdfcFut = new InstrumentKey("NFO", "HDFCBANK26JUNFUT");
    Map<String, InstrumentKey> ladder =
        Map.of("NIFTY 50", niftyFut, "HDFCBANK", hdfcFut);

    FuturesContractSource contracts =
        (underlying, onOrAfter) ->
            List.of(new FutContract(ladder.get(underlying), LocalDate.parse("2026-06-25")));

    List<List<InstrumentKey>> callLog = new ArrayList<>();
    QuoteGateway quotes =
        keys -> {
          callLog.add(List.copyOf(keys));
          Map<InstrumentKey, QuoteGateway.Quote> out = new HashMap<>();
          for (InstrumentKey k : keys) {
            out.put(
                k,
                new QuoteGateway.Quote(
                    k, new BigDecimal("100"), null, null, 1L, 5_000L, OffsetDateTime.now(CLOCK)));
          }
          return out;
        };

    List<FuturesOiSnapshotRepository.Row> captured = new ArrayList<>();
    FuturesOiSnapshotRepository repo =
        new FuturesOiSnapshotRepository(null) {
          @Override
          public void insertAll(List<Row> rows) {
            captured.addAll(rows);
          }
        };

    FuturesOiSnapshotService svc =
        new FuturesOiSnapshotService(
            contracts,
            quotes,
            repo,
            MarketCalendar.nse(),
            CLOCK,
            List.of("NIFTY 50", "HDFCBANK"),
            new SimpleMeterRegistry());

    svc.snapshotNow();

    // exactly one batched call carrying both underlyings' contracts
    assertThat(callLog).hasSize(1);
    assertThat(callLog.get(0)).containsExactlyInAnyOrder(niftyFut, hdfcFut);
    // rows still attributed to the right underlying
    assertThat(captured)
        .extracting(FuturesOiSnapshotRepository.Row::underlying)
        .containsExactlyInAnyOrder("NIFTY 50", "HDFCBANK");
    assertThat(captured)
        .extracting(FuturesOiSnapshotRepository.Row::tradingsymbol)
        .containsExactlyInAnyOrder("NIFTY26JUNFUT", "HDFCBANK26JUNFUT");
  }

  @Test
  void capturesDayOhlcFromQuote() {
    List<FuturesOiSnapshotRepository.Row> captured = new ArrayList<>();
    FuturesOiSnapshotRepository repo =
        new FuturesOiSnapshotRepository(null) {
          @Override
          public void insertAll(List<Row> rows) {
            captured.addAll(rows);
          }
        };
    FuturesContractSource contracts =
        (underlying, onOrAfter) ->
            List.of(new FutContract(NIFTY_FUT, LocalDate.parse("2026-06-25")));
    QuoteGateway quotes =
        keys ->
            Map.of(
                NIFTY_FUT,
                new QuoteGateway.Quote(
                    NIFTY_FUT,
                    new BigDecimal("23950"),
                    null,
                    null,
                    1000L,
                    5000L,
                    new QuoteGateway.Quote.Ohlc(
                        new BigDecimal("23900"),
                        new BigDecimal("24010"),
                        new BigDecimal("23850"),
                        new BigDecimal("23880")),
                    OffsetDateTime.now(CLOCK)));

    FuturesOiSnapshotService svc =
        new FuturesOiSnapshotService(
            contracts,
            quotes,
            repo,
            MarketCalendar.nse(),
            CLOCK,
            List.of("NIFTY 50"),
            new SimpleMeterRegistry());

    svc.snapshotNow();

    assertThat(captured).hasSize(1);
    assertThat(captured.get(0).dayHigh()).isEqualByComparingTo("24010");
    assertThat(captured.get(0).dayLow()).isEqualByComparingTo("23850");
    assertThat(captured.get(0).prevClose()).isEqualByComparingTo("23880");
  }

  // ── T2 (audit 2026-07-02 §9.1): boundary-aligned stamps + the shifted market-hours gate ──────

  private FuturesOiSnapshotService serviceAt(String isoIst, List<FuturesOiSnapshotRepository.Row> captured) {
    Clock clock = Clock.fixed(OffsetDateTime.parse(isoIst).toInstant(), ZoneOffset.UTC);
    FuturesOiSnapshotRepository repo =
        new FuturesOiSnapshotRepository(null) {
          @Override
          public void insertAll(List<Row> rows) {
            captured.addAll(rows);
          }
        };
    FuturesContractSource contracts =
        (underlying, onOrAfter) ->
            List.of(new FutContract(NIFTY_FUT, LocalDate.parse("2026-06-25")));
    QuoteGateway quotes =
        keys ->
            Map.of(
                NIFTY_FUT,
                new QuoteGateway.Quote(
                    NIFTY_FUT, new BigDecimal("23950"), null, null, 1000L, 5000L,
                    OffsetDateTime.now(clock)));
    return new FuturesOiSnapshotService(
        contracts, quotes, repo, MarketCalendar.nse(), clock, List.of("NIFTY 50"),
        new SimpleMeterRegistry());
  }

  @Test
  void stampsRowsOnTheMinuteGrid() {
    List<FuturesOiSnapshotRepository.Row> captured = new ArrayList<>();
    serviceAt("2026-06-16T11:00:37+05:30", captured).snapshotNow();

    assertThat(captured).hasSize(1);
    assertThat(captured.get(0).ts().toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-06-16T11:00:00+05:30").toInstant());
  }

  @Test
  void gateSkipsTheOpenFireRunsTheEodFireAndStopsAfterClose() {
    // 09:15:00 fire: the represented window (09:14, 09:15] starts pre-open -> skipped.
    List<FuturesOiSnapshotRepository.Row> preOpen = new ArrayList<>();
    serviceAt("2026-06-16T09:15:00+05:30", preOpen).scheduledSnapshot();
    assertThat(preOpen).isEmpty();

    // 09:16:00 fire: (09:15, 09:16] fully in-session -> first capture of the day.
    List<FuturesOiSnapshotRepository.Row> first = new ArrayList<>();
    serviceAt("2026-06-16T09:16:00+05:30", first).scheduledSnapshot();
    assertThat(first).isNotEmpty();

    // 15:30:00 fire: (15:29, 15:30] is the closing window -> the EOD capture runs.
    List<FuturesOiSnapshotRepository.Row> eod = new ArrayList<>();
    serviceAt("2026-06-16T15:30:00+05:30", eod).scheduledSnapshot();
    assertThat(eod).isNotEmpty();

    // 15:31:00 fire: the represented window starts at close -> stopped.
    List<FuturesOiSnapshotRepository.Row> closed = new ArrayList<>();
    serviceAt("2026-06-16T15:31:00+05:30", closed).scheduledSnapshot();
    assertThat(closed).isEmpty();
  }

  /**
   * T12 / ledger G5: the pass used to abandon the whole minute the first time it lost the
   * {@code kite-quote} permit — the ~2-min effective cadence and 161 single-minute skips. Kite
   * documents the quote endpoint at 1 req/s and the limiter already matches it, so the fix could not
   * be a second limiter (that would spend 2/s); it is patience across the options-chain pass's
   * ~70-consecutive-second permit run.
   */
  @Test
  void retriesThroughLocalLimiterSaturationInsteadOfSkippingTheMinute() {
    List<FuturesOiSnapshotRepository.Row> captured = new ArrayList<>();
    FuturesOiSnapshotRepository repo =
        new FuturesOiSnapshotRepository(null) {
          @Override
          public void insertAll(List<Row> rows) {
            captured.addAll(rows);
          }
        };
    FuturesContractSource contracts =
        (underlying, onOrAfter) ->
            List.of(new FutContract(NIFTY_FUT, LocalDate.parse("2026-06-25")));

    int[] calls = {0};
    QuoteGateway quotes =
        keys -> {
          if (++calls[0] < 3) {
            throw new ApiException(
                429, ErrorCodes.RATE_LIMIT_LOCAL, "local broker QUOTE budget saturated");
          }
          return Map.of(
              NIFTY_FUT,
              new QuoteGateway.Quote(
                  NIFTY_FUT,
                  new BigDecimal("23950"),
                  null,
                  null,
                  1000L,
                  7_000L,
                  OffsetDateTime.now(CLOCK)));
        };

    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    FuturesOiSnapshotService svc = noSleep(contracts, quotes, repo, meters);

    svc.snapshotNow();

    assertThat(calls[0]).isEqualTo(3); // two saturated attempts, third won the permit
    assertThat(captured).hasSize(1);
    assertThat(captured.get(0).oi()).isEqualTo(7_000L);
    assertThat(meters.counter("ay_futures_oi_snapshot_quote_retries_total").count()).isEqualTo(2.0);
    assertThat(meters.counter("ay_futures_oi_snapshot_skips_total").count()).isZero();
  }

  @Test
  void countsAGenuineSkipWhenEveryAttemptLosesThePermit() {
    List<FuturesOiSnapshotRepository.Row> captured = new ArrayList<>();
    FuturesOiSnapshotRepository repo =
        new FuturesOiSnapshotRepository(null) {
          @Override
          public void insertAll(List<Row> rows) {
            captured.addAll(rows);
          }
        };
    FuturesContractSource contracts =
        (underlying, onOrAfter) ->
            List.of(new FutContract(NIFTY_FUT, LocalDate.parse("2026-06-25")));
    int[] calls = {0};
    QuoteGateway quotes =
        keys -> {
          calls[0]++;
          throw new ApiException(
              429, ErrorCodes.RATE_LIMIT_LOCAL, "local broker QUOTE budget saturated");
        };

    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    noSleep(contracts, quotes, repo, meters).snapshotNow();

    assertThat(calls[0]).isEqualTo(4); // the bounded ladder, not an unbounded spin
    assertThat(captured).isEmpty(); // no partial pass persisted
    assertThat(meters.counter("ay_futures_oi_snapshot_skips_total").count()).isEqualTo(1.0);
  }

  /**
   * The paired positive for the retry: only LOCAL limiter saturation is patient. Anything else —
   * Kite's own 429, an open breaker, an auth failure — must propagate on the first attempt, because
   * retrying it spends budget on a call that cannot succeed.
   */
  @Test
  void doesNotRetryANonSaturationFailure() {
    FuturesContractSource contracts =
        (underlying, onOrAfter) ->
            List.of(new FutContract(NIFTY_FUT, LocalDate.parse("2026-06-25")));
    int[] calls = {0};
    QuoteGateway quotes =
        keys -> {
          calls[0]++;
          throw new ApiException(503, ErrorCodes.KITE_CIRCUIT_OPEN, "kite-rest circuit open");
        };
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    FuturesOiSnapshotService svc =
        noSleep(contracts, quotes, new FuturesOiSnapshotRepository(null) {
          @Override
          public void insertAll(List<Row> rows) {
            // never reached
          }
        }, meters);

    assertThatThrownBy(svc::snapshotNow).isInstanceOf(ApiException.class);
    assertThat(calls[0]).isEqualTo(1);
    assertThat(meters.counter("ay_futures_oi_snapshot_quote_retries_total").count()).isZero();
  }

  /** The service with its retry backoff removed, so the ladder runs at test speed. */
  private static FuturesOiSnapshotService noSleep(
      FuturesContractSource contracts,
      QuoteGateway quotes,
      FuturesOiSnapshotRepository repo,
      SimpleMeterRegistry meters) {
    return new FuturesOiSnapshotService(
        contracts, quotes, repo, MarketCalendar.nse(), CLOCK, List.of("NIFTY 50"), meters) {
      @Override
      boolean pauseBeforeRetry() {
        return true;
      }
    };
  }
}
