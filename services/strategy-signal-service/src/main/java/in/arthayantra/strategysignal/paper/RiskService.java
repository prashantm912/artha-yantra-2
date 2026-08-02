package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.paper.RiskSettingsRepository.Setting;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;

/**
 * Global paper-risk limits (A12 / FP-42). Tripping the daily loss pauses ENTRY emission for the IST
 * day (exit/stop evaluation continues — the gate is consulted only by {@code emitEntry}); the kill
 * switch is one-click pause-all; {@code max_open_paper_positions} caps concurrency. Every trip / flip
 * writes a {@code risk_audit} row (trips deduped per IST day). Limits live on DB rows, never YAML.
 */
@Service
public class RiskService {

  /** The limit keys. */
  public static final String KILL_SWITCH = "kill_switch";
  public static final String MAX_OPEN = "max_open_paper_positions";
  public static final String DAILY_LOSS = "daily_loss_limit";
  public static final String DAILY_PROFIT_TARGET = "daily_profit_target";
  public static final String MAX_DEPLOYMENT_PCT = "max_deployment_pct";
  /** F9 portfolio heat cap: max total SPAN margin-at-risk as % of book equity (options books). */
  public static final String HEAT_CAP_PCT = "heat_cap_pct";
  /** When ON, an emitted ENTRY auto-opens a paper position at the suggested qty (no manual take). */
  public static final String AUTO_PAPER_TRADE = "auto_paper_trade";
  /**
   * M40 governor-coverage marker: a Manas §3.4.3 pyramid ADD, or (2026-08-02) a FRESH entry, blocked by
   * the family's portfolio open-risk cap. NOT a {@code risk_settings} row — the cap itself is the
   * pyramid policy's own {@code artha.manas-arora.pyramid.max-portfolio-risk-pct} knob, not a
   * DB-editable limit — so this key exists only as a {@code risk_audit} label, mirroring the other
   * trip-audited rails' treatment. One label covers both kinds; the free-text {@code detail} passed to
   * {@link #recordPyramidRiskCapBreach} says which.
   */
  public static final String PYRAMID_RISK_CAP = "pyramid_risk_cap";

  /**
   * Round 7 (owner-approved, 2026-08-02): the rail label for a Manas entry refused because its
   * aggregate risk could NOT be safely calculated (unsupported side, undefined governing stop, or
   * non-positive equity) — {@link ManasRiskOutcome}'s non-{@code CALCULATED_BREACH} refusal
   * values. Deliberately a DIFFERENT rail from {@link #PYRAMID_RISK_CAP}: this is not a breach,
   * carries no {@code risk_audit} row, and does not consume that rail's per-day dedup key — see
   * {@link #manasAggregateRiskCheck}'s javadoc for why conflating the two silently suppressed a
   * later, genuine breach.
   */
  public static final String MANAS_RISK_UNCOMPUTABLE = "manas_risk_uncomputable";

