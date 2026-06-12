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
                    .anyExchange()
                    .authenticated())
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
                        csp -> csp.policyDirectives("default-src 'self'; frame-ancestors 'none'"))
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
