package in.arthayantra.marketdata.kite.session.autologin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The three-step browser leg of the Kite login, over a redirect-disabled client with a per-origin
 * cookie store: credential POST → {@code data.request_id}, 2FA POST → session cookies, authorize
 * GET → the {@code request_token} in the {@code Location} header.
 *
 * <p><b>⚠️ THESE ENDPOINTS ARE NOT PART OF THE PUBLISHED KITE CONNECT API.</b> The paths, field
 * names and response shapes below are corroborated across two independent public implementations as
 * of 2026-08-26 and agree exactly — but corroboration is not documentation, and Zerodha can change
 * any of it without notice. The WireMock stubs in this package therefore pin a <b>corroborated
 * contract</b>: a green suite proves this client speaks the flow as corroborated, never that the
 * corroboration is still current.
 *
 * <p><b>Destinations are resolved, not concatenated</b> — see {@link LoginEndpoints}, which exists
 * because this class originally built its URLs with {@code origin + path} and a configured path
 * beginning {@code @} would have redirected the credential POST to an arbitrary host.
 *
 * <p><b>Cookies never cross origins by default</b> — see {@link LoginCookieJar}, which exists
 * because this class originally replayed every login-host cookie to the authorize host.
 *
 * <p><b>Hand-rolled {@link RestClient}, not the shared builder.</b> The authorize step needs
 * redirects DISABLED (following the redirect would consume the {@code Location} header this whole
 * feature exists to read), and cookies are carried in a local store rather than a JVM-global
 * {@code CookieHandler}, so nothing about a login attempt leaks into any other outbound call.
 *
 * <p><b>Nothing here logs a credential, a request body or a response body.</b> Failure detail is a
 * step name plus, at most, an HTTP status CODE — see {@link LoginRefused}.
 *
 * <p><b>Deliberately NOT in {@code kite/wire/}.</b> That package's rule is a FULL mirror of every
 * documented field of a documented endpoint; there is no documentation to mirror here, so the
 * response shape below is minimal and {@code ignoreUnknown}, which is the honest encoding of "we
 * consume one field of a shape we do not own".
 */
public class LiveLoginWireClient implements LoginWireClient {

  private static final Logger log = LoggerFactory.getLogger(LiveLoginWireClient.class);

  /** Redirect budget for the authorize chain. A loop must fail loudly, never spin. */
  private static final int MAX_AUTHORIZE_HOPS = 5;

