package in.arthayantra.marketdata.kite.session.autologin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords — HMAC-SHA1, 30-second step, six digits, JDK crypto only
 * (no new dependency).
 *
 * <p>Pure and clock-injected on purpose: every entry point takes the {@link Instant} to generate
 * for rather than calling {@code Instant.now()} inline, so the whole unit is testable against RFC
 * 6238 Appendix B's published vectors. Those vectors use the RFC's OWN test key
 * ({@code "12345678901234567890"}), which is public test data and not a credential — no real seed
 * exists anywhere near this class or its tests.
 *
 * <p>⚠️ This class NEVER logs. The key it is handed is the account's second factor; a debug line
 * here would be the single worst place in the codebase for one.
 */
public final class Totp {

  /** Digits Zerodha's TOTP field expects. */
  public static final int DEFAULT_DIGITS = 6;

  /** RFC 6238's default time step. */
  public static final Duration DEFAULT_STEP = Duration.ofSeconds(30);

  private static final String HMAC_SHA1 = "HmacSHA1";
  private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private Totp() {}

  /** The six-digit code for {@code at}, on the RFC's 30-second step. */
  public static String code(byte[] key, Instant at) {
    return code(key, at, DEFAULT_STEP, DEFAULT_DIGITS);
  }

  /**
   * The {@code digits}-digit code for the time step containing {@code at}.
   *
   * <p>The step counter is {@code floorDiv(epochSecond, stepSeconds)} — floor division, not
   * truncation, so a pre-1970 instant (only reachable from a test) does not fold onto the wrong
   * step. Truncation is the standard implementation of the dynamic offset from RFC 4226 §5.4.
   */
  public static String code(byte[] key, Instant at, Duration step, int digits) {
    if (key.length == 0) {
      throw new IllegalArgumentException("TOTP key is empty");
    }
    if (digits < 6 || digits > 9) {
      throw new IllegalArgumentException("TOTP digits must be 6-9, got " + digits);
    }
    long stepSeconds = step.toSeconds();
    if (stepSeconds <= 0) {
      throw new IllegalArgumentException("TOTP step must be positive");
    }
    long counter = Math.floorDiv(at.getEpochSecond(), stepSeconds);
    byte[] digest = hmacSha1(key, ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
    int offset = digest[digest.length - 1] & 0x0f;
    int binary =
        ((digest[offset] & 0x7f) << 24)
            | ((digest[offset + 1] & 0xff) << 16)
            | ((digest[offset + 2] & 0xff) << 8)
            | (digest[offset + 3] & 0xff);
    int modulus = (int) Math.pow(10, digits);
    return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulus);
  }

  /**
   * Decodes an RFC 4648 base32 seed (the format an authenticator enrolment hands out).
   *
   * <p>Case-insensitive; spaces, hyphens and {@code =} padding are ignored, because enrolment
   * screens routinely render the seed in spaced groups. Any other character is rejected rather
   * than skipped — a silently-dropped character yields a key that produces wrong codes forever,
   * which would present as "the password is wrong" on a market morning.
   */
  public static byte[] decodeBase32(String seed) {
    StringBuilder bits = new StringBuilder();
    for (char raw : seed.toCharArray()) {
      if (raw == '=' || raw == ' ' || raw == '-' || raw == '\t' || raw == '\n' || raw == '\r') {
        continue;
      }
      char symbol = Character.toUpperCase(raw);
      int value = BASE32_ALPHABET.indexOf(symbol);
      if (value < 0) {
        // Deliberately does NOT echo the character or the seed — this string is the second factor.
        throw new IllegalArgumentException("TOTP seed is not valid base32");
      }
      String chunk = Integer.toBinaryString(value);
      bits.append("00000", 0, 5 - chunk.length()).append(chunk);
    }
    int wholeBytes = bits.length() / 8;
    if (wholeBytes == 0) {
      throw new IllegalArgumentException("TOTP seed decoded to no key material");
    }
    byte[] key = new byte[wholeBytes];
    for (int i = 0; i < wholeBytes; i++) {
      key[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
    }
    return key;
  }

  /** The RFC 6238 Appendix B test key, as ASCII bytes. Public test data, never a credential. */
  static byte[] asciiKey(String ascii) {
    return ascii.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] hmacSha1(byte[] key, byte[] message) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA1);
      mac.init(new SecretKeySpec(key, HMAC_SHA1));
      return mac.doFinal(message);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      // No message interpolation: an InvalidKeyException can name key properties.
      throw new IllegalStateException("HMAC-SHA1 unavailable or key rejected");
    }
  }
}
