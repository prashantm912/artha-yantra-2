package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.options.OiInterpretation;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * Big-OI session event LOG (audit 2026-07-02 §9.2-5 parity): the barometer's Big OI Movement is a
 * chronological session log — rows timestamped through the day — where ours was a top-10 NOW
 * leaderboard. Folds the same one-read session series the heatmap uses into per-bucket ΔOI events,
 * keeping the {@code topPerBucket} largest |ΔOI| moves of each bucket (CE and PE ranked together,
 * zero-ΔOI dropped), newest bucket first. A one-bucket capture gap is bridged by carrying the
 * last-seen OI/LTP forward (same rule as {@link OiHeatmapService}).
 */
@Service
public class BigOiLogService {

  /** One logged move: bucket time (IST "HH:mm"), the leg, its interval deltas + classification. */
  public record LogEvent(
      String time,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal spot,
      @Schema(type = "string") BigDecimal strike,
      String optionType,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal ltp,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal ltpChange,
      @Schema(types = {"integer", "null"}) Long oi,
      long oiChange,
      OiInterpretation interpretation) {}

  public record BigOiLog(
      List<LogEvent> items,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf) {}

  /**
   * Fold {@code series} (one row per bucket/strike/leg) into the chronological event log. The first
   * bucket has no prior so it never produces an event; empty log when the series is empty.
   */
  public BigOiLog fold(List<OptionsSnapshotReader.StrikePoint> series, int topPerBucket) {
    if (series.isEmpty()) {
      return new BigOiLog(List.of(), null);
    }

    TreeSet<OffsetDateTime> bucketSet = new TreeSet<>();
    for (OptionsSnapshotReader.StrikePoint p : series) {
      bucketSet.add(p.bucket());
    }
    List<OffsetDateTime> buckets = new ArrayList<>(bucketSet);
    Map<OffsetDateTime, Integer> bucketIdx = new HashMap<>();
    for (int i = 0; i < buckets.size(); i++) {
      bucketIdx.put(buckets.get(i), i);
    }

    // (strikeKey|leg) -> bucketIdx -> point; plus the per-bucket spot for the Asset column.
    Map<String, Map<Integer, OptionsSnapshotReader.StrikePoint>> byKey = new HashMap<>();
    Map<Integer, BigDecimal> spotAt = new HashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : series) {
      int xi = bucketIdx.get(p.bucket());
      byKey
          .computeIfAbsent(p.strike().stripTrailingZeros().toPlainString() + "|" + p.optionType(), k -> new HashMap<>())
          .put(xi, p);
      if (p.spot() != null) {
        spotAt.putIfAbsent(xi, p.spot());
      }
    }

    List<LogEvent> log = new ArrayList<>();
    for (int x = buckets.size() - 1; x >= 1; x--) {
      List<LogEvent> atBucket = new ArrayList<>();
      for (Map<Integer, OptionsSnapshotReader.StrikePoint> byBucket : byKey.values()) {
        OptionsSnapshotReader.StrikePoint cur = byBucket.get(x);
        if (cur == null) {
          continue;
        }
        OptionsSnapshotReader.StrikePoint prior = lastBefore(byBucket, x);
        if (prior == null || prior.oi() == null || cur.oi() == null) {
          continue; // no prior reading — no interval signal
        }
        long oiChange = cur.oi() - prior.oi();
        if (oiChange == 0) {
          continue;
        }
        BigDecimal ltpChange =
            (cur.ltp() == null || prior.ltp() == null) ? null : cur.ltp().subtract(prior.ltp());
        atBucket.add(
            new LogEvent(
                hhmm(buckets.get(x)),
                spotAt.get(x),
                cur.strike(),
                cur.optionType(),
                cur.ltp(),
                ltpChange,
                cur.oi(),
                oiChange,
                OiInterpretation.classify(ltpChange == null ? BigDecimal.ZERO : ltpChange, oiChange)));
      }
      atBucket.sort(Comparator.comparingLong((LogEvent e) -> Math.abs(e.oiChange())).reversed());
      log.addAll(atBucket.subList(0, Math.min(topPerBucket, atBucket.size())));
    }
    return new BigOiLog(log, buckets.get(buckets.size() - 1));
  }

  /** The nearest prior bucket's point for this leg (bridges capture gaps, like the heatmap). */
  private static OptionsSnapshotReader.StrikePoint lastBefore(
      Map<Integer, OptionsSnapshotReader.StrikePoint> byBucket, int x) {
    for (int i = x - 1; i >= 0; i--) {
      OptionsSnapshotReader.StrikePoint p = byBucket.get(i);
      if (p != null) {
        return p;
      }
    }
    return null;
  }

  /** "HH:mm" IST (stored buckets are UTC off JDBC time_bucket — same rule as the heatmap axis). */
  private static String hhmm(OffsetDateTime b) {
    OffsetDateTime ist = b.atZoneSameInstant(Ist.ZONE).toOffsetDateTime();
    return String.format("%02d:%02d", ist.getHour(), ist.getMinute());
  }
}
