package in.arthayantra.marketdata.kite.ticker;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase-13 IT: the subscriptions surface round-trips in MOCK mode — the registry works without
 * any live socket — and the pinned set includes INDIA VIX (FP-14, an ordinary NSE index).
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class SubscriptionsApiIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private InstrumentSyncService syncService;
  @Autowired private PinnedIndicesSubscriber pinnedIndices;

  @BeforeEach
  void seedMasterAndPins() {
    syncService.runSync(); // deterministic fixture dump — includes INDIA VIX since Phase 13
    pinnedIndices.ensurePinned();
  }

  @Test
  void pinnedSetIncludesIndiaVix() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/subscriptions"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.items[?(@.tradingsymbol == 'INDIA VIX')].priority",
                org.hamcrest.Matchers.hasItem("PINNED_INDEX")))
        .andExpect(
            jsonPath(
                "$.items[?(@.tradingsymbol == 'NIFTY 50')]", org.hamcrest.Matchers.hasSize(1)));
  }

  @Test
  void subscribeListUnsubscribeRoundTrip() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/market/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"exchange\":\"NSE\",\"tradingsymbol\":\"RELIANCE\",\"mode\":\"quote\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveMode").value("quote"));

    // a second, stronger hold raises the effective mode
    mockMvc
        .perform(
            post("/api/v1/market/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"exchange\":\"NSE\",\"tradingsymbol\":\"RELIANCE\",\"mode\":\"full\","
                        + "\"subscriber\":\"engine\",\"priority\":\"strategy\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveMode").value("full"));

    mockMvc
        .perform(get("/api/v1/market/subscriptions"))
        .andExpect(
            jsonPath(
                "$.items[?(@.tradingsymbol == 'RELIANCE')].mode",
                org.hamcrest.Matchers.hasItem("FULL")))
        .andExpect(
            jsonPath(
                "$.items[?(@.tradingsymbol == 'RELIANCE')].subscribers",
                org.hamcrest.Matchers.hasItem(2)));

    mockMvc
        .perform(
            delete("/api/v1/market/subscriptions")
                .param("exchange", "NSE")
                .param("tradingsymbol", "RELIANCE")
                .param("subscriber", "engine"))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            delete("/api/v1/market/subscriptions")
                .param("exchange", "NSE")
                .param("tradingsymbol", "RELIANCE"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/market/subscriptions"))
        .andExpect(
            jsonPath(
                "$.items[?(@.tradingsymbol == 'RELIANCE')]", org.hamcrest.Matchers.empty()));
  }

  @Test
  void unknownInstrumentIs404AndBadModeIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/market/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exchange\":\"NSE\",\"tradingsymbol\":\"NOPE123\",\"mode\":\"quote\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND_INSTRUMENT"));

    mockMvc
        .perform(
            post("/api/v1/market/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exchange\":\"NSE\",\"tradingsymbol\":\"RELIANCE\",\"mode\":\"depth\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
}
