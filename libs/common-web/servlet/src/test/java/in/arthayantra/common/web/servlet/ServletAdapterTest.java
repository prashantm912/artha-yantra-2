package in.arthayantra.common.web.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;

/** The D8 envelope contract through real MVC machinery (A.3). */
@SpringBootTest(classes = ServletAdapterTestApp.class)
@AutoConfigureMockMvc
class ServletAdapterTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext context;

  @Test
  void notFoundMapsToEnvelope() throws Exception {
    mockMvc
        .perform(get("/things/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND_THING"))
        .andExpect(jsonPath("$.message").value("no such thing"))
        .andExpect(jsonPath("$.details").isMap());
  }

  @Test
  void conflictMapsToEnvelope() throws Exception {
    mockMvc
        .perform(get("/things/conflicted"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT_THING_NAME"));
  }

  @Test
  void validationFailureCarriesFieldMap() throws Exception {
    mockMvc
        .perform(post("/things").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details.fields.name").isNotEmpty());
  }

  /**
   * The proxy pin, and the reason this test cannot live beside its siblings in {@code
   * GlobalExceptionHandlerTest}: class-level {@code @Validated} does not validate by itself. It
   * <em>disables</em> Spring MVC's own method validation and hands the job to Spring AOP, so the
   * constraint only fires if the bean really was wrapped. A standalone {@code MockMvcBuilders}
   * setup registers a plain instance with no post-processors and therefore no proxy — the endpoint
   * would answer 200 and any assertion about the failure envelope would pass for the wrong reason
   * (or never run at all). Asserting the CGLIB proxy AND the validation advice up front is what
   * makes the next test's 400 mean something.
   */
  @Test
  void theValidatedControllerIsCglibProxiedByTheValidationAdvice() {
    Object bean = context.getBean(ServletAdapterTestApp.ConstrainedController.class);

    assertThat(AopUtils.isCglibProxy(bean)).isTrue();
    assertThat(((Advised) bean).getAdvisors())
        .anySatisfy(
            advisor ->
                assertThat(advisor.getAdvice()).isInstanceOf(MethodValidationInterceptor.class));
  }

  /**
   * The one that pins the bug. {@code @Validated} + {@code @Min} is the idiomatic Spring answer to
   * "reject a bad query param", but the AOP interceptor throws {@code ConstraintViolationException}
   * — neither {@code MethodArgumentNotValidException} nor {@code HandlerMethodValidationException},
   * and not a {@code ResponseStatusException} subtype either — so before the mapping existed the
   * throw was dispatched to the {@code Exception.class} catch-all and the endpoint answered
   * <b>500</b> with a logged stack trace. Three PRs in one night (#1154/#1157/#1161) hand-rolled an
   * {@code ApiException} instead, one of them after measuring exactly this 500. Verified RED before
   * the fix (500 / INTERNAL_ERROR), green after.
   */
  @Test
  void constraintViolationIsDispatchedTo400NotTheCatchAll500() throws Exception {
    mockMvc
        .perform(get("/things/window").param("lookbackDays", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("Request validation failed"))
        .andExpect(jsonPath("$.details.fields.lookbackDays").isNotEmpty());
  }

  /**
   * The envelope must be indistinguishable from the {@code @RequestBody} one a client already
   * sees — same status, same code, same {@code details.fields.<name>} shape (compare with {@link
   * #validationFailureCarriesFieldMap()}). The field key is the trailing node of the violation's
   * property path: the raw path is {@code window.lookbackDays}, and the leading {@code window} is
   * the java method name, which no client knows or can act on.
   */
  @Test
  void constraintViolationFieldKeyIsTheParamNameNotTheMethodQualifiedPath() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/things/window").param("lookbackDays", "0")).andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body).contains("\"lookbackDays\"").doesNotContain("window.lookbackDays");
  }

  /** A value that satisfies the constraint must still reach the method body. */
  @Test
  void validValueStillReachesTheConstrainedController() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/things/window").param("lookbackDays", "5"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).isEqualTo("window:5");
  }

  @Test
  void unexpectedExceptionNeverLeaksStackTrace() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/things/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.details.correlationId").isNotEmpty())
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("IllegalStateException").doesNotContain("kaboom");
  }

  @Test
  void identityFilterBindsHeadersToMdcAndEchoesRequestId() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/things/mdc").header("X-Request-Id", "rid-42").header("X-Artha-User", "owner"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Request-Id", "rid-42"))
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).isEqualTo("rid-42|owner");
  }

  @Test
  void missingRequestIdGetsGenerated() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/things/mdc")).andExpect(status().isOk()).andReturn();

    assertThat(result.getResponse().getHeader("X-Request-Id")).isNotBlank();
  }
}
