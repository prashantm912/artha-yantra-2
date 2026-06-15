package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OiIntervalTest {

  @Test
  void parsesEverySupportedToken() {
    assertThat(OiInterval.parse("1m").bucket()).isEqualTo(Duration.ofMinutes(1));
    assertThat(OiInterval.parse("3m").bucket()).isEqualTo(Duration.ofMinutes(3));
    assertThat(OiInterval.parse("60m").bucket()).isEqualTo(Duration.ofMinutes(60));
  }

  @Test
  void rejectsUnknownTokenWithValidationCode() {
    assertThatThrownBy(() -> OiInterval.parse("7m"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e ->
                assertThat(((ApiException) e).code()).isEqualTo("VALIDATION_INTERVAL_UNSUPPORTED"));
  }

  @Test
  void pgIntervalLiteralIsSafe() {
    assertThat(OiInterval.M5.pgInterval()).isEqualTo("5 minutes");
  }
}
