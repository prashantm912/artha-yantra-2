package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.ReplayResult;
import in.arthayantra.backtest.replay.Trade;
import in.arthayantra.backtest.replay.options.OptionContractSelector.Catalog;
import in.arthayantra.backtest.replay.options.OptionContractSelector.Expiry;
import in.arthayantra.backtest.replay.options.OptionContractSelector.ExpiryMode;
import in.arthayantra.backtest.replay.options.OptionContractSelector.OptionContract;
import in.arthayantra.backtest.replay.options.OptionsPremiumReplay.PairedLeg;
import in.arthayantra.backtest.replay.options.OptionsPremiumReplay.UniverseSpec;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The dedicated premium-as-primary PARITY GOLDEN (fixtures): a deterministic 3-leg scenario pins the
 * exact premium trades + equity curve, separate from the candle-close {@code BacktestParityTest}. Leg
 * A is a long CE take-profit, B a long CE stop-loss (a DIFFERENT ATM strike as spot moved), C a short
 * PE signal-exit. Any change to the premium path that perturbs the numerics fails here; a re-run
 * proves determinism.
 */
class OptionsPremiumGoldenTest {

  private static final LocalDate W = LocalDate.of(2026, 6, 16);

  private static OffsetDateTime t(String hhmm) {
    return OffsetDateTime.parse("2026-06-16T" + hhmm + ":00+05:30");
  }

  private static EngineCandle bar(String hhmm, String close) {
    BigDecimal c = new BigDecimal(close);
    return new EngineCandle(t(hhmm), c, c, c, c, 0L);
  }

