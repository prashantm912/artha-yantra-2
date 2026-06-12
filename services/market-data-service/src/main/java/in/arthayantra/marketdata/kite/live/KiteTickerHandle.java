package in.arthayantra.marketdata.kite.live;

import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import in.arthayantra.marketdata.kite.RawTick;
import in.arthayantra.marketdata.kite.ticker.SubscriptionMode;
import in.arthayantra.marketdata.kite.ticker.TickerHandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The javakiteconnect 3.5.x wrapper — the ONLY class touching the SDK's WS surface. The
 * token-first constructor quirk is preserved: {@code new KiteTicker(accessToken, apiKey)} takes
 * the ACCESS TOKEN first (B-6). SDK auto-reconnect stays on; our supervisor replays the registry
 * on every onConnected. Untested by design (thin glue) — all supervision logic lives in
 * {@code LiveTickerFeed} against the {@code TickerHandle} seam.
 */
public class KiteTickerHandle implements TickerHandle {

  private final KiteTicker ticker;

  /** Token-first ctor quirk preserved (B-6). */
  public KiteTickerHandle(String accessToken, String apiKey) {
    this.ticker = new KiteTicker(accessToken, apiKey);
    ticker.setTryReconnection(true);
    try {
      ticker.setMaximumRetries(50);
      ticker.setMaximumRetryInterval(60);
    } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException e) {
      throw new IllegalStateException("kite ticker retry configuration failed", e);
    }
  }

  @Override
  public void connect() {
    ticker.connect();
  }

  @Override
  public void disconnect() {
    ticker.disconnect();
  }

  @Override
  public boolean connected() {
    return ticker.isConnectionOpen();
  }

  @Override
  public void subscribe(List<Long> tokens, SubscriptionMode mode) {
    ArrayList<Long> list = new ArrayList<>(tokens);
    ticker.subscribe(list);
    ticker.setMode(list, mode.wireName());
  }

  @Override
  public void unsubscribe(List<Long> tokens) {
    ticker.unsubscribe(new ArrayList<>(tokens));
  }

  @Override
  public void onConnected(Runnable callback) {
    ticker.setOnConnectedListener(callback::run);
  }

  @Override
  public void onDisconnected(Runnable callback) {
    ticker.setOnDisconnectedListener(callback::run);
  }

  @Override
  public void onTicks(Consumer<List<RawTick>> callback) {
    ticker.setOnTickerArrivalListener(
        sdkTicks -> {
          List<RawTick> raw = new ArrayList<>(sdkTicks.size());
          for (Tick tick : sdkTicks) {
            raw.add(toRawTick(tick));
          }
          callback.accept(raw);
        });
  }

  @Override
  public void onError(Consumer<String> callback) {
    ticker.setOnErrorListener(
        new com.zerodhatech.ticker.OnError() {
          @Override
          public void onError(Exception exception) {
            callback.accept(exception.getMessage());
          }

          @Override
          public void onError(com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException kiteException) {
            callback.accept(kiteException.message);
          }

          @Override
          public void onError(String error) {
            callback.accept(error);
          }
        });
  }

  private static RawTick toRawTick(Tick tick) {
    Instant ts =
        tick.getTickTimestamp() != null
            ? tick.getTickTimestamp().toInstant()
            : Instant.now();
    Long oi = tick.getOi() > 0 ? (long) tick.getOi() : null;
    return new RawTick(
        tick.getInstrumentToken(),
        BigDecimal.valueOf(tick.getLastTradedPrice()).setScale(4, RoundingMode.HALF_UP),
        tick.getVolumeTradedToday(),
        oi,
        ts);
  }
}
