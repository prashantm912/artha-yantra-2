package in.arthayantra.strategyschema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * D18 checksum stability goldens: re-canonicalizing yields an identical hash across runs AND
 * across key re-ordering / number re-spelling ({@code 1.0} vs {@code 1}, {@code 20} vs
 * {@code 20.0}); different content yields a different hash.
 */
class CanonicalizationTest {

  @Test
  void recanonicalizingIsStableAcrossRuns() throws Exception {
    String yaml = fixture("options-scalper.yaml");

    StrategyDocuments.Parsed first = StrategyDocuments.parse(yaml);
    StrategyDocuments.Parsed second = StrategyDocuments.parse(yaml);

    assertThat(first.canonicalJson()).isEqualTo(second.canonicalJson());
    assertThat(first.checksum()).isEqualTo(second.checksum()).hasSize(64);
  }

  @Test
  void keyReorderingAndNumberRespellingCanonicalizeIdentically() throws Exception {
    StrategyDocuments.Parsed original = StrategyDocuments.parse(fixture("options-scalper.yaml"));
    StrategyDocuments.Parsed reordered =
        StrategyDocuments.parse(fixture("options-scalper-reordered.yaml"));

    assertThat(reordered.canonicalJson()).isEqualTo(original.canonicalJson());
    assertThat(reordered.checksum()).isEqualTo(original.checksum());
  }

  @Test
  void contentChangePerturbsTheChecksum() throws Exception {
    String yaml = fixture("options-scalper.yaml");

    StrategyDocuments.Parsed original = StrategyDocuments.parse(yaml);
    StrategyDocuments.Parsed changed =
        StrategyDocuments.parse(yaml.replace("threshold: 0.65", "threshold: 0.66"));

    assertThat(changed.checksum()).isNotEqualTo(original.checksum());
  }

  @Test
  void canonicalFormSortsKeysAndNormalizesNumbers() {
    String yaml =
        """
        zebra: 2.0
        alpha: { b: 1.50, a: "text" }
        list: [3.10, 4]
        """;

    com.fasterxml.jackson.databind.JsonNode node = YamlStrategyLoader.load(yaml);
    String canonical = CanonicalJson.write(node);

    assertThat(canonical)
        .isEqualTo("{\"alpha\":{\"a\":\"text\",\"b\":1.5},\"list\":[3.1,4],\"zebra\":2}");
  }

  private static String fixture(String name) throws Exception {
    return Files.readString(
        Path.of(CanonicalizationTest.class.getResource("/corpus/accept/" + name).toURI()),
        StandardCharsets.UTF_8);
  }
}
