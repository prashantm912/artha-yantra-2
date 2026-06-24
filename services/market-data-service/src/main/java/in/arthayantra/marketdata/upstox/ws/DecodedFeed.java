package in.arthayantra.marketdata.upstox.ws;

import java.util.Map;

/**
 * The domain-free decode of one Upstox v3 {@code FeedResponse} protobuf frame (Wave U3) — a map of
 * {@code instrument_key → }{@link Tick} plus the server timestamp. Internal to the {@code upstox.ws}
 * sub-package; {@link in.arthayantra.marketdata.upstox.UpstoxMarketFeedClient} maps it to its
 * exposed tick record before anything outside the module sees it (anti-corruption: the wire shape
 * never leaks).
 */
public record DecodedFeed(Map<String, Tick> feeds, long serverTimestampMillis) {

  /**
   * One instrument's decoded values. {@code volume} / {@code openInterest} are {@code null} when the
   * frame did not carry them (e.g. an index, or an {@code ltpc}-mode tick) — the caller treats a null
   * as "unchanged". {@code lastTradeTimeMillis} is Upstox's {@code ltt} epoch-ms; {@code 0} when
   * absent (the caller falls back to the frame/server time).
   *
   * @param lastPrice last traded price ({@code ltpc.ltp})
   * @param closePrice previous-day close ({@code ltpc.cp}) — kept for parity/diagnostics, not yet sunk
   * @param lastTradeTimeMillis Upstox {@code ltpc.ltt} epoch-ms (0 when absent)
   * @param volume cumulative day volume ({@code marketFF.vtt}); {@code null} when absent
   * @param openInterest open interest ({@code marketFF.oi}); {@code null} when absent
   */
  public record Tick(
      double lastPrice,
      double closePrice,
      long lastTradeTimeMillis,
      Long volume,
      Long openInterest) {}
}
