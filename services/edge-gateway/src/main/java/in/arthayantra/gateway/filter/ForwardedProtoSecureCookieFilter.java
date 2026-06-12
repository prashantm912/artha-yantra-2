package in.arthayantra.gateway.filter;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Marks the session cookie {@code Secure} when the request arrived through a TLS-terminating
 * proxy (Tailscale serve sends {@code X-Forwarded-Proto: https} — A.1.2/A.2.3). Plain loopback
 * HTTP keeps the cookie non-Secure so localhost login still works.
 */
@Component
public class ForwardedProtoSecureCookieFilter implements WebFilter {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String proto = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
    if (!"https".equalsIgnoreCase(proto)) {
      return chain.filter(exchange);
    }
    exchange
        .getResponse()
        .beforeCommit(
            () -> {
              HttpHeaders headers = exchange.getResponse().getHeaders();
              List<String> cookies = headers.get(HttpHeaders.SET_COOKIE);
              if (cookies != null && !cookies.isEmpty()) {
                headers.put(
                    HttpHeaders.SET_COOKIE,
                    cookies.stream()
                        .map(c -> c.contains("Secure") ? c : c + "; Secure")
                        .collect(Collectors.toList()));
              }
              return Mono.empty();
            });
    return chain.filter(exchange);
  }
}
