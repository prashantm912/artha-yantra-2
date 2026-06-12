package in.arthayantra.gateway.ws;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The Redis→browser bridge (A.7b / CD-3): a STOMP-subset endpoint at {@code /ws}
 * (session-authenticated — the security chain rejects anonymous upgrades with 401) with
 * per-symbol subscriptions and a per-session latest-value conflation buffer flushed at
 * {@code GATEWAY_WS_FLUSH_HZ} (default 20 Hz). {@code signals}, {@code jobs.*} and
 * {@code candles.1m.*} are NEVER conflated — low-rate, every message matters (A.7.2).
 */
@Component
public class StompWebSocketHandler implements WebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(StompWebSocketHandler.class);
  private static final Duration HEARTBEAT = Duration.ofSeconds(10);

  private final RedisTopicHub hub;
  private final long flushIntervalMs;
  private final Timer fanoutLatency;
  private final AtomicInteger openSessions = new AtomicInteger();

  /** Wires the hub and the {@code GATEWAY_WS_FLUSH_HZ} knob (10–20 Hz sane range). */
  public StompWebSocketHandler(
      RedisTopicHub hub,
      MeterRegistry meterRegistry,
      @Value("${artha.ws.flush-hz:20}") int flushHz) {
    this.hub = hub;
    this.flushIntervalMs = Math.max(50, 1000 / Math.max(1, flushHz));
    this.fanoutLatency = meterRegistry.timer("ay_ws_fanout_latency_seconds");
    meterRegistry.gauge("ay_ws_sessions", openSessions);
  }

  /** The resolved flush interval — exposed for the configuration-binding test. */
  long flushIntervalMs() {
    return flushIntervalMs;
  }

  /** One message captured off Redis, waiting for the next flush. */
  private record Pending(String destination, String subscriptionId, String body, long receivedNanos) {}

  private static final class SessionState {
    final Map<String, Disposable> subscriptions = new ConcurrentHashMap<>();
    final Map<String, String> destinationBySub = new ConcurrentHashMap<>();
    final Map<String, Pending> conflated = new ConcurrentHashMap<>();
    final ConcurrentLinkedQueue<Pending> passthrough = new ConcurrentLinkedQueue<>();
    final AtomicLong messageIds = new AtomicLong();

    void dispose() {
      subscriptions.values().forEach(Disposable::dispose);
      subscriptions.clear();
      conflated.clear();
      passthrough.clear();
    }
  }

  @Override
  public Mono<Void> handle(WebSocketSession session) {
    SessionState state = new SessionState();
    Sinks.Many<String> control = Sinks.many().unicast().onBackpressureBuffer();
    openSessions.incrementAndGet();

    Mono<Void> inbound =
        session
            .receive()
            .map(message -> message.getPayloadAsText())
            .doOnNext(raw -> onFrame(raw, state, control))
            .then();

    Flux<String> flusher =
        Flux.interval(Duration.ofMillis(flushIntervalMs), Duration.ofMillis(flushIntervalMs))
            .flatMapIterable(tick -> drain(state));

    Flux<String> heartbeats = Flux.interval(HEARTBEAT, HEARTBEAT).map(tick -> "\n");

    Mono<Void> outbound =
        session.send(
            Flux.merge(control.asFlux(), flusher, heartbeats).map(session::textMessage));

    return Mono.when(inbound, outbound)
        .doFinally(
            signal -> {
              state.dispose();
              openSessions.decrementAndGet();
            });
  }

  private void onFrame(String raw, SessionState state, Sinks.Many<String> control) {
    if (StompFrame.isHeartbeat(raw)) {
      return;
    }
    StompFrame frame = StompFrame.parse(raw);
    switch (frame.command()) {
      case "CONNECT", "STOMP" ->
          control.tryEmitNext(
              StompFrame.of(
                      "CONNECTED",
                      Map.of("version", "1.2", "heart-beat", "10000,10000"),
                      "")
                  .serialize());
      case "SUBSCRIBE" -> subscribe(frame, state, control);
      case "UNSUBSCRIBE" -> unsubscribe(frame.headers().get("id"), state);
      case "DISCONNECT" -> {
        String receipt = frame.headers().get("receipt");
        if (receipt != null) {
          control.tryEmitNext(
              StompFrame.of("RECEIPT", Map.of("receipt-id", receipt), "").serialize());
        }
        control.tryEmitComplete();
      }
      default ->
          control.tryEmitNext(
              StompFrame.of("ERROR", Map.of("message", "unsupported command"), frame.command())
                  .serialize());
    }
  }

  private void subscribe(StompFrame frame, SessionState state, Sinks.Many<String> control) {
    String subscriptionId = frame.headers().get("id");
    String destination = frame.headers().get("destination");
    String channel = channelFor(destination);
    if (subscriptionId == null || channel == null) {
      control.tryEmitNext(
          StompFrame.of(
                  "ERROR",
                  Map.of("message", "invalid subscription"),
                  "destination must be /topic/{ticks.*|candles.1m.*|signals|options.chain|jobs/*|system}")
              .serialize());
      return;
    }
    boolean conflatable = channel.startsWith("ticks.");
    Disposable disposable =
        hub.messages(channel)
            .subscribe(
                body -> {
                  Pending pending =
                      new Pending(destination, subscriptionId, body, System.nanoTime());
                  if (conflatable) {
                    state.conflated.put(destination, pending); // latest value wins (A.7.2)
                  } else {
                    state.passthrough.add(pending);
                  }
                },
                error -> log.warn("redis subscription error on {}", channel, error));
    Disposable previous = state.subscriptions.put(subscriptionId, disposable);
    state.destinationBySub.put(subscriptionId, destination);
    if (previous != null) {
      previous.dispose();
    }
  }

  private void unsubscribe(String subscriptionId, SessionState state) {
    if (subscriptionId == null) {
      return;
    }
    Disposable disposable = state.subscriptions.remove(subscriptionId);
    if (disposable != null) {
      disposable.dispose();
    }
    String destination = state.destinationBySub.remove(subscriptionId);
    if (destination != null) {
      state.conflated.remove(destination);
    }
  }

  private List<String> drain(SessionState state) {
    List<Pending> batch = new ArrayList<>();
    var conflatedIterator = state.conflated.values().iterator();
    while (conflatedIterator.hasNext()) {
      batch.add(conflatedIterator.next());
      conflatedIterator.remove();
    }
    Pending queued;
    while ((queued = state.passthrough.poll()) != null) {
      batch.add(queued);
    }
    if (batch.isEmpty()) {
      return List.of();
    }
    List<String> frames = new ArrayList<>(batch.size());
    long now = System.nanoTime();
    for (Pending pending : batch) {
      fanoutLatency.record(now - pending.receivedNanos(), TimeUnit.NANOSECONDS);
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("destination", pending.destination());
      headers.put("subscription", pending.subscriptionId());
      headers.put("message-id", "m-" + state.messageIds.incrementAndGet());
      headers.put("content-type", "application/json");
      frames.add(StompFrame.of("MESSAGE", headers, pending.body()).serialize());
    }
    return frames;
  }

  /**
   * Mechanical destination→channel map (A.7b): {@code /topic/X → X}; the {@code /topic/jobs/{id}}
   * form maps onto the {@code jobs.progress} bus channel (per-job filtering arrives with the jobs
   * spine, Stage D). Unknown forms are rejected.
   */
  static String channelFor(String destination) {
    if (destination == null || !destination.startsWith("/topic/")) {
      return null;
    }
    String channel = destination.substring("/topic/".length());
    if (channel.startsWith("jobs/")) {
      return "jobs.progress";
    }
    boolean allowed =
        channel.startsWith("ticks.")
            || channel.startsWith("candles.1m.")
            || channel.equals("signals")
            || channel.equals("options.chain")
            || channel.equals("jobs.progress")
            || channel.equals("system");
    return allowed ? channel : null;
  }
}
