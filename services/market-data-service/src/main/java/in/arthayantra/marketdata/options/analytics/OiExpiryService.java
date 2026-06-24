package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * oipulse "Options EOD OI Analysis" / OI Expiry Strategy (plan §options/oi-expiry-strategy): the
 * per-strike, last-N-session EOD OHLC + OI + Volume table that feeds the expiry-day trading plan. For
 * each strike it shows two stacked tables (CE and PE), each a daily series with day-over-day % change
 * in close + % change in OI, the 4-state OI interpretation, and the all-day-high/all-day-low flags
 * (the date whose High/Low was the window extreme for that leg).
 *
 * <p>Folds the raw {@link OptionsSnapshotReader#eodSeries} read (one row per strike·leg·day, zero new
 * capture) — the day-over-day deltas are computed against the chronologically prior captured session
 * for that same (strike, leg), so a gap (a missing session) compares against the last one present, not
 * a wall-clock-adjacent day. Rows are returned newest-first (oipulse's descending table order).
 */
@Service
public class OiExpiryService {

  /** One EOD daily row for a (strike, leg): premium OHLC + OI + day-over-day deltas + interpretation. */
  public record EodDay(
      LocalDate date,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      Long oi,
      Long volume,
      BigDecimal changeInClosePct,
      BigDecimal changeInOiPct,
      OiInterpretation interpretation,
      boolean allDayHigh,
      boolean allDayLow) {}

  /** One strike's CE + PE daily tables. */
  public record StrikeExpiry(BigDecimal strike, List<EodDay> ce, List<EodDay> pe) {}

  /**
   * Fold the raw EOD rows (any order) into per-strike CE+PE tables. Strikes are returned in ascending
   * strike order; within each leg the rows are newest-first. Empty list when {@code rows} is empty.
   */
  public List<StrikeExpiry> fold(List<OptionsSnapshotReader.OptionEodRow> rows) {
    // (strike) -> leg ("CE"/"PE") -> chronological day rows.
    Map<BigDecimal, Map<String, List<OptionsSnapshotReader.OptionEodRow>>> byStrike =
        new LinkedHashMap<>();
    for (OptionsSnapshotReader.OptionEodRow r : rows) {
      byStrike
          .computeIfAbsent(r.strike(), k -> new LinkedHashMap<>())
          .computeIfAbsent(r.optionType(), k -> new ArrayList<>())
          .add(r);
    }
    List<BigDecimal> strikes = new ArrayList<>(byStrike.keySet());
    strikes.sort(BigDecimal::compareTo);

    List<StrikeExpiry> out = new ArrayList<>(strikes.size());
    for (BigDecimal strike : strikes) {
      Map<String, List<OptionsSnapshotReader.OptionEodRow>> legs = byStrike.get(strike);
      out.add(
          new StrikeExpiry(
              strike,
              legTable(legs.getOrDefault("CE", List.of())),
              legTable(legs.getOrDefault("PE", List.of()))));
    }
    return out;
  }

  /**
   * One leg's daily table: sort ascending by date, derive day-over-day deltas + interpretation against
   * the prior captured session, flag the window high/low date, then return newest-first.
   */
  private static List<EodDay> legTable(List<OptionsSnapshotReader.OptionEodRow> raw) {
    if (raw.isEmpty()) {
      return List.of();
    }
    List<OptionsSnapshotReader.OptionEodRow> asc = new ArrayList<>(raw);
    asc.sort((a, b) -> a.tradeDate().compareTo(b.tradeDate()));

    // The window high/low: the dates whose High/Low equals the window extreme (oipulse all-day flags).
    BigDecimal maxHigh = null;
    BigDecimal minLow = null;
    for (OptionsSnapshotReader.OptionEodRow r : asc) {
      if (r.high() != null && (maxHigh == null || r.high().compareTo(maxHigh) > 0)) {
        maxHigh = r.high();
      }
      if (r.low() != null && (minLow == null || r.low().compareTo(minLow) < 0)) {
        minLow = r.low();
      }
    }

    List<EodDay> out = new ArrayList<>(asc.size());
    for (int i = 0; i < asc.size(); i++) {
      OptionsSnapshotReader.OptionEodRow cur = asc.get(i);
      OptionsSnapshotReader.OptionEodRow prev = i > 0 ? asc.get(i - 1) : null;

      BigDecimal closePct = pct(prev == null ? null : prev.close(), cur.close());
      BigDecimal oiPct = pctLong(prev == null ? null : prev.oiClose(), cur.oiClose());
      OiInterpretation interp =
          interpretation(
              prev == null ? null : prev.close(),
              cur.close(),
              prev == null ? null : prev.oiClose(),
              cur.oiClose());
      boolean adh = cur.high() != null && cur.high().compareTo(maxHigh) == 0;
      boolean adl = cur.low() != null && cur.low().compareTo(minLow) == 0;
      out.add(
          new EodDay(
              cur.tradeDate(),
              cur.open(),
              cur.high(),
              cur.low(),
              cur.close(),
              cur.oiClose(),
              cur.volume(),
              closePct,
              oiPct,
              interp,
              adh,
              adl));
    }
    java.util.Collections.reverse(out); // newest-first
    return out;
  }

  /**
   * Day-over-day 4-state interpretation from the close move × OI move. Null when either prior value is
   * absent (the first captured session for the leg has no prior). Mirrors the rest of the suite's
   * boundary convention (a flat delta counts as the up side — {@link OiInterpretation#classify}).
   */
  private static OiInterpretation interpretation(
      BigDecimal priorClose, BigDecimal curClose, Long priorOi, Long curOi) {
    if (priorClose == null || curClose == null || priorOi == null || curOi == null) {
      return null;
    }
    return OiInterpretation.classify(curClose.subtract(priorClose), curOi - priorOi);
  }

  /** Signed % change ((cur - prior) / prior * 100), 2dp; null when prior is absent or zero. */
  private static BigDecimal pct(BigDecimal prior, BigDecimal cur) {
    if (prior == null || cur == null || prior.signum() == 0) {
      return null;
    }
    return cur.subtract(prior)
        .multiply(BigDecimal.valueOf(100))
        .divide(prior, 2, RoundingMode.HALF_UP);
  }

  /** As {@link #pct} but for the long OI counts. */
  private static BigDecimal pctLong(Long prior, Long cur) {
    if (prior == null || cur == null || prior == 0L) {
      return null;
    }
    return BigDecimal.valueOf(cur - prior)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(prior), 2, RoundingMode.HALF_UP);
  }
}
