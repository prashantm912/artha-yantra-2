package in.arthayantra.backtest.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import org.junit.jupiter.api.Test;

/**
 * P1-2 / audit B4 costs-block submission validation ({@link JobsService#validatedCosts}) — money-lens
 * review fixes 3+4. Two gates, both 422 {@code VALIDATION_FAILED}: an options_of_underlying strategy
 * rejects {@code costs} outright (the premium replay never reads it — pinning would create a
 * silently-inert knob), and a malformed block is rejected at the API boundary under the strict
 * {@code CostConfig.withOverrides} grammar instead of failing (or silently mis-pricing) at run time.
 * A no-container unit test of the gates (the submission path itself is an integration test), same
 * shape as {@code JobsServiceStressValidationTest}.
 */
class JobsServiceCostsValidationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode node(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode config(String universeMode) {
    return node("{\"universe\":{\"mode\":\"" + universeMode + "\"}}");
  }

  @Test
  void acceptsWellFormedBlockOnCandlePathStrategy() {
    assertThatCode(
            () ->
                JobsService.validatedCosts(
                    node("{\"instrumentClass\":\"FUTURE\",\"slippage\":{\"ticks\":2}}"),
                    config("explicit")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsCostsOnAnOptionsModeStrategy() {
    // The premium replay builds its own per-leg OPTION CostConfig and never reads `costs`; pinning
    // it would make the caller believe the run was re-costed while the numbers say otherwise.
    ApiException ex =
        catchThrowableOfType(
            () ->
                JobsService.validatedCosts(
                    node("{\"slippage\":{\"ticks\":2}}"), config("options_of_underlying")),
            ApiException.class);
    assertThat(ex.httpStatus()).isEqualTo(422);
    assertThat(ex.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
    assertThat(ex.getMessage()).contains("candle path");
  }

  @Test
  void rejectsMalformedBlockAtSubmission() {
    // The strict grammar's IllegalArgumentException surfaces as a 422 — a typo'd or wrong-typed
    // block is refused at submission, never pinned to fail (or silently mis-price) at run time.
    ApiException quotedNumber =
        catchThrowableOfType(
            () ->
                JobsService.validatedCosts(
                    node("{\"slippage\":{\"bps\":\"8\"}}"), config("explicit")),
            ApiException.class);
    assertThat(quotedNumber.httpStatus()).isEqualTo(422);
    assertThat(quotedNumber.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
    assertThat(quotedNumber.getMessage()).contains("JSON number");

    ApiException unknownKey =
        catchThrowableOfType(
            () ->
                JobsService.validatedCosts(node("{\"slipage\":{\"ticks\":2}}"), config("explicit")),
            ApiException.class);
    assertThat(unknownKey.httpStatus()).isEqualTo(422);
    assertThat(unknownKey.getMessage()).contains("unknown key");

    ApiException forcedOption =
        catchThrowableOfType(
            () ->
                JobsService.validatedCosts(
                    node("{\"instrumentClass\":\"OPTION\"}"), config("explicit")),
            ApiException.class);
    assertThat(forcedOption.httpStatus()).isEqualTo(422);
    assertThat(forcedOption.getMessage()).contains("premium replay");
  }
}
