package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.PositionSizer;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The paper-module adapter for the signals {@link EmissionGuard} SPI (A12). Bridges the engine's
 * ENTRY emission to the global risk gate and the paper-account-equity sizing — the engine never
 * imports paper, only this port (so the module graph stays acyclic).
 */
@Component
public class PaperEmissionGuard implements EmissionGuard {

  private static final Logger log = LoggerFactory.getLogger(PaperEmissionGuard.class);

  // §3.7 hero-zero profit-funded sizing: deploy ~10% of accumulated realised PROFIT (mode a, "play with
  // house money, never capital"), floored to a ₹2,500 minimum deploy (mode b — the ₹2-3k midpoint) when
  // profits are thin / negative (owner: "a if we have enough profit, else b").
  static final BigDecimal HERO_ZERO_PROFIT_PCT = new BigDecimal("0.10");
  static final BigDecimal HERO_ZERO_MIN_DEPLOY_INR = new BigDecimal("2500");

  private final RiskService risk;
  private final PaperAccountService account;
  private final InstrumentMetaClient instruments;
  private final ScalperAccountModel scalperAccounts;
  private final PaperPositionRepository positions;
  private final PaperOrderRejectionRecorder rejections;
  private final ManasGoverningStopCache governingStopCache;
  private final EquityMarkCache equityMarks;

  /** Wires the risk gate + capital model + the scalper 5-account discipline + the position ledger. */
  public PaperEmissionGuard(
      RiskService risk,
      PaperAccountService account,
      InstrumentMetaClient instruments,
      ScalperAccountModel scalperAccounts,
      PaperPositionRepository positions,
      PaperOrderRejectionRecorder rejections,
      ManasGoverningStopCache governingStopCache,
      EquityMarkCache equityMarks) {
    this.risk = risk;
    this.account = account;
    this.instruments = instruments;
    this.scalperAccounts = scalperAccounts;
    this.positions = positions;
    this.rejections = rejections;
    this.governingStopCache = governingStopCache;
    this.equityMarks = equityMarks;
  }

  @Override
  public boolean entryAllowed(String book) {
    return risk.entryAllowed(book);
  }

  @Override
  public Optional<String> entryVeto(String book) {
    // PF-03: surface the exact governor rail (the single source of truth is RiskService.entryVeto,
    // of which risk.entryAllowed is a thin isEmpty() view — identical decision AND audit side-effects).
    //
    // NOTE (owner ruling, 2026-08-13): an UNMARKED position does NOT veto entry here. The
    // cross-vendor review rated the fail-open fallback a Critical and a veto rail was built at this
    // exact point, then removed on the owner's call. The reasoning: the mark cache is cold on every
    // boot, so a gate keyed on it locks the books hardest on exactly the days they are already
    // degraded. The pre-entry hydration that would have made a fail-closed rail safe was itself
    // withdrawn (it perturbed the exit sample), so the cold-boot window is real and open. The
    // residual exposure (a position at a LOSS, unmarked, valued at cost, inflating equity) is
    // bounded by the book's own open risk and is
    // SURFACED — AccountDto.unmarkedPositions, the ay_paper_mtm_blind_positions gauge — rather than
    // blocked. Arming it later is a one-branch change at this line.
    return risk.entryVeto(book);
  }

  @Override
  public BigDecimal bookEquity(String book) {
    return account.equity(book);
  }

  @Override
  public BigDecimal openRiskInr(String book) {
    return openRiskInr(positions.listOpen(book), governingStopCache);
  }

  /**
   * Aggregate open risk (₹) over a set of open positions: {@code Σ qty × max(0, avgEntry −
   * effectiveStop)}, a stop-less position contributing 0 (no defined risk to sum). {@code
   * effectiveStop} is {@link #effectiveStop} — the IN-MEMORY {@link ManasGoverningStopCache} entry
   * when cached (Manas only; every other family/position is never cached), else the persisted
   * {@code stopLoss}. Pure (given the cache) + package-visible for a unit test.
   */
  static BigDecimal openRiskInr(
      java.util.List<PaperPositionRepository.PositionRow> open, ManasGoverningStopCache cache) {
    BigDecimal total = BigDecimal.ZERO;
    for (PaperPositionRepository.PositionRow p : open) {
      BigDecimal stop = effectiveStop(p, cache);
      if (stop == null) {
        continue;
      }
      BigDecimal perUnit = p.avgEntryPrice().subtract(stop);
      if (perUnit.signum() > 0) {
        total = total.add(perUnit.multiply(BigDecimal.valueOf(p.qty())));
      }
    }
    return total;
  }

