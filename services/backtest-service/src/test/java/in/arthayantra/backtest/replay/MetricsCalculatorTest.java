package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exact-value fixtures for the §6.2 evolution-engine metric adds (E1): recoveryFactor, tradeFrequency
 * and turnover. Each asserts a hand-computed expected string so a change in the formula, scale, unit,
 * or null semantics turns red. Pure computation over deterministic inputs — no wall-clock, no random.
 */
class MetricsCalculatorTest {

  private static final BigDecimal INITIAL = new BigDecimal("100000");
  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T09:15:00+05:30");
  private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-01-02T09:15:00+05:30");
  private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-01-03T09:15:00+05:30");

  private final MetricsCalculator calc = new MetricsCalculator(new ObjectMapper());

  @Test
  void recoveryFactorTradeFrequencyTurnoverOnANormalDailyRun() {
    // equity 100000 -> 90000 -> 120000: totalReturn +20%, maxDD 10% (from the 100000 peak to 90000).
    List<EquityPoint> equity =
        List.of(
            new EquityPoint(T0, new BigDecimal("100000")),
            new EquityPoint(T1, new BigDecimal("90000")),
            new EquityPoint(T2, new BigDecimal("120000")));
    // Two closed trades: fills = |100*10|+|120*10|+|200*5|+|190*5| = 1000+1200+1000+950 = 4150.
    List<Trade> trades =
        List.of(
            closedTrade(10, "100", "120", "200"),
            closedTrade(5, "200", "190", "-50"));

    MetricsCalculator.Metrics m =
        calc.compute(trades, equity, INITIAL, new BigDecimal("120000"), "1d", 4L, 2L);
    ObjectNode full = m.full();

    assertThat(full.get("totalReturn").asText()).isEqualTo("20.000000");
    assertThat(full.get("maxDrawdown").asText()).isEqualTo("10.000000");
    // recoveryFactor = 20 / 10 = 2 (both are percentages, so the ratio is dimensionless).
    assertThat(full.get("recoveryFactor").asText()).isEqualTo("2.000000");
    // 1d => barsPerSession 1, sessions = 4 bars / 1 = 4; tradeFrequency = 2 trades / 4 = 0.5.
    assertThat(full.get("tradeFrequency").asText()).isEqualTo("0.500000");
    // turnover = 4150 / 100000 (a multiple of the starting account, NOT a percentage).
    assertThat(full.get("turnover").asText()).isEqualTo("0.041500");
    assertThat(full.get("tradeCount").asInt()).isEqualTo(2);
  }

  @Test
  void recoveryFactorIsJsonNullWhenThereIsNoDrawdown() {
    // Monotonic-rising equity => maxDD == 0 => recoveryFactor undefined (null), key still present.
    List<EquityPoint> equity =
        List.of(
            new EquityPoint(T0, new BigDecimal("100000")),
            new EquityPoint(T1, new BigDecimal("110000")),
            new EquityPoint(T2, new BigDecimal("120000")));
    MetricsCalculator.Metrics m =
        calc.compute(
            List.of(closedTrade(1, "100", "110", "10")),
            equity,
            INITIAL,
            new BigDecimal("120000"),
            "1d",
            3L,
            1L);
    ObjectNode full = m.full();

    assertThat(m.maxDrawdown()).isEqualByComparingTo("0"); // no drawdown -> BigDecimal.ZERO
    assertThat(full.has("recoveryFactor")).isTrue();
    assertThat(full.get("recoveryFactor").isNull()).isTrue();
  }

  @Test
  void emptyRunEmitsAllThreeKeysWithZeroOrNull() {
    MetricsCalculator.Metrics m =
        calc.compute(List.of(), List.of(), INITIAL, INITIAL, "1d", 0L, 0L);
    ObjectNode full = m.full();

    // All three keys must be present unconditionally (the metrics catalog pins the emit key set).
    assertThat(full.has("recoveryFactor")).isTrue();
    assertThat(full.get("recoveryFactor").isNull()).isTrue(); // maxDD == 0 on an empty run
    assertThat(full.get("tradeFrequency").asText()).isEqualTo("0.000000"); // no bars
    assertThat(full.get("turnover").asText()).isEqualTo("0.000000"); // no fills
    assertThat(full.get("tradeCount").asInt()).isZero();
  }

