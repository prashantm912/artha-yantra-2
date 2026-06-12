package in.arthayantra.strategyschema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** CD-5 posture: SafeConstructor (no type instantiation), 256 KB cap, mapping root only. */
class LoaderGuardrailsTest {

  @Test
  void oversizeDocumentIsRejected() {
    String filler = "x".repeat(300 * 1024);

    assertThatThrownBy(() -> YamlStrategyLoader.load("name: " + filler))
        .isInstanceOf(StrategyParseException.class)
        .hasMessageContaining("256 KB");
  }

  @Test
  void arbitraryTypeTagsAreRejectedBySafeConstructor() {
    assertThatThrownBy(() -> YamlStrategyLoader.load("evil: !!java.io.File [/etc/passwd]"))
        .isInstanceOf(StrategyParseException.class)
        .hasMessageContaining("unparseable YAML");
  }

  @Test
  void nonMappingRootIsRejected() {
    assertThatThrownBy(() -> YamlStrategyLoader.load("- just\n- a\n- list"))
        .isInstanceOf(StrategyParseException.class)
        .hasMessageContaining("mapping");
  }

  @Test
  void emptyDocumentIsRejected() {
    assertThatThrownBy(() -> YamlStrategyLoader.load("   "))
        .isInstanceOf(StrategyParseException.class)
        .hasMessageContaining("empty");
  }

  @Test
  void unparseableYamlSurfacesAsSingleValidationError() {
    ValidationResult result = StrategyDocuments.validate("{{{ not yaml");

    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).hasSize(1);
  }

  @Test
  void schemaDocumentIsServedByteIdentically() {
    String first = StrategySchemaV1.documentText();
    String second = StrategySchemaV1.documentText();

    assertThat(first).isSameAs(second);
    assertThat(first).contains("\"strategy-schema/v1\"");
    assertThat(first).contains("https://json-schema.org/draft/2020-12/schema");
  }
}
