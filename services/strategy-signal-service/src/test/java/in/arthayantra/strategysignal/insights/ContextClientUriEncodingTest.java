package in.arthayantra.strategysignal.insights;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * Pins the URI {@link ContextClient} actually puts on the wire.
 *
 * <p>⚠️ <b>Why this exists (ledger H27-adjacent, #1420 follow-up).</b> #1420 fixed the CONFIGURED
 * underlying from the non-canonical {@code NIFTY} to the canonical {@code NIFTY 50} and added
 * {@code ContextUnderlyingNamesTest} to freeze that. The sweep still 404-ed on every run — measured
 * live 2026-08-20 by the 10:05 probe: ten sweeps and a 404 on every one, while NIFTY 50
 * max-pain drift sat at exactly 50.00 against a threshold of 50 with a {@code >=} compare, so a
 * MAXPAIN shift was mathematically required to fire and did not.
 *
 * <p>The cause is a DOUBLE ENCODE, and it is invisible to any test that looks at the configured
 * name: {@code UriComponentsBuilder.toUriString()} encodes ({@code NIFTY 50} becomes
 * {@code NIFTY%2050}), and {@code RestClient.uri(String, Object...)} then pre-encodes the template again under
 * its default {@code TEMPLATE_AND_VALUES} mode, turning the {@code %} into {@code %25}. SENSEX was
 * never affected because it contains no character that needs encoding — which is exactly why the
 * defect read as "NIFTY-specific config problem" rather than as a client bug.
 *
 * <p>⚠️ So {@code ContextUnderlyingNamesTest} is NOT this guard and cannot be: it validates the name
 * as source text against market-data's vocabulary, and a name can be perfectly canonical and still
 * unsendable. <b>This test asserts the bytes, which is the only thing the server ever sees.</b>
 */
class ContextClientUriEncodingTest {

  /**
   * Captures the fully-resolved request URI and short-circuits — the interceptor never calls {@code
   * execution.execute()}, so no socket is opened and the assertion does not depend on the request
   * factory {@link ContextClient} installs on itself.
   */
  private static ContextClient clientRecordingInto(List<URI> sent) {
    RestClient.Builder builder =
        RestClient.builder()
            .requestInterceptor(
                (request, body, execution) -> {
                  sent.add(request.getURI());
                  return new MockClientHttpResponse("{}".getBytes(UTF_8), HttpStatus.OK);
                });
    return new ContextClient(builder, new ObjectMapper(), "http://market-data:8080");
  }

  @Test
  @DisplayName("a space in the underlying is encoded exactly once")
  void theUnderlyingNameIsEncodedOnce() {
    List<URI> sent = new ArrayList<>();

    clientRecordingInto(sent).optionsShift("NSE", "NIFTY 50");

    assertThat(sent).hasSize(1);
    assertThat(sent.get(0).getRawQuery())
        .as(
            "a double encode yields name=NIFTY%252050, which market-data answers 404 — measured"
                + " live 2026-08-20: ten sweeps, a 404 on every one")
        .isEqualTo("name=NIFTY%2050");
    // The DECODED round-trip is the reader-facing half of the same claim: whatever the wire bytes,
    // the server must recover the canonical name it was given.
    assertThat(sent.get(0).getQuery()).isEqualTo("name=NIFTY 50");
  }

  @Test
  @DisplayName("a name with no special character is unchanged — SENSEX is the control")
  void aNameNeedingNoEncodingIsUntouched() {
    // SENSEX worked throughout the defect's whole life. If this ever moves, the fix has changed
    // behaviour for the path that was never broken.
    List<URI> sent = new ArrayList<>();

    clientRecordingInto(sent).optionsShift("BSE", "SENSEX");

    assertThat(sent.get(0).getRawQuery()).isEqualTo("name=SENSEX");
  }

  @Test
  @DisplayName("the path-only calls are unaffected by the encoding change")
  void thePathOnlyCallsKeepTheirExactPath() {
    // optionsShift is the ONLY ContextClient method that builds a query param; the other three pass
    // a bare path. The fix widens the shared get() helper, so these pin that the widening is inert
    // for them — a varargs overload with zero variables must not start expanding braces or dropping
    // the path.
    List<URI> sent = new ArrayList<>();
    ContextClient client = clientRecordingInto(sent);

    client.ingestHealth();
    client.dataHealth();
    client.marketStructure();

    assertThat(sent).extracting(URI::getPath)
        .containsExactly(
            "/api/v1/market/health/ingest",
            "/api/v1/market/health/data",
            "/api/v1/market/context/day-context");
    assertThat(sent).allSatisfy(u -> assertThat(u.getRawQuery()).isNull());
  }
}
