package in.arthayantra.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.ErrorResponse;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Owner auth endpoints (A.2.1): login (Argon2id verify → 204 + HttpOnly SameSite=Strict session
 * cookie), logout, and the SPA boot session probe. Argon2 verification runs on boundedElastic —
 * never on the event loop.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private static final String LOGIN_TIME_ATTR = "loginTime";

  private final OwnerAuthService ownerAuth;
  private final LoginRateLimiter rateLimiter;
  private final ServerSecurityContextRepository securityContextRepository;
  private final ObjectMapper objectMapper;
  private final Environment environment;

  public AuthController(
      OwnerAuthService ownerAuth,
      LoginRateLimiter rateLimiter,
      ServerSecurityContextRepository securityContextRepository,
      ObjectMapper objectMapper,
      Environment environment) {
    this.ownerAuth = ownerAuth;
    this.rateLimiter = rateLimiter;
    this.securityContextRepository = securityContextRepository;
    this.objectMapper = objectMapper;
    this.environment = environment;
  }

  /** Login: accepts {@code password=...} form data or {@code {"password": "..."}} JSON. */
  @PostMapping("/login")
  public Mono<ResponseEntity<Object>> login(ServerWebExchange exchange) {
    String ip = clientIp(exchange);
    return rateLimiter
        .tryAcquire(ip)
        .flatMap(
            allowed -> {
              if (!allowed) {
                return rateLimiter
                    .cooldownRemainingMs(ip)
                    .map(
                        remaining ->
                            ResponseEntity.status(429)
                                .body(
                                    (Object)
                                        new ErrorResponse(
                                            ErrorCodes.AUTH_RATE_LIMITED,
                                            "Too many login attempts; cooling down",
                                            Map.of("retryAfterMs", remaining))));
              }
              return extractPassword(exchange)
                  .flatMap(password -> verifyOffEventLoop(password))
                  .flatMap(
                      ok ->
                          ok
                              ? establishSession(exchange)
                              : Mono.just(
                                  ResponseEntity.status(401)
                                      .body(
                                          (Object)
                                              ErrorResponse.of(
                                                  ErrorCodes.AUTH_BAD_CREDENTIALS,
                                                  "Bad owner password"))));
            });
  }

  /** Logout: removes the session from Redis (A.2.1). */
  @PostMapping("/logout")
  public Mono<ResponseEntity<Void>> logout(WebSession session) {
    return session.invalidate().thenReturn(ResponseEntity.noContent().build());
  }

  /** SPA boot probe: authenticated flag, login time, mock/live profile indicator (A.2.1). */
  @GetMapping("/session")
  public Mono<Map<String, Object>> session(ServerWebExchange exchange) {
    return exchange
        .getSession()
        .map(
            webSession -> {
              Map<String, Object> body = new LinkedHashMap<>();
              Object loginTime = webSession.getAttribute(LOGIN_TIME_ATTR);
              boolean authenticated =
                  webSession.getAttributes().containsKey("SPRING_SECURITY_CONTEXT");
              body.put("authenticated", authenticated);
              body.put("loginTime", loginTime);
              body.put("profile", activeMode());
              return body;
            });
  }

  private Mono<Boolean> verifyOffEventLoop(String password) {
    return Mono.fromCallable(() -> ownerAuth.verify(password))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private Mono<ResponseEntity<Object>> establishSession(ServerWebExchange exchange) {
    SecurityContextImpl context =
        new SecurityContextImpl(
            new UsernamePasswordAuthenticationToken(
                "owner", null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    return exchange
        .getSession()
        .flatMap(
            session ->
                session
                    .changeSessionId() // session-fixation rotation
                    .then(securityContextRepository.save(exchange, context))
                    .then(
                        Mono.fromRunnable(
                            () ->
                                session
                                    .getAttributes()
                                    .put(LOGIN_TIME_ATTR, Instant.now().toString()))))
        .thenReturn(ResponseEntity.noContent().build());
  }

  private Mono<String> extractPassword(ServerWebExchange exchange) {
    MediaType contentType = exchange.getRequest().getHeaders().getContentType();
    if (contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
      return exchange
          .getRequest()
          .getBody()
          .reduce(new StringBuilder(), (sb, buffer) -> sb.append(buffer.toString(java.nio.charset.StandardCharsets.UTF_8)))
          .map(
              sb -> {
                try {
                  JsonNode node = objectMapper.readTree(sb.toString());
                  return node.path("password").asText("");
                } catch (Exception e) {
                  return "";
                }
              });
    }
    return exchange.getFormData().map(form -> form.getFirst("password")).defaultIfEmpty("");
  }

  private String activeMode() {
    for (String profile : environment.getActiveProfiles()) {
      if ("live".equals(profile)) {
        return "live";
      }
    }
    return "mock";
  }

  private static String clientIp(ServerWebExchange exchange) {
    InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
    return remote == null ? "unknown" : remote.getAddress().getHostAddress();
  }
}
