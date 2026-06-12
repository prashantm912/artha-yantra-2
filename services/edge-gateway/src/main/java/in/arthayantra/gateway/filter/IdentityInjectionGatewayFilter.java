package in.arthayantra.gateway.filter;

import in.arthayantra.common.web.http.ArthaHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Injects the gateway-asserted identity header on ROUTED requests (A.2.3): downstream services
 * sit on the private compose network with no published ports and trust {@code X-Artha-User} as
 * gateway-injected (any inbound copy was already stripped by {@link RequestIdWebFilter}).
 */
@Component
public class IdentityInjectionGatewayFilter implements GlobalFilter, Ordered {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return exchange
        .getPrincipal()
        .filter(principal -> principal instanceof Authentication auth && auth.isAuthenticated())
        .map(
            principal ->
                exchange
                    .mutate()
                    .request(
                        exchange
                            .getRequest()
                            .mutate()
                            .headers(h -> h.set(ArthaHeaders.X_ARTHA_USER, principal.getName()))
                            .build())
                    .build())
        .defaultIfEmpty(exchange)
        .flatMap(chain::filter);
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 100;
  }
}
