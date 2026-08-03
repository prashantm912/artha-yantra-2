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
  void theApiRefusesToSubscribeACashEquityToTheLiveTicker() throws Exception {
    // PR #1251 ratchet, exercised through the REAL controller - the bypass a config-level guard
    // missed. Until this landed, this exact request returned 200 (it is what this class's
    // round-trip test used to send), which is why the round-trip now uses an NFO future instead.
    // An equity on ticks:last arms a live money-path exposure: PaperBracketEvaluator prices open
    // paper positions off that hash against the STORED paper_positions.stop_loss, which is written
    // once at entry and never re-scaled when a corporate action re-planes the market. See
    // SwingEquityBracketTripwireIntegrationTest for the four Criticals that stopped it being fixed.
    mockMvc
        .perform(
            post("/api/v1/market/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"exchange\":\"NSE\",\"tradingsymbol\":\"RELIANCE\",\"mode\":\"quote\"}"))
        .andExpect(status().isBadRequest());

    // ...and it never reaches the registry, so no hold is persisted for the replayer to restore.
    mockMvc
        .perform(get("/api/v1/market/subscriptions"))
        .andExpect(
            jsonPath(
                "$.items[?(@.tradingsymbol == 'RELIANCE')]", org.hamcrest.Matchers.empty()));
  }

  @Test
  void subscribeListUnsubscribeRoundTrip() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/market/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY2661618000CE\",\"mode\":\"quote\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveMode").value("quote"));

    // a second, stronger hold raises the effective mode
    mockMvc
        .perform(
            post("/api/v1/market/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY2661618000CE\",\"mode\":\"full\","
                        + "\"subscriber\":\"engine\",\"priority\":\"strategy\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveMode").value("full"));

    mockMvc
        .perform(get("/api/v1/market/subscriptions"))
        .andExpect(
            jsonPath(
                "$.items[?(@.tradingsymbol == 'NIFTY2661618000CE')].mode",
                org.hamcrest.Matchers.hasItem("FULL")))
        .andExpect(
            jsonPath(
                "$.items[?(@.tradingsymbol == 'NIFTY2661618000CE')].subscribers",
                org.hamcrest.Matchers.hasItem(2)));

    mockMvc
        .perform(
            delete("/api/v1/market/subscriptions")
                .param("exchange", "NFO")
                .param("tradingsymbol", "NIFTY2661618000CE")
                .param("subscriber", "engine"))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            delete("/api/v1/market/subscriptions")
                .param("exchange", "NFO")
                .param("tradingsymbol", "NIFTY2661618000CE"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/market/subscriptions"))
        .andExpect(
            jsonPath(
                "$.items[?(@.tradingsymbol == 'NIFTY2661618000CE')]", org.hamcrest.Matchers.empty()));
  }

  @Test
  void unknownInstrumentIs404AndBadModeIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/market/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exchange\":\"NFO\",\"tradingsymbol\":\"NOPE123\",\"mode\":\"quote\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND_INSTRUMENT"));

    mockMvc
        .perform(
            post("/api/v1/market/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY2661618000CE\",\"mode\":\"depth\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
}
