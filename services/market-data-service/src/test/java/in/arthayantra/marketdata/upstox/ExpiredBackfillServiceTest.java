package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.openalgo.live.OpenAlgoSymbols;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Bar;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Leg;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit test for {@link ExpiredBackfillService#run}: a (underlying, expiry) chain is walked into the
 * {@code candles} hypertable under the canonical OpenAlgo symbol with {@code source='BACKFILL'}, each
 * contract is registered in {@code expired_contracts}, and a contract whose candles already exist is
 * skipped (the resumable-rerun guard). Client + repositories are mocked — no DB.
 */
class ExpiredBackfillServiceTest {

  private static final String NIFTY_KEY = "NSE_INDEX|Nifty 50";
  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 16);

  @SuppressWarnings("unchecked")
  @Test
  void walksChainIntoCandlesRegistersContractsAndSkipsCovered() {
    UpstoxExpiredInstrumentsClient client = mock(UpstoxExpiredInstrumentsClient.class);
    CandleRepository candles = mock(CandleRepository.class);
    ExpiredBackfillRepository repo = mock(ExpiredBackfillRepository.class);
    ObjectProvider<UpstoxExpiredInstrumentsClient> provider = mock(ObjectProvider.class);

    Leg ce =
        new Leg(
            "NSE_FO", "NSE_FO|CE|16-06-2026", "CE", "NIFTY", NIFTY_KEY, EXPIRY,
            new BigDecimal("25000"), 65, new BigDecimal("5"), true);
    Leg pe =
        new Leg(
            "NSE_FO", "NSE_FO|PE|16-06-2026", "PE", "NIFTY", NIFTY_KEY, EXPIRY,
            new BigDecimal("21200"), 65, new BigDecimal("5"), true);
    Bar bar =
        new Bar(
            OffsetDateTime.parse("2026-06-16T15:29:00+05:30"),
            new BigDecimal("238.95"), new BigDecimal("239.2"),
            new BigDecimal("238.15"), new BigDecimal("239.1"), 22750, 315185L);

    String ceSymbol = OpenAlgoSymbols.optionSymbol("NIFTY", EXPIRY, new BigDecimal("25000"), "CE");
    String peSymbol = OpenAlgoSymbols.optionSymbol("NIFTY", EXPIRY, new BigDecimal("21200"), "PE");

    when(client.expiries(NIFTY_KEY)).thenReturn(List.of(EXPIRY));
    when(client.optionContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of(ce, pe));
    when(client.futureContracts(NIFTY_KEY, EXPIRY)).thenReturn(List.of());
    // CE: not yet registered → fetched (a window of data, then two empties → stop). PE: registered → skipped.
    when(repo.isRegistered("NFO", ceSymbol)).thenReturn(false);
    when(repo.isRegistered("NFO", peSymbol)).thenReturn(true);
    when(client.candles(eq("NSE_FO|CE|16-06-2026"), eq("1minute"), any(), any()))
        .thenReturn(List.of(bar), List.of());

    ExpiredBackfillService service = new ExpiredBackfillService(provider, candles, repo, 0L);
    ExpiredBackfillService.BackfillSummary summary =
        service.run(client, List.of("NIFTY"), EXPIRY.minusDays(7), EXPIRY, "job-1");

    assertThat(summary.expiries()).isEqualTo(1);
    assertThat(summary.contracts()).isEqualTo(2);
    assertThat(summary.legsWritten()).isEqualTo(1);
    assertThat(summary.legsSkipped()).isEqualTo(1);
    assertThat(summary.legsFailed()).isZero();
    assertThat(summary.candleRows()).isEqualTo(1);

    ArgumentCaptor<List<Candle>> rows = ArgumentCaptor.forClass(List.class);
    verify(candles).upsertAll(rows.capture());
    Candle row = rows.getValue().get(0);
    assertThat(row.exchange()).isEqualTo("NFO");
    assertThat(row.tradingsymbol()).isEqualTo(ceSymbol);
    assertThat(row.interval()).isEqualTo("1m");
    assertThat(row.source()).isEqualTo("BACKFILL");
    assertThat(row.oi()).isEqualTo(315185L);
    assertThat(row.close()).isEqualByComparingTo("239.1");

    // CE registered; PE was skipped before any registration / candle write.
    verify(repo).upsertContract(
        eq("NFO"), eq(ceSymbol), eq("CE"), eq("NIFTY"), eq(EXPIRY), any(),
        eq(65), any(), eq(true), eq("NSE_FO|CE|16-06-2026"), eq(NIFTY_KEY));
    verify(repo, never()).upsertContract(
        eq("NFO"), eq(peSymbol), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
        any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());

    // candles were written → the derived aggregates are materialized over the backfilled span.
    verify(candles).refreshDerivedAggregates(any(), any());
  }
}
