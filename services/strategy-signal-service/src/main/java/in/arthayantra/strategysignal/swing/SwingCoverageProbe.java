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
   * @param lookbackBars the DECLARED indicator depth this reading is about (bars). NOT the number
   *     of bars read — the entry probe reads {@link #DEPTH_SLACK} more than this.
   * @param windowSessions trading sessions spanned by the bars actually READ (held + holes). The
   *     footprint. Grows with slack.
   * @param materialityBasis trading sessions spanned by the DECLARED depth alone (held + holes
   *     inside it). The denominator {@link Coverage#materiallyIncomplete()} divides by, held apart
   *     from {@code windowSessions} precisely so widening the footprint cannot loosen the gate.
   * @param windowStart first session in the probed window, {@code null} when not determinable
   * @param missing calendar trading days inside the window with no bar, ascending
   * @param determinable false when no claim can be made (empty series, zero depth, or a year the
   *     bundled calendar does not cover) — callers MUST treat this as "unknown", never "complete"
   */
  public record Coverage(
      int lookbackBars, int windowSessions, int materialityBasis, LocalDate windowStart,
      List<LocalDate> missing, boolean determinable) {

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
     * <p>Measured against {@link #materialityBasis} — the sessions the DECLARED depth spans (bars
     * held + holes inside it) — never against {@link #windowSessions}, the sessions the probe
     * actually READ. On the plain {@link #probe} the two are equal and this is the historic
     * behaviour. They diverge on {@link #probeEntry}, which reads {@link #DEPTH_SLACK} bars past the
     * declared depth, and keeping them apart is the whole point: a hole anywhere in the wider
     * footprint counts in the NUMERATOR, while the denominator stays pinned to the declared depth,
     * so widening the probe can only ever tighten the gate. Dividing by the widened span is exactly
     * the defect this separation replaces — it made {@code 1 x 22 > 23} false and silently reopened
     * the one-hole depth-20 case the 21 -> 22 recalibration existed to refuse.
     *
     * <p>Basis is a SPAN, not the declared number: it is bars-held + holes, so it collapses to the
     * series length whenever the series is shorter than the declared depth — a warming symbol with
     * 25 bars under a 50-bar SMA. Using the declared 50 there would halve the apparent fraction and
     * silently under-report exactly the thin-history symbols most sensitive to a missing bar.
     */
    public boolean materiallyIncomplete() {
      return incomplete() && missing.size() * MATERIALITY_DENOMINATOR > materialityBasis;
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
      String span =
          materialityBasis == windowSessions
              // The plain probe, i.e. every EXIT reading: byte-identical to the pre-split text.
              // Cross-vendor review Major — this string reaches the exit error log and the operator
              // alert (SwingBatchEngine:974, :984), so changing it here breaks the exit-path byte
              // identity this change claims. Only a WIDENED entry reading gets the new wording.
              ? (missing.size() * 100 / Math.max(1, windowSessions)) + "% of the probed span"
              : (missing.size() * 100 / Math.max(1, materialityBasis))
                  + "% of the declared-depth span "
                  + materialityBasis;
      return missing.size()
          + " of "
          + windowSessions
          + " sessions missing ("
          + span
          + ", declared depth "
          + lookbackBars
          + ", "
          + missing.get(0)
          + ".."
          + missing.get(missing.size() - 1)
          + ")";
    }
  }

  /**
   * Extra bars the ENTRY probe reads beyond the declared indicator depth, to cover the gap between
   * a DECLARED depth and the rows the evaluator actually touches. Two, measured in the live
   * evaluator, for two independent reasons:
   *
   * <ul>
   *   <li>an indicator of declared depth D reads the CURRENT bar plus the prior D — {@code
   *       VOLUME_RATIO(20)} touches 21 rows, not 20; and
   *   <li>{@code crossover}/{@code crossunder} read the PREVIOUS value of each operand as well as
   *       the current one, so a D-deep operand under a crossover needs one further bar behind that.
   * </ul>
   *
   * <p>Deliberately a flat widening rather than a per-indicator warm-up calculation: probing MORE
   * bars can only find MORE holes, never fewer, so erring wide errs fail-CLOSED, the safe side of an
   * ENTRY gate by this class's own doctrine. A per-indicator model would be tighter and is exactly
   * the kind of derived constant that was wrong here in the first place.
   *
   * <p>⚠️ <b>This was 2, then 0, and is 2 again — the history is the point.</b> At 2 it originally
   * LOOSENED the gate it was built to tighten, because materiality then divided by the probed span:
   * widening the probe widened the denominator, so one hole in a depth-20 strategy stopped being
   * material ({@code 1 x 22 > 23} is false) and the 21 -> 22 recalibration was silently undone. Two
   * correct-looking changes composing into a defect. It was retreated to 0 to restore a KNOWN state
   * — footprint gap open, materiality calibrated — rather than trade a documented gap for an
   * undocumented loosening.
   *
   * <p>It is 2 again only because the two quantities are now separate: {@link
   * Coverage#windowSessions} is the footprint (grows with slack) and {@link
   * Coverage#materialityBasis} is the declared-depth span (does not). A hole in the slack region
   * counts in the numerator against an unchanged denominator, so slack now moves the gate in one
   * direction only — tighter. Verified by holding every calibration case fixed while slack goes
   * 0 -> 2 ({@code SwingCoverageProbeTest#slackWidensTheFootprintWithoutMovingTheMaterialityBand}).
   *
   * <p>Package-visible so the depth ratchet can assert against it instead of freezing a second copy
   * of the number — a ratchet that hardcodes what it guards drifts silently.
   *
   * <p>Applied to the ENTRY probe only, and applied INSIDE {@link #probeEntry} rather than folded
   * into {@link #entryLookbackBars}: the probe needs the declared depth and the widened depth as two
   * separate numbers, so a caller that pre-widens leaves it no way to compute the basis. {@link
   * #probeExit} widens by the held span instead of by this constant — the exit's footprint is a
   * measured quantity, not a slack allowance.
   *
   * <p>An earlier revision of this paragraph justified leaving the exit narrow on the grounds that
   * "under-probing there costs an alert rather than a wrong trade". That is wrong and {@link
   * #probeExit} documents why: the missed alert is precisely the one covering a position whose
   * trail level IS being computed off bars the probe never looked at.
   */
  static final int DEPTH_SLACK = 2;

  /** A reading that makes no claim — the safe default for every uncertain input. */
  public static Coverage undeterminable(int lookbackBars) {
    return new Coverage(lookbackBars, 0, 0, null, List.of(), false);
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
      // Returns the DECLARED depth, deliberately un-widened. A declared depth is NOT the number of
      // bars the evaluator reads — see DEPTH_SLACK for the two measured reasons — but the widening
      // belongs to probeEntry, which needs BOTH numbers: the wide one to find holes, the declared
      // one as the materiality denominator. Folding slack in here would hand the probe a single
      // pre-widened number and leave it no way to tell the two apart, which is precisely how the
      // widening came to loosen the gate the first time.
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
   * The deepest WARM-UP any exit rule needs — every declared indicator plus every exit rule, plus the
   * built bank's own normalized warm-up. Used by the EXIT pass, whose output is a detective alert
   * rather than a refusal, so over-scoping costs a little alert precision and never a stranded
   * position.
   *
   * <p>⚠️ This is a warm-up depth, NOT the span the exit reads. It was described as "the deepest
   * window ANY rule reads" until 2026-08-21, and that phrasing is what let the exit probe be sized
   * on it alone: the peak-since-entry scan and the entry-pinned ATR reach back to {@code entryIndex},
   * which on a held position is arbitrarily further than this number. Callers must add the held span
   * — {@link #probeExit} does.
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
    return measure(series, lookbackBars, lookbackBars, calendar);
  }

  /**
   * The ENTRY reading: probes {@code declaredDepth + }{@link #DEPTH_SLACK} bars but keeps the
   * materiality denominator at the declared depth's own span.
   *
   * <p>Use this at every entry site and pass the raw {@link #entryLookbackBars} result. Do NOT
   * pre-widen and call {@link #probe} — that hands one number where two are needed and reproduces
   * the composed defect DEPTH_SLACK documents.
   *
   * <p>The direction is one-way by construction: the wider read can only add to {@code missing}
   * (the numerator) while {@code materialityBasis} is computed from the declared depth alone, so
   * slack tightens the gate or leaves it alone, and can never loosen it.
   */
  public static Coverage probeEntry(
      List<EngineCandle> series, int declaredDepth, MarketCalendar calendar) {
    if (declaredDepth <= 0) {
      return undeterminable(declaredDepth);
    }
    return measure(series, declaredDepth + DEPTH_SLACK, declaredDepth, calendar);
  }

  /**
   * How many trailing bars the EXIT pass must READ for {@code definition} — the UNION of the
   * current-anchored window and every entry-anchored operand's reach. Feed the result to {@link
   * #probeExit} as {@code footprintBars}.
   *
   * <h2>Union, not sum (cross-vendor review, 2026-08-24 round 2)</h2>
   *
   * These windows OVERLAP — both end at the current bar — so adding them double-counts. The first
   * cut returned {@code declared + heldBars + prefix}; on live Manas that is {@code 50 + heldBars +
   * 39 = heldBars + 89} where the real requirement is {@code max(50, heldBars + 1 + 59)}. It probed
   * 29 bars no exit operand reads, which is the SAME false-page defect this class removes for
   * Minervini, reintroduced along a different axis.
   *
   * <p>The {@code + 1} is not slop: a suffix of {@code held + 1 + reach} bars starts exactly at
   * {@code entryIndex - reach}, counting the entry bar itself once. It also matters at {@code reach
   * = 0} — true range at the entry bar reads the PREVIOUS close, so the oldest pre-entry candle is a
   * real dependency, not a rounding cushion.
   *
   * <h2>Operand-aware, because most exits do not read the hold at all</h2>
   *
   * {@code minervini}'s exits are an entry-PRICE stop and a {@code basis: indicator} trail on {@code
   * sma50}, and {@code ExitEvaluator}'s indicator branch DISCARDS the peak-since-entry {@code
   * trailing()} computes ({@code ExitEvaluator:569,659-670}). Nothing there reads the held span, so
   * widening would turn a hole in irrelevant history into a per-position ARMED page claiming the
   * stop/trail may be stretched — and under ARMED those pages are the ones carrying real money risk.
   *
   * @param heldBars {@code lastIndex - entryIndex}; contributes only via entry-anchored operands
   */
  public static int exitFootprintBars(
      StrategyDefinition definition, IndicatorBank bank, int heldBars) {
    int footprint = exitLookbackBars(definition, bank); // current-anchored window
    int held = Math.max(0, heldBars);
    if (definition == null || definition.exitRules() == null) {
      return footprint;
    }
    for (StrategyDefinition.ExitRuleSpec rule : definition.exitRules()) {
      int reach = entryReachBars(rule);
      if (reach >= 0) {
        // held span, the entry bar itself, then however far this operand reaches BEFORE entry
        footprint = Math.max(footprint, held + 1 + reach);
      }
    }
    return footprint;
  }

  /**
   * Bars this exit rule reads BEFORE the entry bar, or {@code -1} when it is not anchored at entry at
   * all. Traced against {@code ExitEvaluator} on 2026-08-24.
   *
   * <ul>
   *   <li>{@code basis: atr_multiple} → {@link #atrDecayLength}. It resolves {@code atrAtEntry}, the
   *       RECURSIVE Wilder ATR evaluated AT the entry bar, so its history precedes entry. All four
   *       {@code atrAtEntry} call sites sit under this basis.
   *   <li>a {@code trailing_stop} whose {@code basis} is anything other than {@code indicator} → 0.
   *       {@code premium_pct} and {@code index_points} resolve against {@code favorableExtreme},
   *       which scans {@code entryIndex..index} and reads NO pre-entry bar.
   *   <li>{@code time_stop} → 0. Both forms depend on the hold span: {@code max_bars} counts {@code
   *       index - entryIndex} (a hole changes the count, delaying the exit) and {@code
   *       max_holding_days} explicitly scans {@code entryIndex + 1..index} reading each candle
   *       ({@code ExitEvaluator:692-705}). ⚠️ Missed in the first cut because {@code time_stop} has
   *       no {@code basis} param and the {@code max_bars} branch looks like pure index arithmetic.
   * </ul>
   *
   * <p>Everything else is current-bar anchored: {@code stop_loss basis: percent} (entry PRICE, no
   * series read), {@code square_off} ({@code primaryIndex - fast_bars}, SMA at {@code primaryIndex}),
   * {@code signal_exit} (bank at the current index), {@code scaled_exit} (entry price only).
   */
  static int entryReachBars(StrategyDefinition.ExitRuleSpec rule) {
    if (rule == null) {
      return -1;
    }
    if ("time_stop".equals(rule.type())) {
      return 0;
    }
    if (rule.params() == null) {
      return -1;
    }
    String basis = String.valueOf(rule.params().get("basis"));
    if ("atr_multiple".equals(basis)) {
      Object period = rule.params().get("atr_period");
      int n = period instanceof Number num ? num.intValue() : 14;
      return atrDecayLength(n);
    }
    if ("trailing_stop".equals(rule.type()) && !"indicator".equals(basis)) {
      return 0;
    }
    return -1;
  }

  /** True when ANY exit rule reads bars anchored at {@code entryIndex} rather than the current bar. */
  static boolean readsFromEntryBar(StrategyDefinition definition) {
    if (definition == null || definition.exitRules() == null) {
      return false;
    }
    for (StrategyDefinition.ExitRuleSpec rule : definition.exitRules()) {
      if (entryReachBars(rule) >= 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * How far back an entry-pinned Wilder ATR of period {@code n} is still worth probing.
   *
   * <p>The engine's ATR is ta4j's {@code ATRIndicator} ({@code Ta4jIndicators#atr}), an MMA: each
   * value folds in the previous one, so influence DECAYS geometrically rather than ending at {@code
   * n}. Treating {@code atr_period} as the warm-up — which {@code unstableBars()} reports — under-
   * bounds the real dependency, and that was a review Critical. Decay after {@code k} bars is {@code
   * (1 - 1/n)^k}; this returns the first {@code k} below {@link #ATR_RESIDUAL_INFLUENCE}. At {@code
   * n=20} that is 59, and {@code 0.95^42 = 0.117} reproduces the figure the repo already recorded.
   *
   * <p>⚠️ A THRESHOLD, not a bound. A recursive indicator has no finite exact dependency, so the
   * honest reading is "holes closer than this can move the level enough to matter" — never "holes
   * beyond this cannot move it".
   */
  static int atrDecayLength(int n) {
    if (n <= 1) {
      return 0;
    }
    return (int) Math.ceil(Math.log(ATR_RESIDUAL_INFLUENCE) / Math.log(1.0 - (1.0 / n)));
  }

  /**
   * Residual seed influence at which an entry-pinned ATR's history stops being probed — see {@link
   * #atrDecayLength}. At {@code atr_period 20} (both live {@code manas-arora} slugs) this yields 59
   * bars.
   *
   * <p>⚠️ OWNER-SETTABLE ALERT POLICY, not a correctness constant, and flagged as such by review. The
   * recorded measurement establishes 12% residual after 42 bars and up to ±0.78% stop variance from a
   * differing series start — it does NOT establish that 5% residual is immaterial. Tighter means more
   * true detections and more ARMED pages on a live money path; looser means fewer of both.
   */
  static final double ATR_RESIDUAL_INFLUENCE = 0.05;

  /**
   * The EXIT reading: probes {@code footprintBars} (from {@link #exitFootprintBars}) while keeping the
   * materiality denominator at the DECLARED depth's own span.
   *
   * <p>Widening cannot loosen the gate, by the same one-way construction {@link #DEPTH_SLACK} relies
   * on: extra bars can only ADD to {@code missing} (the numerator) while {@code materialityBasis}
   * stays computed from the declared depth alone. A strategy with no entry-anchored operand gets
   * {@code footprintBars == declaredDepth} and reads byte-identically to the pre-fix call.
   */
  public static Coverage probeExit(
      List<EngineCandle> series, int declaredDepth, int footprintBars, MarketCalendar calendar) {
    if (declaredDepth <= 0) {
      return undeterminable(declaredDepth);
    }
    return measure(series, Math.max(declaredDepth, footprintBars), declaredDepth, calendar);
  }

  /**
   * @param probedBars how many trailing bars to READ (declared depth plus any slack)
   * @param declaredBars the depth the materiality fraction is taken over; {@code <= probedBars}
   */
  private static Coverage measure(
      List<EngineCandle> series, int probedBars, int declaredBars, MarketCalendar calendar) {
    if (series == null || series.isEmpty() || probedBars <= 0 || calendar == null) {
      return undeterminable(declaredBars);
    }
    try {
      int from = Math.max(0, series.size() - probedBars);
      List<LocalDate> dates = new ArrayList<>(series.size() - from);
      for (int i = from; i < series.size(); i++) {
        EngineCandle bar = series.get(i);
        if (bar == null || bar.bucketStart() == null) {
          return undeterminable(declaredBars);
        }
        dates.add(bar.bucketStart().withOffsetSameInstant(IST).toLocalDate());
      }
      LocalDate first = dates.get(0);
      LocalDate last = dates.get(dates.size() - 1);
      for (int year = first.getYear(); year <= last.getYear(); year++) {
        if (!calendar.coveredYears().contains(year)) {
          return undeterminable(declaredBars);
        }
      }
      Set<LocalDate> present = new HashSet<>(dates);
      List<LocalDate> missing = new ArrayList<>();
      for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
        if (calendar.isTradingDay(d) && !present.contains(d)) {
          missing.add(d);
        }
      }
      // The denominator is the DECLARED depth's own span, computed over the same held/holes
      // definition as windowSessions but restricted to the last `declaredBars` rows. When the series
      // is shorter than the declared depth the two coincide, which is the warming-symbol case
      // materiallyIncomplete() relies on.
      int declaredHeld = Math.min(declaredBars, dates.size());
      LocalDate declaredFirst = dates.get(dates.size() - declaredHeld);
      int holesInDeclared = 0;
      for (LocalDate d : missing) {
        if (!d.isBefore(declaredFirst)) {
          holesInDeclared++;
        }
      }
      return new Coverage(
          declaredBars,
          dates.size() + missing.size(),
          declaredHeld + holesInDeclared,
          first,
          missing,
          true);
    } catch (RuntimeException e) {
      return undeterminable(declaredBars);
    }
  }
}
