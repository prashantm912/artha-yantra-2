package in.arthayantra.common.web.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * A missing required query/form param must map to 400 VALIDATION_FAILED, not fall through to the
 * catch-all 500 INTERNAL_ERROR (the data-foundation value-verify hit the old 500 on `?underlying=` /
 * `?from=` omissions).
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void missingRequiredParamMapsTo400Validation() {
    var ex = new MissingServletRequestParameterException("underlying", "String");

    ResponseEntity<ErrorResponse> resp = handler.handleMissingParam(ex);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody()).isNotNull();
    assertThat(resp.getBody().code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
    assertThat(resp.getBody().details().toString()).contains("underlying");
  }
}
