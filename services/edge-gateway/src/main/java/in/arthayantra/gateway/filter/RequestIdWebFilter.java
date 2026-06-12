package in.arthayantra.gateway.filter;

import in.arthayantra.common.web.http.ArthaHeaders;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * A.2.4/A.6.3 correlation + identity hygiene, first in the chain for EVERY request: strips any
 * inbound {@code X-Artha-User} (only the gateway may assert identity) and any inbound
 * {@code X-Request-Id}, generates one fresh UUID per request, and echoes it on the response.
 * Identity injection for routed requests happens in {@link IdentityInjectionGatewayFilter} after
 * authentication.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String requestId = UUID.randomUUID().toString();
    ServerHttpRequest mutated =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {
                  headers.remove(ArthaHeaders.X_ARTHA_USER);
                  headers.set(ArthaHeaders.X_REQUEST_ID, requestId);
                })
            .build();
    exchange.getResponse().getHeaders().set(ArthaHeaders.X_REQUEST_ID, requestId);
    MDC.put(ArthaHeaders.MDC_REQUEST_ID, requestId);
    return chain
        .filter(exchange.mutate().request(mutated).build())
        .doFinally(signal -> MDC.remove(ArthaHeaders.MDC_REQUEST_ID));
  }
}
