package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every seeded scalper strategy (Siva index-option core + derived #4/#12/#2/#9) must be schema-valid,
 * compile into an engine definition, and carry the §12.3 seam wiring — the Phase-3 exit-gate fixture.
 * Pure: no DB, no market-data; the classpath resources ARE the authoritative strategy docs the seeder
 * loads, so this list MUST stay in lockstep with {@link ScalperStrategySeeder}'s STRATEGIES.
 */
class ScalperStrategyLoadTest {

  // The full seeded set (2b-1): each of the 12 Siva scalpers × {NIFTY, SENSEX·NIFTY-OI, SENSEX·SENSEX-OI}
  // = 36 variants, all signal on NFO/NIFTY-FUT-CONT. Each maps to its option-execution underlying so the
  // per-index ScalperConfig assertions hold (NIFTY variant → "NIFTY 50"; both SENSEX variants → "SENSEX").
  // This list MUST stay in lockstep with ScalperStrategySeeder's STRATEGIES.
  private static final Map<String, String> UNDERLYING =
      Map.ofEntries(
          Map.entry("scalp-connect-the-dots-nifty", "NIFTY 50"),
          Map.entry("scalp-connect-the-dots-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-connect-the-dots-sensex-sensexoi", "SENSEX"),
          Map.entry("scalp-two-candle-nifty", "NIFTY 50"),
          Map.entry("scalp-two-candle-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-two-candle-sensex-sensexoi", "SENSEX"),
          Map.entry("scalp-trending-oi-nifty", "NIFTY 50"),
          Map.entry("scalp-trending-oi-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-trending-oi-sensex-sensexoi", "SENSEX"),
          Map.entry("scalp-golden-crossover-nifty", "NIFTY 50"),
          Map.entry("scalp-golden-crossover-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-golden-crossover-sensex-sensexoi", "SENSEX"),
          Map.entry("scalp-gap-theory-nifty", "NIFTY 50"),
          Map.entry("scalp-gap-theory-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-gap-theory-sensex-sensexoi", "SENSEX"),
          Map.entry("scalp-trend-change-nifty", "NIFTY 50"),
          Map.entry("scalp-trend-change-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-trend-change-sensex-sensexoi", "SENSEX"),
          Map.entry("scalp-open-high-low-nifty", "NIFTY 50"),
          Map.entry("scalp-open-high-low-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-open-high-low-sensex-sensexoi", "SENSEX"),
          Map.entry("scalp-morning-trade-nifty", "NIFTY 50"),
          Map.entry("scalp-morning-trade-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-morning-trade-sensex-sensexoi", "SENSEX"),
          Map.entry("scalp-hero-zero-nifty", "NIFTY 50"),
          Map.entry("scalp-hero-zero-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-hero-zero-sensex-sensexoi", "SENSEX"),
          Map.entry("scalp-straddle-nifty", "NIFTY 50"),
          Map.entry("scalp-straddle-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-straddle-sensex-sensexoi", "SENSEX"),
          Map.entry("scalp-market-movers-nifty", "NIFTY 50"),
          Map.entry("scalp-market-movers-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-market-movers-sensex-sensexoi", "SENSEX"),
          Map.entry("scalp-btst-stbt-nifty", "NIFTY 50"),
          Map.entry("scalp-btst-stbt-sensex-niftyoi", "SENSEX"),
          Map.entry("scalp-btst-stbt-sensex-sensexoi", "SENSEX"));

  // Each derived strategy must carry the tag that arms its §12.3 gate (the seeder reads the same tag).
  // Every variant of each gated base carries the same tag (the gate behaviour is instrument-agnostic).
  private static final Map<String, String> EXPECTED_TAG =
      Map.ofEntries(
          Map.entry("scalp-gap-theory-nifty", "gap-theory"),
          Map.entry("scalp-gap-theory-sensex-niftyoi", "gap-theory"),
          Map.entry("scalp-gap-theory-sensex-sensexoi", "gap-theory"),
          Map.entry("scalp-trend-change-nifty", "trend-change"),
          Map.entry("scalp-trend-change-sensex-niftyoi", "trend-change"),
          Map.entry("scalp-trend-change-sensex-sensexoi", "trend-change"),
          Map.entry("scalp-open-high-low-nifty", "open-high-low"),
          Map.entry("scalp-open-high-low-sensex-niftyoi", "open-high-low"),
          Map.entry("scalp-open-high-low-sensex-sensexoi", "open-high-low"),
          Map.entry("scalp-morning-trade-nifty", "opening-tick"),
          Map.entry("scalp-morning-trade-sensex-niftyoi", "opening-tick"),
          Map.entry("scalp-morning-trade-sensex-sensexoi", "opening-tick"),
          Map.entry("scalp-hero-zero-nifty", "hero-zero"),
          Map.entry("scalp-hero-zero-sensex-niftyoi", "hero-zero"),
          Map.entry("scalp-hero-zero-sensex-sensexoi", "hero-zero"),
          Map.entry("scalp-straddle-nifty", "straddle"),
          Map.entry("scalp-straddle-sensex-niftyoi", "straddle"),
          Map.entry("scalp-straddle-sensex-sensexoi", "straddle"));

  // the aliases ScalperConfluenceGate reads off the bank — each strategy must declare all four.
  private static final Set<String> SEAM_ALIASES = Set.of("vwma20", "psar", "rsi14", "supertrend");

  private static String yaml(String id) throws IOException {
    try (InputStream in =
        ScalperStrategyLoadTest.class.getResourceAsStream("/scalper-strategies/" + id + ".yaml")) {
      assertThat(in).as("classpath resource for " + id).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void everyScalperStrategyIsSchemaValidCompilesAndIsSeamWired() throws IOException {
    for (String id : UNDERLYING.keySet()) {
      String body = yaml(id);

      var result = StrategyDocuments.validate(body);
      assertThat(result.valid()).as(id + " must be schema-valid; errors=" + result.errors()).isTrue();

      JsonNode config = StrategyDocuments.parse(body).config();
      assertThat(config.path("id").asText()).as(id + " slug").isEqualTo(id);
      assertThat(config.path("universe").path("mode").asText()).isEqualTo("options_of_underlying");

      List<String> tags = new ArrayList<>();
      config.path("tags").forEach(t -> tags.add(t.asText()));
      assertThat(tags).as(id + " must be tagged scalper (engine detection)").contains("scalper");

      // Each derived strategy carries the tag that arms its §12.3 gate (gap-theory / trend-change /
      // open-high-low / opening-tick); the seeder reads the same tag onto the registry draft.
      String expectedTag = EXPECTED_TAG.get(id);
      if (expectedTag != null) {
        assertThat(tags).as(id + " must carry its gate tag").contains(expectedTag);
      }

      StrategyDefinition def = StrategyCompiler.compile(config);
      assertThat(def.primaryTimeframe()).as(id + " scalps on 3m").isEqualTo("3m");

      ScalperConfig cfg = ScalperConfig.from(config, tags);
      assertThat(cfg.underlying()).as(id + " underlying").isEqualTo(UNDERLYING.get(id));
      // S24 arming: a strategy that carries the ratified delta-s24-floor tag resolves the >=0.7 band
      // (0.7-0.8); the unarmed default stays the legacy 0.6-0.7. Both are valid post-arming states.
      double expectedDeltaLo = tags.contains("delta-s24-floor") ? 0.7 : 0.6;
      assertThat(cfg.strikeParams().deltaLo()).as(id + " delta floor (s24 vs legacy)").isEqualTo(expectedDeltaLo);
      assertThat(cfg.confluenceThreshold()).isEqualByComparingTo("0.6");
      // 2c three-way decoupling: every variant SIGNALS on the NIFTY future (signalIndex "NIFTY 50",
      // mapped from signal_underlying NFO/NIFTY-FUT-CONT); the OI-confluence index is the option-root
      // for a NIFTY variant, else the backtest.oi_confluence_gate.index ("NIFTY 50" or "SENSEX").
      assertThat(cfg.signalIndex()).as(id + " signals on the NIFTY future").isEqualTo("NIFTY 50");
      String expectedOi =
          id.endsWith("-sensex-niftyoi")
              ? "NIFTY 50"
              : id.endsWith("-sensex-sensexoi") ? "SENSEX" : UNDERLYING.get(id);
      assertThat(cfg.oiIndex()).as(id + " oi-confluence index").isEqualTo(expectedOi);

      Set<String> declared = new HashSet<>();
      config.path("indicators").forEach(i -> declared.add(i.path("alias").asText()));
      assertThat(declared).as(id + " declares the seam aliases").containsAll(SEAM_ALIASES);

      // #5 (T2.1): only the scalp-trending-oi family carries the oi-cross-filter tag → the HARD call-put
      // dOI pre-gate. ScalperConfig.requireCallPutDeltaFilter mirrors the tag; the others stay off.
      boolean isTrendingOi = id.startsWith("scalp-trending-oi-");
      assertThat(cfg.requireCallPutDeltaFilter())
          .as(id + " oi-cross-filter pre-gate")
          .isEqualTo(isTrendingOi);

      // E2 M1/M2 (oi-cross-required + oi-slope-agree): the Trending-OI #5 defining hard gates are armed
      // on the scalp-trending-oi family ONLY (the defining strategy per the operative doc); every other
      // variant stays unarmed (the gate reads the tag via cfg.has(...), so assert against the tag list).
      assertThat(tags.contains("oi-cross-required"))
          .as(id + " oi-cross-required armed iff trending-oi")
          .isEqualTo(isTrendingOi);
      assertThat(tags.contains("oi-slope-agree"))
          .as(id + " oi-slope-agree armed iff trending-oi")
          .isEqualTo(isTrendingOi);
      // E2 M3 (oi-divergence-magnitude): armed on the scalp-trending-oi family (its OI-cross edge).
      assertThat(tags.contains("oi-divergence-magnitude"))
          .as(id + " oi-divergence-magnitude armed iff trending-oi")
          .isEqualTo(isTrendingOi);
      // E2 M7 (oi-interval-and-60m-trend): the 60m broader-trend confirm, armed on the same #5 family.
      assertThat(tags.contains("oi-interval-and-60m-trend"))
          .as(id + " oi-interval-and-60m-trend armed iff trending-oi")
          .isEqualTo(isTrendingOi);

      // E4 (iv-buyer-cap, "IV>40 -> don't buy"): armed on the golden-crossover momentum-buyer family.
      boolean isGoldenCrossover = id.startsWith("scalp-golden-crossover-");
      assertThat(tags.contains("iv-buyer-cap"))
          .as(id + " iv-buyer-cap armed iff golden-crossover")
          .isEqualTo(isGoldenCrossover);
      // E6 supertrend-15m (§3.10/§4.14.6 15m trend confirm): armed on the golden-crossover momentum
      // family (a crossover entry confirmed by the higher-TF trend).
      assertThat(tags.contains("supertrend-15m"))
          .as(id + " supertrend-15m armed iff golden-crossover")
          .isEqualTo(isGoldenCrossover);

      // E6 #10 (two-candle-substitution): armed on the scalp-two-candle family (#1, its namesake gate).
      boolean isTwoCandle = id.startsWith("scalp-two-candle-");
      assertThat(tags.contains("two-candle-substitution"))
          .as(id + " two-candle-substitution armed iff two-candle")
          .isEqualTo(isTwoCandle);
      // E5 rsi-cooloff (§3.6 pullback re-entry after a hot bar): armed on the scalp-two-candle family
      // (the cross-bar complement of its already-armed overbought-defer).
      assertThat(tags.contains("rsi-cooloff"))
          .as(id + " rsi-cooloff armed iff two-candle")
          .isEqualTo(isTwoCandle);

      // E6 pct-price-move (§3.3 Market-Movers >1% intraday move): armed on the scalp-market-movers family
      // (a "mover" scalp needs a real move).
      boolean isMarketMovers = id.startsWith("scalp-market-movers-");
      assertThat(tags.contains("pct-price-move"))
          .as(id + " pct-price-move armed iff market-movers")
          .isEqualTo(isMarketMovers);
      // E6 rising-volume (§3.x): armed on the scalp-market-movers family (a mover's move comes WITH
      // expanding participation — complements pct-price-move).
      assertThat(tags.contains("rising-volume"))
          .as(id + " rising-volume armed iff market-movers")
          .isEqualTo(isMarketMovers);

      // E5 higher-TF RSI caps (§3.2 5m + §4.2 daily): armed on the scalp-open-high-low family (the #2
      // setup §3.2 explicitly specifies the 5m + daily caps for).
      boolean isOpenHighLow = id.startsWith("scalp-open-high-low-");
      assertThat(tags.contains("rsi-5m-cap"))
          .as(id + " rsi-5m-cap armed iff open-high-low")
          .isEqualTo(isOpenHighLow);
      assertThat(tags.contains("rsi-daily-cap"))
          .as(id + " rsi-daily-cap armed iff open-high-low")
          .isEqualTo(isOpenHighLow);

      // E3 volume-pump (§4.15.3): armed on the scalp-gap-theory family (a breakout needs a real pump).
      boolean isGapTheory = id.startsWith("scalp-gap-theory-");
      assertThat(tags.contains("volume-pump"))
          .as(id + " volume-pump armed iff gap-theory")
          .isEqualTo(isGapTheory);

      // E3 fii-bias + constituent-gate (§4.6): armed on the scalp-trend-change family (a reversal
      // confirmed by FII flow + the index heavyweights' net push, both cited in trend-change.md).
      boolean isTrendChange = id.startsWith("scalp-trend-change-");
      assertThat(tags.contains("fii-bias"))
          .as(id + " fii-bias armed iff trend-change")
          .isEqualTo(isTrendChange);
      assertThat(tags.contains("constituent-gate"))
          .as(id + " constituent-gate armed iff trend-change")
          .isEqualTo(isTrendChange);
      // E6 psar-durability (§4.14.6): armed on the scalp-trend-change family (a reversal into a new trend
      // needs a durable, wide-PSAR move — not a whipsaw). Debatable home; flagged for owner redirect.
      assertThat(tags.contains("psar-durability"))
          .as(id + " psar-durability armed iff trend-change")
          .isEqualTo(isTrendChange);
      // E9 D4 oi-confluence-exit: armed on the scalp-trend-change family (a reversal strategy must exit
      // when the OI confluence flips back against the captured direction — Day-7 "a read against your
      // position = exit"). Live-only seam exit, parity-safe by firewall.
      assertThat(tags.contains("oi-confluence-exit"))
          .as(id + " oi-confluence-exit armed iff trend-change")
          .isEqualTo(isTrendChange);
      // E7 premium-skew (§3.12 "don't chase the higher-premium side without cues"): the soft warning
      // dot, armed on the scalp-trend-change family (the reversal/risk-aware strategy the audit homes it).
      assertThat(tags.contains("premium-skew"))
          .as(id + " premium-skew armed iff trend-change")
          .isEqualTo(isTrendChange);

      // Connect-the-Dots (#10) is the SOFT weighted-scorer strategy: these confluences are ALREADY soft
      // dots in the 18-dot scorer, so the hard-gate versions ship default-OFF and are armed on NO
      // strategy (the scorer + base rails decide, not an AND of hard pre-gates). The code stays merged +
      // available for the owner to arm selectively later.
      for (String softKeptOff :
          List.of(
              "flat-oi-stand-aside", "max-oi-sr-gate", "indicator-alignment-gate", "futures-oi-gate",
              "breadth-gate", "basis-gate", "directional-vix-gate",
              // E8: the vwap-distance entry-skip gate ships built-but-OFF — its 0.4% band is an
              // un-validated placeholder, so arming awaits an owner/optimizer-tuned threshold.
              "vwap-distance",
              // E8: time-of-day-preference is a HARD skip rendering of the doc's soft "best 10:00-11:30"
              // preference — ships built-but-OFF; arming (prefer -> only) is an owner choice.
              "time-of-day-preference")) {
        assertThat(tags.contains(softKeptOff))
            .as(id + " " + softKeptOff + " unarmed (connect-the-dots kept soft)")
            .isFalse();
      }

      // E4 iv-per-strike: per-strike IV-slope + 10-12 abs-band SOFT dots + a unilateral IV>40 buy-side
      // stand-aside. SOFT (scorer dots / a scorer-internal suppression, NOT an early-return hard gate),
      // so it is consistent with keeping connect-the-dots soft and IS armed on that family (its IV dots
      // already live in the 18-dot scorer). Armed iff the scalp-connect-the-dots family.
      boolean isConnectTheDots = id.startsWith("scalp-connect-the-dots-");
      assertThat(tags.contains("iv-per-strike"))
          .as(id + " iv-per-strike armed iff connect-the-dots")
          .isEqualTo(isConnectTheDots);

      // E4 hero-zero-iv-flat (§3.7 S22 (i) both-sides-flat IV skip): armed on the scalp-hero-zero
      // family (the expiry-day buy-side gate the both-flat block refines).
      boolean isHeroZero = id.startsWith("scalp-hero-zero-");
      assertThat(tags.contains("hero-zero-iv-flat"))
          .as(id + " hero-zero-iv-flat armed iff hero-zero")
          .isEqualTo(isHeroZero);

      // #11 (section 3.11): only the scalp-straddle family carries the straddle tag → the NEUTRAL two-leg
      // path. ScalperConfig.requireStraddle mirrors the tag; the others stay off, and the straddle
      // declares both option_types (it BUYS the ATM CE + PE) rather than a single directional side.
      boolean isStraddle = id.startsWith("scalp-straddle-");
      assertThat(cfg.requireStraddle()).as(id + " straddle neutral path").isEqualTo(isStraddle);
      // E4 low-iv-straddle (§3.11/§4.6 LONG-straddle LOW-IV skip): armed on the scalp-straddle family.
      assertThat(tags.contains("low-iv-straddle"))
          .as(id + " low-iv-straddle armed iff straddle")
          .isEqualTo(isStraddle);
      if (isStraddle) {
        List<String> optTypes = new ArrayList<>();
        config.path("universe").path("options").path("option_types").forEach(t -> optTypes.add(t.asText()));
        assertThat(optTypes).as(id + " trades both ATM legs").containsExactlyInAnyOrder("CE", "PE");
      }
    }
  }
}
