package in.arthayantra.marketdata.options.analytics;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * oipulse "OI Trending": per-bucket total / CE / PE OI across the interval series, each tagged
 * UP/DOWN/FLAT versus the prior bucket's total OI (the first bucket is FLAT — no prior).
 */
@Service
public class OiTrendingService {

  public enum Trend {
    UP,
    DOWN,
    FLAT
  }

  public record TrendPoint(
      OffsetDateTime bucket, long totalOi, long ceOi, long peOi, BigDecimal spot, Trend trend) {}

  public record TrendSeries(List<TrendPoint> items, OffsetDateTime asOf) {}

  /** {@code series} must be bucket-ordered oldest-first (the OptionsSnapshotReader.series contract). */
  public TrendSeries trending(List<OptionsSnapshotReader.StrikePoint> series) {
    Map<OffsetDateTime, long[]> byBucket = new LinkedHashMap<>(); // [ceOi, peOi]
    Map<OffsetDateTime, BigDecimal> spotByBucket = new LinkedHashMap<>(); // underlying LTP per bucket
    for (OptionsSnapshotReader.StrikePoint p : series) {
      long[] v = byBucket.computeIfAbsent(p.bucket(), k -> new long[2]);
      long oi = p.oi() == null ? 0 : p.oi();
      if ("CE".equals(p.optionType())) {
        v[0] += oi;
      } else {
        v[1] += oi;
      }
      if (p.spot() != null) {
        spotByBucket.putIfAbsent(p.bucket(), p.spot());
      }
    }
    List<TrendPoint> items = new ArrayList<>();
    boolean first = true;
    long prevTotal = 0;
    for (Map.Entry<OffsetDateTime, long[]> e : byBucket.entrySet()) {
      long ce = e.getValue()[0];
      long pe = e.getValue()[1];
      long total = ce + pe;
      Trend trend;
      if (first) {
        trend = Trend.FLAT;
      } else if (total > prevTotal) {
        trend = Trend.UP;
      } else if (total < prevTotal) {
        trend = Trend.DOWN;
      } else {
        trend = Trend.FLAT;
      }
      items.add(new TrendPoint(e.getKey(), total, ce, pe, spotByBucket.get(e.getKey()), trend));
      prevTotal = total;
      first = false;
    }
    OffsetDateTime asOf = items.isEmpty() ? null : items.get(items.size() - 1).bucket();
    return new TrendSeries(items, asOf);
  }
}
