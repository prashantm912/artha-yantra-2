package in.arthayantra.gateway.ws;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Shared, refcounted Redis pub/sub subscriptions for the WS bridge (A.7b): one upstream Redis
 * SUBSCRIBE per channel regardless of how many browser sessions watch it; the upstream
 * subscription is dropped shortly after the last session unsubscribes. Subscribing to channels
 * whose producers don't exist yet is legal — silence is fine (A.7.2).
 */
@Component
public class RedisTopicHub {

  private final ReactiveRedisMessageListenerContainer container;
  private final Map<String, Flux<String>> channels = new ConcurrentHashMap<>();

  /** One listener container for all bridge subscriptions. */
  public RedisTopicHub(ReactiveRedisConnectionFactory connectionFactory) {
    this.container = new ReactiveRedisMessageListenerContainer(connectionFactory);
  }

  /** A shared stream of message bodies on {@code channel}. */
  public Flux<String> messages(String channel) {
    return channels.computeIfAbsent(
        channel,
        ch ->
            container
                .receive(ChannelTopic.of(ch))
                .map(message -> message.getMessage())
                .publish()
                .refCount(1, Duration.ofSeconds(10)));
  }
}
