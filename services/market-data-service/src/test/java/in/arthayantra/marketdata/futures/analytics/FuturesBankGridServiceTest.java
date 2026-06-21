package in.arthayantra.marketdata.futures.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FuturesBankGridServiceTest {

  private static final OffsetDateTime B0 =
      OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
  private static final OffsetDateTime B1 = B0.plusMinutes(5);

  private static FuturesSnapshotReader.FutPoint p(
      OffsetDateTime b, String under, String sym, String ltp, long oi, String prevClose,
      LocalDate exp) {
    return new FuturesSnapshotReader.FutPoint(
        b, under, sym, new BigDecimal(ltp), oi, 0L, null, null, null,
        prevClose == null ? null : new BigDecimal(prevClose), null, exp);
  }

  @Test
  void picksFrontContractPerUnderlyingAndBuilds4StateRow() {
    LocalDate jun = LocalDate.parse("2026-06-25");
    LocalDate jul = LocalDate.parse("2026-07-30");
    // HDFCBANK: front (jun) prevClose 100, ltp 110 => +10%; oi 1000->1200 up => LONG_BUILDUP.
    //   far (jul) contract present too — must be IGNORED (front only).
    // SBIN: front (jun) prevClose 200, ltp 198 => -1%; oi 5000->4800 down => LONG_UNWINDING.
    List<FuturesSnapshotReader.FutPoint> pair =
        List.of(
            p(B0, "HDFCBANK", "HDFCBANKJUN", "100", 1000, "100", jun),
            p(B1, "HDFCBANK", "HDFCBANKJUN", "110", 1200, "100", jun),
            p(B0, "HDFCBANK", "HDFCBANKJUL", "105", 50, "105", jul),
            p(B1, "HDFCBANK", "HDFCBANKJUL", "106", 60, "105", jul),
            p(B0, "SBIN", "SBINJUN", "200", 5000, "200", jun),
            p(B1, "SBIN", "SBINJUN", "198", 4800, "200", jun));

    FuturesBankGridService.BankGrid grid = new FuturesBankGridService().grid(pair);

    // one row per underlying, front contract only, sorted by oi desc (SBIN 4800 > HDFCBANK 1200)
    assertThat(grid.items())
        .extracting(FuturesBankGridService.BankGridRow::underlying)
        .containsExactly("SBIN", "HDFCBANK");
    FuturesBankGridService.BankGridRow hdfc =
        grid.items().stream().filter(r -> r.underlying().equals("HDFCBANK")).findFirst().get();
    assertThat(hdfc.tradingsymbol()).isEqualTo("HDFCBANKJUN");
    assertThat(hdfc.pricePct()).isEqualByComparingTo("10.00");
    assertThat(hdfc.oiChange()).isEqualTo(200L);
    assertThat(hdfc.oiPct()).isEqualByComparingTo("20.00");
    assertThat(hdfc.interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(grid.items().get(0).interpretation()).isEqualTo(OiInterpretation.LONG_UNWINDING);
    assertThat(grid.asOf()).isEqualTo(B1);
  }

  @Test
  void emptyPairYieldsEmptyGrid() {
    FuturesBankGridService.BankGrid grid = new FuturesBankGridService().grid(List.of());
    assertThat(grid.items()).isEmpty();
    assertThat(grid.asOf()).isNull();
  }
}
