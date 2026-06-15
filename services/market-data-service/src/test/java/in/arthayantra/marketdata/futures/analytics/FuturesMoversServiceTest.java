package in.arthayantra.marketdata.futures.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FuturesMoversServiceTest {

  private static final OffsetDateTime B0 =
      OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
  private static final OffsetDateTime B1 = B0.plusMinutes(5);

  private static FuturesSnapshotReader.FutPoint p(
      OffsetDateTime b, String sym, String ltp, long oi, String prevClose, LocalDate exp) {
    return new FuturesSnapshotReader.FutPoint(
        b, sym, new BigDecimal(ltp), oi, 0L, null, null, null,
        prevClose == null ? null : new BigDecimal(prevClose), exp);
  }

  @Test
  void moversComputePricePctViaPrevCloseAndSplitGainersLosers() {
    LocalDate exp = LocalDate.parse("2026-06-25");
    // A: prevClose 100, ltp 110 => +10%; oi 1000->1200 (up) => LONG_BUILDUP, gainer
    // B: prevClose 200, ltp 180 => -10%; loser
    List<FuturesSnapshotReader.FutPoint> pair =
        List.of(
            p(B0, "AFUT", "100", 1000, "100", exp),
            p(B1, "AFUT", "110", 1200, "100", exp),
            p(B0, "BFUT", "200", 500, "200", exp),
            p(B1, "BFUT", "180", 600, "200", exp));

    FuturesMoversService.Movers m = new FuturesMoversService().movers(pair);

    assertThat(m.gainers())
        .extracting(FuturesMoversService.MoverRow::tradingsymbol)
        .containsExactly("AFUT");
    assertThat(m.gainers().get(0).pricePct()).isEqualByComparingTo("10.00");
    assertThat(m.gainers().get(0).interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(m.losers())
        .extracting(FuturesMoversService.MoverRow::tradingsymbol)
        .containsExactly("BFUT");
  }

  @Test
  void banksOrderByExpiryWithCalendarSpreadBasis() {
    LocalDate jun = LocalDate.parse("2026-06-25");
    LocalDate jul = LocalDate.parse("2026-07-30");
    List<FuturesSnapshotReader.FutPoint> pair =
        List.of(
            p(B0, "BANKJUL", "128", 900, "128", jul),
            p(B1, "BANKJUL", "130", 1000, "128", jul),
            p(B0, "BANKJUN", "99", 1900, "99", jun),
            p(B1, "BANKJUN", "100", 2000, "99", jun));

    FuturesMoversService.Banks banks = new FuturesMoversService().banks(pair);

    assertThat(banks.items())
        .extracting(FuturesMoversService.BankRow::tradingsymbol)
        .containsExactly("BANKJUN", "BANKJUL"); // expiry-ordered, near first
    assertThat(banks.items().get(0).basis()).isEqualByComparingTo("0"); // near vs itself
    assertThat(banks.items().get(1).basis()).isEqualByComparingTo("30"); // 130 - 100
  }
}
