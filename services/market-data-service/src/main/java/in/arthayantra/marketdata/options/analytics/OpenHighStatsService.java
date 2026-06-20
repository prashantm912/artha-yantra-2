package in.arthayantra.marketdata.options.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Per-strike session Open=High / Open=Low grading around the ATM (Siva #2 "open-high" scan). Folds
 * {@link OptionsSnapshotReader#sessionStats} into the {@code 2*window+1} strikes nearest the ATM
 * (both CE+PE legs of each), marking the OH/OL premium pattern and the fall% from open / prior close.
 */
@Service
public class OpenHighStatsService {

  /** Server tolerance (option-premium points) for grading open==high / open==low. */
  private final BigDecimal tolerance;

  public OpenHighStatsService(
      @Value("${artha.options.oh-tolerance:1.0}") BigDecimal tolerance) {
    this.tolerance = tolerance;
  }

  public record StrikeSessionStat(
      BigDecimal strike,
      String optionType,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal last,
      Long dayVolume,
      Long declineVolume,
      BigDecimal prevClose,
      boolean ohMark,
      boolean olMark,
      BigDecimal fallPctFromOpen,
      BigDecimal fallPctFromPrevClose) {}

  public record StrikeSessionStats(
      OffsetDateTime asOf,
      String underlying,
      LocalDate expiry,
      BigDecimal spot,
      BigDecimal atmStrike,
      int interval,
      List<StrikeSessionStat> items) {}

  /**
   * Grade {@code stats} (one row per CE/PE leg) sliced to the {@code 2*window+1} listed strikes
   * nearest {@code atmStrike}. {@code atmStrike}/{@code spot} may be null when no ATM is resolvable;
   * the slice then keeps the {@code 2*window+1} lowest strikes as a stable fallback.
   */
  public StrikeSessionStats grade(
      String underlying,
      LocalDate expiry,
      int interval,
      BigDecimal spot,
      BigDecimal atmStrike,
      int window,
      OffsetDateTime asOf,
      List<OptionsSnapshotReader.PerStrikeSessionStat> stats) {
    Set<BigDecimal> keep = nearestStrikes(stats, atmStrike, window);
    List<StrikeSessionStat> items = new ArrayList<>();
    for (OptionsSnapshotReader.PerStrikeSessionStat s : stats) {
      if (!keep.contains(s.strike())) {
        continue;
      }
      items.add(
          new StrikeSessionStat(
              s.strike(),
              s.optionType(),
              s.open(),
              s.high(),
              s.low(),
              s.last(),
              s.dayVolume(),
              s.declineVolume(),
              s.prevClose(),
              ohMark(s.open(), s.high()),
              olMark(s.open(), s.low()),
              fallPct(s.last(), s.open()),
              fallPct(s.last(), s.prevClose())));
    }
    return new StrikeSessionStats(asOf, underlying, expiry, spot, atmStrike, interval, items);
  }

  /** open=high iff (high - open) <= tolerance (both non-null). */
  private boolean ohMark(BigDecimal open, BigDecimal high) {
    if (open == null || high == null) {
      return false;
    }
    return high.subtract(open).compareTo(tolerance) <= 0;
  }

  /** open=low iff (open - low) <= tolerance (both non-null). */
  private boolean olMark(BigDecimal open, BigDecimal low) {
    if (open == null || low == null) {
      return false;
    }
    return open.subtract(low).compareTo(tolerance) <= 0;
  }

  /** (last - base) / base * 100, scale 4 HALF_UP; null when base is null or zero. */
  private static BigDecimal fallPct(BigDecimal last, BigDecimal base) {
    if (last == null || base == null || base.signum() == 0) {
      return null;
    }
    return last.subtract(base).divide(base, 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .setScale(4, RoundingMode.HALF_UP);
  }

  /**
   * The {@code 2*window+1} distinct listed strikes nearest {@code atmStrike} (by |strike - atm|;
   * ties broken by the lower strike). When {@code atmStrike} is null, the lowest strikes are kept.
   */
  private static Set<BigDecimal> nearestStrikes(
      List<OptionsSnapshotReader.PerStrikeSessionStat> stats, BigDecimal atmStrike, int window) {
    List<BigDecimal> distinct =
        stats.stream()
            .map(OptionsSnapshotReader.PerStrikeSessionStat::strike)
            .distinct()
            .sorted()
            .toList();
    int limit = 2 * window + 1;
    Comparator<BigDecimal> order =
        atmStrike == null
            ? Comparator.naturalOrder()
            : Comparator.<BigDecimal, BigDecimal>comparing(s -> s.subtract(atmStrike).abs())
                .thenComparing(Comparator.naturalOrder());
    Set<BigDecimal> keep = new LinkedHashSet<>();
    distinct.stream().sorted(order).limit(limit).forEach(keep::add);
    return keep;
  }
}
