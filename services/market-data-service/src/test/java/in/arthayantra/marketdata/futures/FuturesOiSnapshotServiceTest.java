package in.arthayantra.marketdata.futures;

import static org.assertj.core.api.Assertions.assertThat;

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
}
