package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.openalgo.OpenAlgoSymbols;
import in.arthayantra.marketdata.upstox.ExpiredBackfillRepository.Coverage;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Bar;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Leg;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;

/**
 * Unit test for {@link ExpiredBackfillService#run}: the chain is walked into {@code candles} under the
 * canonical OpenAlgo symbol with {@code source='BACKFILL'}; each contract is registered with its
 * coverage; a COMPLETE contract is skipped, a short/missing one is (re)fetched; an empty window is
 * retried once. Client + repositories are mocked — no DB.
 */
class ExpiredBackfillServiceTest {

  private static final String NIFTY_KEY = "NSE_INDEX|Nifty 50";
  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 16);

  @SuppressWarnings("unchecked")
  private static ObjectProvider<UpstoxExpiredInstrumentsClient> provider() {
    return mock(ObjectProvider.class);
  }

  private static Leg ce() {
    return new Leg(
        "NSE_FO", "NSE_FO|CE|16-06-2026", "CE", "NIFTY", NIFTY_KEY, EXPIRY,
        new BigDecimal("25000"), 65, new BigDecimal("5"), true);
  }

  private static Leg pe() {
    return new Leg(
        "NSE_FO", "NSE_FO|PE|16-06-2026", "PE", "NIFTY", NIFTY_KEY, EXPIRY,
        new BigDecimal("21200"), 65, new BigDecimal("5"), true);
  }

  private static Bar barAt(String ts) {
    return new Bar(
        OffsetDateTime.parse(ts),
        new BigDecimal("238.95"), new BigDecimal("239.2"),
        new BigDecimal("238.15"), new BigDecimal("239.1"), 22750, 315185L);
  }

  private static void stubBand(ExpiredBackfillRepository repo) {
    // index range [22000, 24000] → ±20% band [17600, 28800] keeps both test strikes (25000, 21200).
    when(repo.indexRange(eq("NSE"), eq("NIFTY 50"), any(), any()))
        .thenReturn(new BigDecimal[] {new BigDecimal("22000"), new BigDecimal("24000")});
  }

  @Test
  void walksChainIntoCandlesRegistersCompleteAndSkipsCovered() {
    UpstoxExpiredInstrumentsClient client = mock(UpstoxExpiredInstrumentsClient.class);
    CandleRepository candles = mock(CandleRepository.class);
    ExpiredBackfillRepository repo = mock(ExpiredBackfillRepository.class);

    String ceSymbol = OpenAlgoSymbols.optionSymbol("NIFTY", EXPIRY, new BigDecimal("25000"), "CE");
    String peSymbol = OpenAlgoSymbols.optionSymbol("NIFTY", EXPIRY, new BigDecimal("21200"), "PE");

    when(client.expiries(NIFTY_KEY)).thenReturn(List.of(EXPIRY));
    when(client.optionContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of(ce(), pe()));
    when(client.futureContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of());
    stubBand(repo);
    // CE: never registered → fetched (a window of data, then two empties → stop). PE: COMPLETE → skipped.
    when(repo.coverage("NFO", ceSymbol)).thenReturn(Coverage.NONE);
    when(repo.coverage("NFO", peSymbol)).thenReturn(Coverage.COMPLETE);
    // the CE bar lands on the expiry day → reachedExpiry → complete.
    when(client.candles(eq("NSE_FO|CE|16-06-2026"), eq("1minute"), any(), any()))
        .thenReturn(List.of(barAt("2026-06-16T15:29:00+05:30")), List.of());

    ExpiredBackfillService service = new ExpiredBackfillService(provider(), candles, repo, 0L);
    ExpiredBackfillService.BackfillSummary summary =
        service.run(client, List.of("NIFTY"), EXPIRY.minusDays(7), EXPIRY, "job-1", false);

    assertThat(summary.legsWritten()).isEqualTo(1);
    assertThat(summary.legsSkipped()).isEqualTo(1);
    assertThat(summary.legsFailed()).isZero();
    assertThat(summary.candleRows()).isEqualTo(1);

    ArgumentCaptor<List<Candle>> rows = ArgumentCaptor.forClass(List.class);
    verify(candles).upsertAll(rows.capture());
    Candle row = rows.getValue().get(0);
    assertThat(row.exchange()).isEqualTo("NFO");
    assertThat(row.tradingsymbol()).isEqualTo(ceSymbol);
    assertThat(row.source()).isEqualTo("BACKFILL");
    assertThat(row.oi()).isEqualTo(315185L);

    // CE registered COMPLETE (bar reached the expiry day); PE skipped (no registration / candle write).
    verify(repo).upsertContract(
        eq("NFO"), eq(ceSymbol), eq("CE"), eq("NIFTY"), eq(EXPIRY), any(),
        eq(65), any(), eq(true), eq("NSE_FO|CE|16-06-2026"), eq(NIFTY_KEY),
        any(), any(), eq(1), eq(true));
    verify(repo, never()).upsertContract(
        eq("NFO"), eq(peSymbol), any(), any(), any(), any(), anyInt(), any(), anyBoolean(),
        any(), any(), any(), any(), anyInt(), anyBoolean());
  }

  @Test
  @org.junit.jupiter.api.Timeout(15) // MUST never hang — the bug this guards was an unbounded Future.get()
  void aStuckLegIsBoundedThenCancelledAndCountedFailed() {
    UpstoxExpiredInstrumentsClient client = mock(UpstoxExpiredInstrumentsClient.class);
    ExpiredBackfillRepository repo = mock(ExpiredBackfillRepository.class);
    CandleRepository candles = mock(CandleRepository.class);
    stubBand(repo);
    when(client.expiries(NIFTY_KEY)).thenReturn(List.of(EXPIRY));
    when(client.optionContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of(ce()));
    when(client.futureContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of());
    when(repo.coverage(any(), any())).thenReturn(Coverage.NONE);
    // The leg fetch hangs far past the (tiny) per-leg ceiling — simulates a stuck HTTP retry / DB lock.
    when(client.candles(any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              Thread.sleep(10_000);
              return List.of();
            });

    // legTimeoutSec = 1 → the stuck leg is cancelled + counted failed, and run() returns promptly.
    ExpiredBackfillService service = new ExpiredBackfillService(provider(), candles, repo, 0L, 1L);
    ExpiredBackfillService.BackfillSummary summary =
        service.run(client, List.of("NIFTY"), EXPIRY.minusDays(7), EXPIRY, "job-stuck", false);

    assertThat(summary.legsFailed()).isGreaterThanOrEqualTo(1);
    verify(candles, never()).upsertAll(any());
  }

  @Test
  void shortFirstFetchMarksPartial() {
    UpstoxExpiredInstrumentsClient client = mock(UpstoxExpiredInstrumentsClient.class);
    CandleRepository candles = mock(CandleRepository.class);
    ExpiredBackfillRepository repo = mock(ExpiredBackfillRepository.class);
    String ceSymbol = OpenAlgoSymbols.optionSymbol("NIFTY", EXPIRY, new BigDecimal("25000"), "CE");

    when(client.expiries(NIFTY_KEY)).thenReturn(List.of(EXPIRY));
    when(client.optionContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of(ce()));
    when(client.futureContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of());
    stubBand(repo);
    when(repo.coverage("NFO", ceSymbol)).thenReturn(Coverage.NONE);
    // the only bar is days BEFORE expiry → reachedExpiry=false → first fetch is short → PARTIAL.
    when(client.candles(eq("NSE_FO|CE|16-06-2026"), eq("1minute"), any(), any()))
        .thenReturn(List.of(barAt("2026-06-10T15:29:00+05:30")), List.of());

    ExpiredBackfillService service = new ExpiredBackfillService(provider(), candles, repo, 0L);
    service.run(client, List.of("NIFTY"), EXPIRY.minusDays(7), EXPIRY, "job-a", false);

    verify(repo).upsertContract(
        eq("NFO"), eq(ceSymbol), any(), any(), any(), any(), anyInt(), any(), anyBoolean(),
        any(), any(), any(), any(), anyInt(), eq(false));
  }

  @Test
  void partialContractIsReFetchedAndAcceptedComplete() {
    UpstoxExpiredInstrumentsClient client = mock(UpstoxExpiredInstrumentsClient.class);
    CandleRepository candles = mock(CandleRepository.class);
    ExpiredBackfillRepository repo = mock(ExpiredBackfillRepository.class);
    String ceSymbol = OpenAlgoSymbols.optionSymbol("NIFTY", EXPIRY, new BigDecimal("25000"), "CE");

    when(client.expiries(NIFTY_KEY)).thenReturn(List.of(EXPIRY));
    when(client.optionContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of(ce()));
    when(client.futureContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of());
    stubBand(repo);
    // already registered short (PARTIAL) → re-fetched (NOT skipped); even still-short, accepted COMPLETE.
    when(repo.coverage("NFO", ceSymbol)).thenReturn(Coverage.PARTIAL);
    when(client.candles(eq("NSE_FO|CE|16-06-2026"), eq("1minute"), any(), any()))
        .thenReturn(List.of(barAt("2026-06-10T15:29:00+05:30")), List.of());

    ExpiredBackfillService service = new ExpiredBackfillService(provider(), candles, repo, 0L);
    ExpiredBackfillService.BackfillSummary s =
        service.run(client, List.of("NIFTY"), EXPIRY.minusDays(7), EXPIRY, "job-b", false);

    assertThat(s.legsWritten()).isEqualTo(1);
    assertThat(s.legsSkipped()).isZero();
    verify(repo).upsertContract(
        eq("NFO"), eq(ceSymbol), any(), any(), any(), any(), anyInt(), any(), anyBoolean(),
        any(), any(), any(), any(), anyInt(), eq(true));
  }

  @Test
  void emptyWindowIsRetriedOnceBeforeBeingTrustedAsEmpty() {
    UpstoxExpiredInstrumentsClient client = mock(UpstoxExpiredInstrumentsClient.class);
    CandleRepository candles = mock(CandleRepository.class);
    ExpiredBackfillRepository repo = mock(ExpiredBackfillRepository.class);
    String ceSymbol = OpenAlgoSymbols.optionSymbol("NIFTY", EXPIRY, new BigDecimal("25000"), "CE");

    when(client.expiries(NIFTY_KEY)).thenReturn(List.of(EXPIRY));
    when(client.optionContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of(ce()));
    when(client.futureContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of());
    stubBand(repo);
    when(repo.coverage("NFO", ceSymbol)).thenReturn(Coverage.NONE);
    // window 0: [] then (retry) [bar] → the retry rescues the false-empty; later windows empty → stop.
    when(client.candles(eq("NSE_FO|CE|16-06-2026"), eq("1minute"), any(), any()))
        .thenReturn(List.of(), List.of(barAt("2026-06-16T15:29:00+05:30")), List.of(), List.of());

    ExpiredBackfillService service = new ExpiredBackfillService(provider(), candles, repo, 0L);
    ExpiredBackfillService.BackfillSummary s =
        service.run(client, List.of("NIFTY"), EXPIRY.minusDays(7), EXPIRY, "job-c", false);

    // without the retry the first (false-)empty would have yielded zero data; the bar proves the retry.
    assertThat(s.candleRows()).isEqualTo(1);
    verify(candles).upsertAll(any());
  }

  @Test
  void farBackEmptyWindowsAreNotRetried() {
    UpstoxExpiredInstrumentsClient client = mock(UpstoxExpiredInstrumentsClient.class);
    CandleRepository candles = mock(CandleRepository.class);
    ExpiredBackfillRepository repo = mock(ExpiredBackfillRepository.class);
    String ceSymbol = OpenAlgoSymbols.optionSymbol("NIFTY", EXPIRY, new BigDecimal("25000"), "CE");

    when(client.expiries(NIFTY_KEY)).thenReturn(List.of(EXPIRY));
    when(client.optionContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of(ce())); // weekly
    when(client.futureContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of());
    stubBand(repo);
    when(repo.coverage("NFO", ceSymbol)).thenReturn(Coverage.NONE);
    // window 0 (near expiry) has data; windows 1+2 are far-back (< expiry-35d) and empty.
    when(client.candles(eq("NSE_FO|CE|16-06-2026"), eq("1minute"), any(), any()))
        .thenReturn(List.of(barAt("2026-06-16T15:29:00+05:30")), List.of(), List.of());

    ExpiredBackfillService service = new ExpiredBackfillService(provider(), candles, repo, 0L);
    service.run(client, List.of("NIFTY"), EXPIRY.minusDays(40), EXPIRY, "job-d", false);

    // exactly 3 calls: w0 (data, 1) + w1 (far empty, NOT retried, 1) + w2 (far empty, 1) → 2-empty stop.
    // The old retry-every-empty behaviour would have made 5. Far-back retries are the wasted quota.
    verify(client, times(3)).candles(eq("NSE_FO|CE|16-06-2026"), eq("1minute"), any(), any());
  }

  @Test
  void transientErrorOnOneExpirySkipsItAndContinuesTheRun() {
    UpstoxExpiredInstrumentsClient client = mock(UpstoxExpiredInstrumentsClient.class);
    CandleRepository candles = mock(CandleRepository.class);
    ExpiredBackfillRepository repo = mock(ExpiredBackfillRepository.class);
    LocalDate badExpiry = EXPIRY.minusDays(7);
    String ceSymbol = OpenAlgoSymbols.optionSymbol("NIFTY", EXPIRY, new BigDecimal("25000"), "CE");

    when(client.expiries(NIFTY_KEY)).thenReturn(List.of(badExpiry, EXPIRY));
    // first expiry blips (transient DNS/network) on the contract enumeration; the second succeeds.
    when(client.optionContracts(NIFTY_KEY, badExpiry))
        .thenThrow(
            new ResourceAccessException(
                "I/O error", new java.net.UnknownHostException("api.upstox.com")));
    when(client.optionContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of(ce()));
    when(client.futureContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of());
    stubBand(repo);
    when(repo.coverage("NFO", ceSymbol)).thenReturn(Coverage.NONE);
    when(client.candles(eq("NSE_FO|CE|16-06-2026"), eq("1minute"), any(), any()))
        .thenReturn(List.of(barAt("2026-06-16T15:29:00+05:30")), List.of());

    ExpiredBackfillService service = new ExpiredBackfillService(provider(), candles, repo, 0L);
    // The run does NOT throw despite the transient on the first expiry...
    ExpiredBackfillService.BackfillSummary s =
        service.run(client, List.of("NIFTY"), badExpiry.minusDays(7), EXPIRY, "job-e", false);

    // ...the SECOND expiry is still processed (1 leg written) and the blip is counted as a failure.
    assertThat(s.legsWritten()).isEqualTo(1);
    assertThat(s.legsFailed()).isGreaterThanOrEqualTo(1);
    verify(candles).upsertAll(any());
  }
}
