package in.arthayantra.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.ErrorResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * The A.2.3 security posture: session auth everywhere (defense-in-depth even on loopback — a
 * single compose typo degrades to "password required", not "open dashboard"), CSRF on mutating
 * calls, the A.2.3 security headers with a self-only CSP [A13], and the D8 envelope on 401/403.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  private final ObjectMapper objectMapper;

  /** Wires the envelope serializer. */
  public SecurityConfig(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Session-backed security context (Spring Session Redis holds the session). */
  @Bean
  public ServerSecurityContextRepository securityContextRepository() {
    return new WebSessionServerSecurityContextRepository();
  }

  /** The single security chain. */
  @Bean
  public SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http, ServerSecurityContextRepository securityContextRepository) {
    // CSRF for mutating calls (A.2.3); login itself is exempt — it is protected by the
    // credential and rotates the session id against fixation
    var mutatingExceptLogin =
        new AndServerWebExchangeMatcher(
            CsrfWebFilter.DEFAULT_CSRF_MATCHER,
            new NegatedServerWebExchangeMatcher(
                ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/v1/auth/login")));

    http.securityContextRepository(securityContextRepository)
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                    // plain handler: SPA reads XSRF-TOKEN cookie, echoes X-XSRF-TOKEN header
                    .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                    .requireCsrfProtectionMatcher(mutatingExceptLogin))
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers("/api/v1/auth/login", "/api/v1/auth/session")
                    .permitAll()
                    .pathMatchers("/actuator/health/**", "/actuator/health", "/actuator/info")
                    .permitAll()
                    // Deny-by-default still holds for every DYNAMIC surface: the data/control
                    // plane, the WS feed, management beyond liveness, and the api-docs/Swagger
                    // proxy all require the session. A compose typo degrades to "password
                    // required", not "open data". [A.2.3]
                    .pathMatchers(
                        "/api/**",
                        "/ws/**",
                        "/actuator/**",
                        "/docs/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**")
                    .authenticated()
                    // Everything else is the Angular shell served through the SOLE ingress
                    // (catch-all route -> frontend-ui). A compiled SPA bundle carries no
                    // secrets, and it MUST be reachable unauthenticated or the login page —
                    // the only way to obtain a session — can never load. [Phase 27 / C-2.28]
                    .anyExchange()
                    .permitAll())
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(
                        (exchange, ex) ->
                            writeEnvelope(
                                exchange,
                                HttpStatus.UNAUTHORIZED,
                                ErrorCodes.AUTH_REQUIRED,
                                "Authentication required"))
                    .accessDeniedHandler(
                        (exchange, ex) ->
                            writeEnvelope(
                                exchange,
                                HttpStatus.FORBIDDEN,
                                ErrorCodes.AUTH_FORBIDDEN,
                                "Forbidden (missing/invalid CSRF token or insufficient rights)")))
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(
                        // script-src stays strict ('self', inherited from default-src — no inline
                        // JS). style-src adds 'unsafe-inline' because Angular/PrimeNG apply inline
                        // `style` attributes at runtime (virtual-scroll row heights, popups); inline
                        // CSS cannot execute, so this is the standard safe relaxation for a
                        // component SPA. data: covers PrimeNG's inlined icons/fonts. [A.2.3, Phase 27]
                        csp ->
                            csp.policyDirectives(
                                "default-src 'self'; "
                                    + "style-src 'self' 'unsafe-inline'; "
                                    + "img-src 'self' data:; "
                                    + "font-src 'self' data:; "
                                    + "frame-ancestors 'none'"))
                    .frameOptions(
                        frame -> frame.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(
                                ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER))
                    // HSTS off: plain HTTP on loopback is the sanctioned posture (A.2.5)
                    .hsts(ServerHttpSecurity.HeaderSpec.HstsSpec::disable));
    return http.build();
  }

  /** Subscribes the CsrfToken so the XSRF-TOKEN cookie is actually rendered (WebFlux quirk). */
  @Bean
  public WebFilter csrfCookieRenderingFilter() {
    return (exchange, chain) -> {
      Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
      return (token != null ? token.then() : Mono.<Void>empty()).then(chain.filter(exchange));
    };
  }

  private Mono<Void> writeEnvelope(
      ServerWebExchange exchange, HttpStatus status, String code, String message) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    byte[] body;
    try {
      body = objectMapper.writeValueAsBytes(ErrorResponse.of(code, message));
    } catch (Exception e) {
      body = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
    }
    DataBuffer buffer = response.bufferFactory().wrap(body);
    return response.writeWith(Mono.just(buffer));
  }
}
