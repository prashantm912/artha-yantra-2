package in.arthayantra.common.web.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the header-value sanitiser behind the 2026-07-20 ntfy alerting outage.
 *
 * <p>The load-bearing test is {@link #everyCharTheJdkRejectsIsNeutralised()}: rather than trusting
 * a hand-written character table, it drives the REAL JDK {@code HttpRequest.Builder} validator over
 * the whole BMP and asserts the sanitised form is always accepted. That is what makes this a fix at
 * the seam rather than a patch for the one character we happened to see fail.
 */
class HttpHeaderTextTest {

  @Test
  void emDashBecomesAHyphen() {
    assertThat(HttpHeaderText.toHeaderValue("ArthaYantra Paper — bracket starved"))
        .isEqualTo("ArthaYantra Paper - bracket starved");
  }

  @Test
  void typographicCharactersTransliterate() {
    assertThat(HttpHeaderText.toHeaderValue("a → b ⇒ c … 'd' “e” · f"))
        .isEqualTo("a -> b -> c ... 'd' \"e\" - f");
  }

  @Test
  void controlCharactersCollapseToASingleSpace() {
    assertThat(HttpHeaderText.toHeaderValue("a\r\n\tb")).isEqualTo("a b");
  }

  @Test
  void unmappableNonAsciiDegradesToQuestionMark() {
    assertThat(HttpHeaderText.toHeaderValue("alert 中文 ok")).isEqualTo("alert ?? ok");
  }

  @Test
  void asciiIsUnchanged() {
    String ascii = "ArthaYantra BUY NSE:RELIANCE score 0.80 vs 0.50 (~3000s)";
    assertThat(HttpHeaderText.toHeaderValue(ascii)).isEqualTo(ascii);
  }

  @Test
  void nullAndEmptyAreSafe() {
    assertThat(HttpHeaderText.toHeaderValue(null)).isEmpty();
    assertThat(HttpHeaderText.toHeaderValue("")).isEmpty();
  }

  @Test
  void longValuesAreTruncatedWithAnEllipsis() {
    String out = HttpHeaderText.toHeaderValue("A".repeat(500));

    assertThat(out).hasSize(HttpHeaderText.DEFAULT_MAX_LENGTH).endsWith("...");
  }

  @Test
  void leadingAndTrailingWhitespaceIsTrimmed() {
    assertThat(HttpHeaderText.toHeaderValue("  spaced out  ")).isEqualTo("spaced out");
  }

  /**
   * The exhaustive guarantee: for EVERY char in the BMP, the sanitised value is accepted by the JDK
   * HTTP client's own header validator. Proven against the real validator, not a mirrored rule.
   */
  @Test
  void everyCharTheJdkRejectsIsNeutralised() {
    for (int cp = 0; cp <= 0xFFFF; cp++) {
      String raw = "alert " + (char) cp + " tail";
      String safe = HttpHeaderText.toHeaderValue(raw);
      assertThat(acceptedByJdkHttpClient(safe))
          .as("sanitised value for U+%04X (%s) must be a legal header value", cp, safe)
          .isTrue();
    }
  }

  /** True when the JDK's own {@code HttpRequest.Builder} accepts {@code value} as a header value. */
  private static boolean acceptedByJdkHttpClient(String value) {
    try {
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/x")).header("Title", value).GET().build();
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }
}
