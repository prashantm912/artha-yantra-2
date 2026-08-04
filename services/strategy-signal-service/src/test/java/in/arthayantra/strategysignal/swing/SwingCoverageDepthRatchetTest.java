package in.arthayantra.strategysignal.swing;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ratchet the first draft's javadoc promised and never delivered. It reads the SEEDED YAMLs —
 * the same resources {@code MinerviniStrategySeeder} / {@code ManasAroraStrategySeeder} load — rather
 * than hand-built fixtures.
 *
 * <p>That distinction is the whole point. The draft this replaces characterised Manas with a
 * hand-built {@code IndicatorSpec} carrying only {@code period: 1} and asserted a depth of 20; both
 * real Manas YAMLs declare {@code sma50}, so the true value is 50. A test that supplies the input it
 * is meant to be checking cannot fail, and this one is written so that editing a strategy YAML — the
 * thing that actually changes in production — is what moves it.
 */
class SwingCoverageDepthRatchetTest {

  /** resource path -> slug, for every strategy the two swing seeders plant. */
  private static final Map<String, String> SEEDED =
      Map.of(
          "/minervini-strategies/minervini-vcp.yaml", "minervini-vcp",
          "/minervini-strategies/minervini-cheat-3c.yaml", "minervini-cheat-3c",
          "/minervini-strategies/minervini-power-play.yaml", "minervini-power-play",
          "/minervini-strategies/minervini-primary-base.yaml", "minervini-primary-base",
          "/manas-arora-strategies/manas-arora-vcp.yaml", "manas-arora-vcp",
          "/manas-arora-strategies/manas-arora-breakout.yaml", "manas-arora-breakout");

  private static StrategyDefinition definition(String resource) throws IOException {
    try (InputStream in = SwingCoverageDepthRatchetTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("seeded resource %s must exist", resource).isNotNull();
      String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return StrategyCompiler.compile(StrategyDocuments.parse(yaml).config());
    }
  }

  @Test
  @DisplayName("RATCHET: every depth-bearing param in the seeded configs is classified")
  void everyDepthParamInTheSeededConfigsIsClassified() throws IOException {
    // Only NUMERIC params can be depths — deepestParam ignores everything else, so a string or
    // boolean key (basis / alias / atr_basis / breakeven_floor) is categorically not a bar count and
    // needs no classification. Every numeric key must be either a declared depth or a justified
    // non-depth below; a NEW numeric key fails here rather than being silently under-read.
    Set<String> knownNonDepthNumbers =
        Set.of(
            "value", "cap_pct", "arm_pct", "fast_pct", "parabolic_dist_pct", "percent", "min_score",
            "threshold", "multiple", "pct", "min_pct", "max_pct", "ratio", "min_ratio", "tier",
            "qty_fraction", "r_multiple", "min_gain_pct", "atr_mult", "buffer_pct");

    Map<String, List<String>> unclassified = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : SEEDED.entrySet()) {
      StrategyDefinition def = definition(e.getKey());
      Set<String> numericKeys = new TreeSet<>();
      List<Map<String, Object>> paramMaps = new ArrayList<>();
      if (def.indicators() != null) {
        def.indicators().stream()
            .filter(s -> s != null && s.params() != null)
            .forEach(s -> paramMaps.add(s.params()));
      }
      if (def.exitRules() != null) {
        def.exitRules().stream()
            .filter(r -> r != null && r.params() != null)
            .forEach(r -> paramMaps.add(r.params()));
      }
      for (Map<String, Object> params : paramMaps) {
        params.forEach(
            (k, v) -> {
              if (v instanceof Number) {
                numericKeys.add(k);
              }
            });
      }
      List<String> bad = new ArrayList<>();
      for (String k : numericKeys) {
        if (!SwingCoverageProbe.depthParams().contains(k) && !knownNonDepthNumbers.contains(k)) {
          bad.add(k);
        }
      }
      if (!bad.isEmpty()) {
        unclassified.put(e.getValue(), bad);
      }
    }

