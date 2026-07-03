package in.arthayantra.marketdata.options;

import in.arthayantra.marketdata.options.IvDailySummaryRepository.RollupRow;
import in.arthayantra.marketdata.options.IvDailySummaryRepository.Summary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The F-42B IV rollup + rank/percentile read. The 16:00 IST rollup distils a day's
 * {@code options_chain_snapshots} into one {@code iv_daily_summary} row (ATM IV nearest expiry,
 * 30-day constant-maturity IV via variance-time interpolation). IV rank/percentile is computed over
 * a trailing 1-year window of {@code iv_30d} (falling back to {@code atm_iv}); below the
 * {@link #HISTORY_FLOOR_DAYS}-trading-day floor the response is an explicit insufficient-history
 * state — never a fake rank (the binding honesty caveat).
 */
@Service
public class IvAnalyticsService {

  /** Default 30-day constant-maturity target. */
  static final int CONSTANT_MATURITY_DAYS = 30;

  /**
   * Trailing-window floor below which rank/percentile is suppressed (insufficient history).
   * DEFAULT for the config knob — capture-era history started 2026-06-15, so 60 keeps the iv_rank
   * dot honest-null until ~September; `artha.iv.rank-history-floor-days` lets the owner lower it
   * (rollup-evidence-gated, signal-analysis findings item #5) without a rebuild. Never below ~15:
   * a rank over a handful of days is noise wearing a percentile.
   */
  public static final int HISTORY_FLOOR_DAYS = 60;

  /** Trailing window length for rank/percentile. */
  static final int WINDOW_DAYS = 365;

  /** One day in the history series. */
  public record HistoryPoint(
      LocalDate date, BigDecimal iv, BigDecimal atmIv, BigDecimal iv30d, BigDecimal spot) {}

  /** The iv-history read DTO. */
  public record IvHistory(
      String underlying,
      List<HistoryPoint> series,
      BigDecimal currentIv,
      BigDecimal rank,
      Integer percentile,
      int windowDays,
      int floorDays,
      boolean insufficientHistory) {}

  /** The rank/percentile of the latest value within a window (suppressed below the floor). */
  record RankStat(
      BigDecimal current,
      BigDecimal rank,
      Integer percentile,
      int windowDays,
      boolean insufficient) {}

  private final IvDailySummaryRepository repository;
  private final int historyFloorDays;

  /** Wires the rollup repository + the configurable rank floor (default = the pinned 60). */
  public IvAnalyticsService(
      IvDailySummaryRepository repository,
      @org.springframework.beans.factory.annotation.Value(
              "${artha.iv.rank-history-floor-days:" + HISTORY_FLOOR_DAYS + "}")
          int historyFloorDays) {
    this.repository = repository;
    this.historyFloorDays = historyFloorDays;
  }

  /** Rolls up one (underlying, IST date); empty when that day has no snapshot. */
  public Optional<Summary> rollup(String underlying, LocalDate date) {
    Optional<java.time.OffsetDateTime> ts = repository.lastTsOn(underlying, date);
    if (ts.isEmpty()) {
      return Optional.empty();
    }
    List<RollupRow> rows = repository.rowsAtTs(underlying, ts.get());
    Summary summary = compute(underlying, date, rows, repository.snapshotCountOn(underlying, date));
    repository.upsert(summary);
    return Optional.of(summary);
  }

  /** Idempotent retroactive recompute over the archive (optionally bounded by from/to). */
  public int recompute(String underlying, LocalDate from, LocalDate to) {
    int n = 0;
    for (LocalDate date : repository.snapshotDates(underlying)) {
      if ((from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))) {
        if (rollup(underlying, date).isPresent()) {
          n++;
        }
      }
    }
    return n;
  }

  /** The iv-history read: trailing-1-year series + rank/percentile (suppressed below the floor). */
  public IvHistory ivHistory(String underlying) {
    List<Summary> all = repository.series(underlying);
    if (all.isEmpty()) {
      return new IvHistory(underlying, List.of(), null, null, null, 0, HISTORY_FLOOR_DAYS, true);
    }
    LocalDate latest = all.get(all.size() - 1).summaryDate();
    LocalDate cutoff = latest.minusDays(WINDOW_DAYS);
    List<HistoryPoint> series = new ArrayList<>();
    List<BigDecimal> ivs = new ArrayList<>();
    for (Summary s : all) {
      if (s.summaryDate().isBefore(cutoff)) {
        continue;
      }
      BigDecimal iv = s.iv30d() != null ? s.iv30d() : s.atmIv();
      series.add(new HistoryPoint(s.summaryDate(), iv, s.atmIv(), s.iv30d(), s.spotPrice()));
      if (iv != null) {
        ivs.add(iv);
      }
    }
    RankStat stat = rankStat(ivs, historyFloorDays);
    return new IvHistory(
        underlying,
        series,
        stat.current(),
        stat.rank(),
        stat.percentile(),
        stat.windowDays(),
        HISTORY_FLOOR_DAYS,
        stat.insufficient());
  }

  /**
   * IV rank = (current − min) / (max − min); percentile = share strictly below current. Below the
   * floor the rank/percentile are null (insufficient history) — never a fake rank.
   */
  static RankStat rankStat(List<BigDecimal> ivs, int floor) {
    int n = ivs.size();
    if (n == 0) {
      return new RankStat(null, null, null, 0, true);
    }
    BigDecimal current = ivs.get(n - 1);
    if (n < floor) {
      return new RankStat(current, null, null, n, true);
    }
    BigDecimal min = ivs.get(0);
    BigDecimal max = ivs.get(0);
    long below = 0;
    for (BigDecimal iv : ivs) {
      if (iv.compareTo(min) < 0) {
        min = iv;
      }
      if (iv.compareTo(max) > 0) {
        max = iv;
      }
      if (iv.compareTo(current) < 0) {
        below++;
      }
    }
    BigDecimal rank =
        max.compareTo(min) == 0
            ? BigDecimal.ZERO.setScale(4)
            : current.subtract(min).divide(max.subtract(min), 4, RoundingMode.HALF_UP);
    int percentile = (int) Math.round(100.0 * below / n);
    return new RankStat(current, rank, percentile, n, false);
  }

  /** Distils a snapshot pass into the daily summary (ATM IV nearest expiry + 30d CM IV). */
  static Summary compute(String underlying, LocalDate date, List<RollupRow> rows, int snapshotCount) {
    BigDecimal spot =
        rows.stream().map(RollupRow::spotPrice).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    if (spot == null) {
      return new Summary(underlying, date, null, null, null, snapshotCount, null);
    }
    Map<LocalDate, List<RollupRow>> byExpiry = new LinkedHashMap<>();
    for (RollupRow row : rows) {
      byExpiry.computeIfAbsent(row.expiry(), e -> new ArrayList<>()).add(row);
    }
    LocalDate nearest = byExpiry.keySet().stream().min(LocalDate::compareTo).orElse(null);
    BigDecimal atmIv = nearest == null ? null : atmIvForExpiry(byExpiry.get(nearest), spot);
    BigDecimal iv30d = constantMaturity30d(byExpiry, spot, date);
    return new Summary(underlying, date, atmIv, iv30d, spot, snapshotCount, null);
  }

  /** ATM IV for one expiry = mean of the CE/PE IV at the strike closest to spot (non-null). */
  static BigDecimal atmIvForExpiry(List<RollupRow> legs, BigDecimal spot) {
    BigDecimal atmStrike = null;
    BigDecimal best = null;
    for (RollupRow leg : legs) {
      BigDecimal distance = leg.strike().subtract(spot).abs();
      if (best == null || distance.compareTo(best) < 0) {
        best = distance;
        atmStrike = leg.strike();
      }
    }
    if (atmStrike == null) {
      return null;
    }
    BigDecimal sum = BigDecimal.ZERO;
    int count = 0;
    for (RollupRow leg : legs) {
      if (leg.strike().compareTo(atmStrike) == 0 && leg.iv() != null) {
        sum = sum.add(leg.iv());
        count++;
      }
    }
    return count == 0 ? null : sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
  }

  /**
   * 30-day constant-maturity IV via total-variance interpolation between the two expiries bracketing
   * 30 calendar days; null when fewer than two expiries bracket 30 d (early archive).
   */
  static BigDecimal constantMaturity30d(
      Map<LocalDate, List<RollupRow>> byExpiry, BigDecimal spot, LocalDate date) {
    LocalDate lower = null;
    LocalDate upper = null;
    for (LocalDate expiry : byExpiry.keySet()) {
      long days = ChronoUnit.DAYS.between(date, expiry);
      if (days <= 0) {
        continue;
      }
      if (days <= CONSTANT_MATURITY_DAYS && (lower == null || expiry.isAfter(lower))) {
        lower = expiry;
      }
      if (days >= CONSTANT_MATURITY_DAYS && (upper == null || expiry.isBefore(upper))) {
        upper = expiry;
      }
    }
    if (lower == null || upper == null) {
      return null;
    }
    BigDecimal ivLow = atmIvForExpiry(byExpiry.get(lower), spot);
    BigDecimal ivHigh = atmIvForExpiry(byExpiry.get(upper), spot);
    if (ivLow == null || ivHigh == null) {
      return null;
    }
    long dLow = ChronoUnit.DAYS.between(date, lower);
    long dHigh = ChronoUnit.DAYS.between(date, upper);
    if (dLow == dHigh) {
      return ivLow.setScale(6, RoundingMode.HALF_UP);
    }
    // total variance v = iv^2 * (days/365); interpolate v linearly in days to 30, then back out iv
    double vLow = ivLow.doubleValue() * ivLow.doubleValue() * (dLow / 365.0);
    double vHigh = ivHigh.doubleValue() * ivHigh.doubleValue() * (dHigh / 365.0);
    double v30 = vLow + (vHigh - vLow) * (CONSTANT_MATURITY_DAYS - dLow) / (double) (dHigh - dLow);
    double iv30 = Math.sqrt(v30 / (CONSTANT_MATURITY_DAYS / 365.0));
    return BigDecimal.valueOf(iv30).setScale(6, RoundingMode.HALF_UP);
  }
}
