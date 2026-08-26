package in.arthayantra.marketdata.kite.session.autologin;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A per-ORIGIN cookie store for one login attempt.
 *
 * <p><b>⚠️ THIS REPLACES A FLAT JAR THAT LEAKED BEARER COOKIES ACROSS REGISTRABLE DOMAINS.</b> The
 * first cut kept one {@code Map<String,String>} of name/value pairs, discarded {@code Domain},
 * {@code Path}, {@code Secure} and expiry, and then replayed <b>every</b> cookie set by
 * {@code kite.zerodha.com} to {@code kite.trade} on the authorize step — on every successful login,
 * not merely on a failure. No browser could ever scope a cookie across two different registrable
 * domains, so this was strictly broader exposure than the flow it was imitating. Cross-vendor
 * review, Critical 2, 2026-08-26. (It was flagged as {@code assumed} in the build's own
 * open-doubts; review escalated it, correctly — an {@code assumed} that hands session cookies to a
 * second domain is not a doubt, it is a defect.)
 *
 * <p><b>Nothing crosses origins by default.</b> {@link #cookieHeaderFor} returns only cookies
 * stored for that exact origin. If the live flow turns out to require a specific login-host cookie
 * at the authorize host, that ONE cookie is named in {@code artha.kite.auto-login.cross-origin-
 * cookies} — a name allowlist, empty by default — rather than the jar being forwarded wholesale.
 * The failure mode of getting this wrong is loud and safe: the authorize step returns a page
 * instead of a redirect and refuses as {@link LoginRefusal#UNEXPECTED_RESPONSE}.
 *
 * <p><b>What is honoured, and what is deliberately not.</b> {@code Secure} (never sent over
 * plaintext), {@code Domain} (must domain-match the setting host, and a cookie for a parent domain
 * is stored against the origin that set it, never promoted), {@code Path} (prefix-matched on send)
 * and {@code Max-Age=0} / {@code Expires} in the past (a deletion). NOT honoured: absolute expiry
 * dates in the future — this jar lives for the seconds of one login attempt and is then discarded,
 * so an expiry beyond that window can never be reached. {@code SameSite} is a browser-navigation
 * concept with no meaning for a server-side client.
 *
 * <p>Nothing here is ever logged beyond a COUNT. A session cookie is bearer material.
 */
final class LoginCookieJar {

  /** One stored cookie: its value plus the path it was scoped to. */
  private record StoredCookie(String value, String path) {}

  /** origin -> (cookie name -> stored cookie). */
  private final Map<String, Map<String, StoredCookie>> byOrigin = new LinkedHashMap<>();

  /** Cookie NAMES permitted to travel from the login origin to the authorize origin. */
  private final Set<String> crossOriginAllowlist;

  LoginCookieJar(Set<String> crossOriginAllowlist) {
    this.crossOriginAllowlist = Set.copyOf(crossOriginAllowlist);
  }

  /**
   * Stores every {@code Set-Cookie} from a response received from {@code requestUri}.
   *
   * <p>Rejections are silent by design — a cookie we refuse to store is not an error, and naming it
   * in a log would name the cookie.
   */
  void store(URI requestUri, List<String> setCookieHeaders) {
    if (setCookieHeaders == null) {
      return;
    }
    String origin = originOf(requestUri);
    String host = requestUri.getHost() == null ? "" : requestUri.getHost().toLowerCase(Locale.ROOT);
    boolean secureChannel = "https".equalsIgnoreCase(requestUri.getScheme());
    for (String header : setCookieHeaders) {
      parseAndStore(origin, host, secureChannel, header);
    }
  }

  private void parseAndStore(String origin, String host, boolean secureChannel, String header) {
    String[] parts = header.split(";");
    int equals = parts[0].indexOf('=');
    if (equals <= 0) {
      return;
    }
    String name = parts[0].substring(0, equals).trim();
    String value = parts[0].substring(equals + 1).trim();
    if (name.isEmpty()) {
      return;
    }
    String path = "/";
    String domain = null;
    boolean secure = false;
    boolean deleted = false;
    for (int i = 1; i < parts.length; i++) {
      String attribute = parts[i].trim();
      String lower = attribute.toLowerCase(Locale.ROOT);
      if ("secure".equals(lower)) {
        secure = true;
      } else if (lower.startsWith("path=")) {
        String declared = attribute.substring("path=".length()).trim();
        path = declared.startsWith("/") ? declared : "/";
      } else if (lower.startsWith("domain=")) {
        domain = attribute.substring("domain=".length()).trim().toLowerCase(Locale.ROOT);
        domain = domain.startsWith(".") ? domain.substring(1) : domain;
      } else if ("max-age=0".equals(lower.replace(" ", ""))) {
        deleted = true;
      }
    }
    if (secure && !secureChannel) {
      return; // a Secure cookie must never be retained off a plaintext exchange
    }
    if (domain != null && !domainMatches(host, domain)) {
      return; // a host may not set a cookie for a domain it does not belong to
    }
    Map<String, StoredCookie> forOrigin = byOrigin.computeIfAbsent(origin, key -> new LinkedHashMap<>());
    if (deleted || value.isEmpty()) {
      forOrigin.remove(name);
      return;
    }
    forOrigin.put(name, new StoredCookie(value, path));
  }

  /**
   * The {@code Cookie} header value for {@code requestUri}, or {@code null} when there is nothing
   * to send (in which case the caller must omit the header entirely rather than send an empty one).
   *
   * <p>{@code fromOrigin} is the origin whose cookies may additionally be considered under the
   * cross-origin NAME allowlist. Passing {@code null} means "this origin's own cookies only".
   */
  String cookieHeaderFor(URI requestUri, String fromOrigin) {
    String origin = originOf(requestUri);
    String path = requestUri.getPath() == null || requestUri.getPath().isEmpty() ? "/" : requestUri.getPath();
    Map<String, String> send = new LinkedHashMap<>();
    collectInto(send, byOrigin.get(origin), path, null);
    if (fromOrigin != null && !fromOrigin.equals(origin) && !crossOriginAllowlist.isEmpty()) {
      collectInto(send, byOrigin.get(fromOrigin), path, crossOriginAllowlist);
    }
    if (send.isEmpty()) {
      return null;
    }
    List<String> pairs = new ArrayList<>(send.size());
    send.forEach((name, value) -> pairs.add(name + "=" + value));
    return String.join("; ", pairs);
  }

  private static void collectInto(
      Map<String, String> target, Map<String, StoredCookie> source, String path, Set<String> onlyNames) {
    if (source == null) {
      return;
    }
    source.forEach(
        (name, cookie) -> {
          if (onlyNames != null && !onlyNames.contains(name)) {
            return;
          }
          if (pathMatches(path, cookie.path())) {
            target.putIfAbsent(name, cookie.value());
          }
        });
  }

  /** How many cookies are stored for one origin — the only thing safe to log. */
  int sizeFor(URI requestUri) {
    Map<String, StoredCookie> forOrigin = byOrigin.get(originOf(requestUri));
    return forOrigin == null ? 0 : forOrigin.size();
  }

  /** RFC 6265 §5.1.3: an exact host match, or a proper subdomain of the declared domain. */
  private static boolean domainMatches(String host, String domain) {
    return host.equals(domain) || host.endsWith("." + domain);
  }

  /** RFC 6265 §5.1.4 path-match, in the direction that matters here. */
  private static boolean pathMatches(String requestPath, String cookiePath) {
    if (requestPath.equals(cookiePath) || "/".equals(cookiePath)) {
      return true;
    }
    if (!requestPath.startsWith(cookiePath)) {
      return false;
    }
    return cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/';
  }

  /** Scheme + host + port, lowercased. */
  static String originOf(URI uri) {
    String base =
        uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
    return uri.getPort() < 0 ? base : base + ":" + uri.getPort();
  }
}
