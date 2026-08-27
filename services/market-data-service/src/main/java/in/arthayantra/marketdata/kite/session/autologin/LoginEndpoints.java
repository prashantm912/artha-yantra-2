package in.arthayantra.marketdata.kite.session.autologin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * The three resolved, validated login URLs — the ONLY place a configured origin and a configured
 * path are ever combined.
 *
 * <p><b>⚠️ THIS CLASS EXISTS BECAUSE THE ORIGINAL "PINNED HOST" MITIGATION WAS FALSE.</b> The first
 * cut of this feature built each URL by STRING CONCATENATION of {@code origin + path}, and the
 * design note claimed that pinning the host made a wrong path "merely 404 on Zerodha's own
 * infrastructure". That is not how URLs parse. A configured path of
 * {@code @attacker.example/api/login} concatenates to
 * {@code https://kite.zerodha.com@attacker.example/api/login}, in which {@code kite.zerodha.com} is
 * the <b>userinfo</b> component and the real host is {@code attacker.example} — so the user id and
 * password would have been POSTed to the attacker, with the "pinned host" sitting harmlessly in
 * front of an {@code @}. The per-path configurability that was added to make a broken path a config
 * fix is exactly what made this reachable. Cross-vendor review, Critical 1, 2026-08-26.
 *
 * <p>Three independent defences, all of which must hold:
 *
 * <ol>
 *   <li><b>Origins are allowlisted by VALUE after parsing</b>, never by prefix match — scheme, host
 *       and port are compared against {@link #PINNED_ORIGINS}. A prefix test would accept
 *       {@code https://kite.zerodha.com.attacker.example}.
 *   <li><b>Paths must be strictly relative</b>: exactly one leading {@code /}, and no {@code @},
 *       {@code //}, backslash, scheme or control character anywhere. {@code //} matters on its own
 *       — {@code URI.resolve("//attacker/x")} is a NETWORK-PATH REFERENCE and replaces the
 *       authority even without an {@code @}.
 *   <li><b>A post-condition re-reads the resolved URI</b> and fails unless its scheme, host and
 *       port still equal the origin's. This is the belt-and-braces check: if some future edit
 *       reintroduces concatenation or a cleverer traversal defeats (2), the resolved host is
 *       verified to be the allowlisted one before any credential is sent.
 * </ol>
 *
 * <p>Resolution uses {@link URI#resolve(String)} rather than concatenation, so the parser — not
 * string arithmetic — decides what the authority is.
 */
public final class LoginEndpoints {

  /**
   * The only origin auto-login may ever talk to. Compared as parsed scheme + host + port.
   *
   * <p>⚠️ <b>ONE, not two, since 2026-08-28.</b> It held {@code https://kite.trade} as well, on
   * the stated grounds that authorize lived there — which the live failure disproved — and then
   * briefly on the grounds that it was needed for the token exchange. <b>That second reason was
   * also false, and cross-vendor review caught it:</b> the token exchange uses
   * {@code KiteHttpProperties.baseUrl} ({@code https://api.kite.trade}) via
   * {@code LiveSessionWireClient}, a different property in a different class, and
   * {@code api.kite.trade} is not the same origin as {@code kite.trade} in any case.
   *
   * <p>Leaving it here was not merely untidy: {@link #resolve} applies this one set to BOTH
   * configurable origins, so an operator repointing authorize at {@code kite.trade} would pass
   * validation and reproduce the exact live failure this change exists to fix. An allowlist that
   * permits the known-bad value is not an allowlist.
   */
  static final Set<String> PINNED_ORIGINS = Set.of("https://kite.zerodha.com");

  private final URI credentials;
  private final URI twofa;
  private final URI authorize;

  private LoginEndpoints(URI credentials, URI twofa, URI authorize) {
    this.credentials = credentials;
    this.twofa = twofa;
    this.authorize = authorize;
  }

  /**
   * Production resolution: both origins must be exactly an allowlisted Zerodha origin.
   *
   * <p>Called from the bean factory method, so a hostile or fat-fingered override fails the CONTEXT
   * at boot rather than at 08:05 on a market morning — the difference between a stack that refuses
   * to start and a stack that posts credentials somewhere unintended.
   */
  public static LoginEndpoints pinned(KiteAutoLoginProperties properties) {
    return resolve(properties, true);
  }

  /**
   * ⚠️ <b>TEST-ONLY seam.</b> Skips the origin allowlist so WireMock can stand in on
   * {@code http://localhost:<port>}; every other defence — relative-path validation and the
   * resolved-authority post-condition — still applies, and is therefore exercised by the tests
   * exactly as production runs it.
   *
   * <p>Package-private and referenced from NO production code path: {@link #pinned} is what
   * {@code LiveKiteConfig} calls. Loosening production to make a test pass is the failure mode this
   * seam exists to avoid.
   */
  static LoginEndpoints unpinnedForTest(KiteAutoLoginProperties properties) {
    return resolve(properties, false);
  }

  private static LoginEndpoints resolve(KiteAutoLoginProperties properties, boolean enforceAllowlist) {
    URI loginOrigin = origin("login-base-url", properties.loginBaseUrl(), enforceAllowlist);
    URI authorizeOrigin = origin("authorize-base-url", properties.authorizeBaseUrl(), enforceAllowlist);
    return new LoginEndpoints(
        under(loginOrigin, "credential-path", properties.credentialPath()),
        under(loginOrigin, "twofa-path", properties.twofaPath()),
        under(authorizeOrigin, "authorize-path", properties.authorizePath()));
  }

  /** Parses and (in production) allowlists one origin. */
  private static URI origin(String property, String value, boolean enforceAllowlist) {
    String label = "artha.kite.auto-login." + property;
    URI parsed;
    try {
      parsed = new URI(value);
    } catch (URISyntaxException malformed) {
      throw new IllegalStateException(label + " is not a valid URI");
    }
    if (parsed.getScheme() == null || parsed.getHost() == null) {
      throw new IllegalStateException(label + " must be an absolute origin like https://host");
    }
    if (parsed.getUserInfo() != null) {
      throw new IllegalStateException(label + " must not carry userinfo (an '@' before the host)");
    }
    if (!isEmptyPath(parsed) || parsed.getQuery() != null || parsed.getFragment() != null) {
      throw new IllegalStateException(label + " must be a bare origin — no path, query or fragment");
    }
    String normalized = normalize(parsed);
    if (enforceAllowlist) {
      if (!"https".equals(parsed.getScheme())) {
        throw new IllegalStateException(label + " must be https");
      }
      // ⚠️ Set membership on the PARSED value, never startsWith: a prefix test accepts
      // https://kite.zerodha.com.attacker.example, which is a different registrable domain.
      if (!PINNED_ORIGINS.contains(normalized)) {
        throw new IllegalStateException(
            label
                + " resolves to "
                + normalized
                + ", which is not one of the pinned Zerodha origins "
                + PINNED_ORIGINS);
      }
    }
    return URI.create(normalized);
  }

  /**
   * Resolves a strictly-relative path under an origin, then PROVES the authority did not move.
   *
   * <p>The validation and the post-condition are deliberately redundant. The rejection list is a
   * blocklist and blocklists are never provably complete; the post-condition is a whitelist check
   * on the OUTCOME, which is what actually matters.
   */
  private static URI under(URI origin, String property, String path) {
    requireStrictlyRelative(property, path);
    URI resolved = origin.resolve(path);
    if (resolved.getUserInfo() != null || !normalize(origin).equals(normalize(resolved))) {
      throw new IllegalStateException(
          "artha.kite.auto-login."
              + property
              + " resolves away from its own origin ("
              + normalize(origin)
              + " -> "
              + normalize(resolved)
              + ") — refusing to send credentials there");
    }
    return resolved;
  }

  private static void requireStrictlyRelative(String property, String path) {
    String label = "artha.kite.auto-login." + property;
    if (path.isEmpty() || path.charAt(0) != '/') {
      throw new IllegalStateException(label + " must start with '/'");
    }
    if (path.startsWith("//")) {
      // A network-path reference: URI.resolve REPLACES the authority, no '@' required.
      throw new IllegalStateException(label + " must not start with '//' (that replaces the host)");
    }
    if (path.indexOf('@') >= 0) {
      throw new IllegalStateException(label + " must not contain '@'");
    }
    if (path.indexOf('\\') >= 0) {
      throw new IllegalStateException(label + " must not contain a backslash");
    }
    if (path.contains("..")) {
      throw new IllegalStateException(label + " must not contain '..'");
    }
    if (path.toLowerCase(Locale.ROOT).contains("://")) {
      throw new IllegalStateException(label + " must not contain a scheme");
    }
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        throw new IllegalStateException(label + " must not contain control characters");
      }
    }
  }

  private static boolean isEmptyPath(URI uri) {
    return uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath());
  }

  /** Scheme + host + port, lowercased — the identity an allowlist may compare. */
  private static String normalize(URI uri) {
    if (uri.getScheme() == null || uri.getHost() == null) {
      // A resolved URI that lost its authority entirely is, by definition, not the origin.
      return "opaque:" + uri;
    }
    String base =
        uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
    return uri.getPort() < 0 ? base : base + ":" + uri.getPort();
  }

  /** The credential POST target. */
  URI credentials() {
    return credentials;
  }

  /** The 2FA POST target. */
  URI twofa() {
    return twofa;
  }

  /** The authorize GET target, before query parameters are appended. */
  URI authorize() {
    return authorize;
  }
}
