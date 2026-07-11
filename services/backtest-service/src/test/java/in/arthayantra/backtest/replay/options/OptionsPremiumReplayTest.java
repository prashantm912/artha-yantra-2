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
            BigDecimal.ZERO, // min-premium floor: permissive (the fixture's 80→110 premium is normal)
            0, // max-lots: unlimited
            new BigDecimal("200000"),
            underlying); // 2b-E2b strike reference = the signal series (anchor on the entry bar's close)

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
  void minPremiumFloorAndMaxLotsTameTheDegenerateTinyPremiumExplosion() {
    // A ₹0.10 (deep-OTM / expiry-day worthless) premium: budget/premium would buy thousands of lots.
    List<EngineCandle> underlying =
        List.of(
            bar("09:15", "24980"), bar("09:16", "24990"), bar("09:17", "25010"),
            bar("09:18", "25020"), bar("09:19", "25030"));
    CandleReader reader = mock(CandleReader.class);
    when(reader.read(eq("NFO"), eq(CE_SYMBOL), eq("1m"), any(), any()))
        .thenReturn(
            List.of(
                new EngineCandle(t("09:15"), bd("0.10"), bd("0.10"), bd("0.10"), bd("0.10"), 0L),
                new EngineCandle(t("09:16"), bd("0.10"), bd("0.10"), bd("0.10"), bd("0.10"), 0L)));
    OptionsPremiumReplay replay =
        new OptionsPremiumReplay(
            new OptionContractSelector(CATALOG), new CandlePremiumReader(reader));
    List<PairedLeg> legs = List.of(new PairedLeg(false, 0, 4));
    UniverseSpec spec = new UniverseSpec(ExpiryMode.NEAREST_WEEKLY, 0, Set.of("CE", "PE"));
    PremiumExitEvaluator.Rules rules =
        new PremiumExitEvaluator.Rules(bd("20"), bd("35"), null, null, null);
    BigDecimal capital = new BigDecimal("200000");

    // floor ₹1 → the ₹0.10 leg is skipped (legit, no trade, no explosion)
    assertThat(
            replay
                .replayLegs(
                    List.of(), underlying, legs, "NIFTY", spec, rules, 15_000, bd("1"), 0, capital, underlying)
                .trades())
        .isEmpty();

    // floor 0 (permissive) → it trades AND the lot count explodes: 15000/(0.10×65) ≈ 2307 lots = 149955 qty
    var permissive =
        replay.replayLegs(
            List.of(), underlying, legs, "NIFTY", spec, rules, 15_000, BigDecimal.ZERO, 0, capital, underlying);
    assertThat(permissive.trades()).hasSize(1);
    assertThat(permissive.trades().get(0).qty()).isGreaterThan(100_000L);

    // max-lots cap (10) bounds the qty to 10 × lot(65) = 650 even at the degenerate premium
    var capped =
        replay.replayLegs(
            List.of(), underlying, legs, "NIFTY", spec, rules, 15_000, BigDecimal.ZERO, 10, capital, underlying);
    assertThat(capped.trades().get(0).qty()).isEqualTo(650);
  }

  // ---- E7 band-aware backtest selection ----

  @Test
  void premiumBandParsesAndDegradesOnAHalfFilledBand() throws Exception {
    var om = new com.fasterxml.jackson.databind.ObjectMapper();
    var band =
        OptionsPremiumReplay.premiumBand(
            om.readTree(
                "{\"backtest\":{\"strike_premium_band\":{\"enabled\":true,\"lo\":100,\"hi\":250,"
                    + "\"window\":4,\"strike_step\":50,\"fallback_nearest\":false}}}"));
    assertThat(band.enabled()).isTrue();
    assertThat(band.lo()).isEqualByComparingTo("100");
    assertThat(band.hi()).isEqualByComparingTo("250");
    assertThat(band.window()).isEqualTo(4);
    assertThat(band.fallbackNearest()).isFalse();
    // absent → disabled (legacy nearest-strike path)
    assertThat(OptionsPremiumReplay.premiumBand(om.readTree("{}")).enabled()).isFalse();
    // half-filled (lo, no hi) → disabled (never select on a one-sided bound)
    assertThat(
            OptionsPremiumReplay.premiumBand(
                    om.readTree(
                        "{\"backtest\":{\"strike_premium_band\":{\"enabled\":true,\"lo\":100}}}"))
                .enabled())
        .isFalse();
    // window defaults from universe.options.strikes.width
    assertThat(
            OptionsPremiumReplay.premiumBand(
                    om.readTree(
                        "{\"universe\":{\"options\":{\"strikes\":{\"width\":7}}},\"backtest\":{"
                            + "\"strike_premium_band\":{\"enabled\":true,\"lo\":100,\"hi\":250}}}"))
                .window())
        .isEqualTo(7);
  }

  @Test
  void bandEnabledTradesTheInBandStrikeNotTheNearestSpot() {
    String inBand = "NIFTY16JUN2625100CE"; // the 25100 strike — in band, NOT the nearest-spot 25000
    // a 2-strike ladder around spot 25010: 25000 (nearest, premium 300 OUT of band) + 25100 (premium 180 IN).
    Catalog ladder =
        new Catalog() {
          @Override
          public List<Expiry> expiriesOnOrAfter(String u, LocalDate d) {
            return List.of(new Expiry(W, true));
          }

          @Override
          public Optional<OptionContract> nearestStrike(String u, LocalDate e, String t, BigDecimal s) {
            return Optional.of(new OptionContract("NFO", CE_SYMBOL, bd("25000"), e, t, 65));
          }

          @Override
          public List<OptionContract> nearestStrikes(
              String u, LocalDate e, String t, BigDecimal s, int window) {
            return List.of(
                new OptionContract("NFO", CE_SYMBOL, bd("25000"), e, t, 65),
                new OptionContract("NFO", inBand, bd("25100"), e, t, 65));
          }
        };
    List<EngineCandle> underlying = List.of(bar("09:15", "25010"), bar("09:16", "25020"));
    CandleReader reader = mock(CandleReader.class);
    when(reader.read(eq("NFO"), eq(CE_SYMBOL), eq("1m"), any(), any()))
        .thenReturn(List.of(new EngineCandle(t("09:15"), bd("300"), bd("300"), bd("300"), bd("300"), 0L)));
    when(reader.read(eq("NFO"), eq(inBand), eq("1m"), any(), any()))
        .thenReturn(
            List.of(
                new EngineCandle(t("09:15"), bd("180"), bd("180"), bd("180"), bd("180"), 0L),
                new EngineCandle(t("09:16"), bd("190"), bd("190"), bd("190"), bd("190"), 0L)));

    OptionsPremiumReplay replay =
        new OptionsPremiumReplay(new OptionContractSelector(ladder), new CandlePremiumReader(reader));
    OptionsPremiumReplay.PremiumBand band =
        new OptionsPremiumReplay.PremiumBand(true, bd("100"), bd("250"), 1, bd("200"), true);

    var result =
        replay.replayLegs(
            List.of(),
            underlying,
            List.of(new PairedLeg(false, 0, 1)),
            "NIFTY",
            new UniverseSpec(ExpiryMode.NEAREST_WEEKLY, 0, Set.of("CE", "PE")),
            new PremiumExitEvaluator.Rules(bd("20"), bd("35"), null, null, null),
            15_000,
            BigDecimal.ZERO,
            0,
            band,
            new BigDecimal("200000"),
            underlying);

    assertThat(result.trades()).hasSize(1);
    assertThat(result.trades().get(0).tradingsymbol()).isEqualTo(inBand); // band picked 25100, not 25000
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

  // ---- P1-11 / audit B6: option expiry settlement ----

  private static EngineCandle barAt(String iso, String close) {
    BigDecimal c = new BigDecimal(close);
    return new EngineCandle(OffsetDateTime.parse(iso), c, c, c, c, 0L);
  }

  private static EngineCandle premAt(String iso, String px) {
    BigDecimal p = new BigDecimal(px);
    return new EngineCandle(OffsetDateTime.parse(iso), p, p, p, p, 0L);
  }

  /** Entry 06-15, CE 25000 held across the 2026-06-16 expiry to a 06-17 signal exit (spans two days). */
  private static List<EngineCandle> settlementUnderlying(String expiryCloseSpot) {
    return List.of(
        barAt("2026-06-15T09:15:00+05:30", "25050"), // entry, the day before expiry
        barAt("2026-06-16T09:15:00+05:30", "25080"), // expiry day, morning
        barAt("2026-06-16T15:29:00+05:30", expiryCloseSpot), // expiry-day CLOSE → settlement spot
        barAt("2026-06-17T09:15:00+05:30", "25200"), // post-expiry (option no longer trades)
        barAt("2026-06-17T09:16:00+05:30", "25200")); // post-expiry, the signal exit bar
  }

  private static OptionsPremiumReplay settlementReplay(List<EngineCandle> premiumCandles) {
    CandleReader reader = mock(CandleReader.class);
    when(reader.read(eq("NFO"), eq(CE_SYMBOL), eq("1m"), any(), any())).thenReturn(premiumCandles);
    return new OptionsPremiumReplay(new OptionContractSelector(CATALOG), new CandlePremiumReader(reader));
  }

  private static final PremiumExitEvaluator.Rules SL20_TP35 =
      new PremiumExitEvaluator.Rules(bd("20"), bd("35"), null, null, null);
  private static final UniverseSpec BOTH_SIDES =
      new UniverseSpec(ExpiryMode.NEAREST_WEEKLY, 0, Set.of("CE", "PE"));

  @Test
  void itmLegHeldPastExpirySettlesAtIntrinsicWithExerciseStt() {
    // The CE premium is real only through the expiry-day close (80→95→100), then carried flat (the
    // option no longer exists). No bracket fires and the signal exit is a day PAST expiry, so the leg
    // must settle at INTRINSIC off the 06-16 close spot 25100 (max(0, 25100−25000)=100) — NOT the stale
    // carried premium — with the exercise STT leg, at the expiry-day close bar.
    OptionsPremiumReplay replay =
        settlementReplay(
            List.of(
                premAt("2026-06-15T09:15:00+05:30", "80"),
                premAt("2026-06-16T09:15:00+05:30", "95"),
                premAt("2026-06-16T15:29:00+05:30", "100")));

    Trade trade =
        replay
            .tradeForLeg(
                1, settlementUnderlying("25100"), "NIFTY", new PairedLeg(false, 0, 4), BOTH_SIDES,
                SL20_TP35, 15_000)
            .orElseThrow();

    assertThat(trade.exitReason()).isEqualTo("EXPIRY_SETTLEMENT");
    assertThat(trade.exitPrice()).isEqualByComparingTo("100"); // intrinsic, settlement crosses no spread
    assertThat(trade.exitTs()).isEqualTo(OffsetDateTime.parse("2026-06-16T15:29:00+05:30"));
    assertThat(trade.barsHeld()).isEqualTo(2); // settled at bar 2, not the offset-4 signal exit
    assertThat(trade.qty()).isEqualTo(130); // floor(15000/(80×65)) = 2 lots
    // entry 80.05 (1-tick) → settle 100 intrinsic; exit STT is the EXERCISE rate 0.125% (16.25, vs the
    // 0.10% sell-side 13.00) + ₹20/lot brokerage + txn/GST/SEBI → net pnl +2472.84.
    assertThat(trade.pnl()).isEqualByComparingTo("2472.84");
  }

  @Test
  void otmLegHeldPastExpiryLapsesWorthlessAtZeroIntrinsic() {
    // Same hold, but the 06-16 close spot 24900 is BELOW the 25000 CE strike → intrinsic 0: the option
    // expires worthless. The trader loses the entry cost + the settlement's flat per-lot brokerage.
    OptionsPremiumReplay replay =
        settlementReplay(
            List.of(
                premAt("2026-06-15T09:15:00+05:30", "80"),
                premAt("2026-06-16T09:15:00+05:30", "95"),
                premAt("2026-06-16T15:29:00+05:30", "100")));

    Trade trade =
        replay
            .tradeForLeg(
                1, settlementUnderlying("24900"), "NIFTY", new PairedLeg(false, 0, 4), BOTH_SIDES,
                SL20_TP35, 15_000)
            .orElseThrow();

    assertThat(trade.exitReason()).isEqualTo("EXPIRY_SETTLEMENT");
    assertThat(trade.exitPrice()).isEqualByComparingTo("0"); // OTM → worthless
    assertThat(trade.pnl()).isEqualByComparingTo("-10505.53"); // ≈ the whole entry outlay lost
    // sanity: the ITM settlement above beats the worthless OTM lapse by the intrinsic minus exercise STT.
    assertThat(trade.pnl()).isLessThan(bd("2472.84"));
  }

  @Test
  void marketExitOnTheExpiryCloseBarIsNotOverwrittenBySettlement() {
    // The additive boundary: a genuine premium-driven exit that fires AT the expiry-day close (TP at
    // offset 2, the 15:29 bar) is NOT a settlement — it books the observed 110 premium (fill 109.95,
    // sell-side STT), reason TAKE_PROFIT, exactly as before P1-11. Only a hold that OUTLIVES the close
    // settles at intrinsic.
    OptionsPremiumReplay replay =
        settlementReplay(
            List.of(
                premAt("2026-06-15T09:15:00+05:30", "80"),
                premAt("2026-06-16T09:15:00+05:30", "95"),
                premAt("2026-06-16T15:29:00+05:30", "110"))); // hits the +35% target (108) at the close

    Trade trade =
        replay
            .tradeForLeg(
                1, settlementUnderlying("25100"), "NIFTY", new PairedLeg(false, 0, 4), BOTH_SIDES,
                SL20_TP35, 15_000)
            .orElseThrow();

    assertThat(trade.exitReason()).isEqualTo("TAKE_PROFIT");
    assertThat(trade.exitPrice()).isEqualByComparingTo("109.95"); // normal 1-tick sell, not settlement
    assertThat(trade.exitTs()).isEqualTo(OffsetDateTime.parse("2026-06-16T15:29:00+05:30"));
    assertThat(trade.barsHeld()).isEqualTo(2);
  }

  @Test
  void intrinsicValueIsCePeAwareAndZeroFloored() {
    assertThat(OptionsPremiumReplay.intrinsicValue("CE", bd("25100"), bd("25000"))).isEqualByComparingTo("100");
    assertThat(OptionsPremiumReplay.intrinsicValue("CE", bd("24900"), bd("25000"))).isEqualByComparingTo("0");
    assertThat(OptionsPremiumReplay.intrinsicValue("PE", bd("24900"), bd("25000"))).isEqualByComparingTo("100");
    assertThat(OptionsPremiumReplay.intrinsicValue("PE", bd("25100"), bd("25000"))).isEqualByComparingTo("0");
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static in.arthayantra.backtest.client.MarketDataClient.CdRow cdRow(String label, int trend) {
    return new in.arthayantra.backtest.client.MarketDataClient.CdRow(
        label, trend, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  /** A row with the active-strike IV factor set (1 Bullish / 2 Bearish), the rest neutral. */
  private static in.arthayantra.backtest.client.MarketDataClient.CdRow cdRowIv(
      String label, int trend, int activeStrikeIv) {
    return new in.arthayantra.backtest.client.MarketDataClient.CdRow(
        label, trend, 0, 0, 0, activeStrikeIv, 0, 0, 0, 0, 0, 0, 0);
  }

  @Test
  void oiConfluenceGateDropsCounterTrendLegsKeepsAligned() {
    in.arthayantra.backtest.client.MarketDataClient md =
        mock(in.arthayantra.backtest.client.MarketDataClient.class);
    // 2026-06-16, the 10:00-10:05 bucket is Bearish (trend 3).
    when(md.connectingDots(eq("NIFTY 50"), eq(LocalDate.of(2026, 6, 16)), eq("5m")))
        .thenReturn(
            new in.arthayantra.backtest.client.MarketDataClient.CdResponse(
                true, List.of(cdRow("10:00-10:05", 3))));

    OptionsPremiumReplay replay =
        new OptionsPremiumReplay(
            mock(OptionContractSelector.class), mock(CandlePremiumReader.class), md);
    List<EngineCandle> underlying = List.of(bar("10:02", "25000"), bar("10:30", "25010"));

    PairedLeg longLeg = new PairedLeg(false, 0, 1); // long CE entering into Bearish OI → DROP
    PairedLeg shortLeg = new PairedLeg(true, 0, 1); // short PE entering into Bearish OI → aligned, KEEP
    OptionsPremiumReplay.OiGate gate = new OptionsPremiumReplay.OiGate(true, "5m", 5, "", false);

    OptionsPremiumReplay.GateCoverage coverage = new OptionsPremiumReplay.GateCoverage();
    List<PairedLeg> kept =
        replay.filterCounterTrend(List.of(longLeg, shortLeg), underlying, "NIFTY 50", gate, coverage);
    assertThat(kept).containsExactly(shortLeg); // the counter-trend long is dropped
    assertThat(coverage.label()).isEqualTo("1/1"); // one session lookup, rows returned
  }

  @Test
  void ivConfluenceGateDropsLegsAgainstTheIvFactorIndependentOfOi() {
    in.arthayantra.backtest.client.MarketDataClient md =
        mock(in.arthayantra.backtest.client.MarketDataClient.class);
    // 10:00-10:05: the active-strike IV factor is Bearish (2); the OI dimension is OFF, so only the IV
    // filter runs — a long CE entered here is into a bearish IV read and must be dropped.
    when(md.connectingDots(eq("NIFTY 50"), eq(LocalDate.of(2026, 6, 16)), eq("5m")))
        .thenReturn(
            new in.arthayantra.backtest.client.MarketDataClient.CdResponse(
                true, List.of(cdRowIv("10:00-10:05", 0, 2))));

    OptionsPremiumReplay replay =
        new OptionsPremiumReplay(
            mock(OptionContractSelector.class), mock(CandlePremiumReader.class), md);
    List<EngineCandle> underlying = List.of(bar("10:02", "25000"), bar("10:30", "25010"));

    PairedLeg longLeg = new PairedLeg(false, 0, 1); // long CE into a bearish IV read → DROP
    PairedLeg shortLeg = new PairedLeg(true, 0, 1); // short PE into a bearish IV read → KEEP
    // enabled=false (OI dimension off), iv=true — the IV filter is usable on its own.
    OptionsPremiumReplay.OiGate ivOnly = new OptionsPremiumReplay.OiGate(false, "5m", 5, "", true);

    List<PairedLeg> kept =
        replay.filterCounterTrend(
            List.of(longLeg, shortLeg),
            underlying,
            "NIFTY 50",
            ivOnly,
            new OptionsPremiumReplay.GateCoverage());
    assertThat(kept).containsExactly(shortLeg);
  }

  @Test
  void oiConfluenceGatePassesLegsThroughWhenNoOiData() {
    in.arthayantra.backtest.client.MarketDataClient md =
        mock(in.arthayantra.backtest.client.MarketDataClient.class);
    when(md.connectingDots(any(), any(), any()))
        .thenReturn(in.arthayantra.backtest.client.MarketDataClient.CdResponse.EMPTY); // no OI → no filter
    OptionsPremiumReplay replay =
        new OptionsPremiumReplay(
            mock(OptionContractSelector.class), mock(CandlePremiumReader.class), md);
    List<EngineCandle> underlying = List.of(bar("10:02", "25000"), bar("10:30", "25010"));
    PairedLeg longLeg = new PairedLeg(false, 0, 1);
    OptionsPremiumReplay.GateCoverage coverage = new OptionsPremiumReplay.GateCoverage();
    List<PairedLeg> kept =
        replay.filterCounterTrend(
            List.of(longLeg),
            underlying,
            "NIFTY 50",
            new OptionsPremiumReplay.OiGate(true, "5m", 5, "", false),
            coverage);
    assertThat(kept).containsExactly(longLeg);
    assertThat(coverage.label()).isEqualTo("0/1"); // the lookup came back EMPTY — a degraded gate
  }

  @Test
  void oiGateConfigParsesEnabledAndIntervalDefaultsOff() {
    com.fasterxml.jackson.databind.node.ObjectNode off =
        com.fasterxml.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
    assertThat(OptionsPremiumReplay.parseOiGate(off).enabled()).isFalse(); // default OFF → goldens safe
    assertThat(OptionsPremiumReplay.parseOiGate(off).iv()).isFalse(); // IV dimension also OFF by default
  }

  @Test
  void optionRootAndOiGateIndexResolveFromUnderlyingNotTheSignal() throws Exception {
    // 2b-E2: signal on NIFTY-FUT-CONT, legs on SENSEX. The option root + the default OI-gate index must
    // resolve from universe.underlying (SENSEX), NEVER from the decoupled signal series.
    com.fasterxml.jackson.databind.JsonNode config =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                "{\"universe\":{\"mode\":\"options_of_underlying\","
                    + "\"underlying\":{\"exchange\":\"BFO\",\"tradingsymbol\":\"SENSEX\"},"
                    + "\"signal_underlying\":{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY-FUT-CONT\"}},"
                    + "\"backtest\":{\"oi_confluence_gate\":{\"enabled\":true,\"interval\":\"5m\"}}}");
    assertThat(OptionsPremiumReplay.optionRoot(config)).isEqualTo("SENSEX");
    assertThat(OptionsPremiumReplay.optionRootDisplay(config)).isEqualTo("SENSEX");
    OptionsPremiumReplay.OiGate gate = OptionsPremiumReplay.parseOiGate(config);
    assertThat(gate.index()).isEmpty();
    assertThat(OptionsPremiumReplay.oiGateIndex(config, gate)).isEqualTo("SENSEX");
  }

  @Test
  void oiGateIndexHonoursTheExplicitOverride() throws Exception {
    // A SENSEX-option strategy can gate on its NIFTY signal-driver's OI via the explicit index override.
    com.fasterxml.jackson.databind.JsonNode config =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                "{\"universe\":{\"mode\":\"options_of_underlying\","
                    + "\"underlying\":{\"exchange\":\"BFO\",\"tradingsymbol\":\"SENSEX\"}},"
                    + "\"backtest\":{\"oi_confluence_gate\":"
                    + "{\"enabled\":true,\"interval\":\"5m\",\"index\":\"NIFTY 50\"}}}");
    OptionsPremiumReplay.OiGate gate = OptionsPremiumReplay.parseOiGate(config);
    assertThat(gate.index()).isEqualTo("NIFTY 50");
    assertThat(OptionsPremiumReplay.oiGateIndex(config, gate)).isEqualTo("NIFTY 50");
  }

  @Test
  void strikeIsAnchoredOnTheStrikeReferenceNotTheSignal() {
    // 2b-E2b: signal series ~25000 (NIFTY-fut), strike reference ~80000 (SENSEX-fut), same minutes.
    // The ATM strike MUST be selected from the strike-reference price, never the signal bar's close.
    List<EngineCandle> signal = List.of(bar("09:15", "25000"), bar("09:16", "25010"));
    List<EngineCandle> strikeReference =
        List.of(
            new EngineCandle(t("09:15"), bd("80000"), bd("80000"), bd("80000"), bd("80000"), 0L),
            new EngineCandle(t("09:16"), bd("80010"), bd("80010"), bd("80010"), bd("80010"), 0L));
    java.util.concurrent.atomic.AtomicReference<BigDecimal> seenSpot =
        new java.util.concurrent.atomic.AtomicReference<>();
    Catalog capturing =
        new Catalog() {
          @Override
          public List<Expiry> expiriesOnOrAfter(String u, LocalDate d) {
            return List.of(new Expiry(W, true));
          }

          @Override
          public Optional<OptionContract> nearestStrike(
              String u, LocalDate e, String type, BigDecimal spot) {
            seenSpot.set(spot);
            return Optional.of(new OptionContract("NFO", CE_SYMBOL, bd("25000"), e, type, 65));
          }
        };
    CandleReader reader = mock(CandleReader.class);
    when(reader.read(eq("NFO"), eq(CE_SYMBOL), eq("1m"), any(), any()))
        .thenReturn(
            List.of(
                new EngineCandle(t("09:15"), bd("100"), bd("100"), bd("100"), bd("100"), 0L),
                new EngineCandle(t("09:16"), bd("100"), bd("100"), bd("100"), bd("100"), 0L)));
    OptionsPremiumReplay replay =
        new OptionsPremiumReplay(new OptionContractSelector(capturing), new CandlePremiumReader(reader));

    replay.replayLegs(
        List.of(),
        signal,
        List.of(new PairedLeg(false, 0, 1)),
        "SENSEX",
        new UniverseSpec(ExpiryMode.NEAREST_WEEKLY, 0, Set.of("CE", "PE")),
        new PremiumExitEvaluator.Rules(bd("20"), bd("99"), null, null, null),
        15_000,
        BigDecimal.ZERO,
        0,
        new BigDecimal("200000"),
        strikeReference);

    assertThat(seenSpot.get()).isEqualByComparingTo("80000"); // anchored on the SENSEX-fut, not 25000
  }
}
