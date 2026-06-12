package in.arthayantra.gateway.ws;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/** Maps {@code /ws} ahead of the gateway routes (route mapping order is 1). */
@Configuration
public class WsConfig {

  /** The /ws endpoint mapping. */
  @Bean
  public HandlerMapping stompWsHandlerMapping(StompWebSocketHandler handler) {
    return new SimpleUrlHandlerMapping(Map.of("/ws", handler), -1);
  }

  /** WebFlux WS upgrade support. */
  @Bean
  public WebSocketHandlerAdapter webSocketHandlerAdapter() {
    return new WebSocketHandlerAdapter();
  }
}
