package in.arthayantra.backtest.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import in.arthayantra.backtest.jobs.BacktestRunRequest.StressOverrides;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * EVO §3.2.5 cost-stress request validation ({@link JobsService#validatedSlippageMultiplier}): the
 * {@code slippageMultiplier} must be present and in {@code [1, 100]}; anything else is a 422
 * {@code VALIDATION_FAILED}. A no-container unit test of the bounds (the submission path itself is an
 * integration test).
 */
class JobsServiceStressValidationTest {

  @Test
  void acceptsMultipliersInRange() {
    assertThat(JobsService.validatedSlippageMultiplier(new StressOverrides(BigDecimal.ONE)))
        .isEqualByComparingTo("1");
    assertThat(JobsService.validatedSlippageMultiplier(new StressOverrides(new BigDecimal("2"))))
        .isEqualByComparingTo("2");
    assertThat(JobsService.validatedSlippageMultiplier(new StressOverrides(new BigDecimal("100"))))
        .isEqualByComparingTo("100");
  }

  @Test
  void rejectsBelowOne() {
    ApiException ex =
        catchThrowableOfType(
            () -> JobsService.validatedSlippageMultiplier(new StressOverrides(new BigDecimal("0.5"))),
            ApiException.class);
    assertThat(ex.httpStatus()).isEqualTo(422);
    assertThat(ex.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
  }

  @Test
  void rejectsAbsurdlyLarge() {
    ApiException ex =
        catchThrowableOfType(
            () -> JobsService.validatedSlippageMultiplier(new StressOverrides(new BigDecimal("101"))),
            ApiException.class);
    assertThat(ex.httpStatus()).isEqualTo(422);
  }

  @Test
  void rejectsMissingMultiplier() {
    assertThatThrownBy(() -> JobsService.validatedSlippageMultiplier(new StressOverrides(null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("required");
  }
}
