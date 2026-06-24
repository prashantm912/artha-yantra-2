package in.arthayantra.marketdata.options;

import in.arthayantra.marketdata.constituents.StockUpstoxKeyMap;
import in.arthayantra.marketdata.instruments.InstrumentRegistry;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.RawTick;
import in.arthayantra.marketdata.kite.UpstoxLiveTickFeed;
import in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient;
import in.arthayantra.marketdata.upstox.UpstoxMarketFeedClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code options}-module implementation of the {@code kite}-declared {@link UpstoxLiveTickFeed} port
 * (Wave U3) — the bridge that wraps the direct-Upstox v3 WS client and does ALL the Kite-token ↔
 * Upstox-{@code instrument_key} translation, so the {@code kite.upstoxfeed} {@code TickerHandle} adapter
 * (driving the existing {@code LiveTickerFeed} supervisor) stays a thin domain-only delegate.
 *
 * <p>Bound ONLY when {@code artha.marketdata.source.ticker=upstox} (the live Upstox WS feed is wired);
 * a default (Kite-ticker) stack carries no extra bean. Lives in {@code options} — NOT {@code kite} —
 * because it depends on {@link UpstoxMarketFeedClient} ({@code upstox}), and {@code upstox} already
 * reaches {@code kite} transitively ({@code upstox → candles → … → kite}); placing this in {@code kite}
 * would close that Modulith cycle. {@code options} already depends on {@code upstox} + {@code
 * instruments} + {@code constituents} + {@code kite}, so it adds no new module edge.
 *
 * <p>Token→Upstox-key uses the SAME verified map as the U2 quote path ({@link
 * UpstoxQuoteInstrumentKeys}: indices + NSE cash equities; no map duplicated). When the Upstox F&amp;O
 * instrument master ({@link UpstoxFnoMasterClient}) is bound, FUT/option tokens now RESOLVE to their
 * {@code NSE_FO|<token>} / {@code BSE_FO|<token>} key too (the Wave-U4 enabler — the full option-strike
 * scalp A/B); a token still without a derivable Upstox key is logged and skipped (the Kite path stays
 * its source). Anti-corruption: it consumes the exposed {@link UpstoxMarketFeedClient.Tick}, never the
 * {@code upstox.ws} protobuf types.
 */
@Component
@Profile("live")
@ConditionalOnProperty(name = "artha.marketdata.source.ticker", havingValue = "upstox")
public class UpstoxLiveTickFeedAdapter implements UpstoxLiveTickFeed {

  /** Upstox v3 subscribe mode that yields LTP + volume(vtt) + OI — the minimal mode the sink needs. */
  static final String UPSTOX_MODE_FULL = "full";

  private static final Logger log = LoggerFactory.getLogger(UpstoxLiveTickFeedAdapter.class);

  private final UpstoxMarketFeedClient client;
  private final InstrumentRegistry instruments;
  private final UpstoxQuoteInstrumentKeys instrumentKeys;
  private final Timer tickLatency;
  private final Counter ticksReceived;

  // Bi-directional, populated as tokens are subscribed: Kite token ↔ Upstox instrument_key. Decode
  // looks up token-by-upstox-key; unsubscribe drops both directions.
  private final Map<Long, String> upstoxKeyByToken = new ConcurrentHashMap<>();
  private final Map<String, Long> tokenByUpstoxKey = new ConcurrentHashMap<>();
  private volatile Consumer<List<RawTick>> sink = t -> {};

  @Autowired
  public UpstoxLiveTickFeedAdapter(
      UpstoxMarketFeedClient client,
      InstrumentRegistry instruments,
      InstrumentRepository instrumentMaster,
      StockUpstoxKeyMap stockKeys,
      MeterRegistry meterRegistry,
      @Autowired(required = false) @Nullable UpstoxFnoMasterClient fnoMaster) {
    this.client = client;
    this.instruments = instruments;
    UpstoxFnoInstrumentKeys fnoKeys =
        fnoMaster == null ? null : new UpstoxFnoInstrumentKeys(instrumentMaster, fnoMaster);
    this.instrumentKeys = new UpstoxQuoteInstrumentKeys(stockKeys, fnoKeys);
    // §17.3 scalp-latency-gate instrumentation: tick STALENESS (receive-time − exchange ltt) so a
    // live A/B can compare Upstox-WS vs Kite-WS tick latency over ≥1 session. Counter gives the rate.
    this.tickLatency = meterRegistry.timer("ay_upstox_ws_tick_latency");
    this.ticksReceived = meterRegistry.counter("ay_upstox_ws_ticks_received_total");
    client.onTicks(this::deliver);
  }

