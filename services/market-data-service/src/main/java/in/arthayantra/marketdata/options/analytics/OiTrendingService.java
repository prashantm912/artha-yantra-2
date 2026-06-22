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

  /**
   * One bucket's aggregates. {@code ceLtp}/{@code peLtp} are the SUMMED CE/PE premium (option ltp) across
   * the captured chain — the Price-Action columns the "Trending OI - PA" page adds on top of the OI trend.
   */
  public record TrendPoint(
      OffsetDateTime bucket,
      long totalOi,
      long ceOi,
      long peOi,
      BigDecimal spot,
      Trend trend,
      BigDecimal ceLtp,
      BigDecimal peLtp) {}

  public record TrendSeries(List<TrendPoint> items, OffsetDateTime asOf) {}

  /** {@code series} must be bucket-ordered oldest-first (the OptionsSnapshotReader.series contract). */
  public TrendSeries trending(List<OptionsSnapshotReader.StrikePoint> series) {
    Map<OffsetDateTime, long[]> byBucket = new LinkedHashMap<>(); // [ceOi, peOi]
    Map<OffsetDateTime, BigDecimal[]> ltpByBucket = new LinkedHashMap<>(); // [ceLtp, peLtp] summed premium
    Map<OffsetDateTime, BigDecimal> spotByBucket = new LinkedHashMap<>(); // underlying LTP per bucket
    for (OptionsSnapshotReader.StrikePoint p : series) {
      long[] v = byBucket.computeIfAbsent(p.bucket(), k -> new long[2]);
      BigDecimal[] lv =
          ltpByBucket.computeIfAbsent(p.bucket(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      long oi = p.oi() == null ? 0 : p.oi();
      BigDecimal ltp = p.ltp() == null ? BigDecimal.ZERO : p.ltp();
      if ("CE".equals(p.optionType())) {
        v[0] += oi;
        lv[0] = lv[0].add(ltp);
      } else {
        v[1] += oi;
        lv[1] = lv[1].add(ltp);
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
      BigDecimal[] lv = ltpByBucket.get(e.getKey());
      items.add(
          new TrendPoint(
              e.getKey(), total, ce, pe, spotByBucket.get(e.getKey()), trend, lv[0], lv[1]));
      prevTotal = total;
      first = false;
    }
    OffsetDateTime asOf = items.isEmpty() ? null : items.get(items.size() - 1).bucket();
    return new TrendSeries(items, asOf);
  }
}
