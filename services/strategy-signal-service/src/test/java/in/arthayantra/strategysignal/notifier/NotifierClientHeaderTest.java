package in.arthayantra.strategysignal.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Regression for the 2026-07-20 live alerting outage: EVERY ntfy push whose title carried a
 * non-ASCII character died at the JDK HTTP layer with {@code IllegalArgumentException: invalid
 * header value}, so no incident could page the owner.
 *
 * <p>Root cause: ntfy carries the title as the HTTP {@code Title} HEADER, and the JDK
 * {@code HttpClient} (the request factory Spring resolves here — no Apache/Jetty/OkHttp on the
 * classpath) rejects any header value containing a char {@code >= U+0100}, a control char
 * ({@code 0x00–0x1F} except tab) or {@code 0x7F}. Alert titles across this codebase routinely
 * carry an em-dash ({@code —}, U+2014) — e.g. {@code "ArthaYantra Paper — bracket starved (…)"}
 * — which is exactly such a char.
 *
 * <p>These tests drive the REAL {@link NotifierClient} against a loopback {@link HttpServer} that
 * captures what actually reached the wire — never a real ntfy topic (the stub URL and topic here
 * are literals; no credential or live topic appears in this file).
 */
class NotifierClientHeaderTest {

  /** The exact title shape that was failing live (PaperStaleTickAlerter's bracket-starve page). */
  private static final String LIVE_FAILING_TITLE =
      "ArthaYantra Paper — bracket starved (NFO:NIFTY26JUL24500CE)";

  private HttpServer server;
  private NotifierClient client;
  private SimpleMeterRegistry meters;
  private final AtomicReference<String> receivedTitle = new AtomicReference<>();
  private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          receivedTitle.set(exchange.getRequestHeaders().getFirst("Title"));
          try (InputStream in = exchange.getRequestBody()) {
            receivedBody.set(in.readAllBytes());
          }
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    meters = new SimpleMeterRegistry();
    client =
        new NotifierClient(
            RestClient.builder(),
            meters,
            "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort(),
            "unit-test-topic",
            "",
            "");
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  /**
   * THE outage reproduction: before the fix this throws {@code IllegalArgumentException: invalid
   * header value: "ArthaYantra Paper — bracket starved (…)"} and nothing ever reaches the wire.
   */
  @Test
  void emDashTitleReachesTheWireAsAscii() {
    assertThatCode(() -> client.send("NTFY", LIVE_FAILING_TITLE, "detail")).doesNotThrowAnyException();

    assertThat(receivedTitle.get())
        .isEqualTo("ArthaYantra Paper - bracket starved (NFO:NIFTY26JUL24500CE)");
  }

  /** Arrows, ellipsis and smart quotes transliterate rather than being dropped to nothing. */
  @Test
  void typographicCharactersTransliterateToAscii() {
    client.send("NTFY", "ArthaYantra → stop 'hit' “now” …", "detail");

    assertThat(receivedTitle.get()).isEqualTo("ArthaYantra -> stop 'hit' \"now\" ...");
  }

  /**
   * A header cannot span lines — an embedded CR/LF would be a response-splitting vector as well as
   * an {@code invalid header value}. CR, LF, tab and NUL all collapse to a single space.
   */
  @Test
  void newlinesAndControlCharactersCollapseToSpace() {
    client.send("NTFY", "ArthaYantra\r\nbatch\tfailed\0now", "detail");

    assertThat(receivedTitle.get()).isEqualTo("ArthaYantra batch failed now");
  }

  /** An unmappable non-ASCII char degrades to '?' rather than killing the whole delivery. */
  @Test
  void unmappableNonAsciiDegradesToQuestionMark() {
    client.send("NTFY", "ArthaYantra 中文 alert", "detail");

    assertThat(receivedTitle.get()).isEqualTo("ArthaYantra ?? alert");
  }

  /** Titles are bounded — the free-form detail belongs in the body, which is unconstrained. */
  @Test
  void overlongTitleIsTruncated() {
    client.send("NTFY", "A".repeat(500), "detail");

    assertThat(receivedTitle.get()).hasSize(200).endsWith("...");
  }

  /** A plain ASCII title is passed through byte-identically (no regression for existing alerts). */
  @Test
  void asciiTitleIsUnchanged() {
    client.send("NTFY", "ArthaYantra feed stalled", "detail");

    assertThat(receivedTitle.get()).isEqualTo("ArthaYantra feed stalled");
  }

  /**
   * The BODY is free-form and must keep full UTF-8 — it is where the em-dash detail legitimately
   * lives. This pins the wire charset so a body em-dash is never silently mojibake'd.
   */
  @Test
  void bodyKeepsFullUtf8() {
    String body = "position 32 SL/TP un-evaluated for ~3000s (tick absent) — stop may not fire";

    client.send("NTFY", "ArthaYantra Paper bracket starved", body);

    assertThat(new String(receivedBody.get(), StandardCharsets.UTF_8)).isEqualTo(body);
  }

  /** A multi-line body survives intact — only the HEADER is single-line-constrained. */
  @Test
  void bodyKeepsNewlines() {
    String body = "drift detected:\n  field a removed\n  field b retyped";

    client.send("NTFY", "Kite contract drift", body);

    assertThat(new String(receivedBody.get(), StandardCharsets.UTF_8)).isEqualTo(body);
  }

  /**
   * A delivery failure must be VISIBLE, not just a swallowed WARN — the outage went unnoticed for
   * weeks because every call site logged and moved on. The counter is the health signal.
   */
  @Test
  void deliveryFailureIncrementsTheFailureCounter() {
    server.stop(0); // nothing listening -> transport failure

    assertThatCode(() -> client.send("NTFY", "ArthaYantra feed stalled", "detail"))
        .isInstanceOf(RuntimeException.class);

    assertThat(meters.counter("ay_notifier_send_failed_total", "channel", "NTFY").count())
        .isEqualTo(1.0);
  }
}