  /** ATM = spot rounded to the nearest 500; symbol = NIFTY16JUN26<strike><type>, lot 65. */
  private static final Catalog CATALOG =
      new Catalog() {
        @Override
        public List<Expiry> expiriesOnOrAfter(String underlying, LocalDate date) {
          return List.of(new Expiry(W, true));
        }

        @Override
        public Optional<OptionContract> nearestStrike(
            String underlying, LocalDate expiry, String optionType, BigDecimal spot) {
          BigDecimal strike =
              spot.divide(new BigDecimal("500"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("500"));
          String symbol = "NIFTY16JUN26" + strike.stripTrailingZeros().toPlainString() + optionType;
          return Optional.of(new OptionContract("NFO", symbol, strike, expiry, optionType, 65));
        }
      };

  private static EngineCandle prem(String hhmm, String px) {
    BigDecimal p = new BigDecimal(px);
    return new EngineCandle(t(hhmm), p, p, p, p, 0L);
  }

  private OptionsPremiumReplay replay() {
    CandleReader reader = mock(CandleReader.class);
    // leg A: 25000 CE — 80 → 90 → 110 (TP +35% = 108 at offset 2)
    when(reader.read(eq("NFO"), eq("NIFTY16JUN2625000CE"), eq("1m"), any(), any()))
        .thenReturn(List.of(prem("09:15", "80"), prem("09:16", "90"), prem("09:17", "110")));
    // leg B: 25500 CE — 100 → 88 → 78 (SL -20% = 80 at offset 2)
    when(reader.read(eq("NFO"), eq("NIFTY16JUN2625500CE"), eq("1m"), any(), any()))
        .thenReturn(List.of(prem("09:19", "100"), prem("09:20", "88"), prem("09:21", "78")));
    // leg C: 24500 PE — 60 → 62 → 63 (no bracket → signal exit at offset 2)
    when(reader.read(eq("NFO"), eq("NIFTY16JUN2624500PE"), eq("1m"), any(), any()))
        .thenReturn(List.of(prem("09:23", "60"), prem("09:24", "62"), prem("09:25", "63")));
    return new OptionsPremiumReplay(new OptionContractSelector(CATALOG), new CandlePremiumReader(reader));
  }

  private static final List<EngineCandle> UNDERLYING =
      List.of(
          bar("09:15", "25000"), bar("09:16", "24990"), bar("09:17", "25010"), bar("09:18", "25020"),
          bar("09:19", "25500"), bar("09:20", "25490"), bar("09:21", "25510"), bar("09:22", "25480"),
          bar("09:23", "24500"), bar("09:24", "24510"), bar("09:25", "24490"));

  private static final List<PairedLeg> LEGS =
      List.of(
          new PairedLeg(false, 0, 3), // long  → CE 25000, TP
          new PairedLeg(false, 4, 7), // long  → CE 25500, SL
          new PairedLeg(true, 8, 10)); // short → PE 24500, signal exit

  private static final UniverseSpec SPEC =
      new UniverseSpec(ExpiryMode.NEAREST_WEEKLY, 0, Set.of("CE", "PE"));
  private static final PremiumExitEvaluator.Rules RULES =
      new PremiumExitEvaluator.Rules(new BigDecimal("20"), new BigDecimal("35"), null, null, null);

  @Test
  void pinsTheThreeTradesAndEquityCurve() {
    ReplayResult r =
        replay().replayLegs(List.of(), UNDERLYING, LEGS, "NIFTY", SPEC, RULES, 15_000, new BigDecimal("200000"));

    assertThat(r.trades()).hasSize(3);

    // leg A — long CE 25000, TP: entry 80 → exit 110, qty 130, +3900
    Trade a = r.trades().get(0);
    assertThat(a.tradingsymbol()).isEqualTo("NIFTY16JUN2625000CE");
    assertThat(a.side()).isEqualTo(Side.BUY);
    assertThat(a.qty()).isEqualTo(130);
    assertThat(a.entryPrice()).isEqualByComparingTo("80");
    assertThat(a.exitPrice()).isEqualByComparingTo("110");
    assertThat(a.exitReason()).isEqualTo("TAKE_PROFIT");
    assertThat(a.pnl()).isEqualByComparingTo("3900.00");

    // leg B — long CE 25500, SL: entry 100 → exit 78, qty 130, -2860
    Trade b = r.trades().get(1);
    assertThat(b.tradingsymbol()).isEqualTo("NIFTY16JUN2625500CE");
    assertThat(b.entryPrice()).isEqualByComparingTo("100");
    assertThat(b.exitPrice()).isEqualByComparingTo("78");
    assertThat(b.exitReason()).isEqualTo("STOP_LOSS");
    assertThat(b.pnl()).isEqualByComparingTo("-2860.00");

    // leg C — short → PE 24500, signal exit: entry 60 → exit 63, qty 195, +585
    Trade c = r.trades().get(2);
    assertThat(c.tradingsymbol()).isEqualTo("NIFTY16JUN2624500PE");
    assertThat(c.qty()).isEqualTo(195);
    assertThat(c.entryPrice()).isEqualByComparingTo("60");
    assertThat(c.exitPrice()).isEqualByComparingTo("63");
    assertThat(c.exitReason()).isEqualTo("SIGNAL_EXIT");
    assertThat(c.pnl()).isEqualByComparingTo("585.00");

    // equity: 200000 → +3900 @bar2 → -2860 @bar6 → +585 @bar10 = 201625; 6 bars in position
    assertThat(r.finalEquity()).isEqualByComparingTo("201625.00");
    assertThat(r.barsInPosition()).isEqualTo(6);
    assertThat(r.totalBars()).isEqualTo(11);
    assertThat(r.equityCurve().get(0).equity()).isEqualByComparingTo("200000.00");
    assertThat(r.equityCurve().get(5).equity()).isEqualByComparingTo("203900.00"); // after leg A
    assertThat(r.equityCurve().get(9).equity()).isEqualByComparingTo("201040.00"); // after leg B
    assertThat(r.equityCurve().get(10).equity()).isEqualByComparingTo("201625.00"); // after leg C
  }

  @Test
  void isDeterministicAcrossRuns() {
    ReplayResult r1 =
        replay().replayLegs(List.of(), UNDERLYING, LEGS, "NIFTY", SPEC, RULES, 15_000, new BigDecimal("200000"));
    ReplayResult r2 =
        replay().replayLegs(List.of(), UNDERLYING, LEGS, "NIFTY", SPEC, RULES, 15_000, new BigDecimal("200000"));
    assertThat(r2.trades()).isEqualTo(r1.trades());
    assertThat(r2.finalEquity()).isEqualByComparingTo(r1.finalEquity());
    assertThat(r2.equityCurve()).isEqualTo(r1.equityCurve());
  }
}
