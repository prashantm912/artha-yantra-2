package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.strategysignal.registry.BtstExitRuleParityRules.SAFE_LEVEL_BASES;
import static in.arthayantra.strategysignal.registry.BtstExitRuleParityRules.SAFE_TYPES;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The BTST live-vs-sim exit PARITY BOUNDARY (adversarial review MED, 2026-07-12, on the
 * task_3e95fade live sweep). The live {@code SignalEngine.sweepBtstExit} and the sim
 * ({@code TickwiseGoldenRunner} btst branch, P0-5 #759) run the SAME {@code ExitEvaluator} code but
 * over DIFFERENT daily bars: the sim's primary holds synthetic 15:20 pre-close daily bars while the
 * live {@code 1d} series holds native ~15:30 cagg/bhavcopy bars. Equivalence therefore holds ONLY for
 * exit types that read the persisted {@code entryPrice} + today's appended pre-close bar:
 *
 * <ul>
 *   <li>{@code time_stop} — counts primary-series session dates, identical either way;
 *   <li>{@code stop_loss}/{@code take_profit} with basis {@code premium_pct}/{@code percent} — a
 *       fixed level off the persisted entry price, checked against today's appended bar's close.
 * </ul>
 *
 * <p>Every price-HISTORY-reading type ({@code atr_multiple} distances, {@code trailing_stop} peaks,
 * {@code square_off} fast/parabolic reads, {@code r_multiple} off an ATR stop, {@code signal_exit}
 * indicator gates) computes off those DIVERGENT bars and would drift SILENTLY — live exits would
 * neither match the sim nor fail a test; the drift would surface only as reconciliation noise. This
 * test fences every {@code style: btst} strategy YAML to the safe set so a future ATR/trailing btst
 * exit fails LOUDLY here instead. Widening the set requires making the live sweep and the sim read
 * the SAME bars (or pinning the semantics in a shared fixture, the exit-equivalence.json pattern).
 */
class BtstExitRuleParityBoundaryTest {

  @Test
  void everyBtstStrategyUsesOnlyParitySafeExitRules() throws IOException {
    Resource[] yamls =
        new PathMatchingResourcePatternResolver().getResources("classpath*:scalper-strategies/*.yaml");
    assertThat(yamls).as("seeded scalper strategy YAMLs on the classpath").isNotEmpty();

    int btstCount = 0;
    for (Resource resource : yamls) {
      String yaml = resource.getContentAsString(StandardCharsets.UTF_8);
      JsonNode config = StrategyDocuments.parse(yaml).config();
      if (!"btst".equals(config.path("risk").path("session").path("style").asText())) {
        continue;
      }
      btstCount++;
      for (JsonNode rule : config.path("exit_rules")) {
        String type = rule.path("type").asText();
        assertThat(SAFE_TYPES)
            .as(
                "%s: btst exit type '%s' is OUTSIDE the live-vs-sim parity-safe set %s — the live"
                    + " pre-close sweep and the sim evaluate over DIFFERENT daily bars (synthetic"
                    + " 15:20 pre-close vs native ~15:30 cagg/bhavcopy), so a price-history-reading"
                    + " exit drifts SILENTLY. See SignalEngine.sweepBtstExit's PARITY BOUNDARY note.",
                resource.getFilename(), type, SAFE_TYPES)
            .contains(type);
        if ("stop_loss".equals(type) || "take_profit".equals(type)) {
          String basis = rule.path("params").path("basis").asText();
          assertThat(SAFE_LEVEL_BASES)
              .as(
                  "%s: btst %s basis '%s' is OUTSIDE the parity-safe set %s — only a"
                      + " percent-of-entry level (reads the persisted entryPrice, never price"
                      + " history) evaluates identically sim-vs-live on the btst sweep.",
                  resource.getFilename(), type, basis, SAFE_LEVEL_BASES)
              .contains(basis);
        }
      }
    }
    assertThat(btstCount)
        .as("at least one style: btst YAML must exist (else this boundary guards nothing)")
        .isGreaterThanOrEqualTo(1);
  }
}