  /** The credential step's envelope. Only {@code data.request_id} is consumed. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CredentialResponse(@JsonProperty("data") CredentialData data) {

    /** The one field this flow needs from step 1. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CredentialData(@JsonProperty("request_id") String requestId) {}
  }

  private final RestClient restClient;
  private final KiteAutoLoginProperties properties;
  private final LoginEndpoints endpoints;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final String apiKey;

  /**
   * Wires a dedicated redirect-disabled client against pre-validated endpoints.
   *
   * @param endpoints already validated and origin-pinned; this class never re-derives a URL
   * @param apiKey the Kite API key the authorize step echoes; resolved by the caller from its
   *     existing secret file so this class holds no extra credential source
   */
  public LiveLoginWireClient(
      KiteAutoLoginProperties properties,
      LoginEndpoints endpoints,
      ObjectMapper objectMapper,
      Clock clock,
      String apiKey,
      Duration connectTimeout,
      Duration readTimeout) {
    this.properties = properties;
    this.endpoints = endpoints;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.apiKey = apiKey;
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                // NEVER, explicitly: the authorize step's whole output is the Location header, and
                // a followed redirect swallows it and lands on an unrelated page instead.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build());
    factory.setReadTimeout(readTimeout);
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public String fetchRequestToken() {
    String userId;
    String password;
    byte[] totpKey;
    try {
      userId = properties.resolveUserId();
      password = properties.resolvePassword();
      totpKey = Totp.decodeBase32(properties.resolveTotpSeed());
    } catch (IllegalStateException | IllegalArgumentException unreadable) {
      // unreadable.getMessage() here is our own path-only text (KiteAutoLoginProperties) or the
      // seed-shape message from Totp, neither of which carries key material.
      throw new LoginRefused(
          Step.CREDENTIALS, LoginRefusal.SECRET_UNREADABLE, unreadable.getMessage());
    }

    LoginCookieJar jar = new LoginCookieJar(properties.crossOriginCookies());

    MultiValueMap<String, String> credentials = new LinkedMultiValueMap<>();
    credentials.add("user_id", userId);
    credentials.add("password", password);
    URI credentialUri = endpoints.credentials();
    ResponseEntity<String> step1 =
        call(
            Step.CREDENTIALS,
            LoginRefusal.CREDENTIAL_REJECTED,
            () -> postForm(credentialUri, credentials, jar));
    jar.store(credentialUri, step1.getHeaders().get(HttpHeaders.SET_COOKIE));
    String requestId = requestId(step1.getBody());

    // ⚠️ EXACTLY the three corroborated fields. No speculative extras (an earlier draft added a
    // `twofa_type`): a field neither implementation sends is a guess, and a guess on a 2FA
    // endpoint is the kind of thing that turns a working login into a rejected one.
    MultiValueMap<String, String> twofa = new LinkedMultiValueMap<>();
    twofa.add("user_id", userId);
    twofa.add("request_id", requestId);
    twofa.add("twofa_value", Totp.code(totpKey, clock.instant()));
    URI twofaUri = endpoints.twofa();
    ResponseEntity<String> step2 =
        call(Step.TWOFA, LoginRefusal.TOTP_REJECTED, () -> postForm(twofaUri, twofa, jar));
    jar.store(twofaUri, step2.getHeaders().get(HttpHeaders.SET_COOKIE));
    if (jar.sizeFor(twofaUri) == 0) {
      throw new LoginRefused(
          Step.TWOFA, LoginRefusal.UNEXPECTED_RESPONSE, "no session cookie was set");
    }
    log.debug("kite auto-login: {} cookie(s) held for the login origin", jar.sizeFor(twofaUri));

    return authorizeFollowingRedirects(jar);
  }

  private ResponseEntity<String> postForm(
      URI uri, MultiValueMap<String, String> form, LoginCookieJar jar) {
    RestClient.RequestBodySpec request =
        restClient.post().uri(uri).contentType(MediaType.APPLICATION_FORM_URLENCODED);
    // Same-origin only: null here means "send no Cookie header at all", never an empty one.
    String cookies = jar.cookieHeaderFor(uri, null);
    if (cookies != null) {
      request = request.header(HttpHeaders.COOKIE, cookies);
    }
    return request.body(form).retrieve().toEntity(String.class);
  }

  /**
   * The authorize GET — on the LOGIN host, same origin as credential and 2FA.
   *
   * <p>⚠️ It said "on the API host" until 2026-08-28, matching a default that pointed at
   * {@code kite.trade} and produced a live AUTHORIZE failure. See
   * {@link KiteAutoLoginProperties} for what is and is not established about why.
   *
   * <p>⚠️ {@code skip_session=true} is required — without it Zerodha does not hand back the
   * redirect this whole feature reads.
   *
   * <p>⚠️ The cookie header here is built for the AUTHORIZE origin. Login-origin cookies are
   * offered only if the owner has named them in {@code artha.kite.auto-login.cross-origin-cookies},
   * which is EMPTY by default — see {@link LoginCookieJar} for why forwarding the whole jar across
   * two registrable domains was a Critical.
   */
  /**
   * Walks the authorize redirect chain until a hop carries the {@code request_token}.
   *
   * <p><b>Why a chain and not a single hop.</b> The first cut read only the FIRST 3xx and
   * failed live on 2026-08-27 and again on 2026-08-28 with {@code redirect carried no
   * request_token}. That message is the discriminating evidence: the status WAS 3xx and the
   * {@code Location} WAS a valid URI, it simply held no token. Zerodha answers with an
   * intermediate hop, so reading only the first one can never succeed.
   *
   * <p>WARNING: changing the authorize HOST (#1515) did NOT fix this. That is what rules the
   * earlier cookie-scope theory OUT and this one in — two live failures with an identical
   * message across a host change is a stronger signal than either theory on its own.
   *
   * <p><b>It stops at the token and never REQUESTS the final destination.</b> The last hop
   * points at the registered redirect URL, which is not ours to fetch and may not be reachable;
   * the token is already in the {@code Location} header we hold. Following it would be a
   * pointless outbound call carrying a live token.
   *
   * <p><b>Only SAME-ORIGIN hops are followed.</b> A redirect off the login origin is where the
   * chain leaves Zerodha; following it would send a request to a host this feature never
   * vetted. Bounded by {@link #MAX_AUTHORIZE_HOPS} so a redirect loop fails loudly.
   * <p><b>OPEN DOUBT for the first live run</b> (cross-vendor review, 2026-08-28): hop responses
   * are NOT stored back into {@link LoginCookieJar}. The observed chain carries its intermediate
   * state in a {@code sess_id} QUERY parameter rather than a cookie, so this is not believed to
   * matter — but Kite does not document its intermediate redirects. If a live run still fails
   * here, check whether a hop set a {@code Set-Cookie} header, WITHOUT logging its value.
   */
  private String authorizeFollowingRedirects(LoginCookieJar jar) {
    URI target = authorizeUri();
    String loginOrigin = LoginCookieJar.originOf(endpoints.credentials());
    for (int hop = 1; hop <= MAX_AUTHORIZE_HOPS; hop++) {
      final URI current = target;
      ResponseEntity<Void> response =
          call(Step.AUTHORIZE, LoginRefusal.AUTHORIZE_REJECTED, () -> authorize(current, jar));
      String token = tokenIfPresent(response);
      if (token != null) {
        log.info("kite auto-login: request_token found at authorize hop {}", hop);
        return token;
      }
      URI next = sameOriginRedirectTarget(response, current, loginOrigin, hop);
      if (next == null) {
        // Nothing further to follow: let the single-response reader name the precise refusal.
        return requestTokenFrom(response);
      }
      target = next;
    }
    throw new LoginRefused(
        Step.AUTHORIZE,
        LoginRefusal.UNEXPECTED_RESPONSE,
        "authorize did not yield a request_token within " + MAX_AUTHORIZE_HOPS + " redirects");
  }

  /** The token if this response carries one, else null. Never throws: the chain may continue. */
  private static String tokenIfPresent(ResponseEntity<Void> response) {
    if (!response.getStatusCode().is3xxRedirection()) {
      return null;
    }
    String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
    if (location == null || location.isBlank()) {
      return null;
    }
    try {
      String token =
          UriComponentsBuilder.fromUriString(location)
              .build()
              .getQueryParams()
              .getFirst("request_token");
      return token == null || token.isBlank() ? null : token;
    } catch (IllegalArgumentException unparseable) {
      return null;
    }
  }

  /**
   * The next hop, or null to stop.
   *
   * <p>WARNING: the diagnostic line logs scheme, host and PATH only. The query is where a
   * {@code request_token} lives, and on a failed login the submitted parameters too — logging a
   * whole {@code Location} is exactly how a credential reaches a log file.
   */
  private static URI sameOriginRedirectTarget(
      ResponseEntity<Void> response, URI current, String loginOrigin, int hop) {
    if (!response.getStatusCode().is3xxRedirection()) {
      return null;
    }
    String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
    if (location == null || location.isBlank()) {
      return null;
    }
    URI next;
    try {
      next = current.resolve(location);
    } catch (IllegalArgumentException unresolvable) {
      return null;
    }
    boolean sameOrigin = LoginCookieJar.originOf(next).equals(loginOrigin);
    log.info(
        "kite auto-login: authorize hop {} answered HTTP {}, Location {}://{}{}"
            + " (query redacted), same-origin={}",
        hop,
        response.getStatusCode().value(),
        next.getScheme(),
        next.getHost(),
        next.getRawPath(),
        sameOrigin);
    return sameOrigin ? next : null;
  }

  private URI authorizeUri() {
    return UriComponentsBuilder.fromUri(endpoints.authorize())
        .queryParam("v", "3")
        .queryParam("api_key", apiKey)
        .queryParam("skip_session", "true")
        .build(true)
        .toUri();
  }

  private ResponseEntity<Void> authorize(URI uri, LoginCookieJar jar) {
    RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uri);
    String cookies = jar.cookieHeaderFor(uri, LoginCookieJar.originOf(endpoints.credentials()));
    if (cookies != null) {
      request = request.header(HttpHeaders.COOKIE, cookies);
    }
    return request.retrieve().toBodilessEntity();
  }