  private static final Logger log = LoggerFactory.getLogger(RiskService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  /** Upstox caps a margin basket at 20 legs; the paper book is small, but cap defensively. */
  private static final int MAX_LEGS = 20;

  private final RiskSettingsRepository settings;
  private final PaperPositionRepository positions;
  private final PaperAccountService account;
  private final PaperMarginClient marginClient;
  private final NotifierClient notifier;
  private final Clock clock;
  private final ManasGoverningStopCache governingStopCache;
  private final PyramidRiskCapAuditor pyramidRiskCapAuditor;

  /**
   * F9 master enforcement gate (default OFF → advisory: the heat cap audits + ntfy-alerts on a breach
   * but does not BLOCK a new entry until the owner flips it after a clean advisory week). The
   * daily-loss/kill/deployment governors are unaffected — they enforce on their own DB-setting flags.
   */
  private final boolean enforcementEnabled;

  /**
   * M40 cross-vendor review Critical 1/2 fix (2026-08-02): the SAME property key + default {@code
   * in.arthayantra.strategysignal.manas.ManasPyramidPolicy} reads, so the two never drift — the
   * authoritative write-time check ({@link #manasAggregateRiskWouldCross}) and the emission-time
   * estimate share one knob.
   */
  private final BigDecimal manasAggregateRiskCapPct;

  /** Per-day, per-cap trip dedup (key -> IST day it last tripped); re-armed on an {@code update}. */
  private final java.util.Map<String, LocalDate> trippedOn = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Composite dedup-key delimiter (audit M34). A space would let {@code ("a b", "c")} and
   * {@code ("a", "b c")} collide; a NUL char never appears in a book slug or a limit-key constant.
   * Written as the integer literal {@code 0} so the source stays plain ASCII (no NUL byte / no
   * {@code \\u0000} escape that a JSON edit pipeline can re-inject as a real NUL).
   */
  private static final char DEDUP_DELIM = 0;

  private static String tripKey(String book, String key) {
    return book + DEDUP_DELIM + key;
  }

  /** Wires the risk inputs. */
  public RiskService(
      RiskSettingsRepository settings,
      PaperPositionRepository positions,
      PaperAccountService account,
      PaperMarginClient marginClient,
      NotifierClient notifier,
      Clock clock,
      @Value("${artha.paper.risk.enabled:false}") boolean enforcementEnabled,
      @Value("${artha.manas-arora.pyramid.max-portfolio-risk-pct:6.0}")
          BigDecimal manasAggregateRiskCapPct,
      ManasGoverningStopCache governingStopCache,
      PyramidRiskCapAuditor pyramidRiskCapAuditor) {
    this.settings = settings;
    this.positions = positions;
    this.account = account;
    this.marginClient = marginClient;
    this.notifier = notifier;
    this.clock = clock;
    this.enforcementEnabled = enforcementEnabled;
    this.manasAggregateRiskCapPct = manasAggregateRiskCapPct;
    this.governingStopCache = governingStopCache;
    this.pyramidRiskCapAuditor = pyramidRiskCapAuditor;
  }

  /** Whether a book's emitted ENTRY signals should auto-open a paper position (the auto-paper toggle). */
  public boolean autoPaperTradeEnabled(String book) {
    return boolFlag(book, AUTO_PAPER_TRADE);
  }

  /** Whether a new ENTRY may be emitted right now for a book (the per-book {@code emitEntry} gate). */
  public boolean entryAllowed(String book) {
    return entryVeto(book).isEmpty();
  }

  /**
   * The DEPLOYMENT rail alone, projected against a candidate order's own notional — {@code true} when
   * filling it would push the book PAST its cap.
   *
   * <p>Deliberately separate from {@link #entryVeto}, and deliberately PURE: no {@code risk_audit} row,
   * no ntfy, no per-day dedup, no reads that depend on call order. That purity is what makes it safe to
   * call from the fill path. {@code entryVeto} is not: its rails carry once-per-entry side-effects, which
   * is precisely why {@code openOrder} must never re-run it ({@code
   * PaperManualOrderGovernorIntegrationTest.takenPathOpenOrderIsUngatedByDesign} — "or a taken entry
   * would be double-charged"). That test forbids re-running the GOVERNOR; it does not forbid the writer
   * from validating. This method charges nothing, so calling it twice costs nothing.
   *
   * <p>Why the deployment rail specifically cannot be decided at emission: it is the ONE rail whose
   * answer depends on the candidate's SIZE, and at emission neither the tradeable leg nor the sized
   * quantity nor the slippage-adjusted fill price exists yet. The old form could therefore only refuse
   * an entry once the book was ALREADY at or over the cap, so the order that CROSSED the cap was always
   * admitted in full.
   *
   * <p>{@code >} not {@code >=}: landing exactly ON the cap is inside it. The already-at-cap case stays
   * {@link #entryVeto}'s job; this exists solely to catch the crossing order.
   */
  public boolean deploymentWouldCross(String book, BigDecimal candidateDeployment) {
    if (candidateDeployment == null || candidateDeployment.signum() <= 0) {
      return false;
    }
    Optional<Setting> deployment = settings.get(book, MAX_DEPLOYMENT_PCT);
    if (!enabled(deployment)) {
      return false;
    }
    BigDecimal value = deployment.get().value().path("value").decimalValue();
    BigDecimal cap = account.equity(book).multiply(value).divide(BigDecimal.valueOf(100));
    return account.capitalUsed(book).add(candidateDeployment).compareTo(cap) > 0;
  }

  /** The deployment cap in rupees for a book, or empty when the rail is disabled (for refusal detail). */
  public Optional<BigDecimal> deploymentCap(String book) {
    Optional<Setting> deployment = settings.get(book, MAX_DEPLOYMENT_PCT);
    if (!enabled(deployment)) {
      return Optional.empty();
    }
    BigDecimal value = deployment.get().value().path("value").decimalValue();
    return Optional.of(account.equity(book).multiply(value).divide(BigDecimal.valueOf(100)));
  }

  /** The Manas aggregate open-risk cap (%), for a refusal-detail message. */
  public BigDecimal manasAggregateRiskCapPct() {
    return manasAggregateRiskCapPct;
  }

  /**
   * M40 cross-vendor review Critical 1+2 fix (2026-08-02): the aggregate open-risk rail, projected
   * against the ACTUAL fill {@code PaperService#openOrder} is about to persist — the authoritative
   * check at the paper-position WRITE, under the same book lock {@link #deploymentWouldCross} is
   * called from. This is deliberately NOT a replacement for {@code
   * ManasPyramidPolicy#wouldBreachRiskCap}'s emission-time estimate (off the candle close, before the
   * leg/qty/slippage exist) — it is the closer-to-truth backstop the review found missing, mirroring
   * why {@link #deploymentWouldCross} itself exists only here and not at emission.
   *
   * <p>Two defects this closes together (both apply from the SAME read of {@link
   * PaperPositionRepository#listOpen}, the paper ledger's own truth — not "is this symbol currently
   * held" derived from active signal anchors, which a reconciled-but-orphaned paper row can disagree
   * with):
   *
   * <ul>
   *   <li><b>Slippage.</b> The emission-time estimate uses the bar close; the real fill adds the
   *       equity BUY slippage ({@code ltp_slippage/v1}, 5 bps fallback), so a preview that lands
   *       exactly ON the cap can have its ACTUAL persisted risk land fractionally over it.
   *   <li><b>Averaging onto an existing row.</b> {@code PaperService#upsertPosition} averages a 2nd
   *       fill for the same {@code (book,exchange,tradingsymbol,side)} key into ONE row and KEEPS the
   *       row's ORIGINAL bracket. A naive "existing total + this fill's own qty×stopDistance" therefore
   *       undercounts: the true post-fill risk for that symbol is {@code (newAvg − governingStop) ×
   *       newQty}, computed against the SAME {@link PaperEmissionGuard#effectiveStop} the row will
   *       actually retain, not the request's own stop. This method looks up the existing row (if any)
   *       for the exact key, replaces its OLD contribution with the PROJECTED post-fill one, and only
   *       then compares the total to equity.
   * </ul>
   *
   * <p><b>Critical 3 (round 3, owner ruling, 2026-08-02) — fixed for the RISK CALCULATION ONLY, IN
   * MEMORY, never persisted.</b> {@code paper_positions.stop_loss} is set once at open and would stay
   * stale forever if this rail read it directly — but {@code stop_loss} is ALSO the intraday
   * disaster-stop the paper module's 15-second bracket poller reads with no book filter, so writing
   * the daily Chandelier trail there (round 1) OR into any new persisted column (round 2, a
   * dedicated {@code manas_governing_stop} column) both risked or would have coupled the risk figure
   * to an intraday-exit surface. {@code effectiveStop} instead reads {@link
   * ManasGoverningStopCache}, an IN-MEMORY-ONLY map ({@code EmissionGuard#cacheManasGoverningStop})
   * that no {@code stop_loss}-reading path ever sees or can see — M40 fixes the risk calculation;
   * {@code stop_loss} itself, and whether Manas should ever become intraday-trail-managed, are
   * explicitly OUT of scope here (a separate, later, owner decision — see the PR receipt). The cache
   * is empty on a fresh boot / before the trail arms, falling back to the persisted {@code stopLoss}
   * — the SAME reading this rail had before any of M40, not a regression. <b>This fallback is the
   * NORMAL case, not a rare edge one</b> (#1228, 2026-08-02): the trail only arms at +9% gain, zero
   * of the six live Manas positions qualified that day, so this cap runs on the persisted-stop basis
   * most of the time in practice — closer to a ~6-position rail than a true trailing-risk one until
   * the arm rate is actually observed.
   *
   * <p>Manas-only ({@code BookResolver.MANAS_ARORA}) and deliberately PURE like {@link
   * #deploymentWouldCross} — no audit row, no ntfy, safe to call from the fill path; the caller emits
   * the refusal audit via {@link #recordPyramidRiskCapBreach}, mirroring the deployment rail's split.
   *
   * <p><b>Critical 2 (round 4, cross-vendor review, 2026-08-02) — a safety gate FAILS CLOSED when it
   * cannot compute, never open.</b> Three earlier branches treated "I don't know the risk" as "the
   * risk is zero" — non-positive equity returned {@code false} (does not cross, so the fill
   * proceeds), an existing lot with NO governing stop at all (neither cached nor persisted)
   * contributed zero to the projected total, and a genuinely fresh entry with no requested stop
   * ALSO contributed zero. All three inverted the cap's purpose: an insolvent/zeroed book, or a
   * stopless fill, could add UNBOUNDED risk and sail through undetected. Each now returns {@code
   * true} (refuse) instead — "cannot compute" means refuse, matching every other safety gate in
   * this class (e.g. {@link PaperEmissionGuard#suggestedQty} fails closed on an unresolved
   * instrument, checklist "Money/data fidelity"). The {@code !MANAS_ARORA.equals(book) ||
   * fillPrice == null || qty <= 0} guard above stays {@code false} — that is genuine
   * scope-limiting (the cap does not apply outside Manas) and defensive validation already enforced
   * upstream ({@code PaperService#openOrder} rejects non-positive qty before this is ever called),
   * not a "cannot compute" case.
   *
   * <p><b>Critical 2 (round 5, cross-vendor review, 2026-08-02) — the round-4 fix covered the
   * CANDIDATE's matching row; the aggregate SUM over every OTHER open row in the book still failed
   * open.</b> {@link PaperEmissionGuard#openRiskInr} silently SKIPS a stopless position when
   * summing (contributes 0), and its {@code avgEntry − stop} arithmetic is BUY-only — a SHORT's
   * real risk (stop ABOVE entry) also sums to zero. Either shape lets {@code existingTotal}
   * understate the book's true risk while this method validates only the one row matching the
   * candidate's own key. Fixed by sweeping EVERY open row in the book before computing any total:
   * a non-BUY side, or a row with no governing stop at all, now refuses the whole call. **Both
   * halves are LATENT, not live, as measured 2026-08-02** — zero open SELL rows exist in any book,
   * and every one of manas-arora's 6 open rows carries a {@code stopLoss}; state that plainly
   * rather than describing either as reachable today. (A prior draft of this class's javadoc
   * additionally cited a "known parked SELL row" to justify the BUY-only cache guard — that citation
   * was WRONG, sourced from a stale note, and has been retracted; see {@link
   * ManasGoverningStopCache}'s javadoc for the correction.) Failing closed on an unsupported side
   * was chosen over implementing SELL risk arithmetic specifically because there is no live row to
   * verify SELL arithmetic against — refusing what cannot be safely computed is testable and
   * correct regardless; a bespoke formula that has never run against real data would not be.
   *
   * <h2>{@link ManasRiskOutcome} — a TYPED result, not a boolean (round 7, owner-approved,
   * 2026-08-02)</h2>
   * Rounds 4–6 each added a new "cannot compute" refusal (an unsupported side, an undefined
   * governing stop, non-positive equity), and each one returned the SAME {@code true} a genuine
   * calculated breach returns. {@code PaperService#openOrder} could not tell them apart, so it
   * treated EVERY refusal as a calculated breach and audited it via {@link
   * #recordPyramidRiskCapBreach} — which consumes the ONE-PER-IST-DAY-PER-BOOK dedup key. An
   * accidental manual SELL (a cannot-calculate refusal needing no real breach at all) would
   * therefore silently SUPPRESS the audit/alert for a LATER, GENUINE breach on the SAME book the
   * SAME day — the exact "a fix reopens a hole elsewhere" shape this repo keeps hitting. {@link
   * ManasRiskOutcome#CALCULATED_BREACH} is the ONLY value the caller may pass to {@link
   * #recordPyramidRiskCapBreach}; every other refusal value must refuse the entry WITHOUT ever
   * touching that audit/dedup, with its own operator message explaining what actually happened.
   * Full six-round history of this method (three cross-vendor review rounds, each finding a real
   * gap in the shape before it) lives in PR #1221, not repeated inline here.
   */
  public ManasRiskOutcome manasAggregateRiskCheck(
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal fillPrice,
      BigDecimal requestStopLoss) {
    if (!BookResolver.MANAS_ARORA.equals(book) || fillPrice == null || qty <= 0) {
      return ManasRiskOutcome.ADMIT;
    }
    if (!"BUY".equals(side)) {
      // Round 6: the book-wide sweep below only validates sides already IN the book — an empty or
      // all-BUY book has nothing to reject, so a fresh non-BUY candidate would otherwise fall
      // through to the BUY-only arithmetic below and compute a false zero risk. Refuse the
      // incoming side directly, matching how an existing non-BUY row is already refused.
      return ManasRiskOutcome.UNSUPPORTED_SIDE;
    }
    BigDecimal equity = account.equity(book);
    if (equity.signum() <= 0) {
      // Round 4: cannot compute a percentage of non-positive equity.
      return ManasRiskOutcome.NON_POSITIVE_EQUITY;
    }
    List<PositionRow> open = positions.listOpen(book);
    for (PositionRow p : open) {
      if (!"BUY".equals(p.side())) {
        // Round 5: openRiskInr's avgEntry-minus-stop arithmetic is BUY-only — a SHORT's real risk
        // (stop ABOVE entry) would silently sum to zero instead. LATENT today (measured
        // 2026-08-02: zero open SELL rows in any book) — the invariant holds regardless.
        return ManasRiskOutcome.UNSUPPORTED_SIDE;
      }
      if (PaperEmissionGuard.effectiveStop(p, governingStopCache) == null) {
        // Round 5: openRiskInr SKIPS a stopless row when summing (contributes 0), so a fresh
        // candidate could be admitted against an aggregate that silently omits what it cannot
        // price. Any row in the book with no governing stop, not just the candidate's own
        // matching row. LATENT today (measured 2026-08-02: manas-arora's 6/6 open rows all carry
        // a stopLoss).
        return ManasRiskOutcome.UNDEFINED_GOVERNING_STOP;
      }
    }
    BigDecimal existingTotal = PaperEmissionGuard.openRiskInr(open, governingStopCache);
    PositionRow existing =
        open.stream()
            .filter(
                p ->
                    p.exchange().equals(exchange)
                        && p.tradingsymbol().equals(tradingsymbol)
                        && p.side().equals(side))
            .findFirst()
            .orElse(null);
    BigDecimal oldSymbolRisk =
        existing == null
            ? BigDecimal.ZERO
            : PaperEmissionGuard.openRiskInr(List.of(existing), governingStopCache);
    BigDecimal projectedSymbolRisk;
    if (existing != null) {
      // Mirrors PaperService#upsertPosition's averaging math exactly: the row's qty/avg move, its
      // stop does NOT (an add never re-brackets the original lot).
      long newQty = existing.qty() + qty;
      BigDecimal newAvg =
          existing
              .avgEntryPrice()
              .multiply(BigDecimal.valueOf(existing.qty()))
              .add(fillPrice.multiply(BigDecimal.valueOf(qty)))
              .divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP);
      // governingStop cannot be null here: existing is a member of `open`, and the book-wide sweep
      // above already returned UNDEFINED_GOVERNING_STOP if ANY row in `open` — including this one
      // — had no governing stop (round 5 superseded this branch's own round-4 null check, which is
      // now unreachable dead code; removed rather than left as redundant defensive noise).
      BigDecimal governingStop = PaperEmissionGuard.effectiveStop(existing, governingStopCache);
      projectedSymbolRisk =
          newAvg.subtract(governingStop).max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(newQty));
    } else {
      if (requestStopLoss == null) {
        // Round 4: a genuinely fresh Manas entry with no stop at all cannot have its risk computed.
        return ManasRiskOutcome.UNDEFINED_GOVERNING_STOP;
      }
      projectedSymbolRisk =
          fillPrice.subtract(requestStopLoss).max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(qty));
    }
    BigDecimal projectedTotal = existingTotal.subtract(oldSymbolRisk).add(projectedSymbolRisk);
    BigDecimal totalPct =
        projectedTotal.divide(equity, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    return totalPct.compareTo(manasAggregateRiskCapPct) > 0
        ? ManasRiskOutcome.CALCULATED_BREACH
        : ManasRiskOutcome.ADMIT;
  }

  /**
   * The result of {@link #manasAggregateRiskCheck} (round 7, owner-approved, 2026-08-02). Only
   * {@link #CALCULATED_BREACH} may be passed to {@link #recordPyramidRiskCapBreach} — every other
   * refusal value means the risk could not be safely calculated at all, and must refuse the entry
   * WITHOUT auditing it as a breach or consuming the per-day dedup key (see the method javadoc for
   * why: doing so silently suppressed a LATER, genuine breach the same day).
   */
  public enum ManasRiskOutcome {
    /** Not applicable (non-Manas book, a degenerate input {@code PaperService#openOrder} already
     *  validates) or the calculated aggregate stays within the cap — proceed, no audit either way. */
    ADMIT,
    /** A genuinely CALCULATED aggregate risk exceeds the cap. */
    CALCULATED_BREACH,
    /** The incoming candidate's side, or an existing row's side, is not {@code BUY} — Manas is
     *  long-only and there is no live SELL row to verify short-risk arithmetic against. */
    UNSUPPORTED_SIDE,
    /** An open row in the book — the candidate's own matching row, an unrelated row swept by the
     *  book-wide check, or a genuinely fresh candidate's own request — has no governing stop at
     *  all: neither a cached trail nor a persisted {@code stopLoss}. */
    UNDEFINED_GOVERNING_STOP,
    /** The book's equity is zero or negative — a percentage of it is undefined. */
    NON_POSITIVE_EQUITY
  }

  /**
   * The governor rail (a limit-key constant, e.g. {@link #KILL_SWITCH}) blocking a new ENTRY on a book
   * right now, or {@link Optional#empty()} when entry is allowed. This is the single source of truth for
   * the per-book gate: {@link #entryAllowed(String)} is a thin {@code isEmpty()} view, so both callers
   * apply IDENTICAL semantics AND identical audit side-effects (a daily-loss/profit/deployment/heat trip
   * writes its {@code risk_audit} row + ntfy here regardless of which caller triggered it; the kill-switch
   * and max-open rails write no audit, matching the emission path). Used by the manual paper-order path
   * ({@code PaperService.openManualOrder}) to surface the blocking rail in a 422 body.
   */
  public Optional<String> entryVeto(String book) {
    if (boolFlag(book, KILL_SWITCH)) {
      return Optional.of(KILL_SWITCH);
    }
    Optional<Setting> maxOpen = settings.get(book, MAX_OPEN);
    if (enabled(maxOpen)
        && positions.openCount(book) >= maxOpen.get().value().path("value").asInt(Integer.MAX_VALUE)) {
      return Optional.of(MAX_OPEN);
    }
    Optional<Setting> dailyLoss = settings.get(book, DAILY_LOSS);
    if (enabled(dailyLoss)) {
      BigDecimal limit = limitInr(book, dailyLoss.get().value());
      BigDecimal dayPnl = account.dayPnl(book);
      if (dayPnl.compareTo(limit.negate()) <= 0) {
        recordTrip(book, DAILY_LOSS, dayPnl, limit);
        return Optional.of(DAILY_LOSS);
      }
    }
    Optional<Setting> profitTarget = settings.get(book, DAILY_PROFIT_TARGET);
    if (enabled(profitTarget)) {
      BigDecimal target = limitInr(book, profitTarget.get().value());
      BigDecimal dayPnl = account.dayPnl(book);
      if (dayPnl.compareTo(target) >= 0) {
        recordTrip(book, DAILY_PROFIT_TARGET, dayPnl, target);
        return Optional.of(DAILY_PROFIT_TARGET);
      }
    }
    Optional<Setting> deployment = settings.get(book, MAX_DEPLOYMENT_PCT);
    if (enabled(deployment)) {
      // deployment is ALWAYS a % of equity (no mode field) — a live capital-state check, not a
      // one-shot day event, so it is audited directly each time it blocks (no per-day trip dedup).
      BigDecimal value = deployment.get().value().path("value").decimalValue();
      BigDecimal cap = account.equity(book).multiply(value).divide(BigDecimal.valueOf(100));
      BigDecimal used = account.capitalUsed(book);
      if (used.compareTo(cap) >= 0) {
        settings.audit(
            book,
            MAX_DEPLOYMENT_PCT,
            "TRIP",
            "open deployment " + used.toPlainString() + " ≥ cap " + cap.toPlainString());
        return Optional.of(MAX_DEPLOYMENT_PCT);
      }
    }
    // F9 portfolio heat cap — the open book's total SPAN margin as % of equity, blocks a new entry
    // when at/over the cap. Priced ONLY when enforcement is on: the margin call is a synchronous HTTP
    // round-trip to market-data and must NOT sit on the emission hot path in the default advisory-off
    // state. During the advisory week the owner watches heat via GET /api/v1/paper/margin-heat (+ the
    // per-position margin_snapshot annotation), then flips artha.paper.risk.enabled to enforce. Then
    // fail-soft: an unpriced book (analytics off / market-data down) cannot be assessed → never blocks.
    if (enforcementEnabled) {
      Optional<Setting> heatCap = settings.get(book, HEAT_CAP_PCT);
      if (enabled(heatCap)) {
        BigDecimal capPct = heatCap.get().value().path("value").decimalValue();
        BigDecimal heatPct = currentHeatPct(book);
        if (heatPct == null) {
          // Enforcement is ON but heat is unassessable (unpriced basket / >20 legs / analytics
          // down). Fail-soft still holds (never block on blindness) — but make the blind gate
          // VISIBLE, else the owner cannot tell "under cap" from "cannot see" (M1 review).
          log.warn("book '{}' heat-cap enforcement ON but heat unassessable — gate inert this entry", book);
          settings.audit(book, HEAT_CAP_PCT, "UNPRICED", "enforcement on, heat unassessable — entry allowed");
        } else if (heatPct.compareTo(capPct) >= 0) {
          recordHeatTrip(book, heatPct, capPct);
          return Optional.of(HEAT_CAP_PCT);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * The open book's SPAN margin as a % of its equity — priced through market-data's Upstox margin
   * endpoint ({@link PaperMarginClient}). {@code 0} for an empty book; {@code null} when unpriced (a
   * cash-equity book Upstox does not margin / analytics token off / market-data unreachable) — the
   * caller treats {@code null} as "cannot assess" and never blocks on it.
   */
  private BigDecimal currentHeatPct(String book) {
    List<PositionRow> open = positions.listOpen(book);
    if (open.isEmpty()) {
      return BigDecimal.ZERO;
    }
    if (open.size() > MAX_LEGS) {
      // Over the 20-leg Upstox basket limit: a truncated basket under-counts SPAN margin, and an
      // under-counted heat could FAIL to block when it should. Treat it as "cannot assess" (null →
      // never blocks — the fail-soft contract of this method) rather than silently pricing a partial
      // basket (audit M1: the same silent-truncation the margin-heat endpoint refuses loud).
      return null;
    }
    List<PaperMarginClient.Leg> legs =
        open.stream()
            .map(
                p ->
                    new PaperMarginClient.Leg(
                        p.exchange(), p.tradingsymbol(), (int) p.qty(), p.side(), "D"))
            .toList();
    PaperMarginClient.Quote q = marginClient.margin(legs);
    if (!q.priced() || q.spanMargin() == null) {
      return null;
    }
    BigDecimal equity = account.equity(book);
    if (equity.signum() <= 0) {
      return null;
    }
    return q.spanMargin().multiply(BigDecimal.valueOf(100)).divide(equity, 2, RoundingMode.HALF_UP);
  }

  /** Audits + ntfy-alerts a heat-cap breach (deduped per IST day, like {@link #recordTrip}). */
  private void recordHeatTrip(String book, BigDecimal heatPct, BigDecimal capPct) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    String dedupKey = tripKey(book, HEAT_CAP_PCT);
    if (today.equals(trippedOn.get(dedupKey))) {
      return;
    }
    trippedOn.put(dedupKey, today);
    String detail =
        "heat " + heatPct.toPlainString() + "% ≥ cap " + capPct.toPlainString()
            + "% — new entries paused";
    settings.audit(book, HEAT_CAP_PCT, "TRIP", detail);
    log.warn("risk heat cap {} tripped ({})", book, detail);
    pushAlert("ArthaYantra Risk — heat cap (" + book + ")", detail);
  }

  /**
   * Audits + ntfy-alerts a Manas pyramid ADD or FRESH entry blocked by the portfolio open-risk cap
   * (deduped per IST day per book, like {@link #recordHeatTrip} — a same-day add-breach and
   * fresh-entry-breach on the same book therefore dedupe against EACH OTHER, since both share this one
   * {@code (book, PYRAMID_RISK_CAP)} key; only the first trip of the day is audited/alerted, whichever
   * kind it was). <b>Reserved for a GENUINELY CALCULATED breach only</b> (round 7, owner-approved,
   * 2026-08-02) — {@link ManasRiskOutcome#CALCULATED_BREACH}, never a cannot-calculate refusal
   * ({@link ManasRiskOutcome#UNSUPPORTED_SIDE} / {@link ManasRiskOutcome#UNDEFINED_GOVERNING_STOP} /
   * {@link ManasRiskOutcome#NON_POSITIVE_EQUITY}), which would consume this SAME per-day dedup key
   * and silently suppress a later genuine breach the same day — see {@link #manasAggregateRiskCheck}'s
   * javadoc. Called via the {@code EmissionGuard} port so {@code swing}/{@code manas} — which must
   * never import this module (the acyclic module-graph rule that already forced the port pattern for
   * every other paper↔swing signal) — never need to know this class exists.
   *
   * <p>The actual {@link Propagation#REQUIRES_NEW} write lives on the separate {@link
   * PyramidRiskCapAuditor} bean (round 4, cross-vendor review Major 3, 2026-08-02) — {@code
   * PaperService#openOrder}'s own write-time call to THIS method sits INSIDE that method's {@code
   * @Transactional} boundary, immediately before it throws the 422 that refuses the fill, the same
   * "throw rolls back everything in this transaction" hazard {@link PaperOrderRejectionRecorder}'s
   * javadoc documents for the sibling rejection ledger. Round 3 put the REQUIRES_NEW annotation AND
   * an internal try/catch on this same method — but a catch INSIDE a REQUIRES_NEW method's own body
   * can only catch what the body itself throws; it cannot catch the surrounding Spring transaction
   * interceptor failing to acquire a connection (before the body runs) or failing to commit (after
   * it returns), both of which throw from OUTSIDE that stack frame straight to the caller. Round 4
   * moves the write to the separate bean and wraps the PROXIED call here instead — a genuine outer
   * boundary, mirroring how {@code PaperService#recordStaleRejectQuietly} wraps {@code
   * PaperOrderRejectionRecorder}'s own REQUIRES_NEW call at ITS call site. One fail-soft boundary
   * here covers both callers ({@code PaperService#openOrder} and, via {@code PaperEmissionGuard},
   * {@code SwingBatchEngine}'s entry pass) rather than duplicating a catch at each — an audit-write
   * failure must never break either the fill's own transaction or the batch's exit pass that day
   * (the "an observability write must never break the run" rule {@code SwingBatchRecorder}'s
   * flag-snapshot {@code capture()} documents and follows), and never consumes the per-day dedup key
   * on a write that did not durably land (a retry later the same day should still get one real row).
   */
  public void recordPyramidRiskCapBreach(String book, String symbol, String detail) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    String dedupKey = tripKey(book, PYRAMID_RISK_CAP);
    if (today.equals(trippedOn.get(dedupKey))) {
      return;
    }
    try {
      pyramidRiskCapAuditor.record(book, PYRAMID_RISK_CAP, detail);
      trippedOn.put(dedupKey, today);
      log.warn("risk pyramid-cap {} tripped for {} ({})", book, symbol, detail);
    } catch (RuntimeException e) {
      log.warn("risk_audit not written for pyramid-cap trip {}/{}: {}", book, symbol, e.getMessage());
    }
  }

  /** One fail-soft ntfy push for a governor trip (skipped silently when ntfy is unconfigured). */
  private void pushAlert(String title, String message) {
    try {
      if (notifier.configured("NTFY")) {
        notifier.send("NTFY", title, message);
      }
    } catch (RuntimeException e) {
      log.warn("risk governor ntfy push failed: {}", e.getMessage());
    }
  }

  /** Resolves a {@code {value, mode}} limit to INR: {@code pct} → book equity × value/100, else raw INR. */
  private BigDecimal limitInr(String book, JsonNode node) {
    BigDecimal value = node.path("value").decimalValue();
    if ("pct".equalsIgnoreCase(node.path("mode").asText("inr"))) {
      return account.equity(book).multiply(value).divide(BigDecimal.valueOf(100));
    }
    return value;
  }

  private void recordTrip(String book, String key, BigDecimal dayPnl, BigDecimal limit) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    String dedupKey = tripKey(book, key);
    if (!today.equals(trippedOn.get(dedupKey))) {
      trippedOn.put(dedupKey, today);
      String detail = "day P&L " + dayPnl.toPlainString() + " breached limit " + limit.toPlainString();
      settings.audit(book, key, "TRIP", detail);
      log.warn("risk cap {}/{} tripped — ENTRY emission paused for {}", book, key, today);
      pushAlert("ArthaYantra Risk — " + key + " (" + book + ")", detail);
    }
  }

  /** All limit rows for a book's settings panel. */
  public List<Setting> all(String book) {
    return settings.all(book);
  }

  /** Recent trip/flip audit rows for a book. */
  public List<RiskSettingsRepository.AuditEntry> audit(String book, int limit) {
    return settings.auditTail(book, limit);
  }

  /**
   * A book's settings panel: its limit rows plus the recent trip/flip audit tail. Assembled HERE
   * rather than in the controller so the records are the single source of truth for the wire shape
   * (D3), not a controller-side re-mapping.
   */
  public RiskViews.RiskSettings settingsView(String book) {
    List<RiskViews.RiskSettingRow> items =
        all(book).stream()
            .map(s -> new RiskViews.RiskSettingRow(s.key(), s.value(), s.updatedAt()))
            .toList();
    return new RiskViews.RiskSettings(book, items, audit(book, 20));
  }

  /** Upserts a book's limit (audited as a flip). */
  public void update(String book, String key, String valueJson) {
    settings.upsert(book, key, valueJson);
    settings.audit(book, key, "UPDATE", valueJson);
    trippedOn.remove(tripKey(book, key)); // re-arm this cap's per-day trip dedup on a limit change
  }

  private boolean boolFlag(String book, String key) {
    return settings.get(book, key).map(s -> s.value().path("enabled").asBoolean(false)).orElse(false);
  }

  private static boolean enabled(Optional<Setting> setting) {
    return setting.map(s -> s.value().path("enabled").asBoolean(false)).orElse(false);
  }
}
