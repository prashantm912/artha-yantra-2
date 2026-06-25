package in.arthayantra.backtest.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.backtest.client.MarketDataClient;
import in.arthayantra.backtest.client.MarketDataClient.CdRow;
import in.arthayantra.backtest.replay.TradeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OiAttributionServiceTest {

  private static final UUID RUN = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static Map<String, Object> trade(int seq, String sym, String entryTs, String pnl) {
    Map<String, Object> t = new LinkedHashMap<>();
    t.put("seq", seq);
    t.put("tradingsymbol", sym);
    t.put("entryTs", entryTs);
    t.put("pnl", new BigDecimal(pnl));
    return t;
  }

  /** A bullish row: trend ordinal + a few factors set so net() is exercised. */
  private static CdRow row(String label, int trend, int... bullishFactors) {
    int[] f = new int[11];
    for (int i = 0; i < bullishFactors.length && i < 11; i++) {
      f[i] = bullishFactors[i];
    }
    return new CdRow(
        label, trend, f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10]);
  }

  @Test
  void bucketsTradesByEntryTrendAndComputesWinRate() {
    TradeRepository trades = mock(TradeRepository.class);
    MarketDataClient md = mock(MarketDataClient.class);

    // two trades on 2026-06-23 IST: 09:32 (→ 09:30-09:35 bucket) win, 09:47 (→ 09:45-09:50) loss.
    when(trades.findByRun(eq(RUN), org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(), any(), any(), any()))
        .thenReturn(
            List.of(
                trade(1, "NIFTY26JUN2625000CE", "2026-06-23T09:32:00+05:30", "1500.00"),
                trade(2, "NIFTY26JUN2625000CE", "2026-06-23T09:47:00+05:30", "-800.00")));

    // both entries land in the SAME Ext.Bullish (trend 4) bucket window of the matrix.
    when(md.connectingDots(eq("NIFTY 50"), eq(LocalDate.of(2026, 6, 23)), eq("5m")))
        .thenReturn(
            List.of(
                row("09:30-09:35", 4, 1, 1, 1), // net +3
                row("09:45-09:50", 4, 1, 1, 0)));

    OiAttributionService svc = new OiAttributionService(trades, md);
    Map<String, Object> out = svc.attribution(RUN, "5m", null);

    assertThat(out.get("underlying")).isEqualTo("NIFTY 50");
    assertThat(out.get("tradeCount")).isEqualTo(2);
    assertThat(out.get("tradesAttributed")).isEqualTo(2);
    assertThat(out.get("tradesNoData")).isEqualTo(0);
    assertThat(out.get("sessionsCovered")).isEqualTo(1);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
    Map<String, Object> extBull =
        buckets.stream().filter(b -> Integer.valueOf(4).equals(b.get("trend"))).findFirst().orElseThrow();
    assertThat(extBull.get("count")).isEqualTo(2);
    assertThat(extBull.get("wins")).isEqualTo(1);
    assertThat(extBull.get("winRate")).isEqualTo(new BigDecimal("0.5000"));
    assertThat(extBull.get("totalPnl")).isEqualTo(new BigDecimal("700.00"));

    // per-trade attribution carries the bucket label + net vote.
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> perTrade = (List<Map<String, Object>>) out.get("trades");
    assertThat(perTrade).hasSize(2);
    assertThat(perTrade.get(0).get("bucket")).isEqualTo("09:30-09:35");
    assertThat(perTrade.get(0).get("trendLabel")).isEqualTo("Ext.Bullish");
    assertThat(perTrade.get(0).get("net")).isEqualTo(3);
    assertThat(perTrade.get(0).get("win")).isEqualTo(true);
  }

  @Test
  void uncoveredSessionFallsToNoDataBucketNeverDropped() {
    TradeRepository trades = mock(TradeRepository.class);
    MarketDataClient md = mock(MarketDataClient.class);
    when(trades.findByRun(eq(RUN), org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(), any(), any(), any()))
        .thenReturn(List.of(trade(1, "NIFTY26JUN2625000CE", "2026-04-09T10:00:00+05:30", "200.00")));
    when(md.connectingDots(any(), any(), any())).thenReturn(List.of()); // no OI for April

    Map<String, Object> out = new OiAttributionService(trades, md).attribution(RUN, "5m", null);
    assertThat(out.get("tradesNoData")).isEqualTo(1);
    assertThat(out.get("tradesAttributed")).isEqualTo(0);
    assertThat(out.get("sessionsUncovered")).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> perTrade = (List<Map<String, Object>>) out.get("trades");
    assertThat(perTrade.get(0).get("trendLabel")).isEqualTo("NO_DATA");
    assertThat(perTrade.get(0).get("net")).isNull();
  }

  @Test
  void derivesIndexFromSymbolRoots() {
    assertThat(OiAttributionService.indexOf("BANKNIFTY26JUN2652000CE")).isEqualTo("NIFTY BANK");
    assertThat(OiAttributionService.indexOf("FINNIFTY26JUN26CE")).isEqualTo("NIFTY FIN SERVICE");
    assertThat(OiAttributionService.indexOf("MIDCPNIFTY26JUN26CE")).isEqualTo("NIFTY MID SELECT");
    assertThat(OiAttributionService.indexOf("NIFTY26JUN2625000CE")).isEqualTo("NIFTY 50");
    assertThat(OiAttributionService.indexOf("SENSEX26JUN2680000CE")).isEqualTo("SENSEX");
    assertThat(OiAttributionService.indexOf("RELIANCE26JUN26CE")).isNull();
  }

  @Test
  void parsesBothIsoAndPostgresTimestamptzText() {
    // ISO-8601 (the engine/mock path)
    assertThat(OiAttributionService.parseEntry("2026-06-23T09:32:00+05:30").toString())
        .isEqualTo("2026-06-23T09:32+05:30");
    // Postgres timestamptz text: space separator + 2-digit offset (the live JDBC string)
    assertThat(OiAttributionService.parseEntry("2026-06-15 04:56:00+00").toInstant())
        .isEqualTo(java.time.Instant.parse("2026-06-15T04:56:00Z"));
    // …with a fractional second
    assertThat(OiAttributionService.parseEntry("2026-06-15 04:56:00.123+00").toInstant())
        .isEqualTo(java.time.Instant.parse("2026-06-15T04:56:00.123Z"));
  }

  @Test
  void floorsEntryToTheIntervalBucketLabel() {
    OffsetDateTime e = OffsetDateTime.parse("2026-06-23T09:33:40+05:30");
    assertThat(OiAttributionService.bucketLabel(e, 5)).isEqualTo("09:30-09:35");
    assertThat(OiAttributionService.bucketLabel(e, 3)).isEqualTo("09:33-09:36");
    assertThat(OiAttributionService.bucketLabel(e, 15)).isEqualTo("09:30-09:45");
  }
}