  /**
   * M40 Critical 3 fix, round 3 (owner ruling, 2026-08-02): the stop the aggregate open-risk cap
   * should treat as CURRENTLY governing this position — the {@link ManasGoverningStopCache} entry
   * once the daily Chandelier trail has armed (Manas only; every other family/position is never
   * cached), else the persisted {@code stopLoss} (the initial bracket, or the ONLY figure for any
   * family other than Manas). IN MEMORY ONLY — {@code stopLoss} itself is never read from or
   * written to by anything in this fix; a cache miss (fresh boot, or before the trail arms) falls
   * back to it unchanged. Keyed by {@code p.id()} (round 4, cross-vendor review Critical 1) — the
   * position's own immutable row id, never a symbol tuple, so a different position can never read
   * this one's cached value. Package-visible + pure (given the cache) for a unit test.
   */
  static BigDecimal effectiveStop(
      PaperPositionRepository.PositionRow p, ManasGoverningStopCache cache) {
    BigDecimal cached = cache.get(p.id());
    return cached != null ? cached : p.stopLoss();
  }

  @Override
  public boolean scalperEntryAllowed() {
    return scalperAccounts.scalperEntryAllowed();
  }

  @Override
  public BigDecimal suggestedQty(
      StrategyDefinition.SizingSpec sizing,
      String exchange,
      String tradingsymbol,
      BigDecimal price,
      BigDecimal stopDistance,
      String book) {
    return suggestedQty(sizing, exchange, tradingsymbol, price, stopDistance, null, book);
  }

  @Override
  public BigDecimal suggestedQty(
      StrategyDefinition.SizingSpec sizing,
      String exchange,
      String tradingsymbol,
      BigDecimal price,
      BigDecimal stopDistance,
      BigDecimal multiplier,
      String book) {
    InstrumentMeta meta = instruments.meta(exchange, tradingsymbol);
    // FAIL CLOSED on an unresolved derivatives instrument (cross-vendor review C2). A meta lookup
    // failure — market-data restarting mid-session, or a newly listed weekly strike the master has
    // not picked up yet — returns the EQUITY_PROXY with lot=1. Before this PR that produced 0 lots
    // and no position; now it would produce a REAL one at a non-lot-aligned qty (15000/776 = 19
    // units of a 20-lot SENSEX option), auto-taken and silently wrong. An option that does not
    // resolve as an OPTION is not sizable: refuse, and let the ZERO_SIZE trace make the miss
    // visible. A safety gate must fail closed (checklist, Money/data fidelity).
    if (unresolvedDerivative(exchange, tradingsymbol, meta)) {
      return null;
    }
    long lot = Math.max(1, meta.lotSize());
    long base =
        PositionSizer.size(
            sizing, new PositionSizer.Inputs(account.equity(book), price, stopDistance, meta.lotSize()));
    if (base <= 0) {
      return null;
    }
    if (multiplier == null) {
      return BigDecimal.valueOf(base);
    }
    // E8 §3.2: scale the advisory qty by the graded multiplier, then floor to a WHOLE lot (never round
    // UP, never below one lot for a fired entry). PaperEmissionGuard is the one rounding authority.
    long lots =
        Math.max(
            1L,
            BigDecimal.valueOf(base)
                .multiply(multiplier)
                .divideToIntegralValue(BigDecimal.valueOf(lot))
                .longValueExact());
    return BigDecimal.valueOf(lots * lot);
  }

