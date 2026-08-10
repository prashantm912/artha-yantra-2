package in.arthayantra.strategysignal.swing;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategyengine.config.GateNode;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.IndicatorBank;
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
 * changing what those rows span.
 *
 * <p><b>The discriminator is window LENGTH versus gap DISTANCE</b>, which is why the probe measures a
 * window scoped to the reader's declared depth rather than the whole 520-day fetch.
 *
 * <h2>Two depths, because the two callers ask different questions</h2>
 *
 * The first draft used ONE max-over-everything depth for both passes, and cross-vendor review found
 * it would have refused most of the funnel nightly. Two independent defects composed:
 *
 * <ul>
 *   <li><b>The entry gate was scoped to indicators it never reads.</b> In {@code minervini-vcp} /
 *       {@code cheat-3c} / {@code power-play}, {@code sma50} appears only as a {@code trailing_stop}
 *       basis, and in both Manas strategies it is declared but read by nothing. Maxing over exit
 *       rules and unread indicators refused entries on a stretch that provably could not change the
 *       entry computation. {@link #entryLookbackBars} therefore scopes to the gate + scoring closure
 *       — exactly what {@code EntryEvaluator} reads — while {@link #exitLookbackBars} keeps
 *       max-over-everything for the detective alert.
 *   <li><b>Refusal probability rose with declared depth while the harm rises with shallowness.</b>
 *       {@code minervini-primary-base} reads {@code w52h} at {@code period: 252} IN ITS ENTRY GATE,
 *       so entry-scoping alone does not save it: one missing row inside a 252-row 52-week MAX almost
 *       never moves {@code crossover(px, w52h)}, yet an any-gap rule refuses absolutely. {@link
 *       Coverage#materiallyIncomplete()} fixes that — see its measured threshold.
 * </ul>
 *
 * <p><b>Contract: this class is pure and MUST NOT throw.</b> It sits on the live money path, in a
 * batch that is each open position's only exit evaluator, so an exception here would abort the run
 * and skip stops. Every entry point is wrapped and every uncertain input degrades to {@code
 * determinable = false} — an explicit "no claim" — never an exception and never a false "complete".
 */
public final class SwingCoverageProbe {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  /**
   * Refuse only when more than 1-in-{@value} of the probed window is missing. NOT an invented
   * number — measured against the 2026-08-03 Minervini funnel (277 {@code passes_all}), with each
   * symbol's absence at batch time reconstructed from {@code candles.fetched_at > 20:00}, and
   * excluding the session's own bar (the batch's own fetch writes it at 20:01, so counting it marks
   * every symbol absent):
   *
   * <pre>
   *   missing   as % of a 50-window   as % of a 252-window   symbols
   *         5                  9.1%                  1.95%        38
   *         6                 10.7%                  2.33%         7
   *         3                  0.0%                  1.18%         1
   * </pre>
   *
   * ⚠️ <b>Those percentages are {@code m / (L + m)}, NOT {@code m / L}</b> — the denominator is
   * {@link Coverage#windowSessions()}, which is {@code dates.size() + missing.size()}, i.e. the
   * TRADING SESSIONS SPANNED, not the declared depth. An earlier draft of this table quoted
   * {@code m / L} (10.0% / 12.0% / 2.0% / 2.4%) and that mismatch is exactly what produced the
   * wrong denominator in the first recalibration: read as {@code m / L}, one missing session at
   * depth 20 looks like 1/20 = 5%; the code actually computes 1/21 = 4.7619%. Recalibrate from the
   * numbers ABOVE, never from a depth-relative reading.
   *
   * <p>1/22 = 4.545% sits in the empty band between 2.33% and 9.1% with roughly 2x clearance on both
   * sides: it refuses the real harm (a 5-bar hole inside a 50-bar window, the shape that mis-computed
   * three held positions' {@code sma50}) and permits the same hole inside a 252-bar window, where it
   * cannot move a 52-week extreme. Because the test is a FRACTION, refusal probability no longer
   * scales with declared depth — the defect review caught.
   *
   * <p><b>Limitation:</b> a fraction is a proxy for sensitivity, not a measure of it. A mean-based
   * indicator moves roughly proportionally to the missing fraction, but a MAX/MIN barely moves at
   * all, so this is conservative for extremes and about right for averages. A per-indicator-type
   * sensitivity model would be more precise and is deliberately not built.
   */
  // ⚠️ 22, NOT 21 (corrected 2026-08-08 by review, before merge). The rule is
  // `m * D > windowSessions` with `windowSessions = L + m`, so it fires iff `m * (D - 1) > L`.
  // At L=20, m=1 that needs D-1 > 20, i.e. D >= 22. The first cut chose 21 by reading the window as
  // `m / L`, which made the depth-20 case `1*21 > 21` — FALSE, the exact case the recalibration was
  // for, unchanged. Its test passed only because the fixture built 20 sessions and DELETED one,
  // handing the probe 19 bars (windowSessions 20); production hands it ~350 and takes the last 20
  // (windowSessions 21). Boundary tests here MUST use a series longer than the depth.
  //
  // Effect at D=22, per live strategy (m = smallest missing count that refuses):
  //   L=20  (5 of 6 strategies)  1/21 = 4.76% > 4.545%  -> refuses at m=1   [was m=2, this is the fix]
  //   L=50  (exit windows)       m >= 3                 -> unchanged from D=20
  //   L=252 (minervini-primary-base) m >= 13            -> was 14; ONE session tighter, accepted
  // The 252 tightening is a real, unrequested behaviour change on the one strategy the band exists
  // to keep entering. It rides because the owner chose a FIXED band over a depth-relative one, and
  // 13/265 = 4.9% is still a large hole; the direction is a refused entry, never a stranded exit.
  private static final int MATERIALITY_DENOMINATOR = 22;

  /**
   * Params whose value is a BAR COUNT, so a missing session shifts what the rule reads. Pinned by
   * {@code SwingCoverageProbeTest#everyDepthParamInTheSeededConfigsIsClassified}, a ratchet that
   * reads the SEEDED YAMLs rather than hand-built fixtures — the first draft cited a ratchet of this
   * name that was never written, and a hand-built "Manas" fixture in its place asserted a depth of
   * 20 when the real config declares {@code sma50} and yields 50.
   *
   * <p>A whitelist (rather than "max over all numeric params") is deliberate: the non-depth params
   * live in the same maps and are numerically LARGER than the real depths — Manas carries {@code
   * parabolic_dist_pct: 40} and {@code fast_pct: 35} — so a max-over-all-numerics rule would report
   * 40 where the true exit depth is 20.
   */
  private static final Set<String> DEPTH_PARAMS =
      Set.of(
          "period", "lookback", "atr_period", "parabolic_ma", "fast_bars", "max_bars",
          "fast", "slow", "signal");

  private SwingCoverageProbe() {}

  /** Package-visible for the ratchet test, which asserts the seeded configs declare nothing else. */
  static Set<String> depthParams() {
    return DEPTH_PARAMS;
  }

  /**
   * One coverage reading over the last {@code lookbackBars} bars.
   *
   * @param lookbackBars the window depth probed (bars)
   * @param windowStart first session in the probed window, {@code null} when not determinable
   * @param missing calendar trading days inside the window with no bar, ascending
   * @param determinable false when no claim can be made (empty series, zero depth, or a year the
   *     bundled calendar does not cover) — callers MUST treat this as "unknown", never "complete"
   */
  public record Coverage(
      int lookbackBars, int windowSessions, LocalDate windowStart, List<LocalDate> missing,
      boolean determinable) {

    public Coverage {
      missing = missing == null ? List.of() : List.copyOf(missing);
    }

    /** Any hole at all. Diagnostic; {@link #materiallyIncomplete()} is what decisions key on. */
    public boolean incomplete() {
      return determinable && !missing.isEmpty();
    }

    /**
     * A hole big enough to plausibly move the value — see {@link #MATERIALITY_DENOMINATOR}.
     *
     * <p>Measured against {@code windowSessions} (bars held + holes, i.e. the sessions the probed
     * span actually covers) rather than the DECLARED {@code lookbackBars}. The two differ whenever
     * the series is shorter than the declared depth — a warming symbol with 25 bars under a 50-bar
     * SMA — and using the declared depth there would halve the apparent fraction and silently
     * under-report exactly the thin-history symbols most sensitive to a missing bar.
     */
    public boolean materiallyIncomplete() {
      return incomplete() && missing.size() * MATERIALITY_DENOMINATOR > windowSessions;
    }

    /**
     * "This coverage cannot be vouched for" — incomplete OR unknown. The fail-CLOSED test the ENTRY
     * decision must use, and the observation test the EXIT side must use. ⚠️ Do NOT reach for
     * {@link #materiallyIncomplete()} at either site — that was the shipped defect (cross-vendor
     * review, 2026-08-10, caught before merge).
     *
     * <p>Named for the PROPERTY rather than the action deliberately: an earlier draft called it
     * {@code blocksEntry}, which read as a promise that exits block too once the exit path started
     * using it. Exits do not block. They alert.
     *
     * <p>{@link #undeterminable} builds {@code missing = List.of()} with {@code determinable =
     * false}, so {@code incomplete()} is false and {@code materiallyIncomplete()} is therefore false
     * too. Both entry paths keyed on it, which meant a probe that FAILED — an exception, a year
     * outside the bundled calendar (the CD-2 cliff), an invalid bar, a depth-extraction error
     * degrading to depth zero — silently PERMITTED live evaluation. A data-coverage safety gate that
     * fails open on its own failure is worse than no gate, because it reports as a gate.
     *
     * <p>The class contract already said this ("{@code determinable = false} — an explicit 'no
     * claim', never an exception and never a false 'complete'"); nothing consumed it that way. This
     * method is that contract made callable, so a future entry path cannot re-open the hole by
     * reaching for the obvious-looking predicate.
     *
     * <p>Entry only. EXITS stay non-blocking by design: refusing to evaluate an exit on thin data
     * strands an open position, which is the strictly worse failure — you can always decline to
     * ENTER, you cannot decline to LEAVE forever (the #694 doctrine, same shape as paper
     * tick-freshness).
     */
    public boolean notProvenSound() {
      return !determinable || materiallyIncomplete();
    }

    /** Compact ops rendering including the fraction, so an alert shows WHY it tripped. */
    public String describe() {
      if (!determinable) {
        return "coverage undeterminable";
      }
      if (missing.isEmpty()) {
        return "complete over " + lookbackBars + "-bar window";
      }
      return missing.size()
          + " of "
          + windowSessions
          + " sessions missing ("
          + (missing.size() * 100 / Math.max(1, windowSessions))
          + "% of the probed span, declared depth "
          + lookbackBars
          + ", "
          + missing.get(0)
          + ".."
          + missing.get(missing.size() - 1)
          + ")";
    }
  }

  /** A reading that makes no claim — the safe default for every uncertain input. */
  public static Coverage undeterminable(int lookbackBars) {
    return new Coverage(lookbackBars, 0, null, List.of(), false);
  }

  /**
   * The deepest window the ENTRY decision actually reads: the max depth across only those indicators
   * reachable from the gate expression plus those participating in scoring — precisely {@code
   * EntryEvaluator}'s inputs. Exit rules are excluded BY DESIGN; a stretched window under a {@code
   * trailing_stop} cannot change whether an entry fires, and refusing on it would contradict the
   * exit-side ruling that a stretched window is never grounds to refuse.
   *
   * <p>Returns 0 when nothing depth-bearing is read, which callers treat as "no window to probe".
   */
  public static int entryLookbackBars(StrategyDefinition definition) {
    try {
      if (definition == null || definition.indicators() == null) {
        return 0;
      }
      Set<String> read = new HashSet<>();
      collectGateAliases(definition.gate(), read);
      int deepest = 0;
      for (StrategyDefinition.IndicatorSpec spec : definition.indicators()) {
        if (spec == null || spec.alias() == null) {
          continue;
        }
        // scoring() == the indicator carries a normalize mapping, i.e. it feeds the composite
        if (read.contains(spec.alias()) || spec.scoring()) {
          deepest = Math.max(deepest, deepestParam(spec.params()));
        }
      }
      return deepest;
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private static void collectGateAliases(GateNode node, Set<String> out) {
    switch (node) {
      case null -> {
        return;
      }
      case GateNode.All all -> all.children().forEach(child -> collectGateAliases(child, out));
      case GateNode.Any any -> any.children().forEach(child -> collectGateAliases(child, out));
      case GateNode.Not not -> collectGateAliases(not.child(), out);
      case GateNode.Crossover x -> {
        out.add(x.fast());
        out.add(x.slow());
      }
      case GateNode.Crossunder x -> {
        out.add(x.fast());
        out.add(x.slow());
      }
      case GateNode.Expression e -> {
        out.add(e.left());
        if (e.rightOperand() != null) {
          out.add(e.rightOperand());
        }
      }
    }
  }

  /**
   * The deepest window ANY rule reads — every declared indicator plus every exit rule, plus the built
   * bank's own normalized warm-up. Used by the EXIT pass, whose output is a detective alert rather
   * than a refusal, so over-scoping costs a little alert precision and never a stranded position.
   *
   * <p>Both inputs are needed and neither subsumes the other: {@code unstableBars()} is precise for
   * composites ({@code MACD_HIST -> slow + signal - 2}) that the param estimate under-counts, while
   * exit-rule depths are invisible to the bank entirely — Manas's {@code atr_period: 20} builds an
   * ATR INSIDE {@code ExitEvaluator}, never as a bank alias.
   *
   * <p>{@code unstableBars()} had zero production callers when this was written, so any failure
   * reading the bank degrades to the param estimate rather than propagating.
   *
   * <p><b>Known limitation:</b> depths are expressed in the indicator's OWN timeframe, so a {@code
   * 1w} SMA(50) contributes 49 rather than ~250 daily sessions. Every live swing indicator is {@code
   * 1d}; closing this needs a timeframe-ratio conversion, deliberately not built.
   */
  public static int exitLookbackBars(StrategyDefinition definition, IndicatorBank bank) {
    int deepest = 0;
    try {
      if (definition != null) {
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
      }
      if (bank != null) {
        for (IndicatorBank.Bound bound : bank.all().values()) {
          if (bound != null && bound.indicator() != null) {
            deepest = Math.max(deepest, bound.indicator().unstableBars());
          }
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
   * <p><b>Deliberate blind spot:</b> the span starts at the OLDEST bar held, so absence at the
   * LEADING edge is invisible — a contiguous 100-bar series probed at depth 252 reports complete
   * rather than "152 short". That is the correct call here: a short history is a warm-up condition
   * the indicators already handle by returning null, not a hole punched through a window, and
   * treating it as a hole would refuse every newly-listed symbol.
   *
   * <p>Never throws. {@link MarketCalendar#isTradingDay} raises on a year outside the bundled
   * resource (CD-2), and the 520-day warmup window crosses that boundary every January, so the span
   * is coverage-checked FIRST and any uncovered year degrades the whole reading to undeterminable.
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
      return new Coverage(lookbackBars, dates.size() + missing.size(), first, missing, true);
    } catch (RuntimeException e) {
      return undeterminable(lookbackBars);
    }
  }
}
