package in.arthayantra.common.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Applies the {@link LogMasker} rules to every string member of structured (ECS) JSON log lines
 * (A.6) — message, MDC values, exception text alike. Registered per service via
 * {@code logging.structured.json.customizer} (set by default through
 * {@link ArthaLoggingDefaultsPostProcessor}).
 */
public class ArthaMaskingJsonCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

  @Override
  public void customize(JsonWriter.Members<Object> members) {
    members.applyingValueProcessor(
        (path, value) -> value instanceof String text ? LogMasker.mask(text) : value);
  }
}
