package in.arthayantra.marketdata.kite.session.autologin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The malicious-path and hostile-origin suite.
 *
 * <p>⚠️ <b>This file exists because the original "the host is pinned, so a wrong path merely 404s"
 * claim was FALSE.</b> The first cut concatenated {@code origin + path}; a configured path of
 * {@code @attacker.example/api/login} produced
 * {@code https://kite.zerodha.com@attacker.example/api/login}, where the "pinned" host is userinfo
 * and the credential POST goes to the attacker. Cross-vendor review, Critical 1, 2026-08-26.
 *
 * <p>Every case below asserts BOTH halves that matter: the hostile value is rejected, AND — for the
 * values that are accepted — the RESOLVED host is still the origin we pinned. The second half is
 * the one that survives a future refactor, because it checks the outcome rather than the filter.
 */
class LoginEndpointsTest {

  private static KiteAutoLoginProperties with(
      String loginOrigin, String authorizeOrigin, String credentialPath) {
    return new KiteAutoLoginProperties(
        loginOrigin,
        authorizeOrigin,
        credentialPath,
        "/api/twofa",
        "/connect/login",
        "/run/secrets/kite_user_id",
        "/run/secrets/kite_password",
        "/run/secrets/kite_totp_seed",
        Set.of());
  }

  private static KiteAutoLoginProperties defaults() {
    // Both origins are kite.zerodha.com: PINNED_ORIGINS holds exactly one entry since
    // 2026-08-28, so kite.trade would now be rejected by the allowlist itself.
    return with("https://kite.zerodha.com", "https://kite.zerodha.com", "/api/login");
  }

  @Test
  @DisplayName("the SHIPPED defaults resolve to the documented Zerodha endpoints")
  void theDefaultsResolveWhereWeExpect() {
    // ⚠️ Real defaults (all-null), NOT the with(...) helper. Until 2026-08-28 this test was
    // named for the shipped defaults while its helper passed every host EXPLICITLY, so it
    // asserted URL assembly and never the defaults at all. Changing the authorize default
    // reddened nothing here, which is how the gap surfaced. A test name is a coverage claim.
    LoginEndpoints endpoints =
        LoginEndpoints.pinned(
            new KiteAutoLoginProperties(null, null, null, null, null, null, null, null, null));

    assertThat(endpoints.credentials().toString()).isEqualTo("https://kite.zerodha.com/api/login");
    assertThat(endpoints.twofa().toString()).isEqualTo("https://kite.zerodha.com/api/twofa");
    assertThat(endpoints.authorize().toString())
        .as("the LITERAL documented endpoint — same-origin alone is not the invariant, since"
            + " moving BOTH hosts to kite.trade would stay same-origin and still fail")
        .isEqualTo("https://kite.zerodha.com/connect/login");
  }

  @Test
  @DisplayName("⚠️ a path beginning with '@' is REJECTED, and never redirects the credential POST")
  void aPathWithUserinfoIsRejected() {
    // ⚠️ THE case. Under the original concatenation this exact value silently sent the user id and
    // password to attacker.example while every test stayed green.
    assertThatThrownBy(() -> LoginEndpoints.pinned(with(
            "https://kite.zerodha.com", "https://kite.zerodha.com", "@attacker.example/api/login")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("credential-path");
  }

  @Test
  @DisplayName("⚠️ every hostile path shape is rejected — and none of them can move the host")
  void hostilePathsAreRejected() {
    List<String> hostile =
        List.of(
            "@attacker.example/api/login", // userinfo takeover under concatenation
            "//attacker.example/api/login", // network-path reference: URI.resolve REPLACES the host
            "https://attacker.example/api/login", // an absolute URL where a path belongs
            "http://attacker.example/api/login",
            "api/login", // no leading slash: resolves relative to the origin's own path
            "/api/../../@attacker.example/login",
            "\\\\attacker.example\\api\\login", // backslashes: some parsers normalise these to '/'
            "/api/login\nHost: attacker.example", // header injection via a control character
            ""); // empty

    for (String path : hostile) {
      String rendered = path.replace("\n", "\\n");
      assertThatThrownBy(
              () ->
                  LoginEndpoints.pinned(
                      with("https://kite.zerodha.com", "https://kite.zerodha.com", path)))
          .as("hostile credential-path %s must be refused", rendered)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @DisplayName("⚠️ the origin allowlist is a VALUE match, so a lookalike domain is refused")
  void aLookalikeOriginIsRefused() {
    // A startsWith/prefix test would ACCEPT the first two of these. They are different registrable
    // domains, which is the whole point of comparing after parsing.
    List<String> lookalikes =
        List.of(
            "https://kite.zerodha.com.attacker.example",
            "https://kite.zerodha.com.evil.co",
            "https://kite-zerodha.com",
            "https://attacker.example",
            "http://kite.zerodha.com", // right host, wrong scheme
            "https://kite.zerodha.com:8443"); // right host, unexpected port

    for (String origin : lookalikes) {
      assertThatThrownBy(() -> LoginEndpoints.pinned(with(origin, "https://kite.zerodha.com", "/api/login")))
          .as("origin %s must not be accepted as a pinned Zerodha origin", origin)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @DisplayName("an origin carrying userinfo is refused outright")
  void anOriginWithUserinfoIsRefused() {
    assertThatThrownBy(
            () ->
                LoginEndpoints.pinned(
                    with("https://kite.zerodha.com@attacker.example", "https://kite.zerodha.com", "/api/login")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("login-base-url");
  }

  @Test
  @DisplayName("an origin carrying a path, query or fragment is refused")
  void anOriginMustBeBare() {
    for (String origin :
        List.of(
            "https://kite.zerodha.com/api",
            "https://kite.zerodha.com?next=https://attacker.example",
            "https://kite.zerodha.com#x")) {
      assertThatThrownBy(() -> LoginEndpoints.pinned(with(origin, "https://kite.zerodha.com", "/api/login")))
          .as("origin %s is not bare", origin)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @DisplayName("the authorize origin is allowlisted too, not just the login origin")
  void theAuthorizeOriginIsAllowlistedAsWell() {
    // It carries the api_key and — if the cross-origin allowlist is ever populated — a cookie.
    assertThatThrownBy(
            () ->
                LoginEndpoints.pinned(
                    with("https://kite.zerodha.com", "https://attacker.example", "/api/login")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("authorize-base-url");
  }

  @Test
  @DisplayName("the TEST-ONLY seam still enforces paths and the resolved-authority post-condition")
  void theTestSeamOnlyRelaxesTheOriginAllowlist() {
    // The seam exists so WireMock can serve on localhost. If it also relaxed path validation, every
    // assertion in LiveLoginWireClientTest would be running against weaker code than production.
    assertThatCode(() -> LoginEndpoints.unpinnedForTest(with("http://localhost:1234", "http://localhost:1234", "/api/login")))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                LoginEndpoints.unpinnedForTest(
                    with("http://localhost:1234", "http://localhost:1234", "@attacker.example/x")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                LoginEndpoints.unpinnedForTest(
                    with("http://localhost:1234", "http://localhost:1234", "//attacker.example/x")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("⚠️ whatever the path, the resolved host is ALWAYS the allowlisted origin")
  void theResolvedHostIsAlwaysTheAllowlistedOrigin() {
    // The outcome check, stated positively. Any accepted path must land on the pinned host — this
    // is what would still fail if a future edit reintroduced concatenation and someone "fixed" the
    // rejection list to match.
    for (String path : List.of("/api/login", "/", "/a/b/c", "/api.login", "/api/login/")) {
      LoginEndpoints endpoints =
          LoginEndpoints.pinned(with("https://kite.zerodha.com", "https://kite.zerodha.com", path));
      assertThat(endpoints.credentials().getHost())
          .as("path %s must not move the credential host", path)
          .isEqualTo("kite.zerodha.com");
      assertThat(endpoints.credentials().getUserInfo()).isNull();
      assertThat(endpoints.credentials().getScheme()).isEqualTo("https");
      assertThat(endpoints.authorize().getHost()).isEqualTo("kite.zerodha.com");
    }
  }

  @Test
  @DisplayName("the pinned set is exactly ONE Zerodha origin — widening it is a deliberate act")
  void thePinnedSetIsExactlyOne() {
    assertThat(LoginEndpoints.PINNED_ORIGINS)
        .as("adding an origin here must be a reviewed change, never a quiet one")
        .containsExactly("https://kite.zerodha.com");
  }

  /**
   * ⚠️ The live AUTHORIZE failure of 2026-08-27, pinned so it cannot come back as a default.
   *
   * <p>Credential and 2FA both succeeded; AUTHORIZE returned {@code UNEXPECTED_RESPONSE
   * (redirect carried no request_token)}. <b>Only the failing STEP was measured — the mechanism
   * is not established</b>, and {@link KiteAutoLoginProperties} records the two candidates.
   * This test deliberately pins neither of them; it pins the DEFAULT.
   *
   * <p>⚠️ The literal endpoint is primary and same-origin is secondary, which is the reverse of
   * this test's first draft. Cross-vendor review supplied the counter-example: pointing BOTH
   * defaults at {@code kite.trade} keeps them same-origin and still fails, because
   * {@code kite.trade/connect/login} answers with an intermediate 302 carrying no token. A
   * relationship-only assertion would have passed that.
   */
  @Test
  void theAuthorizeStepIsSameOriginAsLoginSoTheSessionCookieTravels() {
    KiteAutoLoginProperties defaults =
        new KiteAutoLoginProperties(null, null, null, null, null, null, null, null, null);
    LoginEndpoints endpoints = LoginEndpoints.pinned(defaults);

    // ⚠️ The LITERAL endpoint is the load-bearing invariant; same-origin is a consequence of it,
    // not a substitute. Cross-vendor review, 2026-08-28: pointing BOTH defaults at kite.trade
    // would satisfy a same-origin-only assertion and still reproduce the live failure, because
    // kite.trade/connect/login answers with an intermediate 302 carrying no token.
    assertThat(endpoints.authorize().toString())
        .as("the endpoint Kite Connect documents and its official SDK pins")
        .isEqualTo("https://kite.zerodha.com/connect/login");
    assertThat(endpoints.authorize().getHost())
        .as("secondary: authorize must carry the login session, so it stays same-origin as 2FA")
        .isEqualTo(endpoints.twofa().getHost());
    assertThat(endpoints.authorize().getScheme()).isEqualTo(endpoints.twofa().getScheme());
  }

  /** The empty cross-origin allowlist is the security posture; the fix must not have widened it. */
  @Test
  void theFixDidNotWidenTheCrossOriginCookieAllowlist() {
    KiteAutoLoginProperties defaults =
        new KiteAutoLoginProperties(null, null, null, null, null, null, null, null, null);

    assertThat(defaults.crossOriginCookies()).isEmpty();
  }
}
