package in.arthayantra.strategysignal.swing;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategysignal.signals.SignalRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The family-specific rules a swing batch varies by — and nothing more. The {@link SwingBatchEngine}
 * is a pure function of the doctrine: it owns the run/entry-pass/exit-pass/emit/anchor-adoption/retry
 * skeleton (the H2/H3/P0-3 exit-safety logic, once), and reads everything that differs between
 * families off this port. The two adapters ({@code MinerviniDoctrine}, {@code ManasDoctrine}) make it
 * a real seam; the sell-decision service is the second consumer that keeps the context-seed
 * abstraction from over-fitting the engine.
 */
public interface SwingDoctrine {

  /**
   * The paper book this family trades (its own capital / risk / positions) — one of the {@code Books}
   * string constants, as the {@code EmissionGuard} SPI takes the book as a String.
   */
  String book();

  /** The {@code universe.mode} this family owns — the cross-family isolation key. */
  String universeMode();

  /**
   * The {@code swing_batch_runs} marker key + the {@code /api/v1/signals/<batchName>-swing/…} path
   * stem (e.g. {@code "minervini"} / {@code "manas-arora"}). MUST stay stable — the P0-4 did-not-run
   * canary looks the marker up by this key.
   */
  String batchName();

  /** The human label for ops alerts (e.g. {@code "Minervini swing"} / {@code "Manas swing"}). */
  String alertLabel();

  /** The single arming gate: with this off the batch is an inert no-op whoever calls it. */
  boolean enabled();

  /** Daily-series warmup window (days) fetched per symbol. */
  int warmupDays();

  /** Minimum bars a candidate's series must carry to be scored. */
  int minBars();

  /** Signal TTL (minutes) stamped on each emitted row. */
  long ttlMinutes();

  /**
   * The setup token a strategy trades ({@code "breakout"}/{@code "vcp"}), or {@code null} when the
   * family has no setup routing (Minervini). Matched against a candidate's {@code eligibleSetups}.
   */
  String setupToken(JsonNode config, String slug);

  /** The day's family-neutral funnel candidates (buyable + on-deck), normalized from the family funnel. */
  List<SwingCandidate> candidates();

  /**
   * WHICH session's screen {@link #candidates()} would serve right now — the catch-up's input-readiness
   * gate. The funnel endpoint silently falls back to the newest PERSISTED screen date, so a batch fired
   * before its screener landed reads the PRIOR session's names with no error and no exception; that is
   * the 2026-07-17 shape (screens did not land until 22:15, so a 22:14 catch-up would have entered off
   * 07-16's funnel). {@link SwingBatchCatchUp} refuses unless this equals the session it is catching up.
   * Empty = unknown/unreachable, which the caller treats as NOT ready.
   */
  Optional<LocalDate> inputsAsOf();

  /**
   * The indicator-context seeds for a sell-decision / held-position re-evaluation, read from the
   * position's persisted {@code *_detail} JSON (the entry seeds it, held positions read it back). The
   * exit pass seeds neutral context (see {@link #neutralContextSeeds}); the sell-decision re-runs the
   * ENTRY gate, so it needs the real persisted pivots here.
   */
  Map<String, BigDecimal> contextSeedsFromDetail(JsonNode anchorDetail);

  /**
   * The family's context series all seeded to 0 — used by the exit pass and the sell-decision's exit
   * read, where geometry is irrelevant (percent stop / ATR trail never read it) but the bank must
   * still BUILD every indicator the definition declares (the pivot-context indicators need a series
   * present, even at 0). Carries the family's context NAMES unconditionally (all of them at 0).
   */
  Map<String, BigDecimal> neutralContextSeeds();

  /** Persists the family {@code *_detail} side-channel (the column differs per family). */
  void stampDetail(long signalId, String detailJson);

  /**
   * The family {@code *_detail} JSON persisted on an open anchor ({@code minerviniDetail} /
   * {@code manasAroraDetail}) — the source the sell-decision feeds to {@link #contextSeedsFromDetail}
   * to re-run the entry gate on a held position.
   */
  JsonNode detailOf(SignalRepository.SignalRow anchor);

  /** This family's lot-adding policy ({@link PyramidPolicy#NONE} for a single-lot family). */
  PyramidPolicy pyramid();

  /**
   * The trail level to SHOW on a held position's sell-decision (advisory — not the exit trigger). The
   * families differ: Minervini surfaces the 50-day-MA trail, Manas the doc-faithful 2×ATR trailing
   * stop (null until it arms). {@code null} when the family has no advisory trail to show.
   */
  BigDecimal trailLevel(
      StrategyDefinition definition,
      IndicatorBank bank,
      ExitEvaluator.Position position,
      int lastIndex,
      int entryIndex);
}