  @Test
  void tradeFrequencyIsTradesPerSessionOnAnIntradayRun() {
    // 1m => barsPerSession 375; 750 bars = 2 sessions; 3 trades / 2 = 1.5 trades per session.
    List<EquityPoint> equity =
        List.of(
            new EquityPoint(T0, new BigDecimal("100000")),
            new EquityPoint(T1, new BigDecimal("101000")));
    List<Trade> trades =
        List.of(
            closedTrade(1, "100", "101", "1"),
            closedTrade(1, "100", "101", "1"),
            closedTrade(1, "100", "101", "1"));
    MetricsCalculator.Metrics m =
        calc.compute(trades, equity, INITIAL, new BigDecimal("101000"), "1m", 750L, 100L);

    assertThat(m.full().get("tradeFrequency").asText()).isEqualTo("1.500000");
  }

  @Test
  void tradeFrequencyOnA3mPrimaryUsesA125BarSession() {
    // #721 regression (chip task_547656bf): 3m was absent from the interval table and fell through to
    // barsPerSession == 1, so tradeFrequency read trades-per-BAR (~125x the true rate). 125 3m bars ==
    // one session (375 trading minutes / 3), so 3 trades over 125 bars == 3.0 trades per session
    // (pre-fix this read 3 / 125 == 0.024000).
    List<EquityPoint> equity =
        List.of(
            new EquityPoint(T0, new BigDecimal("100000")),
            new EquityPoint(T1, new BigDecimal("101000")));
    List<Trade> trades =
        List.of(
            closedTrade(1, "100", "101", "1"),
            closedTrade(1, "100", "101", "1"),
            closedTrade(1, "100", "101", "1"));
    MetricsCalculator.Metrics m =
        calc.compute(trades, equity, INITIAL, new BigDecimal("101000"), "3m", 125L, 50L);

    assertThat(m.full().get("tradeFrequency").asText()).isEqualTo("3.000000");
  }

  @Test
  void tradeFrequencyPreservesTheEnumeratedIntervalDivisors() {
    // The 3m addition must not perturb the intervals periodsPerYear already enumerates: 5m == 75
    // bars/session and 1h == 6.25 bars/session (375/60), byte-identical to the prior derivation.
    List<EquityPoint> equity =
        List.of(
            new EquityPoint(T0, new BigDecimal("100000")),
            new EquityPoint(T1, new BigDecimal("101000")));
    List<Trade> three =
        List.of(
            closedTrade(1, "100", "101", "1"),
            closedTrade(1, "100", "101", "1"),
            closedTrade(1, "100", "101", "1"));
    // 5m: 150 bars / 75 == 2 sessions; 3 trades / 2 == 1.5.
    assertThat(
            calc.compute(three, equity, INITIAL, new BigDecimal("101000"), "5m", 150L, 50L)
                .full()
                .get("tradeFrequency")
                .asText())
        .isEqualTo("1.500000");
    // 1h: 25 bars / 6.25 == 4 sessions; 1 trade / 4 == 0.25 (pins 6.25, NOT 7).
    assertThat(
            calc.compute(
                    List.of(closedTrade(1, "100", "101", "1")),
                    equity,
                    INITIAL,
                    new BigDecimal("101000"),
                    "1h",
                    25L,
                    10L)
                .full()
                .get("tradeFrequency")
                .asText())
        .isEqualTo("0.250000");
  }

  @Test
  void turnoverCountsAnOpenAtEndTradesEntryLegOnly() {
    // One closed trade (entry+exit fills) + one open-at-end trade (entry fill only, no exit).
    // fills = |100*10|+|120*10| + |200*5| = 1000+1200+1000 = 3200.
    List<Trade> trades =
        List.of(
            closedTrade(10, "100", "120", "200"),
            openTrade(5, "200"));
    List<EquityPoint> equity =
        List.of(
            new EquityPoint(T0, new BigDecimal("100000")),
            new EquityPoint(T1, new BigDecimal("101000")));
    MetricsCalculator.Metrics m =
        calc.compute(trades, equity, INITIAL, new BigDecimal("101000"), "1d", 4L, 2L);
    ObjectNode full = m.full();

    assertThat(full.get("turnover").asText()).isEqualTo("0.032000");
    assertThat(full.get("tradeCount").asInt()).isEqualTo(1); // the open trade is not a closed trade
  }

  private static Trade closedTrade(long qty, String entry, String exit, String pnl) {
    return new Trade(
        0, null, qty, T0, new BigDecimal(entry), T1, new BigDecimal(exit),
        new BigDecimal(pnl), null, null, 0, null, null, null, null, null, null);
  }

  private static Trade openTrade(long qty, String entry) {
    return new Trade(
        0, null, qty, T0, new BigDecimal(entry), null, null,
        BigDecimal.ZERO, null, null, 0, null, null, null, null, null, null);
  }
}
