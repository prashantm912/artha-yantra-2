package in.arthayantra.marketdata.kite.session.autologin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cross-origin containment suite.
 *
 * <p>⚠️ <b>This file exists because the original jar was flat.</b> The first cut kept one
 * {@code Map<String,String>}, dropped {@code Domain}/{@code Path}/{@code Secure}, and replayed every
 * {@code kite.zerodha.com} cookie to {@code kite.trade} on EVERY successful login. No browser could
 * ever scope a cookie across two registrable domains, so it was strictly broader bearer-token
 * exposure than the flow it imitated. Cross-vendor review, Critical 2, 2026-08-26.
 *
 * <p>These tests use two genuinely different origins, which {@code LiveLoginWireClientTest} cannot
 * do — it runs everything against one WireMock server, so it can only pin how the client ASKS for
 * the header, never what the store would answer across hosts.
 */
class LoginCookieJarTest {

  private static final URI LOGIN = URI.create("https://kite.zerodha.com/api/login");
  private static final URI TWOFA = URI.create("https://kite.zerodha.com/api/twofa");
  private static final URI AUTHORIZE = URI.create("https://kite.trade/connect/login");
  private static final String LOGIN_ORIGIN = "https://kite.zerodha.com";

  @Test
  @DisplayName("⚠️ by default NOTHING crosses from the login origin to the authorize origin")
  void nothingCrossesOriginsByDefault() {
    LoginCookieJar jar = new LoginCookieJar(Set.of());
    jar.store(LOGIN, List.of("kf_session=abc; Path=/; HttpOnly", "enctoken=xyz; Path=/"));

    assertThat(jar.cookieHeaderFor(TWOFA, null))
        .as("same origin — these must be sent, or the 2FA POST is anonymous")
        .contains("kf_session=abc")
        .contains("enctoken=xyz");
    assertThat(jar.cookieHeaderFor(AUTHORIZE, LOGIN_ORIGIN))
        .as("⚠️ kite.trade is a DIFFERENT registrable domain — no login cookie may reach it")
        .isNull();
  }

  @Test
  @DisplayName("a null header means OMIT the header, never send an empty one")
  void anEmptyResultIsNullNotBlank() {
    // An empty `Cookie:` header is a distinct thing on the wire from no header at all, and the
    // caller branches on null — so this is the contract, not a detail.
    LoginCookieJar jar = new LoginCookieJar(Set.of());
    assertThat(jar.cookieHeaderFor(AUTHORIZE, LOGIN_ORIGIN)).isNull();
    assertThat(jar.sizeFor(AUTHORIZE)).isZero();
  }

  @Test
  @DisplayName("only an explicitly NAMED cookie may cross, and only that one")
  void onlyTheAllowlistedNameCrosses() {
    LoginCookieJar jar = new LoginCookieJar(Set.of("enctoken"));
    jar.store(LOGIN, List.of("kf_session=abc; Path=/", "enctoken=xyz; Path=/", "other=zzz; Path=/"));

    String crossed = jar.cookieHeaderFor(AUTHORIZE, LOGIN_ORIGIN);

    assertThat(crossed).contains("enctoken=xyz");
    assertThat(crossed)
        .as("the allowlist is a NAME list, not a switch that opens the whole jar")
        .doesNotContain("kf_session")
        .doesNotContain("other");
  }

  @Test
  @DisplayName("the authorize origin's OWN cookies are always sent to it")
  void anOriginAlwaysGetsItsOwnCookies() {
    LoginCookieJar jar = new LoginCookieJar(Set.of());
    jar.store(AUTHORIZE, List.of("session=own; Path=/"));

    assertThat(jar.cookieHeaderFor(AUTHORIZE, LOGIN_ORIGIN)).contains("session=own");
  }

  @Test
  @DisplayName("a Secure cookie is never retained off a plaintext exchange")
  void aSecureCookieIsNotRetainedOverHttp() {
    LoginCookieJar jar = new LoginCookieJar(Set.of());
    URI plaintext = URI.create("http://localhost:1234/api/login");
    jar.store(plaintext, List.of("kf_session=abc; Path=/; Secure", "plain=ok; Path=/"));

    assertThat(jar.cookieHeaderFor(plaintext, null)).doesNotContain("kf_session").contains("plain=ok");
  }

  @Test
  @DisplayName("a host may not set a cookie for a domain it does not belong to")
  void aForeignDomainAttributeIsRefused() {
    LoginCookieJar jar = new LoginCookieJar(Set.of());
    jar.store(
        LOGIN,
        List.of("evil=1; Domain=attacker.example; Path=/", "fine=2; Domain=zerodha.com; Path=/"));

    String sent = jar.cookieHeaderFor(TWOFA, null);
    assertThat(sent).doesNotContain("evil");
    assertThat(sent).as("a parent domain the host belongs to is legitimate").contains("fine=2");
  }

  @Test
  @DisplayName("Path scoping is honoured on send")
  void pathScopingIsHonoured() {
    LoginCookieJar jar = new LoginCookieJar(Set.of());
    jar.store(LOGIN, List.of("scoped=1; Path=/api", "elsewhere=2; Path=/other"));

    String sent = jar.cookieHeaderFor(TWOFA, null);
    assertThat(sent).contains("scoped=1");
    assertThat(sent).doesNotContain("elsewhere");
  }

  @Test
  @DisplayName("a deletion clears the cookie rather than storing an empty value")
  void aDeletionRemovesTheCookie() {
    LoginCookieJar jar = new LoginCookieJar(Set.of());
    jar.store(LOGIN, List.of("kf_session=abc; Path=/"));
    jar.store(TWOFA, List.of("kf_session=; Path=/; Max-Age=0"));

    assertThat(jar.cookieHeaderFor(TWOFA, null)).isNull();
  }

  @Test
  @DisplayName("a later response overwrites the same cookie name for that origin")
  void aRefreshedCookieReplacesTheOldValue() {
    LoginCookieJar jar = new LoginCookieJar(Set.of());
    jar.store(LOGIN, List.of("kf_session=old; Path=/"));
    jar.store(TWOFA, List.of("kf_session=new; Path=/"));

    assertThat(jar.cookieHeaderFor(TWOFA, null)).contains("kf_session=new").doesNotContain("old");
  }

  @Test
  @DisplayName("origins differing only by port are different origins")
  void portIsPartOfTheOriginIdentity() {
    LoginCookieJar jar = new LoginCookieJar(Set.of());
    jar.store(URI.create("http://localhost:1111/x"), List.of("a=1; Path=/"));

    assertThat(jar.cookieHeaderFor(URI.create("http://localhost:2222/x"), null)).isNull();
    assertThat(jar.cookieHeaderFor(URI.create("http://localhost:1111/x"), null)).contains("a=1");
  }
}