  @Override
  public BigDecimal heroZeroSuggestedQty(
      StrategyDefinition.SizingSpec sizing, String exchange, String tradingsymbol, BigDecimal premium) {
    if (premium == null || premium.signum() <= 0) {
      return null;
    }
    InstrumentMeta meta = instruments.meta(exchange, tradingsymbol);
    if (unresolvedDerivative(exchange, tradingsymbol, meta)) {
      return null; // same fail-closed rule as suggestedQty (review C2) — hero-zero is options-only
    }
    // The declared min_premium_inr binds here too. This quantity OVERRIDES the ordinary sized one,
    // so a floor enforced only inside PositionSizer.size left hero-zero unfloored — the SAME defect
    // already fixed for max_lots, repeated one param later (cross-vendor review, #1084). Floor ₹10 /
    // premium ₹5 / lot 75 / cap 5 gave 375 live units where the replay skips the entry outright.
    // Null (not 0) so the caller keeps its ordinary advisory qty, which the floor has already
    // zeroed — hero-zero must not resurrect a trade the sizer refused.
    if (PositionSizer.belowPremiumFloor(sizing, premium)) {
      return null;
    }
    long lot = Math.max(1, meta.lotSize());
    // Hero-zero is a scalper (expiry-day options) concept — funded off the scalper book's realised P&L.
    BigDecimal budget = heroZeroDeployBudget(account.realisedProfit(BookResolver.SCALPER));
    BigDecimal perLotCost = premium.multiply(BigDecimal.valueOf(lot));
    long affordableLots = budget.divide(perLotCost, 0, RoundingMode.DOWN).longValueExact();
    long lots = Math.max(1L, affordableLots); // a fired entry deploys at least one lot (advisory)
    // The declared max_lots binds HERE too. This quantity overrides the ordinary suggestedQty, so a
    // cap enforced only inside PositionSizer left hero-zero uncapped: the config said 5 while live
    // deployed whatever the profit pot afforded and the backtest replay capped at 5 — a live-vs-sim
    // divergence introduced by the very change meant to remove one (cross-vendor review, #1075).
    long maxLots = PositionSizer.maxLots(sizing);
    if (maxLots > 0 && lots > maxLots) {
      lots = maxLots;
    }
    return BigDecimal.valueOf(lots * lot);
  }

  @Override
  public void recordPyramidRiskCapBreach(String book, String symbol, String detail) {
    risk.recordPyramidRiskCapBreach(book, symbol, detail);
  }

  /**
   * Round 4 fix (cross-vendor review Critical 1, 2026-08-02): resolves the CURRENTLY open position
   * for this key via a FRESH repository read — never the stale in-loop anchor snapshot {@code
   * SwingBatchEngine}'s exit pass iterates. This closes the close-race the round-4 review found:
   * {@code SwingBatchEngine} has no {@code @Transactional} boundary of its own, so a concurrent
   * manual close (which evicts by id in {@code PaperService#doSettle}) commits and is immediately
   * visible here.
   *
   * <p><b>Round 5 fix (cross-vendor review Critical 1, 2026-08-02): "currently open" is not enough —
   * it must be the SAME position {@code openingSignalId} was computed from.</b> The round-4 version
   * resolved WHATEVER row was open for the tuple key, which fixed the read side (a closed key can
   * never be resurrected) but not the write side: if the anchor's own position closed and a
   * DIFFERENT position opened on the same key before this write landed, round 4 would have cached
   * the first position's trail under the second position's perfectly valid id — a wrongly-attributed
   * entry, not a stale one. {@link PaperPositionRepository#findOpenIdIfOpenedBy} validates the
   * currently-open row's own {@code opening_signal_id} column equals {@code openingSignalId} before
   * caching anything — a mismatch (nothing open, or something open but opened by a DIFFERENT signal)
   * is a safe no-op, never a best-effort attach. {@code opening_signal_id} is retained unchanged
   * across an averaging add (audit H5, {@code PaperService#upsertPosition}), so a pyramid add onto
   * the SAME anchor's position still validates correctly — only a genuinely different position is
   * rejected.
   */
  @Override
  public void cacheManasGoverningStop(
      String book, String exchange, String tradingsymbol, String side, long openingSignalId,
      BigDecimal newStop) {
    positions
        .findOpenIdIfOpenedBy(book, exchange, tradingsymbol, side, openingSignalId)
        .ifPresent(id -> governingStopCache.put(id, side, newStop));
  }

