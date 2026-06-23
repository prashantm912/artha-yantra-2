package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.Trade;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.backtest.replay.options.OptionContractSelector.Catalog;
import in.arthayantra.backtest.replay.options.OptionContractSelector.Expiry;
import in.arthayantra.backtest.replay.options.OptionContractSelector.ExpiryMode;
import in.arthayantra.backtest.replay.options.OptionContractSelector.OptionContract;
import in.arthayantra.backtest.replay.options.OptionsPremiumReplay.PairedLeg;
import in.arthayantra.backtest.replay.options.OptionsPremiumReplay.UniverseSpec;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fixture test for {@link OptionsPremiumReplay#tradeForLeg}: a long underlying signal buys the ATM CE
 * and is taken to its premium take-profit — the trade is on the OPTION, filled + P&L'd on the option's
 * own premium series. A fake {@link Catalog} resolves the strike and a mocked {@link CandleReader}
 * supplies the premium candles, so no DB is needed.
 */
class OptionsPremiumReplayTest {

  private static final LocalDate W = LocalDate.of(2026, 6, 16);
  private static final String CE_SYMBOL = "NIFTY16JUN2625000CE";

  private static OffsetDateTime t(String hhmm) {
    return OffsetDateTime.parse("2026-06-16T" + hhmm + ":00+05:30");
  }

  private static EngineCandle bar(String hhmm, String close) {
    BigDecimal c = new BigDecimal(close);
    return new EngineCandle(t(hhmm), c, c, c, c, 0L);
  }

  /** Fake registry: nearest-weekly 2026-06-16, the ATM-25000 CE at lot 65. */
  private static final Catalog CATALOG =
      new Catalog() {
        @Override
        public List<Expiry> expiriesOnOrAfter(String underlying, LocalDate date) {
          return List.of(new Expiry(W, true));
        }

        @Override
        public Optional<OptionContract> nearestStrike(
            String underlying, LocalDate expiry, String optionType, BigDecimal spot) {
          return Optional.of(
              new OptionContract("NFO", CE_SYMBOL, new BigDecimal("25000"), expiry, optionType, 65));
        }
      };

  @Test
  void longSignalBuysAtmCeAndTakesPremiumProfit() {
    // underlying spot path (only the entry-bar close matters for strike selection)
    List<EngineCandle> underlying =
        List.of(
            bar("09:15", "24980"), bar("09:16", "24990"), bar("09:17", "25010"),
            bar("09:18", "25020"), bar("09:19", "25030"));

    // the CE's premium candles: 80 → 90 → 110 (110 ≥ the +35% target 108 at offset 2)
    CandleReader reader = mock(CandleReader.class);
    when(reader.read(eq("NFO"), eq(CE_SYMBOL), eq("1m"), any(), any()))
        .thenReturn(
            List.of(
                new EngineCandle(t("09:15"), bd("80"), bd("80"), bd("80"), bd("80"), 0L),
                new EngineCandle(t("09:16"), bd("90"), bd("90"), bd("90"), bd("90"), 0L),
                new EngineCandle(t("09:17"), bd("110"), bd("110"), bd("110"), bd("110"), 0L)));

    OptionsPremiumReplay replay =
        new OptionsPremiumReplay(
            new OptionContractSelector(CATALOG), new CandlePremiumReader(reader));

    PairedLeg leg = new PairedLeg(false, 0, 4); // long, entry bar 0, signal-exit bar 4
    UniverseSpec spec = new UniverseSpec(ExpiryMode.NEAREST_WEEKLY, 0, Set.of("CE", "PE"));
    PremiumExitEvaluator.Rules rules =
        new PremiumExitEvaluator.Rules(bd("20"), bd("35"), null, null, null);

    Optional<Trade> t = replay.tradeForLeg(1, underlying, "NIFTY", leg, spec, rules, 15_000);

    assertThat(t).isPresent();
    Trade trade = t.get();
    assertThat(trade.exchange()).isEqualTo("NFO");
    assertThat(trade.tradingsymbol()).isEqualTo(CE_SYMBOL);
    assertThat(trade.side()).isEqualTo(Side.BUY);
    // lots = floor(15000 / (80 × 65)) = 2 → qty 130
    assertThat(trade.qty()).isEqualTo(130);
    // observed 80→110 fill at 80.05/109.95 (1-tick option slippage)
    assertThat(trade.entryPrice()).isEqualByComparingTo("80.05");
    assertThat(trade.exitPrice()).isEqualByComparingTo("109.95");
    assertThat(trade.exitReason()).isEqualTo("TAKE_PROFIT");
    assertThat(trade.barsHeld()).isEqualTo(2);
    // pnl = gross (109.95-80.05)×130 minus the full options cost stack = 3767.76 (vs +3900 cost-free)
    assertThat(trade.pnl()).isEqualByComparingTo("3767.76");
    assertThat(trade.pnlPct()).isEqualByComparingTo("36.228462");
    // entry-time protective levels: 80 × 0.80 = 64 stop, 80 × 1.35 = 108 target
    assertThat(trade.stopLoss()).isEqualByComparingTo("64");
    assertThat(trade.takeProfit()).isEqualByComparingTo("108");
    assertThat(trade.entryTs()).isEqualTo(t("09:15"));
    assertThat(trade.exitTs()).isEqualTo(t("09:17"));
  }

  @Test
  void noContractForBiasYieldsNoTrade() {
    Catalog ceOnly =
        new Catalog() {
          @Override
          public List<Expiry> expiriesOnOrAfter(String u, LocalDate d) {
            return List.of(new Expiry(W, true));
          }

          @Override
          public Optional<OptionContract> nearestStrike(
              String u, LocalDate e, String type, BigDecimal s) {
            return Optional.of(new OptionContract("NFO", CE_SYMBOL, bd("25000"), e, type, 65));
          }
        };
    OptionsPremiumReplay replay =
        new OptionsPremiumReplay(
            new OptionContractSelector(ceOnly), new CandlePremiumReader(mock(CandleReader.class)));

    // a SHORT signal needs a PE, but option_types only allows CE → no trade.
    Optional<Trade> t =
        replay.tradeForLeg(
            1,
            List.of(bar("09:15", "24980"), bar("09:16", "24990")),
            "NIFTY",
            new PairedLeg(true, 0, 1),
            new UniverseSpec(ExpiryMode.NEAREST_WEEKLY, 0, Set.of("CE")),
            new PremiumExitEvaluator.Rules(bd("20"), bd("35"), null, null, null),
            15_000);
    assertThat(t).isEmpty();
  }

  @Test
  void aResolvedContractWithNoBackfilledPremiumFailsWithDataGap() {
    // The contract resolves, but its premium series is empty (not backfilled) → fail-run 422 DATA_GAP,
    // NOT a silent skip (the owner-chosen behavior — a hidden 0-trade run masks a data hole).
    CandleReader reader = mock(CandleReader.class);
    when(reader.read(eq("NFO"), eq(CE_SYMBOL), eq("1m"), any(), any())).thenReturn(List.of());
    OptionsPremiumReplay replay =
        new OptionsPremiumReplay(
            new OptionContractSelector(CATALOG), new CandlePremiumReader(reader));

    assertThatThrownBy(
            () ->
                replay.tradeForLeg(
                    1,
                    List.of(bar("09:15", "24980"), bar("09:16", "24990"), bar("09:17", "25010")),
                    "NIFTY",
                    new PairedLeg(false, 0, 2),
                    new UniverseSpec(ExpiryMode.NEAREST_WEEKLY, 0, Set.of("CE", "PE")),
                    new PremiumExitEvaluator.Rules(bd("20"), bd("35"), null, null, null),
                    15_000))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("no backfilled premium coverage");
  }

  @Test
  void replayLegsBuildsResultWithPerBarMtmEquity() {
    List<EngineCandle> underlying =
        List.of(
            bar("09:15", "24980"), bar("09:16", "24990"), bar("09:17", "25010"),
            bar("09:18", "25020"), bar("09:19", "25030"));
    CandleReader reader = mock(CandleReader.class);
    when(reader.read(eq("NFO"), eq(CE_SYMBOL), eq("1m"), any(), any()))
        .thenReturn(
            List.of(
                new EngineCandle(t("09:15"), bd("80"), bd("80"), bd("80"), bd("80"), 0L),
                new EngineCandle(t("09:16"), bd("90"), bd("90"), bd("90"), bd("90"), 0L),
                new EngineCandle(t("09:17"), bd("110"), bd("110"), bd("110"), bd("110"), 0L)));

    OptionsPremiumReplay replay =
        new OptionsPremiumReplay(
            new OptionContractSelector(CATALOG), new CandlePremiumReader(reader));

    var result =
        replay.replayLegs(
            List.of(),
            underlying,
            List.of(new PairedLeg(false, 0, 4)),
            "NIFTY",
            new UniverseSpec(ExpiryMode.NEAREST_WEEKLY, 0, Set.of("CE", "PE")),
            new PremiumExitEvaluator.Rules(bd("20"), bd("35"), null, null, null),
            15_000,
            new BigDecimal("200000"));

    assertThat(result.trades()).hasSize(1);
    assertThat(result.barsInPosition()).isEqualTo(2); // TAKE_PROFIT at offset 2
    assertThat(result.totalBars()).isEqualTo(5);
    // per-bar MTM + the full options cost stack: bar0 marks the open position net of entry cost
    // (199941.67); the +3767.76 net trade realizes at the exit bar → final 203767.76.
    assertThat(result.finalEquity()).isEqualByComparingTo("203767.76");
    assertThat(result.equityCurve().get(0).equity()).isEqualByComparingTo("199941.67"); // bar0: open, marked
    assertThat(result.equityCurve().get(result.equityCurve().size() - 1).equity())
        .isEqualByComparingTo("203767.76"); // post-exit
  }

  @Test
  void parsesTheOptionsConfigIntoSpecRulesAndBudget() throws Exception {
    com.fasterxml.jackson.databind.JsonNode config =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                """
                {
                  "universe": {"mode":"options_of_underlying",
                    "underlying":{"exchange":"NSE","tradingsymbol":"NIFTY 50"},
                    "options":{"expiry":"nearest_weekly","strikes":{"selector":"atm"},
                      "option_types":["CE","PE"]}},
                  "exit_rules":[
                    {"type":"stop_loss","params":{"basis":"premium_pct","value":20}},
                    {"type":"take_profit","params":{"basis":"premium_pct","value":35}},
                    {"type":"trailing_stop","params":{"basis":"premium_pct","activate_at":20,"trail_by":10}},
                    {"type":"time_stop","params":{"max_bars":15}},
                    {"type":"signal_exit","params":{"rule":"x"}}
                  ],
                  "risk":{"position_sizing":{"method":"premium_budget","params":{"budget_inr":15000}}}
                }
                """);

    UniverseSpec spec = OptionsPremiumReplay.universeSpec(config);
    assertThat(spec.expiryMode()).isEqualTo(ExpiryMode.NEAREST_WEEKLY);
    assertThat(spec.optionTypes()).containsExactlyInAnyOrder("CE", "PE");

    PremiumExitEvaluator.Rules rules = OptionsPremiumReplay.exitRules(config);
    assertThat(rules.stopLossPct()).isEqualByComparingTo("20");
    assertThat(rules.takeProfitPct()).isEqualByComparingTo("35");
    assertThat(rules.trailActivatePct()).isEqualByComparingTo("20");
    assertThat(rules.trailByPct()).isEqualByComparingTo("10");
    assertThat(rules.timeStopBars()).isEqualTo(15);

    assertThat(OptionsPremiumReplay.budgetInr(config)).isEqualTo(15_000);
    assertThat(OptionsPremiumReplay.registryUnderlying("NIFTY 50")).isEqualTo("NIFTY");
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}
