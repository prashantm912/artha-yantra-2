package in.arthayantra.marketdata.kite.session.autologin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three-step login leg against WireMock.
 *
 * <p>⚠️ <b>These stubs pin a CORROBORATED contract, not a published one.</b> The paths, field names
 * and response shapes were corroborated across two independent public implementations on
 * 2026-08-26 and agree exactly — but they are not part of the published Kite Connect API, so a
 * green run here proves this client speaks the flow as corroborated, and says nothing about whether
 * the corroboration is still current. See {@link LiveLoginWireClient}'s class javadoc.
 *
 * <p>No real credential is used or created. The temp files hold literal placeholder strings, and
 * the TOTP seed is the base32 form of <b>RFC 6238's own published test key</b>.
 */
class LiveLoginWireClientTest {

  /** RFC 6238 Appendix B's test key in base32 — public test data, never an account seed. */
  private static final String RFC_TEST_SEED_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

  private static final String CREDENTIAL_PATH = "/api/login";
  private static final String TWOFA_PATH = "/api/twofa";
  private static final String AUTHORIZE_PATH = "/connect/login";

  private static final String REQUEST_ID_BODY =
      """
      {"status":"success","data":{"user_id":"placeholder-user","request_id":"req-1234"}}
      """;

  private static WireMockServer wireMock;

  @TempDir private Path secrets;

  private KiteAutoLoginProperties properties;

  @BeforeAll
  static void start() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stop() {
    wireMock.stop();
  }

  @BeforeEach
  void reset() throws IOException {
    wireMock.resetAll();
    properties =
        new KiteAutoLoginProperties(
            wireMock.baseUrl(),
            wireMock.baseUrl(),
            CREDENTIAL_PATH,
            TWOFA_PATH,
            AUTHORIZE_PATH,
            write("kite_user_id", "placeholder-user"),
            write("kite_password", "placeholder-password"),
            write("kite_totp_seed", RFC_TEST_SEED_BASE32),
            // ⚠️ EMPTY, which is production's default: no login-host cookie may reach the
            // authorize host. theAuthorizeStepCarriesNoLoginHostCookie pins that.
            Set.of());
  }

  private String write(String name, String content) throws IOException {
    Path file = secrets.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file.toString();
  }

  private LiveLoginWireClient client() {
    return new LiveLoginWireClient(
        properties,
        // The TEST-ONLY seam: skips the origin allowlist so WireMock can stand in on localhost.
        // Path validation and the resolved-authority post-condition still run, exactly as in
        // production — see LoginEndpointsTest for the allowlist itself.
        LoginEndpoints.unpinnedForTest(properties),
        new ObjectMapper(),
        Clock.fixed(Instant.ofEpochSecond(1111111109L), ZoneOffset.UTC),
        "placeholder-api-key",
        Duration.ofSeconds(2),
        Duration.ofSeconds(5));
  }

