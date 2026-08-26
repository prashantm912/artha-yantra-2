package in.arthayantra.marketdata.kite.session.autologin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Totp} against RFC 6238 Appendix B's PUBLISHED vectors.
 *
 * <p>⚠️ The key below is the RFC's own test key, {@code "12345678901234567890"} — it is printed in
 * the standard, it is public test data, and it is not a credential. No real seed exists in this
 * repository, and none may ever be added to a test.
 *
 * <p>Appendix B tabulates the SHA-1 vectors at <b>eight</b> digits, so they are asserted at eight —
 * checking six would silently discard the two most significant digits of every published value and
 * weaken the test for no reason. The six-digit form we actually send is then pinned separately as
 * the low six digits of the same code, which is what RFC 4226 §5.3's modulus means.
 */
class TotpTest {

  /** RFC 6238 Appendix B, {@code seed} — the standard's own published test key. */
  private static final byte[] RFC_KEY = Totp.asciiKey("12345678901234567890");

  /** One published row: the T value in seconds, and the expected 8-digit SHA-1 TOTP. */
  private record Vector(long epochSecond, String expected) {}

  private static final List<Vector> RFC_6238_APPENDIX_B =
      List.of(
          new Vector(59L, "94287082"),
          new Vector(1111111109L, "07081804"),
          new Vector(1111111111L, "14050471"),
          new Vector(1234567890L, "89005924"),
          new Vector(2000000000L, "69279037"),
          new Vector(20000000000L, "65353130"));

  @Test
  @DisplayName("every RFC 6238 Appendix B SHA-1 vector reproduces exactly")
  void theRfcVectorsReproduce() {
    for (Vector vector : RFC_6238_APPENDIX_B) {
      assertThat(Totp.code(RFC_KEY, Instant.ofEpochSecond(vector.epochSecond()), Totp.DEFAULT_STEP, 8))
          .as("RFC 6238 Appendix B, T=%d", vector.epochSecond())
          .isEqualTo(vector.expected());
    }
  }

  @Test
  @DisplayName("the six-digit code we actually send is the low six digits of the published vector")
  void theSixDigitFormIsTheLowSixDigits() {
    for (Vector vector : RFC_6238_APPENDIX_B) {
      String six = Totp.code(RFC_KEY, Instant.ofEpochSecond(vector.epochSecond()));
      assertThat(six).hasSize(Totp.DEFAULT_DIGITS);
      assertThat(six)
          .as("six digits must be the modulus of the same binary code, T=%d", vector.epochSecond())
          .isEqualTo(vector.expected().substring(2));
    }
  }

  @Test
  @DisplayName("a wrong step or digit count does NOT reproduce the published vectors")
  void aWrongStepOrDigitCountBreaksTheVectors() {
    // ⚠️ This is the red-proof for the two parameters most likely to be "fixed" by a later reader:
    // if a 60 s step or a 7-digit truncation still matched, the assertions above would be proving
    // nothing about either. Both must DISAGREE, and they must disagree on the very first vector.
    Instant at = Instant.ofEpochSecond(RFC_6238_APPENDIX_B.get(0).epochSecond());
    assertThat(Totp.code(RFC_KEY, at, Duration.ofSeconds(60), 8))
        .as("a 60 s step is a different counter and must not match the 30 s vector")
        .isNotEqualTo("94287082");
    assertThat(Totp.code(RFC_KEY, at, Totp.DEFAULT_STEP, 7))
        .as("seven digits is a different modulus")
        .isNotEqualTo("94287082");
  }

  @Test
  @DisplayName("the code is constant within a step and changes at the boundary")
  void theCodeIsStableWithinItsStepAndChangesAtTheBoundary() {
    // 1111111109 and 1111111111 straddle a 30 s boundary (…109 -> step 37037036, …111 -> 37037037)
    // and the RFC lists DIFFERENT codes for them, so the boundary is pinned by the vectors above.
    // What is not pinned there is stability INSIDE a step, which is what makes a code usable at all.
    Instant start = Instant.ofEpochSecond(1111111110L);
    String atStart = Totp.code(RFC_KEY, start);
    assertThat(Totp.code(RFC_KEY, start.plusSeconds(29))).isEqualTo(atStart);
    assertThat(Totp.code(RFC_KEY, start.plusSeconds(30))).isNotEqualTo(atStart);
  }

  @Test
  @DisplayName("base32 decoding accepts the shapes an enrolment screen renders")
  void base32DecodingAcceptsSpacedAndPaddedSeeds() {
    // "12345678901234567890" (the RFC key) is GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ in base32.
    String base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    assertThat(Totp.decodeBase32(base32)).isEqualTo(RFC_KEY);
    assertThat(Totp.decodeBase32(base32.toLowerCase(java.util.Locale.ROOT))).isEqualTo(RFC_KEY);
    assertThat(Totp.decodeBase32("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ")).isEqualTo(RFC_KEY);
    assertThat(Totp.decodeBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ==")).isEqualTo(RFC_KEY);
  }

  @Test
  @DisplayName("an invalid base32 character is refused, and the refusal never echoes the seed")
  void anInvalidSeedIsRefusedWithoutEchoingIt() {
    // Skipping an unknown character would produce a key that generates wrong codes forever — which
    // would surface on a market morning as "the login is broken", naming nothing.
    assertThatThrownBy(() -> Totp.decodeBase32("GEZDGNBV1GY3TQOJQ"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("GEZD")
        .hasMessageNotContaining("1");
    assertThatThrownBy(() -> Totp.decodeBase32("  ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("degenerate parameters are refused rather than producing a plausible code")
  void degenerateParametersAreRefused() {
    Instant at = Instant.ofEpochSecond(59L);
    assertThatThrownBy(() -> Totp.code(new byte[0], at)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Totp.code(RFC_KEY, at, Totp.DEFAULT_STEP, 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Totp.code(RFC_KEY, at, Duration.ZERO, 6))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
