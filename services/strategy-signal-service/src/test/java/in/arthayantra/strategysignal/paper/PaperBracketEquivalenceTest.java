package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * The LIVE enforcement half of the exit-equivalence contract (audit
 * no-live-vs-backtest-exit-equivalence-test): walks the shared fixture's premium closes through
 * {@link PaperBracketEvaluator#breach} with the fixture's bracket levels (the ones
 * {@code PremiumBracketRules} derives — pinned by {@code PremiumBracketEquivalenceTest}) and
 * asserts the SAME exit reason at the SAME bar the backtest's {@code PremiumExitEvaluator}
 * produces. Index 0 is the entry bar — like the evaluator, it never exits.
 */
class PaperBracketEquivalenceTest {

  private record FirstBreach(String reason, Integer barOffset) {}

  private static FirstBreach walk(PositionRow pos, JsonNode premiums) {
    for (int i = 1; i < premiums.size(); i++) {
      String reason = PaperBracketEvaluator.breach(pos, new BigDecimal(premiums.get(i).asText()));
      if (reason != null) {
        return new FirstBreach(reason, i);
      }
    }
    return new FirstBreach(null, null);
  }

  @Test
  void liveBracketEnforcementMatchesTheSharedEquivalenceFixture() throws Exception {
    Path p = Path.of("..", "..", "contracts", "fixtures", "exit-equivalence.json");
    JsonNode fx = new ObjectMapper().readTree(Files.readString(p));
    PositionRow pos =
        new PositionRow(
            1L, "NFO", "NIFTY26JUL25000CE", "BUY", 75,
            new BigDecimal(fx.path("entryPremium").asText()), BigDecimal.ZERO, "OPEN",
            OffsetDateTime.parse("2026-07-03T09:18:00+05:30"), null, null,
            new BigDecimal(fx.path("expectedLevels").path("stopLoss").asText()),
            new BigDecimal(fx.path("expectedLevels").path("takeProfit").asText()));

    for (JsonNode sc : fx.path("scenarios")) {
      String name = sc.path("name").asText();
      FirstBreach got = walk(pos, sc.path("premiums"));
      JsonNode expect = sc.path("expect");
      if (expect.path("reason").isNull()) {
        assertThat(got.reason()).as(name).isNull();
      } else {
        assertThat(got.reason()).as(name).isEqualTo(expect.path("reason").asText());
        assertThat(got.barOffset()).as(name).isEqualTo(expect.path("barOffset").asInt());
      }
    }
  }
}
