package in.arthayantra.marketdata.kite.session;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase-12 mock-profile indifference (D13): status is always connected, the ritual endpoints
 * answer 503 NOT_CONFIGURED, and not a single Kite variable or secret file exists in this
 * context — the FAIL criterion is the mock profile demanding one.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class KiteAuthMockProfileIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private MockMvc mockMvc;

  @Test
  void statusIsAlwaysConnectedUnderMock() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/kite/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connected").value(true))
        .andExpect(jsonPath("$.profile").value("MOCK"))
        .andExpect(jsonPath("$.tickerState").value("MOCK"));
  }

  @Test
  void ritualEndpointsAnswerNotConfiguredUnderMock() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/kite/login-url"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("NOT_CONFIGURED"));
  }
}
