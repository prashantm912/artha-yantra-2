package in.arthayantra.common.web.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * A missing required query/form param must map to 400 VALIDATION_FAILED, not fall through to the
 * catch-all 500 INTERNAL_ERROR (the data-foundation value-verify hit the old 500 on `?underlying=` /
 * `?from=` omissions); a body whose Content-Type the endpoint does not consume must map to 415
 * MEDIA_TYPE_UNSUPPORTED for the same reason (task_9ffe390d); and a controller-raised
 * {@code ResponseStatusException} must answer ITS OWN status, not the catch-all 500 (task_e2e01j).
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

  /**
   * The 404 the insights endpoints were always trying to return. Same class of defect as the 415
   * above: no mapping existed, so the throw was dispatched to the {@code Exception.class} catch-all
   * and the endpoint answered <b>500</b> with a logged stack trace (E2E T1b-F1). Only a real
   * dispatch can see that, so this drives MockMvc rather than calling the handler.
   */
  @Test
  void responseStatusExceptionIsDispatchedToItsOwnStatusNotTheCatchAll500() throws Exception {
    MockMvc mvc = standaloneMvc();

    mvc.perform(get("/not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCodes.NOT_FOUND_RESOURCE))
        .andExpect(jsonPath("$.message").value("insight not found"));
  }

  /** A non-404 status must be carried through faithfully, not flattened to 404 or 500. */
  @Test
  void nonNotFoundResponseStatusIsCarriedThrough() throws Exception {
    MockMvc mvc = standaloneMvc();

    mvc.perform(get("/conflicted"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_FAILED))
        .andExpect(jsonPath("$.message").value("already acked"));
  }

  /** A {@code null} reason falls back to the status' own phrase rather than a null message. */
  @Test
  void absentReasonFallsBackToTheStatusPhrase() {
    var ex = new ResponseStatusException(HttpStatus.FORBIDDEN);

    ResponseEntity<ErrorResponse> resp = handler.handleResponseStatus(ex);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(resp.getBody()).isNotNull();
    assertThat(resp.getBody().code()).isEqualTo(ErrorCodes.AUTH_FORBIDDEN);
    assertThat(resp.getBody().message()).isEqualTo("Forbidden");
  }

  /**
   * The resolution pin. {@code NoResourceFoundException} does NOT extend
   * {@code ResponseStatusException} at Spring 6.2.x (it extends {@code ServletException} and
   * implements {@code ErrorResponse}), so the two mappings sit in separate hierarchies and neither
   * declaration order nor re-parenting could hand this exception to the broader handler — Spring
   * always prefers the most specific mapping. What this test actually guards is the envelope itself
   * against a resolver or configuration change: it drives a real dispatch and fails the moment
   * anything other than the dedicated mapping wins. (Wording corrected after the cross-vendor review
   * pointed out the original "a future Spring that re-parents it would swap the envelope" reasoning
   * was wrong about how Spring selects handlers.)
   */
  @Test
  void noResourceFoundKeepsItsOwnEnvelopeAfterTheResponseStatusMapping() throws Exception {
    MockMvc mvc = standaloneMvc();

    mvc.perform(get("/no-resource"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCodes.NOT_FOUND_RESOURCE))
        .andExpect(jsonPath("$.message").value("No such resource"));
  }

  private static MockMvc standaloneMvc() {
    return MockMvcBuilders.standaloneSetup(new JsonOnlyController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  /** Stands in for any JSON-consuming endpoint (the live case was the strategy publish endpoint). */
  @RestController
  static class JsonOnlyController {
    @PostMapping(value = "/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
    String echo(@RequestBody(required = false) String body) {
      return "ok";
    }

    /** Mirrors InsightController's unknown-id throw. */
    @GetMapping("/not-found")
    String notFound() {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "insight not found");
    }

    /** A non-404 ResponseStatusException, to prove the status is carried and not normalized. */
    @GetMapping("/conflicted")
    String conflicted() {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "already acked");
    }

    /** The dedicated 404 mapping's own trigger, raised directly (no resource handler in standalone). */
    @GetMapping("/no-resource")
    String noResource() throws NoResourceFoundException {
      throw new NoResourceFoundException(HttpMethod.GET, "/static/missing.css");
    }
  }
}
