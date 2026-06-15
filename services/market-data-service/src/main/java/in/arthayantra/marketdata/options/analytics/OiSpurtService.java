package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Primitive #3: per-strike buildup classification + OI-spurt %. */
@Service
public class OiSpurtService {

  public record SpurtRow(OiInterpretation interpretation, BigDecimal spurtPct) {}

  /** One spurt row per (strike, optionType): interval LTP/OI deltas + 4-state classification. */
  public record StrikeSpurt(
      BigDecimal strike,
      String optionType,
      BigDecimal ltp,
      Long oi,
      long oiChange,
      BigDecimal spurtPct,
      OiInterpretation interpretation) {}

  /** Underlying rollup: spot direction x total OI-delta direction -> the 4-state badge. */
  public record SpurtSummary(OiInterpretation interpretation, BigDecimal spotDelta, long oiChange) {}

  public record SpurtChain(List<StrikeSpurt> items, SpurtSummary summary, OffsetDateTime asOf) {}

  /** priorOi = oi before this interval's change (= currentOi - oiChange). */
  public SpurtRow classify(BigDecimal ltpDelta, long oiChange, long priorOi) {
    OiInterpretation interp = OiInterpretation.classify(ltpDelta, oiChange);
    BigDecimal spurt =
        priorOi == 0
            ? null
            : BigDecimal.valueOf(oiChange)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(priorOi), 2, RoundingMode.HALF_UP);
    return new SpurtRow(interp, spurt);
  }

  /**
   * Fold the latest-two-bucket window (from {@link OptionsSnapshotReader#latestPair}) into spurt
   * rows: per (strike, optionType) diff the newest bucket against the prior one. Strikes present in
   * only the newest bucket are skipped (no interval signal). The summary diffs the underlying spot
   * and sums every strike's OI-delta. {@code items} is empty and {@code summary} null when there is
   * no prior bucket (and {@code summary} is also null when spot is missing in either bucket);
   * {@code asOf} is always the newest bucket (null only when the window is empty).
   */
  public SpurtChain spurts(List<OptionsSnapshotReader.StrikePoint> pair) {
    OffsetDateTime newest =
        pair.stream()
            .map(OptionsSnapshotReader.StrikePoint::bucket)
            .max(Comparator.naturalOrder())
            .orElse(null);
    if (newest == null) {
      return new SpurtChain(List.of(), null, null);
    }
    OffsetDateTime prior =
        pair.stream()
            .map(OptionsSnapshotReader.StrikePoint::bucket)
            .filter(b -> b.isBefore(newest))
            .max(Comparator.naturalOrder())
            .orElse(null);
    if (prior == null) {
      return new SpurtChain(List.of(), null, newest);
    }
    Map<String, OptionsSnapshotReader.StrikePoint> newAt = new HashMap<>();
    Map<String, OptionsSnapshotReader.StrikePoint> oldAt = new HashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : pair) {
      String key = p.strike().toPlainString() + p.optionType();
      if (p.bucket().equals(newest)) {
        newAt.put(key, p);
      } else if (p.bucket().equals(prior)) {
        oldAt.put(key, p);
      }
    }
    List<StrikeSpurt> items = new ArrayList<>();
    long totalOiDelta = 0;
    for (Map.Entry<String, OptionsSnapshotReader.StrikePoint> e : newAt.entrySet()) {
      OptionsSnapshotReader.StrikePoint cur = e.getValue();
      OptionsSnapshotReader.StrikePoint old = oldAt.get(e.getKey());
      if (old == null) {
        continue; // no prior bucket for this strike -> no spurt
      }
      long priorOi = old.oi() == null ? 0 : old.oi();
      long curOi = cur.oi() == null ? 0 : cur.oi();
      long oiDelta = curOi - priorOi;
      BigDecimal ltpDelta =
          (cur.ltp() == null ? BigDecimal.ZERO : cur.ltp())
              .subtract(old.ltp() == null ? BigDecimal.ZERO : old.ltp());
      SpurtRow r = classify(ltpDelta, oiDelta, priorOi);
      items.add(
          new StrikeSpurt(
              cur.strike(),
              cur.optionType(),
              cur.ltp(),
              cur.oi(),
              oiDelta,
              r.spurtPct(),
              r.interpretation()));
      totalOiDelta += oiDelta;
    }
    items.sort(Comparator.comparing(StrikeSpurt::strike).thenComparing(StrikeSpurt::optionType));
    BigDecimal spotNew = spotAt(pair, newest);
    BigDecimal spotOld = spotAt(pair, prior);
    SpurtSummary summary = null;
    if (spotNew != null && spotOld != null) {
      BigDecimal spotDelta = spotNew.subtract(spotOld);
      summary =
          new SpurtSummary(
              OiInterpretation.classify(spotDelta, totalOiDelta), spotDelta, totalOiDelta);
    }
    return new SpurtChain(items, summary, newest);
  }

  private static BigDecimal spotAt(
      List<OptionsSnapshotReader.StrikePoint> pair, OffsetDateTime bucket) {
    return pair.stream()
        .filter(p -> p.bucket().equals(bucket))
        .map(OptionsSnapshotReader.StrikePoint::spot)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }
}
