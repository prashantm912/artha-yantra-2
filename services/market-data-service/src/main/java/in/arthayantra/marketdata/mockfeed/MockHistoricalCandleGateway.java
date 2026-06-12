package in.arthayantra.marketdata.mockfeed;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.candles.TradingBuckets;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic mock history (B-11): bucket-keyed synthesis — the same (symbol, interval, bucket)
 * always yields the same bar, so overlapping fetches are idempotent and two runs are
 * byte-identical. Carries the Phase-16A synthetic corporate-action scenario: when active, the
 * designated symbol's history is served back-adjusted by the planted ratio for buckets before the
 * event boundary — exactly how Kite re-serves a split.
 */
public class MockHistoricalCandleGateway implements HistoricalCandleGateway {

  private final TradingBuckets tradingBuckets;
  private final long seed;
  private final String corporateActionSymbol;
  private final BigDecimal corporateActionRatio;
  private final LocalDate corporateActionBoundary;
  private final AtomicBoolean corporateActionActive;

  /** Wires the synthesizer + CA scenario knobs. */
  public MockHistoricalCandleGateway(
      TradingBuckets tradingBuckets,
      long seed,
      String corporateActionSymbol,
      BigDecimal corporateActionRatio,
      LocalDate corporateActionBoundary,
      boolean corporateActionActive) {
    this.tradingBuckets = tradingBuckets;
    this.seed = seed;
    this.corporateActionSymbol = corporateActionSymbol;
    this.corporateActionRatio = corporateActionRatio;
    this.corporateActionBoundary = corporateActionBoundary;
    this.corporateActionActive = new AtomicBoolean(corporateActionActive);
  }

  /** Toggles the planted-split scenario (Phase 16A IT + demo). */
  public void setCorporateActionActive(boolean active) {
    corporateActionActive.set(active);
  }

  @Override
  public List<Candle> fetch(InstrumentKey key, String interval, Instant from, Instant to) {
    List<OffsetDateTime> buckets =
        interval.equals("1d")
            ? tradingBuckets.dayBuckets(from.atOffset(Ist.OFFSET), to.atOffset(Ist.OFFSET))
            : tradingBuckets.minuteBuckets(from.atOffset(Ist.OFFSET), to.atOffset(Ist.OFFSET));
    List<Candle> out = new ArrayList<>(buckets.size());
    for (OffsetDateTime bucket : buckets) {
      out.add(synthesize(key, interval, bucket));
    }
    return out;
  }

  private Candle synthesize(InstrumentKey key, String interval, OffsetDateTime bucket) {
    long symbolSeed = seed * 31 + key.canonical().hashCode();
    long bucketEpoch = bucket.toEpochSecond();
    // deterministic pseudo-random values keyed (symbol, bucket)
    double w1 = unit(symbolSeed, bucketEpoch, 1);
    double w2 = unit(symbolSeed, bucketEpoch, 2);
    double w3 = unit(symbolSeed, bucketEpoch, 3);

    BigDecimal base =
        MockTickGenerator.basePrice(Math.floorMod(key.canonical().hashCode(), 1_000_000));
    // a slow deterministic daily drift + intra-bucket spread
    double drift = 1.0 + 0.0001 * (bucketEpoch % 86_400) / 86_400.0 + 0.02 * (w1 - 0.5);
    BigDecimal open = snap(base.multiply(BigDecimal.valueOf(drift)));
    BigDecimal close = snap(open.multiply(BigDecimal.valueOf(1.0 + 0.01 * (w2 - 0.5))));
    BigDecimal high = open.max(close).add(snap(base.multiply(BigDecimal.valueOf(0.002 * w3))));
    BigDecimal low =
        open.min(close).subtract(snap(base.multiply(BigDecimal.valueOf(0.002 * (1 - w3)))));
    long volume = 100 + (long) (w1 * 10_000);
    Long oi = key.exchange().equals("NFO") ? 50_000 + (long) (w2 * 10_000) : null;

    BigDecimal factor = adjustmentFactor(key, bucket);
    return new Candle(
        key, interval, bucket,
        scale(open.multiply(factor)), scale(high.multiply(factor)),
        scale(low.multiply(factor)), scale(close.multiply(factor)),
        volume, oi);
  }

  private BigDecimal adjustmentFactor(InstrumentKey key, OffsetDateTime bucket) {
    if (corporateActionActive.get()
        && key.tradingsymbol().equals(corporateActionSymbol)
        && bucket.withOffsetSameInstant(Ist.OFFSET).toLocalDate().isBefore(corporateActionBoundary)) {
      return corporateActionRatio; // back-adjusted pre-event history, Kite-style
    }
    return BigDecimal.ONE;
  }

  private static double unit(long symbolSeed, long bucketEpoch, int salt) {
    long h = symbolSeed ^ (bucketEpoch * 0x9E3779B97F4A7C15L) ^ (salt * 0xC2B2AE3D27D4EB4FL);
    h ^= h >>> 33;
    h *= 0xFF51AFD7ED558CCDL;
    h ^= h >>> 33;
    return (h >>> 11) / (double) (1L << 53);
  }

  private static BigDecimal snap(BigDecimal price) {
    return price.setScale(2, RoundingMode.HALF_UP).max(new BigDecimal("0.05"));
  }

  private static BigDecimal scale(BigDecimal price) {
    return price.setScale(4, RoundingMode.HALF_UP);
  }
}
