package in.arthayantra.marketdata.upstox;

import in.arthayantra.marketdata.upstox.ws.DecodedFeed;
import in.arthayantra.marketdata.upstox.ws.FeedFrameDecoder;
import in.arthayantra.marketdata.upstox.ws.NvUpstoxWebSocket;
import in.arthayantra.marketdata.upstox.ws.UpstoxReconnectPolicy;
import in.arthayantra.marketdata.upstox.ws.UpstoxSubscriptionMessage;
import in.arthayantra.marketdata.upstox.ws.UpstoxWebSocket;
import in.arthayantra.marketdata.upstox.wire.UpstoxFeedAuthorize;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Direct-Upstox LIVE v3 market-data WebSocket feed client (Wave U3) — the exposed {@code upstox}
 * module API for real-time ticks, on the long-lived 1-yr analytics token (proven login-free
 * 2026-06-24: {@code GET /v3/feed/market-data-feed/authorize} returns a one-time {@code
 * authorized_redirect_uri} wss URL). Domain-free by construction (the same anti-corruption boundary
 * as {@link UpstoxQuoteClient}): it speaks only Upstox {@code instrument_key} strings and emits its
 * OWN top-level {@link Tick} record; the {@code options}-module adapter maps {@link Tick} to the
 * {@code kite} domain {@code RawTick}, so neither this client nor the sink couples to the other.
 *
 * <p>Owns the reconnect/backoff supervision ({@link UpstoxReconnectPolicy}) the SDK does NOT provide:
 * on a drop it backs off, RE-FETCHES the authorize URL (a reconnect after the ~3 AM daily rollover
 * must sign with the live token, mirroring the OpenAlgo reference), rebuilds the socket, and fires the
 * {@link #onConnected} callback again so the caller (the {@code LiveTickerFeed} supervisor) RE-PLAYS
 * the subscription set + schedules a gap-heal — exactly as the Kite SDK fires {@code onConnected} on
 * first connect AND every reconnect. The subscription set is owned by the supervisor / {@code
 * SubscriptionRegistry} (single source of truth), so this client holds NO desired-set of its own. The
 * WS transport ({@code nv-websocket-client}) and protobuf decode ({@code upstox.ws}) are seams so the
 * supervisor is unit-tested with no real socket.
 */
public final class UpstoxMarketFeedClient {

  /** A blockable sleep — seam so tests drive the backoff loop without wall-clock waits. */
  @FunctionalInterface
  public interface Sleeper {
    void sleepMillis(long millis) throws InterruptedException;
  }

  /** One instrument's mapped tick (Upstox {@code instrument_key} → values); greeks/depth dropped. */
  public record Tick(
      String instrumentKey,
      double lastPrice,
      double closePrice,
      long lastTradeTimeMillis,
      Long volume,
      Long openInterest) {}

  private static final Logger log = LoggerFactory.getLogger(UpstoxMarketFeedClient.class);

  private final Supplier<String> authorizeUrlSupplier;
  private final Function<String, UpstoxWebSocket> socketFactory;
  private final UpstoxReconnectPolicy reconnectPolicy;
  private final Sleeper sleeper;

  private final AtomicBoolean running = new AtomicBoolean();
  private volatile UpstoxWebSocket socket;
  private volatile Consumer<List<Tick>> ticksSink = t -> {};
  private volatile Runnable onConnected = () -> {};
  private Thread supervisor;

  /** Live ctor: authorize via the analytics token + the real nv-websocket transport. */
  public UpstoxMarketFeedClient(
      RestClient.Builder builder, UpstoxAnalyticsProperties properties, UpstoxRateLimiter limiter) {
    this(
        authorizeSupplier(builder, properties, limiter),
        NvUpstoxWebSocket::new,
        new UpstoxReconnectPolicy(),
        Thread::sleep);
  }

  /** Test ctor: inject the authorize URL, socket factory, policy, and sleeper (no real socket). */
  public UpstoxMarketFeedClient(
      Supplier<String> authorizeUrlSupplier,
      Function<String, UpstoxWebSocket> socketFactory,
      UpstoxReconnectPolicy reconnectPolicy,
      Sleeper sleeper) {
    this.authorizeUrlSupplier = authorizeUrlSupplier;
    this.socketFactory = socketFactory;
    this.reconnectPolicy = reconnectPolicy;
    this.sleeper = sleeper;
  }

  /** Registers the tick sink (the adapter maps these to {@code RawTick}). */
  public void onTicks(Consumer<List<Tick>> sink) {
    this.ticksSink = sink;
  }

  /** Fires after EVERY successful socket open (first connect + each reconnect) — the replay trigger. */
  public void onConnected(Runnable callback) {
    this.onConnected = callback;
  }

  /** Opens the socket and starts reconnect supervision. Idempotent. */
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    reconnectPolicy.onConnected(); // fresh storm budget
    openSocket();
  }

  /** Stops supervision and closes the socket. Idempotent. */
  public synchronized void stop() {
    running.set(false);
    UpstoxWebSocket current = socket;
    if (current != null) {
      current.close();
    }
    socket = null;
    if (supervisor != null) {
      supervisor.interrupt();
      supervisor = null;
    }
  }

  /** Whether the socket is currently open. */
  public boolean isOpen() {
    UpstoxWebSocket current = socket;
    return current != null && current.isOpen();
  }

  /** Subscribes {@code keys} in {@code mode} if connected (the supervisor owns WHICH keys). */
  public void subscribe(List<String> keys, String mode) {
    UpstoxWebSocket current = socket;
    if (current != null && current.isOpen() && !keys.isEmpty()) {
      current.sendBinary(
          UpstoxSubscriptionMessage.subscribe(UpstoxSubscriptionMessage.newGuid(), mode, keys));
    }
  }

  /** Unsubscribes {@code keys} if connected. */
  public void unsubscribe(List<String> keys) {
    UpstoxWebSocket current = socket;
    if (current != null && current.isOpen() && !keys.isEmpty()) {
      current.sendBinary(
          UpstoxSubscriptionMessage.unsubscribe(UpstoxSubscriptionMessage.newGuid(), keys));
    }
  }

  /**
   * Builds + wires + connects a fresh socket, then notifies the caller to replay subscriptions.
   * {@code synchronized} so the {@code socket}-field write is serialized with {@link #stop()} (a
   * reconnect that loses the race to a {@code stop()} sees {@code running=false} and bails BEFORE
   * opening, so {@code stop()} can never be undone by an in-flight reconnect). Reentrant from {@link
   * #start()} (same thread). The {@code onConnected} callback runs while holding the lock, but the
   * replay it triggers ({@code subscribe}) is NOT synchronized, so there is no deadlock.
   */
  private synchronized void openSocket() {
    if (!running.get()) {
      return; // a stop() won the lock — do not resurrect the feed
    }
    String wssUrl = authorizeUrlSupplier.get();
    UpstoxWebSocket ws = socketFactory.apply(wssUrl);
    ws.onBinaryMessage(this::onFrame);
    ws.onError(message -> log.warn("upstox ws error: {}", message));
    ws.onDisconnected(this::onDisconnected);
    this.socket = ws;
    ws.connect();
    reconnectPolicy.onConnected();
    onConnected.run(); // the supervisor replays the registry against the freshly-open socket
    log.info("upstox ws connected");
  }

  /** Decodes one protobuf frame and delivers the mapped ticks. */
  private void onFrame(byte[] frame) {
    DecodedFeed decoded;
    try {
      decoded = FeedFrameDecoder.decode(frame);
    } catch (RuntimeException malformed) {
      log.warn("dropping malformed upstox frame: {}", malformed.getMessage());
      return;
    }
    if (decoded.feeds().isEmpty()) {
      return;
    }
    List<Tick> ticks = new ArrayList<>(decoded.feeds().size());
    for (Map.Entry<String, DecodedFeed.Tick> entry : decoded.feeds().entrySet()) {
      DecodedFeed.Tick t = entry.getValue();
      ticks.add(
          new Tick(
              entry.getKey(),
              t.lastPrice(),
              t.closePrice(),
              t.lastTradeTimeMillis(),
              t.volume(),
              t.openInterest()));
    }
    ticksSink.accept(ticks);
  }

  /** Socket dropped: if still running, spin a backoff-reconnect supervisor. */
  private synchronized void onDisconnected() {
    if (!running.get()) {
      return;
    }
    log.warn("upstox ws disconnected — scheduling reconnect");
    if (supervisor != null && supervisor.isAlive()) {
      return; // a reconnect loop is already in flight
    }
    supervisor = new Thread(this::reconnectLoop, "upstox-ws-reconnect");
    supervisor.setDaemon(true);
    supervisor.start();
  }

  /** Backoff-reconnect until a socket opens or the per-storm budget is exhausted. */
  void reconnectLoop() {
    while (running.get() && !isOpen()) {
      long delay = reconnectPolicy.nextDelayMillis();
      if (reconnectPolicy.exhausted()) {
        log.error("upstox ws reconnect budget exhausted — feed down until restart");
        return;
      }
      try {
        sleeper.sleepMillis(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      try {
        openSocket();
      } catch (RuntimeException retry) {
        log.warn(
            "upstox ws reconnect attempt {} failed: {}",
            reconnectPolicy.attempts(),
            retry.getMessage());
      }
    }
  }

  /**
   * The live authorize-GET: analytics bearer → {@code data.authorized_redirect_uri} wss URL. Debits
   * the ONE shared token budget (EXT-02) on the live path — a BOUNDED wait so a saturated budget
   * throws (the reconnect loop retries with backoff) rather than parking the connect thread ~30 min.
   * Package-private so the shared-budget test can drive it without opening a real socket.
   */
  static Supplier<String> authorizeSupplier(
      RestClient.Builder builder, UpstoxAnalyticsProperties properties, UpstoxRateLimiter limiter) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(15_000);
    factory.setReadTimeout(15_000);
    RestClient client = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    return () -> {
      if (!limiter.tryAcquire()) {
        throw new IllegalStateException("Upstox rate budget exhausted");
      }
      UpstoxFeedAuthorize response =
          client
              .get()
              .uri("/v3/feed/market-data-feed/authorize")
              .header("Authorization", "Bearer " + properties.resolveToken())
              .header("Accept", "application/json")
              .retrieve()
              .body(UpstoxFeedAuthorize.class);
      if (response == null
          || response.data() == null
          || response.data().authorizedRedirectUri() == null) {
        throw new IllegalStateException("Upstox feed authorize returned no authorized_redirect_uri");
      }
      return response.data().authorizedRedirectUri();
    };
  }
}
