package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.util.List;

/**
 * The parsed market-context bundle the CONTEXT_SHIFT + MARKET_STRUCTURE generators consume (INT
 * design §6.1/§6.2). Assembled at the engine edge by {@link ContextClient} from the SHIPPED digest
 * endpoints (#745: the options-digest per configured underlying + the day-context one-call); the
 * generators are pure over this typed bundle, so identical inputs replay byte-identical (§10.1).
 *
 * <p>Each nested view carries its own {@code dataTrust} + {@code asOf} + baseline-relative deltas
 * exactly as the digest computed them — CONTEXT_SHIFT never persists its own baseline, it reads the
 * digest's already-computed "since open" / "vs prior" deltas and checks the config-pinned crossing
 * (§6.2, "each threshold and its baseline is named in the insight evidence"). A source that 404s /
 * fails yields an EMPTY list / {@code null} view (honest degrade, never a fabricated number, §6.5).
 */
public record MarketContext(List<OptionsShift> optionsShifts, MarketStructureView structure) {

  /**
   * The options-digest deltas for one underlying, since session-open (INT design §6.1.1 folds). Every
   * delta is nullable (no baseline → the digest returns null and the crossing is skipped, not faked).
   *
   * @param pcrDeltaVsOpen PCR now − at-open
   * @param maxPainDrift max-pain now − at-open, in index points
   * @param straddleDeltaPct ATM straddle % change vs open
   * @param activeStrikeChanged count of active (top-N peak-OI) strikes that entered/exited vs open
   * @param dataTrust the digest's own OK/DEGRADED/BLOCKED verdict (BLOCKED → no crossing emitted)
   */
  public record OptionsShift(
      String exchange,
      String underlying,
      String expiry,
      BigDecimal pcrNow,
      BigDecimal pcrDeltaVsOpen,
      BigDecimal maxPainDrift,
      BigDecimal straddleDeltaPct,
      Integer activeStrikeChanged,
      String structureVerdict,
      String dataTrust,
      List<String> trustReasons,
      String asOf) {}

  /**
   * The coarse market-regime read from the day-context one-call (INT design §6.1.5): the INDIA VIX
   * level/band and the primary index's gap-open + day-range regime. Any field is nullable off-hours
   * (fail-soft VIX/quote) — the generators skip a null.
   *
   * @param vixBand LOW / NORMAL / ELEVATED / HIGH (the day-context display band)
   * @param gapOpenPct primary-index gap-open % vs prior close
   * @param rangeState WIDE / NORMAL / NARROW (day range vs its 20-session average)
   * @param rangeVsAvg day range ÷ 20-session average range
   * @param indexDirection UP / DOWN / FLAT (close vs open)
   */
  public record MarketStructureView(
      String indexSymbol,
      BigDecimal vixLevel,
      String vixBand,
      BigDecimal gapOpenPct,
      String rangeState,
      BigDecimal rangeVsAvg,
      String indexDirection,
      String dataTrust,
      String asOf) {}
}
