package in.arthayantra.marketdata.kite;

/**
 * Where a {@link MarketFeed} delivers raw ticks. Feed callback threads only ever ENQUEUE through
 * this seam (the bounded ingress queue implements it, A.7.1) — never evaluate work inline (the v1
 * anti-pattern ran signals synchronously on the WS callback).
 */
public interface TickListener {

  /** Accepts one raw tick; must be cheap and non-blocking. */
  void onTick(RawTick tick);
}
