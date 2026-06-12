package in.arthayantra.common.web.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The D8 envelope contract through real MVC machinery (A.3). */
@SpringBootTest(classes = ServletAdapterTestApp.class)
@AutoConfigureMockMvc
class ServletAdapterTest {

  @Autowired private MockMvc mockMvc;

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
