package in.arthayantra.common.logging;

import java.util.regex.Pattern;

/**
 * The A.6.1 sensitive-data masking rules, applied to every log line before it leaves the process:
 * PHC-format password hashes, secret-bearing key/value pairs (api_secret, access/request tokens,
 * passwords, Authorization headers) and {@code request_token} query parameters never survive into
 * output. JSON logs make this testable — a unit test asserts a fake token never appears.
 */
public final class LogMasker {

  private static final String MASK = "***";

  /** PHC strings, e.g. {@code $argon2id$v=19$m=19456,t=2,p=1$...} — commas are part of PHC. */
  private static final Pattern PHC =
      Pattern.compile("\\$argon2[a-z0-9]*\\$[A-Za-z0-9$=,+/_-]+");

  /** Secret-bearing key/value pairs in any of the common separators. */
  private static final Pattern KEY_VALUE =
      Pattern.compile(
          "(?i)(api[_-]?secret|api[_-]?key|access[_-]?token|request[_-]?token|password|enctoken"
              + "|authorization|master[_-]?key)(\\\\?[\"']?\\s*[:=]\\s*\\\\?[\"']?)([^\\s\"'&,;\\\\]+)");

  private LogMasker() {}

  /** Masks every sensitive pattern in {@code text}; {@code null} passes through. */
  public static String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String masked = PHC.matcher(text).replaceAll("\\$argon2" + MASK);
    masked = KEY_VALUE.matcher(masked).replaceAll("$1$2" + MASK);
    return masked;
  }

  /**
   * First-4/last-2 echo form for non-secret identifiers that still shouldn't appear whole — the
   * {@code KITE_API_KEY} startup config echo style ({@code tdwk…t2}, A.6.1).
   */
  public static String firstLast(String value) {
    if (value == null || value.length() < 8) {
      return MASK;
    }
    return value.substring(0, 4) + "…" + value.substring(value.length() - 2);
  }
}
