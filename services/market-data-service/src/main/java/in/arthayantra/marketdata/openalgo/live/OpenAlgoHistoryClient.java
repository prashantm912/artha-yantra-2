package in.arthayantra.marketdata.openalgo.live;

import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway.Candle;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.OiHistorySource;
import java.time.Instant;
import java.util.List;

/**
 * OpenAlgo adapter for the {@link OiHistorySource} backfill port (data-foundation milestone, plan §2).
 * Thin wrapper over an {@link OpenAlgoHistoricalCandleGateway} pinned to the {@code 1m} interval — it
 * reuses that gateway's wire client (POST {@code /history}, per-bar OI in the response) but exposes the
 * backfill's OWN port type, so it never competes in the routed {@code HistoricalCandleGateway} bean
 * pool. Built unconditionally (not behind {@code source.candles}); gated only by the live profile +
 * {@code artha.openalgo.oi-backfill-enabled} in {@link OpenAlgoConfig}.
 */
public final class OpenAlgoHistoryClient implements OiHistorySource {

  private final HistoricalCandleGateway delegate;

  /** Wraps an OpenAlgo {@code /history} gateway dedicated to the backfill path. */
  public OpenAlgoHistoryClient(HistoricalCandleGateway delegate) {
    this.delegate = delegate;
  }

  @Override
  public List<Candle> oneMinute(InstrumentKey key, Instant from, Instant to) {
    return delegate.fetch(key, "1m", from, to);
  }
}
