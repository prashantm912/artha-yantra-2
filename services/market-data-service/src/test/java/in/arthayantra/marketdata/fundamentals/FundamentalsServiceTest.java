package in.arthayantra.marketdata.fundamentals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.upstox.UpstoxIncomeStatement;
import in.arthayantra.marketdata.upstox.UpstoxKeyRatios;
import in.arthayantra.marketdata.upstox.UpstoxShareHoldings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure-math test of the Upstox fundamentals → snapshot derivation (no Spring / no DB). */
class FundamentalsServiceTest {

  private final FundamentalsService svc =
      new FundamentalsService(
          null, null, null, Clock.fixed(Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC));

  private static UpstoxShareHoldings holdings(String... promoterPctByQuarter) {
    List<UpstoxShareHoldings.Point> hist =
        java.util.Arrays.stream(promoterPctByQuarter)
            .map(v -> new UpstoxShareHoldings.Point("Q", new BigDecimal(v)))
            .toList();
    return new UpstoxShareHoldings(
        "success", List.of(new UpstoxShareHoldings.Category("promoters", hist)));
  }

  private static UpstoxIncomeStatement income(String[] netProfit, String[] revenue) {
    return new UpstoxIncomeStatement(
        "success",
        new UpstoxIncomeStatement.Data(
            "crore",
            "yearly",
            List.of(
                new UpstoxIncomeStatement.Line("net_profit", points(netProfit)),
                new UpstoxIncomeStatement.Line("revenue", points(revenue)))));
  }

  private static List<UpstoxIncomeStatement.Point> points(String[] vals) {
    return java.util.Arrays.stream(vals)
        .map(v -> new UpstoxIncomeStatement.Point(new BigDecimal(v), "P", null))
        .toList();
  }

  @Test
  void derivesFreeFloatMarketCapAndRoe() {
    UpstoxShareHoldings sh = holdings("70", "72"); // latest promoter 72% → free float 28%
    UpstoxKeyRatios kr =
        new UpstoxKeyRatios(
            "success",
            List.of(
                new UpstoxKeyRatios.Ratio("P/E", "25", "30"),
                new UpstoxKeyRatios.Ratio("ROE %", "18.5", "15")));
    UpstoxIncomeStatement is =
        income(new String[] {"100", "120"}, new String[] {"800", "1000"}); // latest np 120, rev 1000

    EquityFundamentals f = svc.derive("ACME", "INE001A01001", sh, kr, is);

    assertThat(f.promoterPct()).isEqualByComparingTo("72");
    assertThat(f.freeFloatPct()).isEqualByComparingTo("28");
    assertThat(f.pe()).isEqualByComparingTo("25");
    assertThat(f.netProfitCr()).isEqualByComparingTo("120");
    assertThat(f.marketCapCr()).as("pe × net-profit").isEqualByComparingTo("3000.00");
    assertThat(f.freeFloatMcapCr()).as("mcap × free-float%").isEqualByComparingTo("840.00");
    assertThat(f.roe()).isEqualByComparingTo("18.5");
    assertThat(f.revenueCr()).isEqualByComparingTo("1000");
    assertThat(f.revenuePrevCr()).isEqualByComparingTo("800");
    assertThat(f.asOf()).isEqualTo(java.time.LocalDate.of(2026, 7, 4));
  }

  @Test
  void marketCapNullForLossMaker() {
    EquityFundamentals f =
        svc.derive(
            "LOSS",
            "INE002",
            holdings("60"),
            new UpstoxKeyRatios("success", List.of(new UpstoxKeyRatios.Ratio("P/E", "-", "10"))),
            income(new String[] {"-50"}, new String[] {"500"}));

    assertThat(f.freeFloatPct()).isEqualByComparingTo("40");
    assertThat(f.marketCapCr()).as("no market cap from a negative net profit").isNull();
    assertThat(f.freeFloatMcapCr()).isNull();
  }
}