  /**
   * The {@code request_token} carried by the authorize step's redirect.
   *
   * <p>Parsed as a URI query parameter, never regexed out of the raw header: the {@code Location}
   * value also carries an {@code action} parameter and, on failure, error parameters, and a regex
   * over the whole header is exactly how a partial match becomes a plausible wrong token.
   *
   * <p>A 2xx here means Zerodha rendered a PAGE instead of redirecting — the login did not
   * complete, and the likely causes (a new consent screen, a device check, a captcha) are all the
   * "something changed" case. Terminal and loud, never retried.
   */
  private static String requestTokenFrom(ResponseEntity<Void> response) {
    if (!response.getStatusCode().is3xxRedirection()) {
      throw new LoginRefused(
          Step.AUTHORIZE,
          LoginRefusal.UNEXPECTED_RESPONSE,
          "expected a redirect, got HTTP " + response.getStatusCode().value());
    }
    String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
    if (location == null || location.isBlank()) {
      throw new LoginRefused(
          Step.AUTHORIZE, LoginRefusal.UNEXPECTED_RESPONSE, "redirect carried no Location header");
    }
    String token;
    try {
      token =
          UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("request_token");
    } catch (IllegalArgumentException unparseable) {
      // The Location value itself is NOT echoed: on a failed login it can carry query parameters
      // describing what was submitted.
      throw new LoginRefused(
          Step.AUTHORIZE, LoginRefusal.UNEXPECTED_RESPONSE, "redirect Location is not a valid URI");
    }
    if (token == null || token.isBlank()) {
      throw new LoginRefused(
          Step.AUTHORIZE, LoginRefusal.UNEXPECTED_RESPONSE, "redirect carried no request_token");
    }
    return token;
  }

