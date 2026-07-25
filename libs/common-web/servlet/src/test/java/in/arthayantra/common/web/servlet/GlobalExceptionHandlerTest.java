package in.arthayantra.common.web.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A missing required query/form param must map to 400 VALIDATION_FAILED, not fall through to the
 * catch-all 500 INTERNAL_ERROR (the data-foundation value-verify hit the old 500 on `?underlying=` /
 * `?from=` omissions); a body whose Content-Type the endpoint does not consume must map to 415
 * MEDIA_TYPE_UNSUPPORTED for the same reason (task_9ffe390d).
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

  @Test
  void unsupportedContentTypeMapsTo415() {
    var ex =
        new HttpMediaTypeNotSupportedException(
            MediaType.APPLICATION_FORM_URLENCODED, java.util.List.of(MediaType.APPLICATION_JSON));

    ResponseEntity<ErrorResponse> resp = handler.handleUnsupportedMediaType(ex);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(resp.getBody()).isNotNull();
    assertThat(resp.getBody().code()).isEqualTo(ErrorCodes.MEDIA_TYPE_UNSUPPORTED);
    assertThat(resp.getBody().details().toString()).contains("application/x-www-form-urlencoded");
    assertThat(resp.getBody().details().toString()).contains("application/json");
  }

  /** A body with no Content-Type at all also raises this — the null contentType must not NPE. */
  @Test
  void absentContentTypeMapsTo415WithoutNpe() {
    var ex = new HttpMediaTypeNotSupportedException("no content type");

    ResponseEntity<ErrorResponse> resp = handler.handleUnsupportedMediaType(ex);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(resp.getBody()).isNotNull();
    assertThat(resp.getBody().details().toString()).contains("(absent)");
  }

  /**
   * The one that actually pins the bug. The defect was never "the mapping is wrong" — there WAS no
   * mapping, so Spring dispatched the exception to the {@code Exception.class} catch-all and the
   * endpoint answered <b>500</b>. Calling the handler method directly cannot detect that; only
   * driving a real dispatch can. Verified RED before the fix (500 / INTERNAL_ERROR), green after.
   */
  @Test
  void wrongContentTypeIsDispatchedTo415NotTheCatchAll500() throws Exception {
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new JsonOnlyController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    mvc.perform(
            post("/echo")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("password=x"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value(ErrorCodes.MEDIA_TYPE_UNSUPPORTED));
  }

  /** Stands in for any JSON-consuming endpoint (the live case was the strategy publish endpoint). */
  @RestController
  static class JsonOnlyController {
    @PostMapping(value = "/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
    String echo(@RequestBody(required = false) String body) {
      return "ok";
    }
  }
}