    assertThat(unclassified)
        .as(
            "unclassified param key(s) in a seeded swing config — add each to"
                + " SwingCoverageProbe.DEPTH_PARAMS if it is a BAR COUNT, else to knownNonDepth here")
        .isEmpty();
  }

  @Test
  @DisplayName("RATCHET: entry depth is scoped to what the entry gate + scoring actually read")
  void entryDepthMatchesTheSeededGates() throws IOException {
    Map<String, Integer> actual = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : SEEDED.entrySet()) {
      actual.put(e.getValue(), SwingCoverageProbe.entryLookbackBars(definition(e.getKey())));
    }

    // primary-base is the Critical case: its gate IS crossover(px, w52h) with period 252, so entry
    // scoping alone cannot shrink it — materiality is what stops it refusing the funnel nightly.
    assertThat(actual.get("minervini-primary-base"))
        .as("w52h(252) is read by the entry gate")
        .isEqualTo(252);

    // For every other seeded strategy sma50 is exit-only (or declared-but-unread), so the entry
    // window must NOT be 50 — that was the Major: refusing entries on an indicator the entry never
    // reads. These read px/sma20/vol/pivot/cheat/thrust.
    assertThat(actual.get("minervini-vcp")).isLessThan(50);
    assertThat(actual.get("minervini-cheat-3c")).isLessThan(50);
    assertThat(actual.get("minervini-power-play")).isLessThan(50);
    assertThat(actual.get("manas-arora-vcp")).isLessThan(50);
    assertThat(actual.get("manas-arora-breakout")).isLessThan(50);
  }

  @Test
  @DisplayName("exit depth spans exit rules too, so it can exceed entry depth")
  void exitDepthCoversExitRules() throws IOException {
    // Manas's deepest EXIT window lives only in its exit rules (atr_period 20) plus its declared
    // sma50 — invisible to the entry gate. The real value is 50, NOT the 20 the deleted hand-built
    // fixture asserted.
    StrategyDefinition manas = definition("/manas-arora-strategies/manas-arora-vcp.yaml");
    assertThat(SwingCoverageProbe.exitLookbackBars(manas, null))
        .as("declared sma50 dominates atr_period 20 — the value the hand-built fixture got wrong")
        .isEqualTo(50);
    assertThat(SwingCoverageProbe.exitLookbackBars(manas, null))
        .isGreaterThan(SwingCoverageProbe.entryLookbackBars(manas));

    // and every Minervini strategy's exit reaches sma50
    for (String r :
        List.of(
            "/minervini-strategies/minervini-vcp.yaml",
            "/minervini-strategies/minervini-cheat-3c.yaml",
            "/minervini-strategies/minervini-power-play.yaml")) {
      assertThat(SwingCoverageProbe.exitLookbackBars(definition(r), null))
          .as("%s trails to sma50", r)
          .isGreaterThanOrEqualTo(50);
    }
  }

  @Test
  @DisplayName("no seeded strategy declares a multi-timeframe indicator (the documented blind spot)")
  void seededStrategiesAreSingleTimeframe() throws IOException {
    Set<String> timeframes = new HashSet<>();
    for (String r : SEEDED.keySet()) {
      StrategyDefinition def = definition(r);
      if (def.indicators() != null) {
        def.indicators().stream()
            .filter(s -> s != null && s.timeframe() != null)
            .forEach(s -> timeframes.add(s.timeframe()));
      }
    }
    // The probe expresses depth in the indicator's OWN timeframe. That is only safe while every
    // swing indicator is daily; a 1w indicator would under-state its daily depth. If this fails,
    // the timeframe-ratio conversion the probe javadoc defers is no longer optional.
    assertThat(timeframes)
        .as("a non-1d indicator invalidates the probe's timeframe assumption")
        .containsExactly("1d");
  }
}
