package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fail-soft + merged-namespace unit tests for the registry control plane (no Spring, stubbed repo):
 * a DB read failure at BOOT degrades to the env fallback without crashing the context; a LATER
 * failure keeps the prior active set (never silently evicts registered challengers mid-session);
 * an env-fallback name cannot be re-registered (the DB row would silently shadow it).
 */
class ShadowVariantRegistryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ENV_JSON =
      "[{\"name\":\"env-vol-off\",\"rails\":[{\"rail\":\"volume-floor\",\"disable\":true}]}]";

  private static ShadowVariants env(String json) {
    return new ShadowVariants(MAPPER, json);
  }

  private static ShadowVariantRegistryRepository.RegistryRow row(String name, String specJson) {
    try {
      return new ShadowVariantRegistryRepository.RegistryRow(
          UUID.randomUUID(), name, null, MAPPER.readTree(specJson), true, null,
          OffsetDateTime.now(), null);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void bootDbFailureDegradesToEnvFallbackWithoutCrash() {
    ShadowVariantRegistryRepository repo = mock(ShadowVariantRegistryRepository.class);
    when(repo.findEnabled()).thenThrow(new RuntimeException("db down at boot"));

    ShadowVariantRegistry registry =
        new ShadowVariantRegistry(env(ENV_JSON), repo, MAPPER, 16, new BigDecimal("0.60"));

    assertThat(registry.active())
        .extracting(ShadowVariants.Variant::name)
        .containsExactly("env-vol-off");
  }

  @Test
  void laterDbFailureKeepsThePriorActiveSet() {
    ShadowVariantRegistryRepository repo = mock(ShadowVariantRegistryRepository.class);
    when(repo.findEnabled())
        .thenReturn(List.of(row("db-composite-055", "{\"compositeThreshold\":0.55}")))
        .thenThrow(new RuntimeException("transient db hiccup"));

    ShadowVariantRegistry registry =
        new ShadowVariantRegistry(env(ENV_JSON), repo, MAPPER, 16, new BigDecimal("0.60"));
    assertThat(registry.active())
        .extracting(ShadowVariants.Variant::name)
        .containsExactly("env-vol-off", "db-composite-055");

    registry.reload(); // the failing read — must NOT evict the registered challenger

    assertThat(registry.active())
        .extracting(ShadowVariants.Variant::name)
        .containsExactly("env-vol-off", "db-composite-055");
  }

  @Test
  void envFallbackNameCannotBeReRegistered() {
    ShadowVariantRegistryRepository repo = mock(ShadowVariantRegistryRepository.class);
    when(repo.findEnabled()).thenReturn(List.of());
    when(repo.existsByName(anyString())).thenReturn(false);

    ShadowVariantRegistry registry =
        new ShadowVariantRegistry(env(ENV_JSON), repo, MAPPER, 16, new BigDecimal("0.60"));

    assertThatThrownBy(
            () ->
                registry.register(
                    "env-vol-off", null, MAPPER.readTree("{\"compositeThreshold\":0.55}"), null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(409);
              assertThat(e.getMessage()).contains("env fallback");
            });
  }
}