  /**
   * Stores the session mark — the OFFICIAL NSE close since ledger H9, the daily-bar close only when
   * the exchange published none — in the {@link EquityMarkCache} so
   * {@link PaperAccountService#equity} can mark cash equities to market. No position lookup and no {@code openingSignalId} validation
   * here, unlike {@link #cacheManasGoverningStop}: a mark is a property of the SYMBOL, not of a
   * position, so there is no identity to mis-attach it to — every book holding that symbol wants the
   * same number, and a mark for a symbol nobody holds is simply never read (the equity sum iterates
   * OPEN positions).
   */
  @Override
  public void cacheEquityMark(
      String exchange, String tradingsymbol, BigDecimal close, java.time.LocalDate session) {
    equityMarks.put(exchange, tradingsymbol, close, session);
  }

  @Override
  public void recordZeroSizedEntry(
      long signalId,
      String strategySlug,
      StrategyDefinition.SizingSpec sizing,
      String book,
      String exchange,
      String tradingsymbol,
      BigDecimal premium,
      BigDecimal stopDistance,
      String side) {
    try {
      InstrumentMeta meta = instruments.meta(exchange, tradingsymbol);
      long lot = Math.max(1, meta.lotSize());
      long computedQty =
          PositionSizer.size(
              sizing,
              new PositionSizer.Inputs(account.equity(book), premium, stopDistance, meta.lotSize()));
      long computedLots = computedQty / lot;
      BigDecimal budget = budgetInr(sizing);
      String detail =
          "strategy=" + strategySlug
              + "; premium=" + premium.toPlainString()
              + "; lot=" + lot
              + "; budget_inr=" + (budget == null ? "n/a" : budget.toPlainString())
              + "; computed_lots=" + computedLots;
      log.warn(
          "paper ENTRY zero-sized: strategy={} book={} symbol={}:{} premium={} lot={} budget={} computedLots={}",
          strategySlug, book, exchange, tradingsymbol, premium, lot, budget, computedLots);
      rejections.recordZeroSize(signalId, book, exchange, tradingsymbol, side, detail);
    } catch (RuntimeException e) {
      log.warn(
          "paper_order_rejections (zero-size) not written for {}:{}: {}",
          exchange,
          tradingsymbol,
          e.getMessage());
    }
  }

  private static BigDecimal budgetInr(StrategyDefinition.SizingSpec sizing) {
    Object value = sizing.params().get("budget_inr");
    return value == null
        ? null
        : value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
  }

  /**
   * §3.7 the hero-zero deploy budget (INR): mode a = 10% of accumulated realised PROFIT when there is
   * enough profit (10% &gt; the floor), mode b = the {@link #HERO_ZERO_MIN_DEPLOY_INR} floor when profits
   * are thin / negative. Package-private + static for a focused unit test.
   */
  static BigDecimal heroZeroDeployBudget(BigDecimal realisedProfit) {
    BigDecimal tenPct =
        realisedProfit == null ? BigDecimal.ZERO : realisedProfit.multiply(HERO_ZERO_PROFIT_PCT);
    return tenPct.compareTo(HERO_ZERO_MIN_DEPLOY_INR) > 0 ? tenPct : HERO_ZERO_MIN_DEPLOY_INR;
  }

  /**
   * True when a derivatives symbol did not resolve as an OPTION — i.e. the instrument-meta lookup
   * fell back to the EQUITY_PROXY (lot 1) because market-data was unreachable or the master has not
   * picked the contract up yet.
   *
   * <p>FAIL CLOSED (cross-vendor review C2). Before the option-leg sizing fix this state produced
   * 0 lots and no position; with it, a lot-1 proxy yields a NON-LOT-ALIGNED quantity — 15000/776 =
   * 19 units of a 20-lot SENSEX option — auto-taken and silently wrong, which is the exact defect
   * that fix exists to remove. Refusing costs one missed entry; filling costs a position that cannot
   * be traded and that a live broker would reject.
   */
  private boolean unresolvedDerivative(String exchange, String tradingsymbol, InstrumentMeta meta) {
    if (!"NFO".equals(exchange) && !"BFO".equals(exchange)) {
      return false;
    }
    if (meta.instrumentClass() == InstrumentClass.OPTION) {
      return false;
    }
    log.warn(
        "refusing to size {}:{} — derivatives symbol did not resolve as an OPTION (class={}, lot={});"
            + " instrument meta is unavailable or stale",
        exchange, tradingsymbol, meta.instrumentClass(), meta.lotSize());
    return true;
  }
}
