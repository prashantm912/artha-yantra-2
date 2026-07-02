package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.black76.Black76;
import in.arthayantra.black76.IvSolver;
import in.arthayantra.marketdata.options.ExpiryClock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

  /**
   * One active-strike Call/Put IV + price point per snapshot bucket (newest-last) — the Active Strike IV
   * chart. IV is per-strike and cannot be aggregated, so this carries the SINGLE peak-OI strike's IVs
   * (not the top-N aggregate the OI series uses); {@code price} is that strike's underlying spot.
   */
  public record ActiveStrikeIvPoint(
      OffsetDateTime bucket, BigDecimal ceIv, BigDecimal peIv, BigDecimal price) {}

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

  /** Per-bucket accumulator: total OI (the active-strike selection key) + the strike's CE/PE IV + spot. */
  private static final class IvAcc {
    long totalOi;
    BigDecimal ceIv;
    BigDecimal peIv;
    BigDecimal spot;
    BigDecimal ceLtp;
    BigDecimal peLtp;
  }

  /**
   * Active-strike Call/Put IV + price per snapshot bucket (the "Active Strike IV" chart). Per bucket it
   * picks the SINGLE peak-total-OI strike (ties broken by the LOWEST strike, deterministically) and emits
   * that strike's CE IV, PE IV and underlying spot. IV is per-strike and unsummable, so — unlike
   * {@link #activeStrikeOiSeries} (top-N aggregate) — this is rank-1 only. One point per bucket,
   * newest-last; an IV leg absent on the chosen strike rides through as null (the chart gaps it).
   */
  public List<ActiveStrikeIvPoint> activeStrikeIvSeries(
      List<OptionsSnapshotReader.StrikePoint> series) {
    List<ActiveStrikeIvPoint> out = new ArrayList<>();
    foldPeakStrike(
        series,
        (bucket, strike, peak) ->
            out.add(new ActiveStrikeIvPoint(bucket, peak.ceIv, peak.peIv, peak.spot)));
    return out;
  }

  /**
   * Per-side SPOT-solved IV series for the Active Strike IV chart (audit 2026-07-02 §10.2-1). The
   * stored per-leg {@code iv} is solved against the PCP-implied forward, which enforces put-call
   * parity — CE IV ≈ PE IV by construction, so the call-vs-put IV split ("skew") this page exists
   * to show can never appear. This series re-solves each leg UNCONSTRAINED against the bucket's
   * SPOT (the oipulse convention), making the carry-side IV gap visible. DISPLAY PATH ONLY: the
   * stored iv, {@link #activeStrikeIvSeries} (the scalper gate's iv_slope input) and the chain's
   * greeks are untouched. Same rank-1 peak-strike selection; a side with no LTP/spot rides through
   * as a null IV (the chart gaps it).
   */
  public List<ActiveStrikeIvPoint> activeStrikeSideIvSeries(
      List<OptionsSnapshotReader.StrikePoint> series, LocalDate expiry, BigDecimal riskFreeRate) {
    double r = riskFreeRate.doubleValue();
    List<ActiveStrikeIvPoint> out = new ArrayList<>();
    foldPeakStrike(
        series,
        (bucket, strike, peak) -> {
          BigDecimal ceIv = null;
          BigDecimal peIv = null;
          if (peak.spot != null) {
            double t =
                ExpiryClock.yearsToExpiry(bucket.toInstant(), expiry).orElse(Black76.T_MIN);
            double f = peak.spot.doubleValue();
            double k = strike.doubleValue();
            if (peak.ceLtp != null) {
              ceIv =
                  IvSolver.solve(Black76.OptionType.CE, f, k, t, r, peak.ceLtp.doubleValue()).iv();
            }
            if (peak.peLtp != null) {
              peIv =
                  IvSolver.solve(Black76.OptionType.PE, f, k, t, r, peak.peLtp.doubleValue()).iv();
            }
          }
          out.add(new ActiveStrikeIvPoint(bucket, ceIv, peIv, peak.spot));
        });
    return out;
  }

  /** Per-bucket peak-total-OI strike fold shared by the IV series (bucket, peak strike, accumulator). */
  private interface PeakStrikeConsumer {
    void accept(OffsetDateTime bucket, BigDecimal strike, IvAcc peak);
  }

  private static void foldPeakStrike(
      List<OptionsSnapshotReader.StrikePoint> series, PeakStrikeConsumer consumer) {
    Map<OffsetDateTime, Map<BigDecimal, IvAcc>> byBucket = new LinkedHashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : series) {
      Map<BigDecimal, IvAcc> m = byBucket.computeIfAbsent(p.bucket(), k -> new LinkedHashMap<>());
      IvAcc a = m.computeIfAbsent(p.strike(), k -> new IvAcc());
      a.totalOi += p.oi() == null ? 0 : p.oi();
      if ("CE".equals(p.optionType())) {
        a.ceIv = p.iv();
        a.ceLtp = p.ltp();
      } else {
        a.peIv = p.iv();
        a.peLtp = p.ltp();
      }
      if (a.spot == null) {
        a.spot = p.spot();
      }
    }
    byBucket.forEach(
        (bucket, m) -> {
          BigDecimal peakStrike = null;
          IvAcc peak = null;
          for (Map.Entry<BigDecimal, IvAcc> e : m.entrySet()) {
            // Highest total OI wins; on a tie the lower strike wins (explicit, not map-order luck).
            if (peak == null
                || e.getValue().totalOi > peak.totalOi
                || (e.getValue().totalOi == peak.totalOi && e.getKey().compareTo(peakStrike) < 0)) {
              peak = e.getValue();
              peakStrike = e.getKey();
            }
          }
          if (peak != null) {
            consumer.accept(bucket, peakStrike, peak);
          }
        });
  }
}