  private String requestId(String body) {
    String id;
    try {
      CredentialResponse parsed = objectMapper.readValue(body, CredentialResponse.class);
      id = parsed.data() == null ? null : parsed.data().requestId();
    } catch (Exception unreadable) {
      // Never the parser message: Jackson quotes the offending input, which IS the response body.
      throw new LoginRefused(
          Step.CREDENTIALS, LoginRefusal.UNEXPECTED_RESPONSE, "credential response did not parse");
    }
    if (id == null || id.isBlank()) {
      throw new LoginRefused(
          Step.CREDENTIALS, LoginRefusal.UNEXPECTED_RESPONSE, "credential response had no request_id");
    }
    return id;
  }

  /**
   * Runs one wire call and maps every failure into the closed refusal set.
   *
   * <p>⚠️ {@code HttpClientErrorException.getMessage()} embeds the RESPONSE BODY, so only the
   * status CODE is ever taken from it. That is the single most important line in this class.
   */
  private static <T> T call(Step step, LoginRefusal on4xx, Supplier<T> action) {
    try {
      return action.get();
    } catch (HttpClientErrorException rejected) {
      throw new LoginRefused(step, on4xx, "HTTP " + rejected.getStatusCode().value());
    } catch (HttpServerErrorException upstream) {
      throw new LoginRefused(
          step, LoginRefusal.UPSTREAM_ERROR, "HTTP " + upstream.getStatusCode().value());
    } catch (ResourceAccessException transportFailure) {
      throw new LoginRefused(step, LoginRefusal.NETWORK, "transport failure");
    }
  }
}
