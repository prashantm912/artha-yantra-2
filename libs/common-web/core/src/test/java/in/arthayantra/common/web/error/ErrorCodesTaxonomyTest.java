package in.arthayantra.common.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins the A.4 / COMMON §8.3 taxonomy: family prefixes and canonical spellings. */
class ErrorCodesTaxonomyTest {

  private static final List<String> FAMILIES =
      List.of(
          "VALIDATION_", "AUTH_", "KITE_", "NOT_FOUND_", "CONFLICT_", "STRATEGY_", "DATA_",
          "INTERNAL_", "NOT_CONFIGURED", "UPSTREAM_");

  @Test
  void everyCodeIsScreamingSnakeMatchingItsConstantName() throws Exception {
    for (Field field : ErrorCodes.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      String value = (String) field.get(null);
      assertThat(value).as("constant name must equal its value").isEqualTo(field.getName());
      assertThat(value).matches("[A-Z][A-Z0-9_]*");
    }
  }

  @Test
  void everyCodeBelongsToKnownFamily() throws Exception {
    for (Field field : ErrorCodes.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      String value = (String) field.get(null);
      assertThat(FAMILIES)
          .as("code %s must start with a known family prefix", value)
          .anyMatch(value::startsWith);
    }
  }

  @Test
  void canonicalSpellingPinsHold() {
    // COMMON §3: these spellings win over the plan's older variants
    assertThat(ErrorCodes.KITE_TOKEN_EXPIRED).isEqualTo("KITE_TOKEN_EXPIRED");
    assertThat(ErrorCodes.DATA_GAP).isEqualTo("DATA_GAP");
    // and the older variants must not exist as constants
    assertThat(ErrorCodes.class.getDeclaredFields())
        .extracting(Field::getName)
        .doesNotContain("KITE_SESSION_EXPIRED", "INSUFFICIENT_DATA");
  }
}
