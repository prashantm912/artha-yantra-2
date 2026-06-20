package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScalperManualChecksTest {

  @Test
  void canonicalListIsWellFormed() {
    List<ScalperManualChecks.Check> checks = ScalperManualChecks.CHECKS;
    assertThat(checks).hasSize(7);
    assertThat(checks).extracting(ScalperManualChecks.Check::key).doesNotHaveDuplicates();
    assertThat(checks)
        .allSatisfy(
            c -> {
              assertThat(c.key()).isNotBlank();
              assertThat(c.label()).isNotBlank();
              assertThat(c.docRef()).isNotBlank();
              assertThat(c.assist()).isNotBlank();
            });
  }

  @Test
  void appendToStampsAManualChecksArray() {
    ObjectMapper om = new ObjectMapper();
    ObjectNode root = om.createObjectNode();

    ScalperManualChecks.appendTo(root);

    JsonNode arr = root.get("manual_checks");
    assertThat(arr.isArray()).isTrue();
    assertThat(arr).hasSize(7);
    JsonNode first = arr.get(0);
    assertThat(first.has("key")).isTrue();
    assertThat(first.has("label")).isTrue();
    assertThat(first.has("doc_ref")).isTrue();
    assertThat(first.has("assist")).isTrue();
    assertThat(first.get("key").asText()).isEqualTo("news_clear");
  }
}
