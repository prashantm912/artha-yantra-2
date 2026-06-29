package in.arthayantra.marketdata.futures.screener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.futures.analytics.FuturesSnapshotReader;
import in.arthayantra.marketdata.futures.analytics.FuturesSnapshotReader.EodRow;
import in.arthayantra.marketdata.futures.analytics.FuturesSnapshotReader.FutPoint;
import in.arthayantra.marketdata.options.OiInterpretation;
import in.arthayantra.marketdata.options.OiInterval;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §3.3 Market-Movers WIRING: front-pick + eod-history merge + delegate to the screener core. */
class MarketMoversScreenServiceTest {

  private static final OffsetDateTime B1 =
      OffsetDateTime.of(2026, 6, 26, 9, 45, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
  private static final OffsetDateTime B0 = B1.minusMinutes(3);

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static FutPoint fut(
      OffsetDateTime bucket, String sym, String o, String h, String l, String ltp, long oi,
      LocalDate expiry) {
    return new FutPoint(
        bucket, "HDFCBANK", sym, bd(ltp), oi, 0L, bd(o), bd(h), bd(l), bd("100"), 5_000_000L, expiry);
  }

  @Test
  void returnsNullWhenNoRadarBucket() {
    FuturesSnapshotReader reader = mock(FuturesSnapshotReader.class);
    when(reader.latestPairAll(any(), any(), any())).thenReturn(List.of());
    MarketMoversScreenService svc = new MarketMoversScreenService(reader);
    assertThat(svc.screen(List.of("HDFCBANK"), OiInterval.M3, null)).isNull();
  }

  @Test
  void gradesTheFrontContractLongAndIgnoresTheFarMonth() {
    FuturesSnapshotReader reader = mock(FuturesSnapshotReader.class);
    LocalDate jul = LocalDate.of(2026, 7, 31);
    LocalDate aug = LocalDate.of(2026, 8, 28);
    // Newest bucket: the JUL front (Open=Low strength bar) + the AUG far month (NOT Open=Low).
    FutPoint frontToday = fut(B1, "HDFCBANK26JULFUT", "108", "110", "108", "109", 1100, jul);
    FutPoint farToday = fut(B1, "HDFCBANK26AUGFUT", "120", "125", "118", "121", 900, aug);
    when(reader.latestPairAll(any(), eq(OiInterval.M3), eq(null)))
        .thenReturn(List.of(frontToday, farToday));
    // 9 prior JUL sessions, all highs below today's 110, a gentle up-trend (RSI null < 15 bars => OK).
    List<EodRow> hist = new ArrayList<>();
    for (int i = 1; i <= 9; i++) {
      hist.add(
          new EodRow("HDFCBANK26JULFUT", LocalDate.of(2026, 6, i + 10),
              bd("100"), bd("105"), bd("99"), bd(String.valueOf(100 + i)), 990L + i, 1L, 4_000_000L));
    }
    hist.add(
        new EodRow("HDFCBANK26AUGFUT", LocalDate.of(2026, 6, 20),
            bd("118"), bd("124"), bd("117"), bd("119"), 800L, 1L, 3_000_000L)); // filtered out
    when(reader.eod(eq("HDFCBANK"), any(), any())).thenReturn(hist);

    MarketMoversScreenService svc = new MarketMoversScreenService(reader);
    MarketMoversScreenService.Screen s = svc.screen(List.of("HDFCBANK"), OiInterval.M3, null);

    assertThat(s).isNotNull();
    assertThat(s.asOf()).isEqualTo(B1);
    assertThat(s.longCandidates()).hasSize(1); // the JUL front graded long; the AUG far month ignored
    MarketMoversScreener.ScreenerRow row = s.longCandidates().get(0);
    assertThat(row.symbol()).isEqualTo("HDFCBANK");
    assertThat(row.breakoutDays()).isEqualTo(9); // today 110 high clears all 9 prior 105 highs
    assertThat(row.openLow()).isTrue();
    assertThat(row.interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP); // price up + OI up
    assertThat(s.shortCandidates()).isEmpty();
  }
}
