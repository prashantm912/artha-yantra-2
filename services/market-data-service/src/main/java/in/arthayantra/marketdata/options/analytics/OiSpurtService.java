package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.marketdata.options.OiInterpretation;
import io.swagger.v3.oas.annotations.media.Schema;
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

  public record SpurtRow(
      OiInterpretation interpretation,
      @Schema(types = {"number", "null"}) BigDecimal spurtPct) {}

  /**
   * One spurt row per (strike, optionType): interval LTP/OI deltas + 4-state classification.
   * {@code ltpChangePct} is the signed % change of this strike's LTP between the latest pair of
   * buckets ((now - prior) / prior * 100); null when the prior LTP is absent or zero.
   */
  public record StrikeSpurt(
      BigDecimal strike,
      String optionType,
      @Schema(types = {"number", "null"}) BigDecimal ltp,
      @Schema(types = {"number", "null"}) BigDecimal prevLtp,
      @Schema(types = {"integer", "null"}) Long oi,
      long oiChange,
      @Schema(types = {"number", "null"}) BigDecimal spurtPct,
      @Schema(types = {"number", "null"}) BigDecimal ltpChange,
      @Schema(types = {"number", "null"}) BigDecimal ltpChangePct,
      @Schema(types = {"integer", "null"}) Long volume,
      OiInterpretation interpretation) {}

  /**
   * Underlying rollup: spot direction x total OI-delta direction -> the 4-state badge.
   * {@code oiChangePct}/{@code priceChangePct} are the representative spurt strike's (largest
   * |spurtPct|) OI %-change and matching price %-change magnitudes the scalper gates on; null when
   * no strike has a spurt.
   */
  public record SpurtSummary(
      OiInterpretation interpretation,
      @Schema(types = {"number", "null"}) BigDecimal spotDelta,
      long oiChange,
      @Schema(types = {"number", "null"}) BigDecimal oiChangePct,
      @Schema(types = {"number", "null"}) BigDecimal priceChangePct) {}

  public record SpurtChain(
      List<StrikeSpurt> items,
      SpurtSummary summary,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf) {}

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
      BigDecimal ltpChangePct = pctChange(old.ltp(), cur.ltp());
      items.add(
          new StrikeSpurt(
              cur.strike(),
              cur.optionType(),
              cur.ltp(),
              old.ltp(),
              cur.oi(),
              oiDelta,
              r.spurtPct(),
              ltpDelta,
              ltpChangePct,
              cur.volume(),
              r.interpretation()));
      totalOiDelta += oiDelta;
    }
    items.sort(Comparator.comparing(StrikeSpurt::strike).thenComparing(StrikeSpurt::optionType));
    BigDecimal spotNew = spotAt(pair, newest);
    BigDecimal spotOld = spotAt(pair, prior);
    SpurtSummary summary = null;
    if (spotNew != null && spotOld != null) {
      BigDecimal spotDelta = spotNew.subtract(spotOld);
      StrikeSpurt rep = representativeSpurt(items);
      BigDecimal oiChangePct = rep == null ? null : rep.spurtPct();
      BigDecimal priceChangePct = rep == null ? null : rep.ltpChangePct();
      summary =
          new SpurtSummary(
              OiInterpretation.classify(spotDelta, totalOiDelta),
              spotDelta,
              totalOiDelta,
              oiChangePct,
              priceChangePct);
    }
    return new SpurtChain(items, summary, newest);
  }

  /** The spurt strike: the row with the largest |spurtPct| (null when no row has a spurt). */
  private static StrikeSpurt representativeSpurt(List<StrikeSpurt> items) {
    return items.stream()
        .filter(s -> s.spurtPct() != null)
        .max(Comparator.comparing(s -> s.spurtPct().abs()))
        .orElse(null);
  }

  /** Signed % change ((cur - prior) / prior * 100); null when prior is absent or zero. */
  private static BigDecimal pctChange(BigDecimal prior, BigDecimal cur) {
    if (prior == null || cur == null || prior.signum() == 0) {
      return null;
    }
    return cur.subtract(prior)
        .multiply(BigDecimal.valueOf(100))
        .divide(prior, 2, RoundingMode.HALF_UP);
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
