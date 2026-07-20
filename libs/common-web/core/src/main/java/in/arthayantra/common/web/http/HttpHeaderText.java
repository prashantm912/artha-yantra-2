package in.arthayantra.common.web.http;

/**
 * Makes free-form operator text safe to carry in an HTTP HEADER value.
 *
 * <p>Motivated by the 2026-07-20 live alerting outage: ntfy carries the alert title as the
 * {@code Title} HEADER, and the JDK {@code HttpClient} — the request factory Spring resolves in
 * these services (no Apache/Jetty/OkHttp on the classpath) — rejects a header value containing any
 * char {@code >= U+0100}, any control char ({@code 0x00-0x1F}) or {@code 0x7F}, throwing
 * {@code IllegalArgumentException: invalid header value}. Alert titles across this codebase
 * routinely carry an em-dash (U+2014), so EVERY such push died before it left the JVM and no
 * incident could page the owner.
 *
 * <p>Chars {@code 0x80-0xFF} are accepted by the JDK but written as raw Latin-1 bytes, which a
 * UTF-8 server renders as mojibake — so this normalises to plain ASCII rather than merely to what
 * the JDK tolerates.
 *
 * <p>This is a HEADER concern only. Message BODIES are unconstrained: they are free-form UTF-8 and
 * must NOT be passed through here — anything long or multi-line belongs in the body.
 */
public final class HttpHeaderText {

  /** Default cap for a header value; the full free-form text belongs in the body, not the header. */
  public static final int DEFAULT_MAX_LENGTH = 200;

  private static final String ELLIPSIS = "...";

  /** Lowest printable ASCII char (space). */
  private static final char FIRST_PRINTABLE_ASCII = 0x20;

  /** Highest printable ASCII char ({@code ~}); everything above needs transliterating. */
  private static final char LAST_PRINTABLE_ASCII = 0x7E;

  /** DEL — a control char the JDK rejects despite it sitting above the printable range. */
  private static final char DEL = 0x7F;

  /** Non-breaking space: JDK-legal but Latin-1 on the wire, so fold it to a plain space. */
  private static final char NBSP = 0xA0;

  private HttpHeaderText() {}

  /**
   * Normalises {@code raw} to a header-safe ASCII value capped at {@link #DEFAULT_MAX_LENGTH}.
   *
   * @param raw the free-form text (may be {@code null})
   * @return an ASCII, single-line, bounded value that no HTTP client will reject
   */
  public static String toHeaderValue(String raw) {
    return toHeaderValue(raw, DEFAULT_MAX_LENGTH);
  }

  /**
   * Normalises {@code raw} to a header-safe ASCII value capped at {@code maxLength}.
   *
   * <p>Common typographic characters are transliterated to their ASCII equivalents so the text
   * stays readable (dashes to {@code -}, arrows to {@code ->}, ellipsis to {@code ...}, smart
   * quotes to plain ones). Any other non-ASCII char degrades to {@code ?} rather than failing the
   * delivery. CR/LF/tab and other control chars collapse to a single space — a header cannot span
   * lines, and an embedded CR/LF would also be a response-splitting vector.
   *
   * @param raw the free-form text (may be {@code null})
   * @param maxLength the maximum length of the returned value; the tail is replaced by {@code ...}
   * @return an ASCII, single-line, bounded value that no HTTP client will reject
   */
  public static String toHeaderValue(String raw, int maxLength) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      append(out, raw.charAt(i));
    }
    String collapsed = collapseSpaces(out);
    if (collapsed.length() <= maxLength) {
      return collapsed;
    }
    if (maxLength <= ELLIPSIS.length()) {
      return collapsed.substring(0, Math.max(maxLength, 0));
    }
    return collapsed.substring(0, maxLength - ELLIPSIS.length()) + ELLIPSIS;
  }

  /**
   * Appends {@code c} transliterated or degraded to header-safe ASCII. The transliterated cases are
   * the typographic characters this codebase's alert titles actually use; anything else non-ASCII
   * degrades to {@code ?}, so the set below is a readability nicety, never a correctness dependency.
   */
  private static void append(StringBuilder out, char c) {
    switch (c) {
      // em dash, en dash, minus sign, middot, bullet
      case '—', '–', '−', '·', '•' -> out.append('-');
      // rightwards arrow, rightwards double arrow
      case '→', '⇒' -> out.append("->");
      case '…' -> out.append(ELLIPSIS); // horizontal ellipsis
      case '‘', '’' -> out.append('\''); // smart single quotes
      case '“', '”' -> out.append('"'); // smart double quotes
      default -> {
        if (c >= FIRST_PRINTABLE_ASCII && c <= LAST_PRINTABLE_ASCII) {
          out.append(c); // printable ASCII - the overwhelmingly common case
        } else if (c < FIRST_PRINTABLE_ASCII || c == DEL || c == NBSP) {
          out.append(' '); // control char, DEL or non-breaking space
        } else {
          out.append('?'); // unmappable non-ASCII: degrade, never fail the delivery
        }
      }
    }
  }

  /** Collapses runs of spaces to one and trims — every control char above became a space. */
  private static String collapseSpaces(CharSequence in) {
    StringBuilder out = new StringBuilder(in.length());
    boolean pendingSpace = false;
    for (int i = 0; i < in.length(); i++) {
      char c = in.charAt(i);
      if (c == ' ') {
        pendingSpace = !out.isEmpty();
        continue;
      }
      if (pendingSpace) {
        out.append(' ');
        pendingSpace = false;
      }
      out.append(c);
    }
    return out.toString();
  }
}
