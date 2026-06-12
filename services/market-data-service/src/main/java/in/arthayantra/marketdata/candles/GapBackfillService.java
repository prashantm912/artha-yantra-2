package in.arthayantra.marketdata.candles;

import in.arthayantra.marketdata.kite.GapBackfiller;
import in.arthayantra.marketdata.kite.InstrumentKey;
import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Candles-side implementation of the kite gap-backfill seam (B-6): silent-through-reconnect
 * instruments get their 1m bars re-fetched through the rate-limited gateway with
 * {@code source='BACKFILL'} so analytics can tell replayed bars from streamed ones.
 */
@Component
public class GapBackfillService implements GapBackfiller {

  private static final Logger log = LoggerFactory.getLogger(GapBackfillService.class);

  private final CandleQueryService queryService;

  /** Wires the cache-first fetch machinery. */
  public GapBackfillService(CandleQueryService queryService) {
    this.queryService = queryService;
  }

  @Override
  public void backfill(InstrumentKey key, Instant from, Instant to) {
    try {
      queryService.backfillRange(
          key.exchange(),
          key.tradingsymbol(),
          from.atOffset(ZoneOffset.UTC),
          to.atOffset(ZoneOffset.UTC));
    } catch (Exception e) {
      log.warn("gap backfill failed for {}: {}", key.canonical(), e.getMessage());
    }
  }
}
