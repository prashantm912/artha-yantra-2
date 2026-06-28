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

      // E4 (iv-buyer-cap, "IV>40 -> don't buy"): armed on the golden-crossover momentum-buyer family.
      boolean isGoldenCrossover = id.startsWith("scalp-golden-crossover-");
      assertThat(tags.contains("iv-buyer-cap"))
          .as(id + " iv-buyer-cap armed iff golden-crossover")
          .isEqualTo(isGoldenCrossover);

      // E6 #10 (two-candle-substitution): armed on the scalp-two-candle family (#1, its namesake gate).
      boolean isTwoCandle = id.startsWith("scalp-two-candle-");
      assertThat(tags.contains("two-candle-substitution"))
          .as(id + " two-candle-substitution armed iff two-candle")
          .isEqualTo(isTwoCandle);

      // E3 volume-pump (§4.15.3): armed on the scalp-gap-theory family (a breakout needs a real pump).
      boolean isGapTheory = id.startsWith("scalp-gap-theory-");
      assertThat(tags.contains("volume-pump"))
          .as(id + " volume-pump armed iff gap-theory")
          .isEqualTo(isGapTheory);

      // E3 fii-bias (§4.6): armed on the scalp-trend-change family (a reversal confirmed by FII flow).
      boolean isTrendChange = id.startsWith("scalp-trend-change-");
      assertThat(tags.contains("fii-bias"))
          .as(id + " fii-bias armed iff trend-change")
          .isEqualTo(isTrendChange);

      // Connect-the-Dots (#10) is the SOFT weighted-scorer strategy: these confluences are ALREADY soft
      // dots in the 18-dot scorer, so the hard-gate versions ship default-OFF and are armed on NO
      // strategy (the scorer + base rails decide, not an AND of hard pre-gates). The code stays merged +
      // available for the owner to arm selectively later.
      for (String softKeptOff :
          List.of(
              "flat-oi-stand-aside", "max-oi-sr-gate", "indicator-alignment-gate", "futures-oi-gate",
              "breadth-gate", "basis-gate", "directional-vix-gate")) {
        assertThat(tags.contains(softKeptOff))
            .as(id + " " + softKeptOff + " unarmed (connect-the-dots kept soft)")
            .isFalse();
      }

      // #11 (section 3.11): only the scalp-straddle family carries the straddle tag → the NEUTRAL two-leg
      // path. ScalperConfig.requireStraddle mirrors the tag; the others stay off, and the straddle
      // declares both option_types (it BUYS the ATM CE + PE) rather than a single directional side.
      boolean isStraddle = id.startsWith("scalp-straddle-");
      assertThat(cfg.requireStraddle()).as(id + " straddle neutral path").isEqualTo(isStraddle);
      if (isStraddle) {
        List<String> optTypes = new ArrayList<>();
        config.path("universe").path("options").path("option_types").forEach(t -> optTypes.add(t.asText()));
        assertThat(optTypes).as(id + " trades both ATM legs").containsExactlyInAnyOrder("CE", "PE");
      }
    }
  }
}
