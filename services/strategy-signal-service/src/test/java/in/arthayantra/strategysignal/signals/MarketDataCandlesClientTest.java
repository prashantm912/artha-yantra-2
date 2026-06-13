package in.arthayantra.strategysignal.signals;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Locks the candle warm-up query encoding (Phase 27): an IST {@code +05:30} timestamp must reach
 * the wire as a UTC {@code …Z} instant, never a literal {@code +} — which the receiver decodes as a
 * space (x-www-form rules) and rejects with a 500, leaving the engine permanently cold.
 */
class MarketDataCandlesClientTest {

  @Test
  void sendsUtcInstantsSoThePlusOffsetIsNeverDecodedAsASpace() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MarketDataCandlesClient client =
        new MarketDataCandlesClient(builder, new ObjectMapper(), "http://market-data:8081");

    server
        .expect(
            requestTo(
                Matchers.allOf(
                    Matchers.containsString("from=2026-06-09T03:41:17.871210208Z"),
                    Matchers.containsString("to=2026-06-09T04:41:17Z"),
                    Matchers.not(Matchers.containsString("+05:30")),
                    Matchers.not(Matchers.containsString("%2B")))))
        .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

    client.fetch(
        "NSE",
        "RELIANCE",
        "1m",
        OffsetDateTime.parse("2026-06-09T09:11:17.871210208+05:30"),
        OffsetDateTime.parse("2026-06-09T10:11:17+05:30"));

    server.verify();
  }
}
