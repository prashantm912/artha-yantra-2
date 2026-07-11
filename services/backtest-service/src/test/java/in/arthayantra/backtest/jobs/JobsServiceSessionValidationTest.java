package in.arthayantra.backtest.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import in.arthayantra.backtest.jobs.BacktestRunRequest.SessionOverrides;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import org.junit.jupiter.api.Test;

/**
 * EVO §7.1.2 session-override validation ({@link JobsService#validatedFillTiming}): {@code fillTiming}
 * must be present and one of {@code at_close}/{@code next_open} (case-insensitive), returning the
 * canonical lowercase value; anything else is a 422 {@code VALIDATION_FAILED}. STRICT by design — a
 * silently-ignored pin would run the reconcile re-sim at the version default and manufacture a fake
 * divergence — so an unknown value is a hard reject, unlike the cost-stress fail-soft clamp. A
 * no-container unit test of the validation (the submission path itself is an integration test).
 */
class JobsServiceSessionValidationTest {

  @Test
  void acceptsBothCanonicalValues() {
    assertThat(JobsService.validatedFillTiming(new SessionOverrides("at_close"))).isEqualTo("at_close");
    assertThat(JobsService.validatedFillTiming(new SessionOverrides("next_open"))).isEqualTo("next_open");
  }

  @Test
  void normalizesCaseAndWhitespace() {
    assertThat(JobsService.validatedFillTiming(new SessionOverrides("AT_CLOSE"))).isEqualTo("at_close");
    assertThat(JobsService.validatedFillTiming(new SessionOverrides("  Next_Open "))).isEqualTo("next_open");
  }

  @Test
  void rejectsUnknownValue() {
    ApiException ex =
        catchThrowableOfType(
            () -> JobsService.validatedFillTiming(new SessionOverrides("mid_bar")), ApiException.class);
    assertThat(ex.httpStatus()).isEqualTo(422);
    assertThat(ex.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
  }

  @Test
  void rejectsMissingFillTiming() {
    assertThatThrownBy(() -> JobsService.validatedFillTiming(new SessionOverrides(null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("required");
    assertThatThrownBy(() -> JobsService.validatedFillTiming(new SessionOverrides("   ")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("required");
  }
}
