package in.arthayantra.marketdata.options.analytics;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * oipulse-style OI-change heatmap (plan §20 breadth — the grid-heatmap archetype): a strike × time
 * grid of the per-bucket interval OI change, separately for Call (CE) and Put (PE) legs. Each cell is
 * the OI delta from the prior captured bucket for that (strike, leg) — i.e. how much fresh OI built
 * (positive) or unwound (negative) in that interval. Folds a single
 * {@link OptionsSnapshotReader#series} read (zero new capture), windowed to the {@code 2*window+1}
 * strikes nearest the ATM so the grid stays bounded.
 *
 * <p>The first bucket has no prior, so it never produces a cell. A cell is null (a gap the heatmap
 * renders empty, never zero — absence != no-change) when the current bucket has no snapshot for that
 * (strike, leg); a single-bucket gap is bridged by carrying the last-seen OI forward.
 */
@Service
public class OiHeatmapService {

  /** One heatmap cell: column index {@code x} (bucket), row index {@code y} (strike), {@code value} = ΔOI. */
  public record Cell(int x, int y, Long value) {}

  public record Heatmap(
      List<String> buckets,
      List<String> strikes,
      List<Cell> ce,
      List<Cell> pe,
      Long maxAbs,
      OffsetDateTime asOf) {}

  /**
   * Fold {@code series} (one row per bucket/strike/leg, oldest-first per {@link OptionsSnapshotReader})
   * into the CE+PE ΔOI grids, sliced to the {@code 2*window+1} listed strikes nearest {@code atmStrike}.
   * When {@code atmStrike} is null the lowest strikes are kept (stable, ATM-independent fallback).
   * Empty grid when the series holds no points.
   */
  public Heatmap fold(
      List<OptionsSnapshotReader.StrikePoint> series, BigDecimal atmStrike, int window) {
    if (series.isEmpty()) {
      return new Heatmap(List.of(), List.of(), List.of(), List.of(), 0L, null);
    }

    // Ordered bucket axis (x, oldest→newest) and the kept-strike axis (y, low→high).
    TreeSet<OffsetDateTime> bucketSet = new TreeSet<>();
    TreeSet<BigDecimal> strikeSet = new TreeSet<>();
    for (OptionsSnapshotReader.StrikePoint p : series) {
      bucketSet.add(p.bucket());
      strikeSet.add(p.strike());
    }
    List<BigDecimal> keptStrikes = nearestStrikes(strikeSet, atmStrike, window);
    List<OffsetDateTime> buckets = new ArrayList<>(bucketSet);

    Map<OffsetDateTime, Integer> bucketIdx = new HashMap<>();
    for (int i = 0; i < buckets.size(); i++) {
      bucketIdx.put(buckets.get(i), i);
    }
    Map<String, Integer> strikeIdx = new HashMap<>();
    for (int i = 0; i < keptStrikes.size(); i++) {
      strikeIdx.put(strikeKey(keptStrikes.get(i)), i);
    }

    // (strikeKey|leg) -> bucketIdx -> oi, indexing the single read once.
    Map<String, Map<Integer, Long>> oiByKey = new HashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : series) {
      if (!strikeIdx.containsKey(strikeKey(p.strike()))) {
        continue; // outside the ATM window
      }
      Integer xi = bucketIdx.get(p.bucket());
      oiByKey
          .computeIfAbsent(strikeKey(p.strike()) + "|" + p.optionType(), k -> new HashMap<>())
          .put(xi, p.oi());
    }

    long[] maxAbs = {0L};
    List<Cell> ce = grid(keptStrikes, buckets.size(), "CE", oiByKey, maxAbs);
    List<Cell> pe = grid(keptStrikes, buckets.size(), "PE", oiByKey, maxAbs);

    return new Heatmap(
        buckets.stream().map(OiHeatmapService::hhmm).toList(),
        keptStrikes.stream().map(OiHeatmapService::strikeKey).toList(),
        ce,
        pe,
        maxAbs[0],
        buckets.get(buckets.size() - 1));
  }

  /**
   * The ΔOI grid for one leg: for each kept strike (row {@code y}) and bucket (col {@code x} ≥ 1), the
   * OI delta from the prior bucket. A cell is null when the current bucket has no snapshot for that
   * (strike, leg); a one-bucket gap is bridged by carrying the last-seen OI forward. Tracks
   * {@code maxAbs} across both legs so the FE can centre its diverging colour scale symmetrically.
   */
  private static List<Cell> grid(
      List<BigDecimal> strikes,
      int bucketCount,
      String leg,
      Map<String, Map<Integer, Long>> oiByKey,
      long[] maxAbs) {
    List<Cell> cells = new ArrayList<>();
    for (int y = 0; y < strikes.size(); y++) {
      Map<Integer, Long> byBucket = oiByKey.getOrDefault(strikeKey(strikes.get(y)) + "|" + leg, Map.of());
      Long prior = byBucket.get(0);
      for (int x = 1; x < bucketCount; x++) {
        Long cur = byBucket.get(x);
        Long delta = (cur == null || prior == null) ? null : cur - prior;
        cells.add(new Cell(x, y, delta));
        if (delta != null && Math.abs(delta) > maxAbs[0]) {
          maxAbs[0] = Math.abs(delta);
        }
        if (cur != null) {
          prior = cur; // carry the last seen OI forward so a one-bucket gap doesn't poison the next delta
        }
      }
    }
    return cells;
  }

  /**
   * The {@code 2*window+1} distinct strikes nearest {@code atmStrike} (by |strike - atm|, ties to the
   * lower strike), returned in ascending strike order so the heatmap rows read low→high. When
   * {@code atmStrike} is null the lowest strikes are kept.
   */
  private static List<BigDecimal> nearestStrikes(
      TreeSet<BigDecimal> strikes, BigDecimal atmStrike, int window) {
    int limit = 2 * window + 1;
    Comparator<BigDecimal> order =
        atmStrike == null
            ? Comparator.naturalOrder()
            : Comparator.<BigDecimal, BigDecimal>comparing(s -> s.subtract(atmStrike).abs())
                .thenComparing(Comparator.naturalOrder());
    return strikes.stream().sorted(order).limit(limit).sorted().toList();
  }

  /** Scale-insensitive strike key (mirrors {@link OptionsSnapshotReader}). */
  private static String strikeKey(BigDecimal strike) {
    return strike.stripTrailingZeros().toPlainString();
  }

  /** "HH:mm" of the bucket in its own (IST) offset. */
  private static String hhmm(OffsetDateTime b) {
    return String.format("%02d:%02d", b.getHour(), b.getMinute());
  }
}
