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
    return with("https://kite.zerodha.com", "https://kite.trade", "/api/login");
  }

  @Test
  @DisplayName("the shipped defaults resolve to the two pinned Zerodha origins")
  void theDefaultsResolveWhereWeExpect() {
    LoginEndpoints endpoints = LoginEndpoints.pinned(defaults());

    assertThat(endpoints.credentials().toString()).isEqualTo("https://kite.zerodha.com/api/login");
    assertThat(endpoints.twofa().toString()).isEqualTo("https://kite.zerodha.com/api/twofa");
    assertThat(endpoints.authorize().toString()).isEqualTo("https://kite.trade/connect/login");
  }

  @Test
  @DisplayName("⚠️ a path beginning with '@' is REJECTED, and never redirects the credential POST")
  void aPathWithUserinfoIsRejected() {
    // ⚠️ THE case. Under the original concatenation this exact value silently sent the user id and
    // password to attacker.example while every test stayed green.
    assertThatThrownBy(() -> LoginEndpoints.pinned(with(
            "https://kite.zerodha.com", "https://kite.trade", "@attacker.example/api/login")))
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
                      with("https://kite.zerodha.com", "https://kite.trade", path)))
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
      assertThatThrownBy(() -> LoginEndpoints.pinned(with(origin, "https://kite.trade", "/api/login")))
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
                    with("https://kite.zerodha.com@attacker.example", "https://kite.trade", "/api/login")))
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
      assertThatThrownBy(() -> LoginEndpoints.pinned(with(origin, "https://kite.trade", "/api/login")))
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
          LoginEndpoints.pinned(with("https://kite.zerodha.com", "https://kite.trade", path));
      assertThat(endpoints.credentials().getHost())
          .as("path %s must not move the credential host", path)
          .isEqualTo("kite.zerodha.com");
      assertThat(endpoints.credentials().getUserInfo()).isNull();
      assertThat(endpoints.credentials().getScheme()).isEqualTo("https");
      assertThat(endpoints.authorize().getHost()).isEqualTo("kite.trade");
    }
  }

  @Test
  @DisplayName("the pinned set is exactly the two Zerodha origins — widening it is a deliberate act")
  void thePinnedSetIsExactlyTwo() {
    assertThat(LoginEndpoints.PINNED_ORIGINS)
        .as("adding an origin here must be a reviewed change, never a quiet one")
        .containsExactlyInAnyOrder("https://kite.zerodha.com", "https://kite.trade");
  }
}