  private void stubCredentials() {
    wireMock.stubFor(
        post(urlPathEqualTo(CREDENTIAL_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Set-Cookie", "kf_session=session-value; Path=/; HttpOnly")
                    .withBody(REQUEST_ID_BODY)));
  }

  private void stubTwofa() {
    wireMock.stubFor(
        post(urlPathEqualTo(TWOFA_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Set-Cookie", "enctoken=enc-value; Path=/; HttpOnly")
                    .withBody("{\"status\":\"success\",\"data\":{}}")));
  }

  private void stubAuthorizeRedirect(String location) {
    wireMock.stubFor(
        get(urlPathEqualTo(AUTHORIZE_PATH))
            .willReturn(aResponse().withStatus(302).withHeader("Location", location)));
  }

  @Test
  @DisplayName("the happy path posts the corroborated fields and returns the request_token")
  void theThreeStepsProduceARequestToken() {
    stubCredentials();
    stubTwofa();
    stubAuthorizeRedirect(
        "https://127.0.0.1:8080/api/v1/auth/kite/callback?action=login&status=success"
            + "&request_token=tok-abcdef");

    assertThat(client().fetchRequestToken()).isEqualTo("tok-abcdef");

    wireMock.verify(
        postRequestedFor(urlPathEqualTo(CREDENTIAL_PATH))
            .withRequestBody(containing("user_id=placeholder-user"))
            .withRequestBody(containing("password=placeholder-password")));
    // The TOTP for RFC T=1111111109 is 081804 (the low six of the published 07081804) — proving the
    // code is generated from the INJECTED clock, not Instant.now(), which is the only reason this
    // assertion can be a literal at all.
    wireMock.verify(
        postRequestedFor(urlPathEqualTo(TWOFA_PATH))
            .withRequestBody(containing("request_id=req-1234"))
            .withRequestBody(containing("twofa_value=081804")));
  }

  @Test
  @DisplayName("cookies from step 1 are carried into step 2 — same origin, so they must be")
  void theLoginOriginCookiesAreCarriedBetweenTheTwoPosts() {
    stubCredentials();
    stubTwofa();
    stubAuthorizeRedirect("https://127.0.0.1:8080/cb?request_token=tok-1");

    client().fetchRequestToken();

    // Without this the 2FA POST is anonymous and Zerodha could never tie it to the request_id.
    wireMock.verify(
        postRequestedFor(urlPathEqualTo(TWOFA_PATH))
            .withHeader("Cookie", containing("kf_session=session-value")));
  }

  @Test
  @DisplayName("⚠️ the authorize step carries NO login-host cookie by default")
  void theAuthorizeStepCarriesNoLoginHostCookie() {
    // ⚠️ Cross-vendor review Critical 2. The first cut kept one flat jar and replayed every
    // kite.zerodha.com cookie to kite.trade on EVERY successful login. No browser could scope a
    // cookie across two registrable domains, so that was strictly broader bearer-token exposure
    // than the flow it imitated. Default is now: nothing crosses.
    //
    // ⚠️ This test runs both steps against ONE WireMock origin, so it cannot prove cross-ORIGIN
    // behaviour by itself — LoginCookieJarTest does that directly against two distinct origins.
    // What this pins is that the production wiring asks for the header the safe way.
    stubCredentials();
    stubTwofa();
    stubAuthorizeRedirect("https://127.0.0.1:8080/cb?request_token=tok-1");

    client().fetchRequestToken();

    wireMock.verify(
        getRequestedFor(urlPathEqualTo(AUTHORIZE_PATH))
            .withQueryParam("api_key", equalTo("placeholder-api-key"))
            .withQueryParam("v", equalTo("3"))
            // ⚠️ Required by the corroborated contract: without it Zerodha does not issue the
            // redirect this whole feature reads, and WireMock would never notice its absence.
            .withQueryParam("skip_session", equalTo("true")));
  }

  @Test
  @DisplayName("a rejected credential is TERMINAL and the TOTP step is never reached")
  void aRejectedCredentialNeverSendsTheTotp() {
    wireMock.stubFor(
        post(urlPathEqualTo(CREDENTIAL_PATH))
            .willReturn(aResponse().withStatus(403).withBody("{\"message\":\"bad password\"}")));
    stubTwofa();

    assertThatThrownBy(() -> client().fetchRequestToken())
        .isInstanceOf(LoginRefused.class)
        .satisfies(
            thrown -> {
              LoginRefused refused = (LoginRefused) thrown;
              assertThat(refused.refusal()).isEqualTo(LoginRefusal.CREDENTIAL_REJECTED);
              assertThat(refused.step()).isEqualTo(LoginWireClient.Step.CREDENTIALS);
              assertThat(refused.refusal().retryable()).isFalse();
            })
        // ⚠️ The message is what every log line and every ntfy alert carries, so it is the
        // containment boundary: the upstream body must not survive into it.
        .hasMessageNotContaining("bad password");

    assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo(CREDENTIAL_PATH)))).hasSize(1);
    assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo(TWOFA_PATH)))).isEmpty();
  }

  @Test
  @DisplayName("a rejected TOTP is TERMINAL and is never re-sent")
  void aRejectedTotpIsNeverResent() {
    stubCredentials();
    wireMock.stubFor(
        post(urlPathEqualTo(TWOFA_PATH))
            .willReturn(aResponse().withStatus(400).withBody("{\"message\":\"invalid totp\"}")));

    assertThatThrownBy(() -> client().fetchRequestToken())
        .isInstanceOf(LoginRefused.class)
        .satisfies(
            thrown -> assertThat(((LoginRefused) thrown).refusal()).isEqualTo(LoginRefusal.TOTP_REJECTED))
        .hasMessageNotContaining("invalid totp");

    // A second TOTP POST here is the shape that locks a broker account.
    assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo(TWOFA_PATH)))).hasSize(1);
  }

  @Test
  @DisplayName("a 5xx is classified as retryable, but this client still sends only once")
  void anUpstreamErrorIsRetryableButNotRetriedHere() {
    stubCredentials();
    wireMock.stubFor(post(urlPathEqualTo(TWOFA_PATH)).willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> client().fetchRequestToken())
        .isInstanceOf(LoginRefused.class)
        .satisfies(
            thrown -> {
              LoginRefusal refusal = ((LoginRefused) thrown).refusal();
              assertThat(refusal).isEqualTo(LoginRefusal.UPSTREAM_ERROR);
              assertThat(refusal.retryable()).isTrue();
            });

    assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo(TWOFA_PATH)))).hasSize(1);
  }

  @Test
  @DisplayName("a credential response with no request_id is the 'Zerodha changed something' signature")
  void aMissingRequestIdIsTerminalAndUnexpected() {
    wireMock.stubFor(
        post(urlPathEqualTo(CREDENTIAL_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"success\",\"data\":{}}")));

    assertThatThrownBy(() -> client().fetchRequestToken())
        .isInstanceOf(LoginRefused.class)
        .satisfies(
            thrown -> {
              LoginRefused refused = (LoginRefused) thrown;
              assertThat(refused.refusal()).isEqualTo(LoginRefusal.UNEXPECTED_RESPONSE);
              assertThat(refused.refusal().retryable()).isFalse();
            });
  }

  @Test
  @DisplayName("a 200 page instead of a redirect is terminal — a consent screen or captcha")
  void anAuthorizePageInsteadOfARedirectIsTerminal() {
    stubCredentials();
    stubTwofa();
    wireMock.stubFor(
        get(urlPathEqualTo(AUTHORIZE_PATH))
            .willReturn(aResponse().withStatus(200).withBody("<html>please confirm</html>")));

    assertThatThrownBy(() -> client().fetchRequestToken())
        .isInstanceOf(LoginRefused.class)
        .satisfies(
            thrown -> {
              LoginRefused refused = (LoginRefused) thrown;
              assertThat(refused.step()).isEqualTo(LoginWireClient.Step.AUTHORIZE);
              assertThat(refused.refusal()).isEqualTo(LoginRefusal.UNEXPECTED_RESPONSE);
            })
        .hasMessageNotContaining("please confirm");
  }

  @Test
  @DisplayName("a redirect carrying no request_token is terminal, not a silent empty token")
  void aRedirectWithoutARequestTokenIsTerminal() {
    stubCredentials();
    stubTwofa();
    stubAuthorizeRedirect("https://kite.zerodha.com/connect/login?status=error&error_type=TokenException");

    assertThatThrownBy(() -> client().fetchRequestToken())
        .isInstanceOf(LoginRefused.class)
        .satisfies(
            thrown ->
                assertThat(((LoginRefused) thrown).refusal())
                    .isEqualTo(LoginRefusal.UNEXPECTED_RESPONSE));
  }

  @Test
  @DisplayName("a missing credential file is refused before anything is sent")
  void aMissingSecretFileSendsNothing() {
    stubCredentials();
    properties =
        new KiteAutoLoginProperties(
            properties.loginBaseUrl(),
            properties.authorizeBaseUrl(),
            CREDENTIAL_PATH,
            TWOFA_PATH,
            AUTHORIZE_PATH,
            secrets.resolve("does_not_exist").toString(),
            properties.passwordFile(),
            properties.totpSeedFile(),
            Set.of());

    assertThatThrownBy(() -> client().fetchRequestToken())
        .isInstanceOf(LoginRefused.class)
        .satisfies(
            thrown -> {
              LoginRefused refused = (LoginRefused) thrown;
              assertThat(refused.refusal()).isEqualTo(LoginRefusal.SECRET_UNREADABLE);
              assertThat(refused.refusal().retryable()).isFalse();
            });

    assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo(CREDENTIAL_PATH)))).isEmpty();
  }

  /**
   * THE regression this whole change exists for.
   *
   * <p>Measured live twice — 2026-08-27 21:25 and 2026-08-28 08:40 — both
   * {@code redirect carried no request_token}. That message is the discriminating evidence:
   * status WAS 3xx and Location WAS a valid URI, it simply held no token. Reading only the
   * FIRST hop can never succeed against a chain.
   *
   * <p>WARNING: changing the authorize HOST (#1515) did not fix it, which is what rules the
   * cookie-scope theory out. Two identical failures across a host change beat either theory.
   */
  @Test
  void theTokenIsFoundOnALaterHopOfTheAuthorizeChain() {
    stubCredentials();
    stubTwofa();
    wireMock.stubFor(
        get(urlPathEqualTo(AUTHORIZE_PATH))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader("Location", wireMock.baseUrl() + "/connect/finish?v=3")));
    wireMock.stubFor(
        get(urlPathEqualTo("/connect/finish"))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader(
                        "Location",
                        "https://127.0.0.1/redirect?action=login&status=success"
                            + "&request_token=token-from-hop-two")));

    assertThat(client().fetchRequestToken()).isEqualTo("token-from-hop-two");
  }

  /**
   * A token in the Location is read WITHOUT fetching the destination. The stub host is
   * unreachable on purpose: if the client fetched it, this test would fail with a connection
   * error instead of returning the token, so the unreachability IS the assertion.
   */
  @Test
  void theFinalHopIsReadFromTheHeaderAndNeverFetched() {
    stubCredentials();
    stubTwofa();
    wireMock.stubFor(
        get(urlPathEqualTo(AUTHORIZE_PATH))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader(
                        "Location",
                        "https://example.invalid/redirect?request_token=token-off-origin")));

    assertThat(client().fetchRequestToken()).isEqualTo("token-off-origin");
  }

  /**
   * THE cross-origin guard, tested where it actually applies: a hop that leaves the login
   * origin carrying NO token must STOP the chain rather than be followed.
   *
   * <p>WARNING: my first cut of this test put the token ON the cross-origin hop, so
   * {@code tokenIfPresent} returned before the origin check ever ran and the test passed
   * whatever the guard did. A tokenless hop is what forces the guard to decide.
   *
   * <p>example.invalid is unreachable, so following it would surface as a transport error;
   * getting the precise no-token refusal instead is the proof it was not followed.
   */
  @Test
  void aTokenlessCrossOriginHopStopsTheChainInsteadOfBeingFollowed() {
    stubCredentials();
    stubTwofa();
    wireMock.stubFor(
        get(urlPathEqualTo(AUTHORIZE_PATH))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader("Location", "https://example.invalid/somewhere?v=3")));

    assertThatThrownBy(() -> client().fetchRequestToken())
        .isInstanceOf(LoginRefused.class)
        .hasMessageContaining("redirect carried no request_token");
  }

  /** A redirect loop must fail loudly and bounded, never spin. */
  @Test
  void aRedirectLoopIsRefusedAfterTheHopBudget() {
    stubCredentials();
    stubTwofa();
    wireMock.stubFor(
        get(urlPathEqualTo(AUTHORIZE_PATH))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader("Location", wireMock.baseUrl() + AUTHORIZE_PATH)));

    assertThatThrownBy(() -> client().fetchRequestToken())
        .isInstanceOf(LoginRefused.class)
        .hasMessageContaining("within 5 redirects");
  }

  /**
   * A hop that stops WITHOUT a token still produces the precise original refusal, rather than
   * the generic budget message — the single-hop diagnosis must not regress into a vague one.
   */
  @Test
  void aTerminalHopWithNoTokenStillNamesTheRealRefusal() {
    stubCredentials();
    stubTwofa();
    wireMock.stubFor(
        get(urlPathEqualTo(AUTHORIZE_PATH))
            .willReturn(aResponse().withStatus(200)));

    assertThatThrownBy(() -> client().fetchRequestToken())
        .isInstanceOf(LoginRefused.class)
        .hasMessageContaining("expected a redirect");
  }
}
