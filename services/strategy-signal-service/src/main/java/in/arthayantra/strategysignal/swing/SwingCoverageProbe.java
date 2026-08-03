package in.arthayantra.strategysignal.swing;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.indicators.EngineIndicator;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects the failure the 2026-08-03 investigation found: every price window in the engine is
 * <b>row</b>-based ({@code ROWS BETWEEN 49 PRECEDING}, {@code close[i - N]}, ta4j's index-addressed
 * {@code SMAIndicator}), so a missing daily bar does NOT shorten a window — it silently
 * <b>stretches</b> how far back in time the window reaches.
 *
 * <p><b>Why a {@code minBars} floor cannot detect this</b> (the naive fix, deliberately not built):
 * {@code warmupDays = 520} yields roughly 350 bars against a 60-bar floor, so the floor never fires
 * for a normally-covered symbol. It counts rows, and this defect <em>preserves row count</em> while
 * changing what those rows span. Measured on 2026-08-03: three held Minervini positions computed
 * {@code sma50} over a window reaching back to 2026-05-15 instead of 2026-05-22 — five extra
 * sessions, trail off by ₹0.42–₹2.41/share — while the row count stayed 50 throughout.
 *
 * <p><b>The discriminator is window LENGTH versus gap DISTANCE.</b> The same five-bar gap sat 30–35
 * rows back, so it fell inside {@code sma50} (50 rows) and outside Manas's ATR-20, {@code
 * parabolic_ma} 10, {@code fast_bars} 3 and the 8% entry-price stop — all verified unaffected. That
 * is why this probe measures a window scoped to {@link #lookbackBars} rather than the whole fetched
 * series: probing the full 520-day window would fire on every symbol with any historical hole and
 * drown the real signal.
 *
 * <p><b>Contract: this class is pure and MUST NOT throw.</b> It sits on the live money path, in a
 * batch that is each open position's only exit evaluator, so an exception here would abort the run
 * and skip stops. Every uncertain input degrades to {@code determinable = false} — an explicit "no
 * claim" — never an exception and never a false "complete".
 */
public final class SwingCoverageProbe {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  /**
   * Params whose value is a BAR COUNT, so a missing session shifts what the rule reads. Pinned by
   * {@code SwingCoverageProbeTest#everyLiveParamKeyIsClassified}, a ratchet over the seeded swing
   * configs: a new depth-bearing key that lands here unclassified fails that test rather than
   * silently under-reporting depth.
   *
   * <p>A whitelist (rather than "max over all numeric params") is deliberate. The non-depth params
   * live in the same maps and are numerically LARGER than the real depths — Manas carries {@code
   * parabolic_dist_pct: 40} and {@code fast_pct: 35} beside a true max depth of 20 — so a max-over-
   * all-numerics rule would have reported Manas as gap-exposed on 2026-08-03 when it demonstrably
   * was not. That false positive would destroy exactly the precision this probe exists to provide.
   */
  private static final Set<String> DEPTH_PARAMS =
      Set.of(
          "period", "lookback", "atr_period", "parabolic_ma", "fast_bars", "max_bars",
          "fast", "slow", "signal");

  private SwingCoverageProbe() {}

  /**
   * One coverage reading over the last {@link #lookbackBars} bars.
   *
   * @param lookbackBars the window depth probed (bars)
   * @param windowStart first session in the probed window, {@code null} when not determinable
   * @param missing calendar trading days inside the window with no bar, ascending
   * @param determinable false when no claim can be made (empty series, or a year the bundled
   *     calendar does not cover) — callers MUST treat this as "unknown", never as "complete"
   */
  public record Coverage(
      int lookbackBars, LocalDate windowStart, List<LocalDate> missing, boolean determinable) {

    public Coverage {
      missing = missing == null ? List.of() : List.copyOf(missing);
    }

    /** True only when a positive claim of incompleteness can be made. */
    public boolean incomplete() {
      return determinable && !missing.isEmpty();
    }

    /** Compact ops rendering, e.g. {@code "5 of 50-bar window (2026-06-12..2026-06-19)"}. */
    public String describe() {
      if (!determinable) {
        return "coverage undeterminable";
      }
      if (missing.isEmpty()) {
        return "complete over " + lookbackBars + "-bar window";
      }
      return missing.size()
          + " session(s) missing inside the "
          + lookbackBars
          + "-bar window ("
          + missing.get(0)
          + ".."
          + missing.get(missing.size() - 1)
          + ")";
    }
  }

  /** A reading that makes no claim — the safe default for every uncertain input. */
  public static Coverage undeterminable(int lookbackBars) {
    return new Coverage(lookbackBars, null, List.of(), false);
  }

  /**
   * The deepest BAR-COUNT window this definition reads: the max {@link #DEPTH_PARAMS} value across
   * every declared indicator and every exit rule. Alias indirection needs no special handling — a
   * {@code trailing_stop basis:indicator alias:sma50} resolves to an {@code IndicatorSpec} that is
   * itself in {@code indicators}, so scanning both lists covers it.
   *
   * <p>Returns 0 when nothing depth-bearing is declared, which callers treat as "no window to
   * probe" — a strategy reading only the current bar and the entry price cannot be gap-stretched.
   */
  public static int lookbackBars(StrategyDefinition definition) {
    if (definition == null) {
      return 0;
    }
    int deepest = 0;
    if (definition.indicators() != null) {
      for (StrategyDefinition.IndicatorSpec spec : definition.indicators()) {
        deepest = Math.max(deepest, deepestParam(spec == null ? null : spec.params()));
      }
    }
    if (definition.exitRules() != null) {
      for (StrategyDefinition.ExitRuleSpec rule : definition.exitRules()) {
        deepest = Math.max(deepest, deepestParam(rule == null ? null : rule.params()));
      }
    }
    return deepest;
  }

  /**
   * The deepest window in DAILY BARS, taking the max of the declared-param estimate and the built
   * bank's own warm-up. Both inputs are needed and neither subsumes the other:
   *
   * <ul>
   *   <li>{@link EngineIndicator#unstableBars()} is the precise, already-normalized warm-up for an
   *       INDICATOR ({@code SMA -> period-1}, {@code ADX -> 2*period}, {@code MACD_HIST -> slow +
   *       signal - 2}) — a composite whose true depth exceeds any single param, which the param
   *       estimate alone under-counts.
   *   <li>Exit-rule depths are invisible to the bank. Manas's {@code atr_period: 20} and {@code
   *       parabolic_ma: 10} build an ATR/SMA INSIDE {@code ExitEvaluator}, never as a bank alias, so
   *       {@code unstableBars} alone would miss the entire Manas exit surface.
   * </ul>
   *
   * <p>{@code unstableBars()} had zero production callers when this was written, so it is treated as
   * unproven: any failure reading the bank degrades to the param estimate rather than propagating.
   *
   * <p><b>Known limitation:</b> both inputs are expressed in the indicator's OWN timeframe. A {@code
   * 1w} SMA(50) is 50 weeks (~250 daily sessions) but contributes 49 here, so a multi-timeframe
   * strategy under-states its daily depth. Every live swing indicator is {@code 1d} today, so this
   * is latent; closing it needs a timeframe-ratio conversion, deliberately not built.
   */
  public static int lookbackBars(StrategyDefinition definition, IndicatorBank bank) {
    int deepest = lookbackBars(definition);
    if (bank == null) {
      return deepest;
    }
    try {
      for (IndicatorBank.Bound bound : bank.all().values()) {
        if (bound != null && bound.indicator() != null) {
          deepest = Math.max(deepest, bound.indicator().unstableBars());
        }
      }
    } catch (RuntimeException e) {
      return deepest;
    }
    return deepest;
  }

  private static int deepestParam(Map<String, Object> params) {
    if (params == null) {
      return 0;
    }
    int deepest = 0;
    for (Map.Entry<String, Object> e : params.entrySet()) {
      if (!DEPTH_PARAMS.contains(e.getKey()) || !(e.getValue() instanceof Number n)) {
        continue;
      }
      double v = n.doubleValue();
      // a depth is a small positive whole number of bars; anything else is a mis-typed config and
      // must not silently widen or narrow the probe
      if (v >= 1 && v <= 10_000) {
        deepest = Math.max(deepest, (int) v);
      }
    }
    return deepest;
  }

  /**
   * Probes the last {@code lookbackBars} bars of {@code series} against the exchange calendar.
   *
   * <p>The window is stretched <b>iff the calendar says more sessions fell in the window's date span
   * than we hold bars for</b> — the bars are the last {@code lookbackBars} rows, their span is
   * {@code [first.date, last.date]}, and any trading day in that span without a bar is a hole the
   * row-based window silently reached past.
   *
   * <p>Never throws. {@link MarketCalendar#isTradingDay} raises on a year outside the bundled
   * resource (CD-2), and the 520-day warmup window will cross that boundary every January, so the
   * span is coverage-checked FIRST and any uncovered year degrades the whole reading to
   * undeterminable. A blanket {@code catch (RuntimeException)} backstops it — on this path a wrong
   * answer is recoverable, an exception is not.
   */
  public static Coverage probe(
      List<EngineCandle> series, int lookbackBars, MarketCalendar calendar) {
    if (series == null || series.isEmpty() || lookbackBars <= 0 || calendar == null) {
      return undeterminable(lookbackBars);
    }
    try {
      int from = Math.max(0, series.size() - lookbackBars);
      List<LocalDate> dates = new ArrayList<>(series.size() - from);
      for (int i = from; i < series.size(); i++) {
        EngineCandle bar = series.get(i);
        if (bar == null || bar.bucketStart() == null) {
          return undeterminable(lookbackBars);
        }
        dates.add(bar.bucketStart().withOffsetSameInstant(IST).toLocalDate());
      }
      LocalDate first = dates.get(0);
      LocalDate last = dates.get(dates.size() - 1);
      for (int year = first.getYear(); year <= last.getYear(); year++) {
        if (!calendar.coveredYears().contains(year)) {
          return undeterminable(lookbackBars);
        }
      }
      Set<LocalDate> present = new HashSet<>(dates);
      List<LocalDate> missing = new ArrayList<>();
      for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
        if (calendar.isTradingDay(d) && !present.contains(d)) {
          missing.add(d);
        }
      }
      return new Coverage(lookbackBars, first, missing, true);
    } catch (RuntimeException e) {
      return undeterminable(lookbackBars);
    }
  }
}