  /** Index + equity only (the pre-U4 shape; F&amp;O stays unmapped). Used by unit tests. */
  UpstoxLiveTickFeedAdapter(
      UpstoxMarketFeedClient client,
      InstrumentRegistry instruments,
      StockUpstoxKeyMap stockKeys,
      MeterRegistry meterRegistry) {
    this(client, instruments, null, stockKeys, meterRegistry, null);
  }

  @Override
  public void start() {
    client.start();
  }

  @Override
  public void stop() {
    client.stop();
  }

  @Override
  public boolean running() {
    return client.isOpen();
  }

  @Override
  public void subscribe(List<Long> tokens) {
    List<String> keys = new ArrayList<>(tokens.size());
    for (Long token : tokens) {
      String upstoxKey = resolveUpstoxKey(token);
      if (upstoxKey == null) {
        continue;
      }
      upstoxKeyByToken.put(token, upstoxKey);
      tokenByUpstoxKey.put(upstoxKey, token);
      keys.add(upstoxKey);
    }
    if (!keys.isEmpty()) {
      client.subscribe(keys, UPSTOX_MODE_FULL);
    }
  }

  @Override
  public void unsubscribe(List<Long> tokens) {
    List<String> keys = new ArrayList<>(tokens.size());
    for (Long token : tokens) {
      String upstoxKey = upstoxKeyByToken.remove(token);
      if (upstoxKey != null) {
        tokenByUpstoxKey.remove(upstoxKey);
        keys.add(upstoxKey);
      }
    }
    if (!keys.isEmpty()) {
      client.unsubscribe(keys);
    }
  }

  @Override
  public void onTicks(Consumer<List<RawTick>> sink) {
    this.sink = sink;
  }

  @Override
  public void onConnected(Runnable callback) {
    client.onConnected(callback);
  }

  /** Token → Upstox instrument_key via the master (token→key) + the verified index/stock map. */
  private String resolveUpstoxKey(long token) {
    InstrumentKey key = instruments.keyForToken(token).orElse(null);
    if (key == null) {
      log.debug("no instrument-master entry for token {} — not streamed over upstox", token);
      return null;
    }
    String upstoxKey = instrumentKeys.key(key);
    if (upstoxKey == null) {
      log.debug("no upstox instrument_key for {} — not streamed over upstox", key.canonical());
    }
    return upstoxKey;
  }

  /** Maps the Upstox ticks back to token-keyed {@link RawTick}s and pushes them to the sink. */
  private void deliver(List<UpstoxMarketFeedClient.Tick> ticks) {
    Instant now = Instant.now();
    List<RawTick> out = new ArrayList<>(ticks.size());
    for (UpstoxMarketFeedClient.Tick tick : ticks) {
      Long token = tokenByUpstoxKey.get(tick.instrumentKey());
      if (token == null) {
        continue; // a tick for a key we no longer track (mid-unsubscribe) — drop
      }
      Instant ts =
          tick.lastTradeTimeMillis() > 0
              ? Instant.ofEpochMilli(tick.lastTradeTimeMillis())
              : now;
      recordLatency(now, tick.lastTradeTimeMillis());
      out.add(
          new RawTick(
              token,
              BigDecimal.valueOf(tick.lastPrice()).setScale(4, RoundingMode.HALF_UP),
              tick.volume() != null ? tick.volume() : 0L,
              tick.openInterest(),
              ts));
    }
    if (!out.isEmpty()) {
      sink.accept(out);
    }
  }

  /** Records tick staleness (receive − exchange ltt) for the §17.3 latency-gate A/B; skips bad ltt. */
  private void recordLatency(Instant now, long lastTradeTimeMillis) {
    ticksReceived.increment();
    if (lastTradeTimeMillis <= 0) {
      return;
    }
    long staleMillis = now.toEpochMilli() - lastTradeTimeMillis;
    if (staleMillis >= 0) { // a future ltt (clock skew) is not a meaningful latency sample
      tickLatency.record(Duration.ofMillis(staleMillis));
    }
  }
}
