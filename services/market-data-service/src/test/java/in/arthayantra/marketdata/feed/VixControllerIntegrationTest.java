package in.arthayantra.marketdata.feed;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class VixControllerIntegrationTest extends MarketDataIntegrationTestBase {

  private static final InstrumentKey VIX = new InstrumentKey("NSE", "INDIA VIX");

  @TestConfiguration
  static class StubVixQuotes {
    /** Stub the quote gateway with an INDIA VIX quote (the mock feed does not seed the VIX index). */
    @Bean
    @Primary
    QuoteGateway vixQuoteGateway() {
      return keys ->
          keys.contains(VIX)
              ? Map.of(
                  VIX,
                  new QuoteGateway.Quote(
                      VIX,
                      new BigDecimal("12.97"),
                      null,
                      null,
                      null,
                      null,
                      new QuoteGateway.Quote.Ohlc(
                          new BigDecimal("12.50"),
                          new BigDecimal("13.10"),
                          new BigDecimal("12.40"),
                          new BigDecimal("12.67")),
                      OffsetDateTime.parse("2026-06-21T15:30:00+05:30")))
              : Map.of();
    }
  }

  @Autowired MockMvc mockMvc;

  @Test
  void holidaysReturnsDatedNamedRows() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/holidays"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items[0].date").exists())
        // Republic Day 2026-01-26 is a Monday with its published description.
        .andExpect(
            jsonPath(
                "$.items[?(@.date == '2026-01-26')].description",
                Matchers.hasItem("Republic Day")))
        .andExpect(
            jsonPath("$.items[?(@.date == '2026-01-26')].day", Matchers.hasItem("Monday")));
  }

  @Test
  void vixReturnsLtpDayOhlcAndChange() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/vix"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ltp").value("12.97"))
        .andExpect(jsonPath("$.dayHigh").value("13.10"))
        .andExpect(jsonPath("$.dayLow").value("12.40"))
        .andExpect(jsonPath("$.dayOpen").value("12.50"))
        .andExpect(jsonPath("$.prevClose").value("12.67"))
        // change = 12.97 − 12.67 = 0.30; changePct = 0.30 / 12.67 × 100 = 2.3678 (4 dp)
        .andExpect(jsonPath("$.change").value("0.30"))
        .andExpect(jsonPath("$.changePct").value(Matchers.startsWith("2.36")));
  }
}
