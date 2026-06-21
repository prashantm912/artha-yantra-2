package in.arthayantra.marketdata.kite;

import in.arthayantra.marketdata.kite.HistoricalCandleGateway.Candle;
import java.time.Instant;
import java.util.List;

/**
 * Dedicated 1-minute OHLC+OI history fetch for the OI-backfill importer (data-foundation milestone,
 * plan §2 / build-task #2). A SEPARATE port from {@link HistoricalCandleGateway} on purpose: the
 * backfill must reach OpenAlgo's {@code /history} (Upstox-backed, per-bar OI for active contracts)
 * UNCONDITIONALLY — independent of the {@code artha.marketdata.source.candles} routing flag (which we
 * keep on Kite). Its own type also keeps the backfill bean out of the {@code HistoricalCandleGateway}
 * bean pool, so the routed/Kite candle gateways stay the unambiguous primary for the live candle path.
 *
 * <p>The single live implementation (OpenAlgo) is wired only when {@code
 * artha.openalgo.oi-backfill-enabled=true} on the live profile; absent otherwise (the backfill
 * endpoint then 503s as unconfigured).
 */
public interface OiHistorySource {

  /** Active-contract 1m OHLC+OI for {@code key} within {@code [from, to)}; empty when none. */
  List<Candle> oneMinute(InstrumentKey key, Instant from, Instant to);
}
