package in.arthayantra.marketdata.options.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Primitive #2: active (peak-OI) strikes + Active Strike Sentiment % (v1, tunable — D4). */
@Service
public class ActiveStrikeService {

  private final int topN;

  public ActiveStrikeService(@Value("${artha.options.active-strikes-top-n:5}") int topN) {
    this.topN = topN;
  }

  public record StrikeOiSnap(
      BigDecimal strike, long ceOi, long ceOiChange, long peOi, long peOiChange) {}

  /** One Active Strike Sentiment % point per snapshot bucket (newest-last in the series). */
  public record SentimentPoint(OffsetDateTime bucket, BigDecimal sentimentPct) {}

  /** One active-strike Call/Put OI point per snapshot bucket (newest-last) — the LEFT chart series. */
  public record ActiveStrikeOiPoint(OffsetDateTime bucket, long ceOi, long peOi) {}

  public List<StrikeOiSnap> activeStrikes(List<StrikeOiSnap> chain) {
    return chain.stream()
        .sorted(Comparator.comparingLong((StrikeOiSnap s) -> s.ceOi() + s.peOi()).reversed())
        .limit(topN)
        .toList();
  }

  /** 100 · (ΣpeΔOI − ΣceΔOI) / Σ(ceOi+peOi) over active strikes; null when no base OI. */
  public BigDecimal sentimentPct(List<StrikeOiSnap> chain) {
    List<StrikeOiSnap> active = activeStrikes(chain);
    long bullishFlow = 0;
    long baseOi = 0;
    for (StrikeOiSnap s : active) {
      bullishFlow += s.peOiChange() - s.ceOiChange();
      baseOi += s.ceOi() + s.peOi();
    }
    if (baseOi == 0) {
      return null;
    }
    return BigDecimal.valueOf(bullishFlow)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(baseOi), 2, RoundingMode.HALF_UP);
  }

  /**
   * Active Strike Sentiment % per snapshot bucket: groups {@code series} (ordered oldest-first, as
   * {@link OptionsSnapshotReader#series} returns) by bucket, folds each bucket's points per strike,
   * and applies the same top-N formula. One point per bucket, newest-last; a bucket with no base OI
   * carries a null {@code sentimentPct}.
   */
  public List<SentimentPoint> sentimentSeries(List<OptionsSnapshotReader.StrikePoint> series) {
    Map<OffsetDateTime, Map<BigDecimal, long[]>> byBucket = new LinkedHashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : series) {
      Map<BigDecimal, long[]> m =
          byBucket.computeIfAbsent(p.bucket(), k -> new LinkedHashMap<>()); // [ceOi,ceChg,peOi,peChg]
      long[] v = m.computeIfAbsent(p.strike(), k -> new long[4]);
      long oi = p.oi() == null ? 0 : p.oi();
      long chg = p.oiChange() == null ? 0 : p.oiChange();
      if ("CE".equals(p.optionType())) {
        v[0] += oi;
        v[1] += chg;
      } else {
        v[2] += oi;
        v[3] += chg;
      }
    }
    List<SentimentPoint> out = new ArrayList<>();
    byBucket.forEach(
        (bucket, m) -> {
          List<StrikeOiSnap> snaps = new ArrayList<>();
          m.forEach((strike, v) -> snaps.add(new StrikeOiSnap(strike, v[0], v[1], v[2], v[3])));
          out.add(new SentimentPoint(bucket, sentimentPct(snaps)));
        });
    return out;
  }

  /**
   * Active-strike Call vs Put OI per snapshot bucket (the "Active Strike Change in OI" chart). Folds
   * {@code series} exactly as {@link #sentimentSeries} does and reuses the same {@link #activeStrikes}
   * top-N selection per bucket — so this OI series and the sentiment series agree, bucket for bucket, on
   * which strikes are active. One point per bucket, newest-last.
   */
  public List<ActiveStrikeOiPoint> activeStrikeOiSeries(
      List<OptionsSnapshotReader.StrikePoint> series) {
    Map<OffsetDateTime, Map<BigDecimal, long[]>> byBucket = new LinkedHashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : series) {
      Map<BigDecimal, long[]> m =
          byBucket.computeIfAbsent(p.bucket(), k -> new LinkedHashMap<>()); // [ceOi,ceChg,peOi,peChg]
      long[] v = m.computeIfAbsent(p.strike(), k -> new long[4]);
      long oi = p.oi() == null ? 0 : p.oi();
      long chg = p.oiChange() == null ? 0 : p.oiChange();
      if ("CE".equals(p.optionType())) {
        v[0] += oi;
        v[1] += chg;
      } else {
        v[2] += oi;
        v[3] += chg;
      }
    }
    List<ActiveStrikeOiPoint> out = new ArrayList<>();
    byBucket.forEach(
        (bucket, m) -> {
          List<StrikeOiSnap> snaps = new ArrayList<>();
          m.forEach((strike, v) -> snaps.add(new StrikeOiSnap(strike, v[0], v[1], v[2], v[3])));
          long ceOi = 0;
          long peOi = 0;
          for (StrikeOiSnap s : activeStrikes(snaps)) {
            ceOi += s.ceOi();
            peOi += s.peOi();
          }
          out.add(new ActiveStrikeOiPoint(bucket, ceOi, peOi));
        });
    return out;
  }
}
