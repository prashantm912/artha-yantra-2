package in.arthayantra.strategysignal.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.eval.ScoreBreakdownJson;
import in.arthayantra.strategyengine.eval.SeriesProvider;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.registry.UniverseResolver;
import in.arthayantra.strategysignal.signals.DroppedCandidate;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalExited;
import in.arthayantra.strategysignal.signals.SignalPublisher;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one daily swing batch engine (audit M31 consolidation): a post-close batch that generates
 * live-paper swing signals for a family's published {@code session.style=swing} strategies over its
 * selection funnel, and evaluates exits on the open holdings. It exists because funnel equities do NOT
 * tick (the live feed is index/options only), so the tick-driven {@code SignalEngine} never evaluates
 * them; this batch drives them off the daily bar.
 *
 * <p>It is a pure function of a {@link SwingDoctrine} — the family (Minervini SEPA, Manas Arora)
 * supplies the book, universe, funnel candidates, context-seeds, detail side-channel, setup routing
 * and pyramid policy; the engine owns the run/entry-pass/exit-pass/emit/anchor-adoption/retry
 * skeleton ONCE (the H2 anchor-adoption, H3 gate-recheck, P0-3 exit-retry). Stateless: every field is a
 * shared collaborator; all run state is method-local, so a single singleton serves every family.
 *
 * <p><b>Parity:</b> it reuses the FROZEN {@link EntryEvaluator}/{@link ExitEvaluator}/{@link
 * IndicatorBank} verbatim — no scoring/exit code — so goldens are untouched and each bar scores
 * identically to the backtest. It speaks multi-lot natively (group by symbol, drive the exit off the
 * oldest lot, close all lots); a single-lot family ({@link PyramidPolicy#NONE}) is the degenerate case
 * where every group is a singleton and the passes reduce to the per-anchor loop.
 */
@Component
public class SwingBatchEngine {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchEngine.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String EX = "NSE";
  private static final String IV = "1d";

  /**
   * A loaded published swing strategy (identity + compiled definition + neutral setup token). {@code
   * includesOnDeck} is its {@code universe.bucket} leaf resolved through {@link
   * UniverseResolver#includesOnDeck} — the SAME predicate the submission pin uses, so the leaf cannot
   * mean one thing in the pinned universe and another live.
   */
  public record SwingStrategy(
      UUID versionId, String slug, String name, String version, String checksum, String setupToken,
      boolean includesOnDeck, StrategyDefinition definition) {}

  /**
   * Summary of one batch run (for logging / the manual-trigger endpoint). {@code exitSkipped} counts
   * held anchors whose stop/trail could NOT be evaluated this run (no daily series even after a retry)
   * — this batch is the position's ONLY exit evaluator, so a non-zero value is an ops signal. {@code
   * admission} is the ledger-F3 slot-cap probe (measurement-only; see {@link AdmissionProbe}).
   */
  public record SwingRun(
      int strategies, int candidates, int entries, int exits, int exitSkipped,
      AdmissionProbe admission, List<String> refusalReasons, boolean deadlineReached) {

    public SwingRun(
        int strategies, int candidates, int entries, int exits, int exitSkipped,
        AdmissionProbe admission) {
      this(strategies, candidates, entries, exits, exitSkipped, admission, List.of(), false);
    }

    public SwingRun(
        int strategies, int candidates, int entries, int exits, int exitSkipped,
        AdmissionProbe admission, List<String> refusalReasons) {
      this(strategies, candidates, entries, exits, exitSkipped, admission, refusalReasons, false);
    }

    public SwingRun {
      refusalReasons = refusalReasons == null ? List.of() : List.copyOf(refusalReasons);
    }
  }

  /**
   * The ledger-F3 admission probe: a MEASUREMENT-ONLY read of how hard the book's slot cap bound this
   * run — it never changes admission. {@code wouldEnter} is how many non-held funnel candidates fired a
   * fresh entry IGNORING the cap; {@code admitted} is how many the governor actually let in; {@code
   * capExceedance = wouldEnter - admitted} is the count the cap (or a mid-run kill-switch / daily-loss
   * trip) dropped. The funnel is walked in RS-priority order (buyable RS-desc, then on-deck RS-desc), so
   * {@code droppedByCap} carries each dropped name with its 1-based admission-order rank — the raw
   * material for the offline FIFO-vs-RS net-vs-net read ({@code portfolioFifoNet}, which the source spec
   * could only INFER). Computed AFTER the emission passes and fail-soft, so a probe bug can never touch
   * the entries the batch fires.
   */
  public record AdmissionProbe(
      int openAtStart, int wouldEnter, int admitted, int capExceedance, boolean capBound,
      List<DroppedCandidate> droppedByCap) {

    /** The degenerate probe for a flag-off / errored run (nothing measured). */
    public static AdmissionProbe empty() {
      return new AdmissionProbe(0, 0, 0, 0, false, List.of());
    }
  }

  @FunctionalInterface
  public interface RunDeadline {

    boolean expired();

    static RunDeadline never() {
      return () -> false;
    }
  }

  private record EntryResult(int candidates, int fired, List<String> refusalReasons, boolean deadlineReached) {}

  private record ExitResult(int closed, int skipped, List<String> refusalReasons, boolean deadlineReached) {}

  private final StrategyRepository registry;
  private final MarketDataCandlesClient candles;
  private final SignalRepository signals;
  private final SignalPublisher publisher;
  private final ApplicationEventPublisher events;
  private final Optional<EmissionGuard> emissionGuard;
  private final TransactionTemplate tx;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final SwingPaperEffectRepository paperEffects;
  private final SwingBatchRefusalRepository refusals;

  /** Wires the shared collaborators (family-neutral); the family varies only via the doctrine arg. */
  @Autowired
  public SwingBatchEngine(
      StrategyRepository registry,
      MarketDataCandlesClient candles,
      SignalRepository signals,
      SignalPublisher publisher,
      ApplicationEventPublisher events,
      Optional<EmissionGuard> emissionGuard,
      TransactionTemplate tx,
      ObjectMapper objectMapper,
      Clock clock,
      SwingPaperEffectRepository paperEffects,
      SwingBatchRefusalRepository refusals) {
    this.registry = registry;
    this.candles = candles;
    this.signals = signals;
    this.publisher = publisher;
    this.events = events;
    this.emissionGuard = emissionGuard;
    this.tx = tx;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.paperEffects = paperEffects;
    this.refusals = refusals;
  }

  /** Backwards-compatible seam for focused unit tests that do not exercise the V049 ledgers. */
  public SwingBatchEngine(
      StrategyRepository registry,
      MarketDataCandlesClient candles,
      SignalRepository signals,
      SignalPublisher publisher,
      ApplicationEventPublisher events,
      Optional<EmissionGuard> emissionGuard,
      TransactionTemplate tx,
      ObjectMapper objectMapper,
      Clock clock) {
    this(
        registry, candles, signals, publisher, events, emissionGuard, tx, objectMapper, clock,
        null, null);
  }

  /** Runs one full daily batch for a family: the entry pass over its funnel, then the exit pass. */
  public SwingRun runDaily(SwingDoctrine doctrine) {
    return runDaily(doctrine, null, true);
  }

  /** Runs a batch PINNED to a session (entries enabled) — see {@link #runDaily(SwingDoctrine, LocalDate, boolean)}. */
  public SwingRun runDaily(SwingDoctrine doctrine, LocalDate requiredBarDate) {
    return runDaily(doctrine, requiredBarDate, true);
  }

  /**
   * Runs one daily batch, optionally PINNED to the session whose daily bar it must decide on, with the
   * entry pass optionally suppressed.
   *
   * <p><b>{@code requiredBarDate}</b> is the catch-up staleness guard ({@link SwingBatchCatchUp}):
   * every scoring decision reads the LAST bar of a {@code now-warmupDays → now} fetch, so a late run
   * would price entries and — far worse — EXITS off whatever bar is newest. When pinned, each series is
   * TRUNCATED to end at the session's bar (see {@link #truncateToSession}): the exit then settles at
   * the session's own daily close, exactly as the on-time run would have, whether the catch-up is that
   * night or days later. A symbol with no bar for the session is dropped to empty, which both passes
   * surface as a counted, alerted skip ("STOP NOT EVALUATED TODAY"). {@code null} — the scheduled /
   * on-demand path — truncates nothing and is byte-identical to the pre-catch-up behaviour.
   *
   * <p><b>{@code entriesEnabled}</b> gates the ENTRY pass (and its F3 probe). The catch-up runs exits
   * unconditionally — a held stop must be evaluated off the session bar regardless of the funnel — but
   * only enters when the session's screen is actually available (the funnel silently serves the latest
   * persisted screen, so entering off it would take the WRONG day's names). The scheduled path passes
   * {@code true}.
   */
  public SwingRun runDaily(SwingDoctrine doctrine, LocalDate requiredBarDate, boolean entriesEnabled) {
    return runDaily(doctrine, requiredBarDate, entriesEnabled, RunDeadline.never());
  }

  /** Catch-up overload carrying the market-open deadline into the per-session engine. */
  public SwingRun runDaily(
      SwingDoctrine doctrine,
      LocalDate requiredBarDate,
      boolean entriesEnabled,
      RunDeadline deadline) {
    if (!doctrine.enabled()) {
      return new SwingRun(0, 0, 0, 0, 0, AdmissionProbe.empty());
    }
    Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot =
        entriesEnabled
            ? Optional.of(new SwingDoctrine.CandidateSnapshot(null, doctrine.candidates()))
            : Optional.empty();
    return runDaily(doctrine, requiredBarDate, entriesEnabled, candidateSnapshot, doctrine.enabled(), deadline);
  }

  /** Runs a batch from one already-fetched funnel snapshot; an empty Optional means the fetch failed. */
  public SwingRun runDaily(
      SwingDoctrine doctrine,
      LocalDate requiredBarDate,
      boolean entriesEnabled,
      Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot) {
    return runDaily(doctrine, requiredBarDate, entriesEnabled, candidateSnapshot, doctrine.enabled(), RunDeadline.never());
  }

  /** Runs from a snapshot while explicitly supplying the schedule-time arming state. */
  public SwingRun runDaily(
      SwingDoctrine doctrine,
      LocalDate requiredBarDate,
      boolean entriesEnabled,
      Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot,
      boolean executionArmed) {
    return runDaily(
        doctrine, requiredBarDate, entriesEnabled, candidateSnapshot, executionArmed, RunDeadline.never());
  }

  /** Runs from a snapshot and schedule-time arming state with an optional market-open deadline. */
  public SwingRun runDaily(
      SwingDoctrine doctrine,
      LocalDate requiredBarDate,
      boolean entriesEnabled,
      Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot,
      boolean executionArmed,
      RunDeadline deadline) {
    // The single arming gate for BOTH the scheduler AND the on-demand POST /run: with the family flag
    // off the batch is a NO-OP whoever calls it (audit P9 — else a curious authenticated POST /run
    // fires entries + auto-paper before the owner has armed the flag).
    if (!executionArmed) {
      log.debug("{} swing batch disabled — skipping", doctrine.batchName());
      return new SwingRun(0, 0, 0, 0, 0, AdmissionProbe.empty());
    }
    if (deadline.expired()) {
      return new SwingRun(0, 0, 0, 0, 0, AdmissionProbe.empty(), List.of(), true);
    }
    List<SwingStrategy> swings = loadPublishedSwingStrategies(doctrine);
    if (swings.isEmpty()) {
      return new SwingRun(0, 0, 0, 0, 0, AdmissionProbe.empty());
    }
    Map<String, List<EngineCandle>> seriesCache = new HashMap<>();
    AnchorResolution resolution = new AnchorResolution(doctrine, swings);
    // Consume the caller-owned funnel snapshot for both the entry pass and the F3 probe. Snapshot the
    // held set BEFORE the entry pass mutates it (the probe partitions would-be entrants into
    // admitted/dropped by diffing against the held-AFTER set). Entries suppressed → no probe (a
    // catch-up whose screen is not the session's).
    List<SwingCandidate> candidates =
        entriesEnabled
            ? candidateSnapshot.map(SwingDoctrine.CandidateSnapshot::candidates).orElse(List.of())
            : List.of();
    java.util.Set<String> heldBefore =
        new java.util.HashSet<>(openLotsBySymbol(resolution).keySet());
    EntryResult entry =
        entriesEnabled
            ? entryPass(
                doctrine, swings, resolution, seriesCache, candidates, requiredBarDate, deadline)
            : new EntryResult(0, 0, List.of(), false);
    ExitResult exit =
        entry.deadlineReached()
            ? new ExitResult(0, 0, List.of(), true)
            : exitPass(doctrine, resolution, seriesCache, requiredBarDate, deadline);
    AdmissionProbe probe =
        entriesEnabled
            ? admissionProbe(
                doctrine, swings, resolution, seriesCache, candidates, heldBefore, requiredBarDate)
            : AdmissionProbe.empty();
    log.info(
        "{} swing batch: {} strategies, {} candidates, {} entries, {} exits, {} exit-skipped"
            + " (would-enter {}, admitted {}, cap-exceedance {})",
        doctrine.batchName(), swings.size(), entry.candidates(), entry.fired(), exit.closed(),
        exit.skipped(), probe.wouldEnter(), probe.admitted(), probe.capExceedance());
    List<String> refusalReasons = new ArrayList<>(entry.refusalReasons());
    refusalReasons.addAll(exit.refusalReasons());
    return new SwingRun(
        swings.size(), entry.candidates(), entry.fired(), exit.closed(), exit.skipped(), probe,
        refusalReasons,
        entry.deadlineReached() || exit.deadlineReached());
  }

  // ---- anchor resolution (audit H2) -----------------------------------------------------------

  /**
   * Per-run anchor→strategy resolution that ADOPTS anchors of superseded/unpublished versions. Keying
   * the exit pass on the CURRENT published version id silently orphaned every open anchor whenever a
   * strategy was republished (the boot seeder auto-publishes on any YAML change), disabled, or its
   * config later failed to compile — the position's stop/trail was never evaluated again AND the
   * symbol dropped out of the held set, so a new version could re-enter it (double exposure).
   * Resolution order: currently-published strategy, else the anchor's OWN version row compiled on the
   * fly. Empty = another family's anchor, or genuinely unmanageable (logged at ERROR).
   */
  private final class AnchorResolution {
    private final SwingDoctrine doctrine;
    private final Map<UUID, SwingStrategy> published = new HashMap<>();
    private final Map<UUID, Optional<SwingStrategy>> adopted = new HashMap<>();

    AnchorResolution(SwingDoctrine doctrine, List<SwingStrategy> swings) {
      this.doctrine = doctrine;
      swings.forEach(s -> published.put(s.versionId(), s));
    }

    Optional<SwingStrategy> resolve(UUID versionId) {
      SwingStrategy current = published.get(versionId);
      if (current != null) {
        return Optional.of(current);
      }
      return adopted.computeIfAbsent(versionId, v -> adoptVersion(doctrine, v));
    }
  }

  /**
   * Adopts one superseded/unpublished version for exit management. The strategy's {@code enabled} flag
   * is deliberately NOT checked — a disabled strategy no longer ENTERS (the published load skips it)
   * but its open positions must still be exit-managed to their close.
   */
  private Optional<SwingStrategy> adoptVersion(SwingDoctrine doctrine, UUID versionId) {
    Optional<StrategyRepository.VersionRow> versionRow = registry.findVersionById(versionId);
    if (versionRow.isEmpty()) {
      log.error(
          "{} swing: anchor version {} has no version row — OPEN POSITION UNMANAGED",
          doctrine.batchName(), versionId);
      return Optional.empty();
    }
    JsonNode config = versionRow.get().config();
    if (!doctrine.universeMode().equals(config.path("universe").path("mode").asText())) {
      return Optional.empty(); // another family's anchor — its own engine manages it
    }
    try {
      StrategyDefinition definition = StrategyCompiler.compile(config);
      if (!"swing".equals(definition.session().style())) {
        return Optional.empty();
      }
      StrategyRepository.StrategyRow strategy =
          registry.findById(versionRow.get().strategyId()).orElse(null);
      if (strategy == null) {
        log.error(
            "{} swing: strategy for anchor version {} not found — OPEN POSITION UNMANAGED",
            doctrine.batchName(), versionId);
        return Optional.empty();
      }
      log.warn(
          "{} swing: adopting anchors of superseded version {} of {} — exit-managed with the"
              + " version's own frozen config",
          doctrine.batchName(), versionRow.get().version(), strategy.slug());
      // includesOnDeck is entry-pass state: an adopted version is only ever exit-managed (a held
      // position must reach its stop whichever bucket admitted it), so it is resolved for uniformity
      // and never read on this path.
      return Optional.of(
          new SwingStrategy(
              versionId, strategy.slug(), strategy.name(), versionRow.get().version(),
              versionRow.get().checksum(), doctrine.setupToken(config, strategy.slug()),
              UniverseResolver.includesOnDeck(config.path("universe")), definition));
    } catch (RuntimeException e) {
      log.error(
          "{} swing: anchor version {} failed to compile — OPEN POSITION UNMANAGED: {}",
          doctrine.batchName(), versionId, e.getMessage());
      return Optional.empty();
    }
  }

  // ---- entry pass ---------------------------------------------------------------------------

  private EntryResult entryPass(
      SwingDoctrine doctrine, List<SwingStrategy> swings, AnchorResolution resolution,
      Map<String, List<EngineCandle>> seriesCache, List<SwingCandidate> candidates,
      LocalDate requiredBarDate, RunDeadline deadline) {
    // Per-book risk governor early-out: a book already kill-switched / daily-loss-tripped at the START
    // of the run takes no entries at all (cheap — skips the whole candidate scan).
    if (entryBlocked(doctrine)) {
      return new EntryResult(0, 0, List.of(), false);
    }
    if (candidates.isEmpty()) {
      return new EntryResult(0, 0, List.of(), false);
    }
    // The open lots per symbol at the START of the run (a DB snapshot). A symbol with ≥1 lot is
    // "held": with a NONE pyramid policy it is skipped (single-lot); with an add-capable policy it may
    // take an ADD (§3.4) — a fresh lot averaged into the one paper position.
    Map<String, List<SignalRepository.SignalRow>> openLots = openLotsBySymbol(resolution);
    java.util.Set<String> firedThisRun = new java.util.HashSet<>();
    PyramidPolicy pyramid = doctrine.pyramid();
    int fired = 0;
    List<String> refusalReasons = new ArrayList<>();
    for (SwingCandidate c : candidates) {
      if (deadline.expired()) {
        return new EntryResult(candidates.size(), fired, refusalReasons, true);
      }
      if (firedThisRun.contains(c.symbol())) {
        continue; // already emitted an entry/add for this symbol earlier in the run
      }
      List<SignalRepository.SignalRow> allLots = openLots.getOrDefault(c.symbol(), List.of());
      if (requiredBarDate != null && !allLots.isEmpty()) {
        List<SignalRepository.SignalRow> asOfLots = lotsAsOf(allLots, requiredBarDate);
        if (asOfLots.size() != allLots.size()) {
          String reason =
              asOfLots.isEmpty() ? postSessionLotReason(c.symbol()) : mixedLotReason(c.symbol());
          recordMixedLotRefusal(doctrine, requiredBarDate, c.symbol(), reason);
          refusalReasons.add(reason);
          continue;
        }
      }
      // A retry must not emit a second logical entry for the same pinned symbol after a prior paper
      // effect was claimed. That second signal would average into the same paper position.
      LocalDate effectSession = effectSession(requiredBarDate);
      if (paperEffects != null
          && paperEffects.entryAttempted(doctrine.batchName(), effectSession, c.symbol())) {
        continue;
      }
      List<SignalRepository.SignalRow> lots = allLots;
      boolean isAdd = !lots.isEmpty();
      if (isAdd && !pyramid.hasRoom(lots)) {
        continue; // held with no room → skip BEFORE any fetch (the single-lot held-skip)
      }
      List<EngineCandle> series = series(doctrine, c.symbol(), seriesCache, requiredBarDate);
      if (series.size() < doctrine.minBars()) {
        continue;
      }
      EngineCandle bar = series.get(series.size() - 1);
      if (isAdd && !pyramid.qualifiesForAdd(lots, bar)) {
        continue; // §3.4.1: an add needs a fresh +min-gain% move since the most-recent lot
      }
      for (SwingStrategy strat : swings) {
        // Setup routing (as data): a candidate valid only for some setups fires only the matching
        // strategy; eligibleSetups == null means no routing (all strategies eligible).
        if (c.eligibleSetups() != null && !c.eligibleSetups().contains(strat.setupToken())) {
          continue;
        }
        // universe.bucket routing: a bucket=buyable strategy never enters an on-deck name — the same
        // reading the submission pin gives the leaf. Per-strategy (not per-funnel) so one family's
        // strategies may declare different buckets while still sharing the single funnel fetch.
        if (c.onDeck() && !strat.includesOnDeck()) {
          continue;
        }
        IndicatorBank bank = buildBank(strat.definition(), c.symbol(), series, c.contextSeeds());
        Optional<EntryEvaluator.Evaluation> eval =
            EntryEvaluator.evaluate(strat.definition(), bank, series.size() - 1);
        if (eval.isPresent() && eval.get().entry()) {
          // Audit H3: re-check the per-book gate BEFORE each emit (auto-paper is a synchronous
          // @EventListener, so entries fired earlier this run are already counted) — so
          // max_open / max_deployment_pct / daily_loss bound a single batch.
          if (entryBlocked(doctrine)) {
            log.info(
                "{} swing: {} book gate tripped mid-run after {} entries — stopping",
                doctrine.batchName(), doctrine.book(), fired);
            return new EntryResult(candidates.size(), fired, refusalReasons, false);
          }
          // §3.4.3 / M40 (owner-directed 2026-08-02, HOLD-tier — see
          // docs/signal-analysis/2026-08-02-m40-fresh-entry-risk-cap-gap.md for the gap this closes):
          // the book's aggregate open risk must stay within the portfolio cap for BOTH a pyramid ADD
          // and a FRESH (first) entry — the deployment/daily-loss governor rails bound CAPITAL (or day
          // P&L), never aggregate open RISK, so concurrent fresh entries could otherwise exceed
          // doctrine's 5-6% cap even with pyramiding OFF (multiple names each risking 1% of equity is
          // reachable at current config, since max_open_paper_positions=7 is shared by both Manas
          // strategies through the one Books.MANAS_ARORA key). wouldBreachRiskCap's formula is exact,
          // not merely conservative, for a fresh entry: with no existing lot on this symbol the new
          // position's own risk contribution truly IS newQty × stopDistance — the averaging caveat in
          // its javadoc applies only to an add into an already-open bracket.
          if (pyramid.wouldBreachRiskCap(
              strat.definition(), EX, c.symbol(), bank, series.size() - 1, bar.close(),
              emissionGuard.orElse(null))) {
            String kind = isAdd ? "pyramid add" : "fresh entry";
            log.info(
                "{} swing: {} for {} would breach the open-risk cap — skipped",
                doctrine.batchName(), kind, c.symbol());
            // Audit + alert on refusal (E4 §2f pattern; M40 extends the SAME call site to the
            // fresh-entry path rather than duplicating it): three of RiskService's four audited rails
            // (daily-loss/profit-target/heat-cap) write a risk_audit row + push an ntfy alert on trip;
            // deployment audits only. Both kinds share one EmissionGuard port call + one RiskService
            // audit key (PYRAMID_RISK_CAP, per-IST-day-per-book deduped) — the free-text detail records
            // which kind fired; a same-day add-breach and fresh-entry-breach on the same book dedupe
            // against each other (only the first of the day is audited/alerted), see the receipt's
            // open-doubts.
            emissionGuard.ifPresent(
                g ->
                    g.recordPyramidRiskCapBreach(
                        doctrine.book(),
                        c.symbol(),
                        kind + " for " + c.symbol() + " blocked by the "
                            + pyramid.describe().getOrDefault("maxPortfolioRiskPct", "?")
                            + "% portfolio open-risk cap"));
            break; // no setup may open/add more risk for this symbol this run
          }
          if (deadline.expired()) {
            return new EntryResult(candidates.size(), fired, refusalReasons, true);
          }
          if (emitEntry(
              doctrine, strat, c, bar, eval.get(), bank, series.size() - 1, lots.size() + 1,
              effectSession)) {
            firedThisRun.add(c.symbol()); // one entry/add per symbol per run
            fired++;
          }
          break;
        }
      }
    }
    return new EntryResult(candidates.size(), fired, refusalReasons, false);
  }

  /** True when the family book's per-book gate (kill-switch / caps / daily-loss) blocks entry. */
  private boolean entryBlocked(SwingDoctrine doctrine) {
    return emissionGuard.map(g -> !g.entryAllowed(doctrine.book())).orElse(false);
  }

  /**
   * The open lots (active ENTRY anchors) grouped by tradingsymbol, resolved through {@link
   * AnchorResolution} so a lot held by a SUPERSEDED version still counts (the audit-H2 double-exposure
   * path). A symbol with ≥1 lot is "held"; the list size is its current lot count for the pyramid cap.
   */
  private Map<String, List<SignalRepository.SignalRow>> openLotsBySymbol(AnchorResolution resolution) {
    Map<String, List<SignalRepository.SignalRow>> bySymbol = new HashMap<>();
    for (SignalRepository.SignalRow anchor : signals.activeEntries()) {
      if (resolution.resolve(anchor.strategyVersionId()).isPresent()) {
        bySymbol.computeIfAbsent(anchor.tradingsymbol(), k -> new ArrayList<>()).add(anchor);
      }
    }
    return bySymbol;
  }

  // ---- admission probe (ledger F3) ----------------------------------------------------------

  /**
   * The measurement-only FIFO-vs-RS slot-admission probe (ledger F3). MEASURES how hard the slot cap
   * bound this run — it NEVER changes admission and runs AFTER both emission passes, wrapped fail-soft so
   * a probe defect can never break the batch (this batch is the swing positions' only exit evaluator).
   */
  private AdmissionProbe admissionProbe(
      SwingDoctrine doctrine, List<SwingStrategy> swings, AnchorResolution resolution,
      Map<String, List<EngineCandle>> seriesCache, List<SwingCandidate> candidates,
      java.util.Set<String> heldBefore, LocalDate requiredBarDate) {
    try {
      return computeAdmissionProbe(
          doctrine, swings, resolution, seriesCache, candidates, heldBefore, requiredBarDate);
    } catch (RuntimeException e) {
      log.warn(
          "{} swing admission probe failed (measurement-only, ignored): {}",
          doctrine.batchName(), e.getMessage());
      return AdmissionProbe.empty();
    }
  }

  /**
   * Walks the RS-priority funnel and partitions the FRESH would-be entrants (non-held candidates whose
   * entry fires, IGNORING the cap) into {@code admitted} (held AFTER the entry pass — the governor gave
   * them a slot) vs dropped-by-cap (still not held — the cap or a mid-run kill-switch / daily-loss trip
   * shed them). {@code EntryEvaluator} is a pure function of (definition, bank, index), so re-evaluating
   * here yields the SAME decision the entry pass made — the only difference is the emission side effects,
   * which the evaluator never reads. A held-before candidate is skipped (an add does not consume a fresh
   * slot). The dropped names' 1-based funnel rank is their RS-priority position.
   */
  private AdmissionProbe computeAdmissionProbe(
      SwingDoctrine doctrine, List<SwingStrategy> swings, AnchorResolution resolution,
      Map<String, List<EngineCandle>> seriesCache, List<SwingCandidate> candidates,
      java.util.Set<String> heldBefore, LocalDate requiredBarDate) {
    java.util.Set<String> heldAfter = openLotsBySymbol(resolution).keySet();
    int wouldEnter = 0;
    int admitted = 0;
    List<DroppedCandidate> dropped = new ArrayList<>();
    int rank = 0;
    for (SwingCandidate c : candidates) {
      rank++; // 1-based position in the funnel's RS-priority admission order (the walk order)
      if (heldBefore.contains(c.symbol())) {
        continue; // already held at start — an add does not compete for a fresh slot
      }
      List<EngineCandle> series = series(doctrine, c.symbol(), seriesCache, requiredBarDate);
      if (series.size() < doctrine.minBars() || !firesEntry(swings, c, series)) {
        continue;
      }
      wouldEnter++;
      if (heldAfter.contains(c.symbol())) {
        admitted++; // the batch admitted a fresh position for this name
      } else {
        dropped.add(new DroppedCandidate(c.symbol(), rank)); // the governor (cap) dropped it
      }
    }
    int capExceedance = wouldEnter - admitted;
    return new AdmissionProbe(
        heldBefore.size(), wouldEnter, admitted, capExceedance, capExceedance > 0, dropped);
  }

  /**
   * True iff at least one setup-eligible strategy's frozen {@link EntryEvaluator} fires a fresh entry on
   * {@code series}' last bar — the cap-free "would this name have opened" predicate the F3 probe counts.
   * Mirrors the entry pass's per-candidate eval exactly (eligible-setup + universe.bucket routing +
   * {@link #buildBank}) — a candidate the entry pass could never admit must not be counted here.
   */
  private boolean firesEntry(
      List<SwingStrategy> swings, SwingCandidate c, List<EngineCandle> series) {
    int index = series.size() - 1;
    for (SwingStrategy strat : swings) {
      if (c.eligibleSetups() != null && !c.eligibleSetups().contains(strat.setupToken())) {
        continue;
      }
      if (c.onDeck() && !strat.includesOnDeck()) {
        continue;
      }
      IndicatorBank bank = buildBank(strat.definition(), c.symbol(), series, c.contextSeeds());
      Optional<EntryEvaluator.Evaluation> eval =
          EntryEvaluator.evaluate(strat.definition(), bank, index);
      if (eval.isPresent() && eval.get().entry()) {
        return true;
      }
    }
    return false;
  }

  /** The oldest (earliest-generated) lot — the pyramid's governing exit driver (§3.5.D). */
  private static SignalRepository.SignalRow oldestLot(List<SignalRepository.SignalRow> lots) {
    return lots.stream()
        .min(
            java.util.Comparator.comparing(SignalRepository.SignalRow::generatedAt)
                .thenComparing(SignalRepository.SignalRow::id))
        .orElseThrow();
  }

  private boolean emitEntry(
      SwingDoctrine doctrine, SwingStrategy strat, SwingCandidate c, EngineCandle bar,
      EntryEvaluator.Evaluation eval, IndicatorBank bank, int index, int lotNumber,
      LocalDate effectSession) {
    BigDecimal entryPrice = bar.close();
    ExitEvaluator.EntryLevels levels =
        ExitEvaluator.entryLevels(
            strat.definition(), bank,
            new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice, index));
    BigDecimal stopLoss = levels.stopLoss();
    BigDecimal target = levels.takeProfit();
    String breakdownJson = ScoreBreakdownJson.write(eval.breakdown());
    OffsetDateTime generatedAt = bar.bucketStart().withOffsetSameInstant(IST);
    OffsetDateTime expiresAt = generatedAt.plusMinutes(doctrine.ttlMinutes());
    // The detail side-channel always leads with the emitting strategy's slug (known only here, at
    // emit), then the candidate's baked fields (footprint/pivot/…), then the engine-owned lot number
    // for a pyramid add (≥2) — so a first entry's detail is byte-identical to the single-lot path.
    ObjectNode detail = objectMapper.createObjectNode();
    detail.put("setup", strat.slug());
    detail.setAll(c.detailBase());
    if (lotNumber > 1) {
      detail.put("pyramidLot", lotNumber);
    }
    String detailJson = detail.toString();
    // The advisory (lot-rounded) size vs the BOOK's paper equity — REQUIRED for auto-paper (the
    // listener skips any ENTRY whose suggested_qty is null). Computed before the tx (reads the
    // instrument master / paper equity). Null when the emission guard is absent (mock/no-risk).
    BigDecimal stopDistance = stopLoss == null ? null : entryPrice.subtract(stopLoss).abs();
    BigDecimal suggestedQty =
        emissionGuard
            .map(
                g ->
                    g.suggestedQty(
                        strat.definition().sizing(), EX, c.symbol(), entryPrice, stopDistance,
                        doctrine.book()))
            .orElse(null);
    long id =
        tx.execute(
            status -> {
              if (paperEffects != null
                  && !paperEffects.expectEntry(doctrine.batchName(), effectSession, c.symbol())) {
                return -1L;
              }
              long newId =
                  signals.insert(
                      strat.versionId(), EX, c.symbol(), IV, "ENTRY", "BUY", entryPrice, stopLoss,
                      target, eval.breakdown().composite(), breakdownJson, generatedAt, expiresAt,
                      null); // ENTRY carries no exit reason
              if (suggestedQty != null) {
                signals.stampSuggestedQty(newId, suggestedQty);
              }
              doctrine.stampDetail(newId, detailJson);
              if (paperEffects != null) {
                paperEffects.bindEntry(
                    doctrine.batchName(), effectSession, c.symbol(), newId,
                    suggestedQty == null ? 0L : suggestedQty.longValue());
                if (suggestedQty == null || suggestedQty.signum() <= 0) {
                  // No sized paper order can be produced for this signal; retain the ledger row as
                  // the idempotency marker but do not strand the session behind a nonexistent effect.
                  paperEffects.skipEntry(newId);
                }
              }
              return newId;
            });
    if (id <= 0) {
      return false;
    }
    JsonNode canonical;
    try {
      canonical = objectMapper.readTree(breakdownJson);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("canonical breakdown unparseable", e);
    }
    publisher.publish(
        id, strat.versionId(), strat.name(), strat.slug(), strat.version(), strat.checksum(),
        doctrine.book(), EX, c.symbol(), IV, "ENTRY", "BUY", entryPrice, stopLoss, target,
        eval.breakdown().composite(), canonical, generatedAt);
    events.publishEvent(
        new SignalEmitted(
            id, strat.versionId(), EX, c.symbol(), "BUY", entryPrice, stopLoss, target,
            eval.breakdown().composite(), eval.breakdown().threshold(), null));
    log.info(
        "{} swing ENTRY #{} {} {} at {} (composite {})",
        doctrine.batchName(), id, strat.slug(), c.symbol(), entryPrice,
        eval.breakdown().composite());
    return true;
  }

  // ---- exit pass ----------------------------------------------------------------------------

  private ExitResult exitPass(
      SwingDoctrine doctrine, AnchorResolution resolution,
      Map<String, List<EngineCandle>> seriesCache, LocalDate requiredBarDate, RunDeadline deadline) {
    // Group the open lots by symbol. A pyramided symbol holds several lots sharing ONE averaged paper
    // position (§3.4); the governing stop is the OLDEST lot's trailed stop — the tightest, first to
    // hit (§3.5.D). So the exit is driven off that lot and, when it fires, closes the whole position
    // and expires every sibling lot. A single-lot symbol is a singleton group — byte-behaviour-
    // identical to the per-anchor loop; a pyramid (add-capable policy) is the only multi-lot source.
    Map<String, List<SignalRepository.SignalRow>> bySymbol = openLotsBySymbol(resolution);
    int closed = 0;
    int skipped = 0;
    List<String> refusalReasons = new ArrayList<>();
    for (Map.Entry<String, List<SignalRepository.SignalRow>> e : bySymbol.entrySet()) {
      if (deadline.expired()) {
        return new ExitResult(closed, skipped, refusalReasons, true);
      }
      List<SignalRepository.SignalRow> allLots = e.getValue();
      List<SignalRepository.SignalRow> lots = lotsAsOf(allLots, requiredBarDate);
      if (lots.isEmpty()) {
        if (requiredBarDate != null) {
          log.info(
              "{} swing exit: all active lots for {} opened after pinned session {} â€” not evaluated",
              doctrine.batchName(), e.getKey(), requiredBarDate);
        }
        continue;
      }
      if (requiredBarDate != null && lots.size() != allLots.size()) {
        if (!lots.isEmpty()) {
          String reason = mixedLotReason(e.getKey());
          recordMixedLotRefusal(doctrine, requiredBarDate, e.getKey(), reason);
          refusalReasons.add(reason);
        }
        log.error(
            "{} swing exit: {} has mixed pre/post-session lots for pinned session {} â€” refusing"
                + " approximate evaluation",
            doctrine.batchName(), e.getKey(), requiredBarDate);
        continue;
      }
      SignalRepository.SignalRow primary = oldestLot(lots);
      SwingStrategy strat = resolution.resolve(primary.strategyVersionId()).orElse(null);
      if (strat == null) {
        continue; // another family's anchor, or unmanageable (already logged at ERROR)
      }
      List<EngineCandle> series = series(doctrine, primary.tradingsymbol(), seriesCache, requiredBarDate);
      if (series.isEmpty()) {
        // One retry OUTSIDE the per-run cache: MarketDataCandlesClient fail-softs to an empty list on
        // any REST hiccup and the cache pins it — silently skipping the position's ONLY daily
        // stop/trail evaluation (audit P0-3). A held anchor's series is worth a second round-trip.
        series = retryFetch(doctrine, primary.tradingsymbol(), seriesCache, requiredBarDate);
      }
      if (series.isEmpty()) {
        log.error(
            "{} swing exit: no daily series for held anchor #{} {} even after retry —"
                + " STOP NOT EVALUATED TODAY",
            doctrine.batchName(), primary.id(), primary.tradingsymbol());
        skipped++;
        continue;
      }
      // geometry is irrelevant to the exit rules (percent/ATR stop + trail), so the pivot contexts are
      // seeded neutral (0) — the bank still builds every declared indicator, the exit eval never reads
      // their value.
      IndicatorBank bank =
          buildBank(strat.definition(), primary.tradingsymbol(), series, doctrine.neutralContextSeeds());
      int entryIndex = bank.primarySeries().indexAtOrBefore(primary.generatedAt().toInstant());
      if (entryIndex < 0) {
        // the entry bar fell outside the fetched window (held > warmupDays, or a dropped entry
        // bucket) — an entry-relative exit distance would be unreliable, so skip rather than default to
        // bar 0 (the entry-pinned ATR / peak-since-entry trail is entry-index-DEPENDENT).
        log.error(
            "{} swing exit: entry bar for #{} {} is outside the fetched window —"
                + " STOP NOT EVALUATED TODAY",
            doctrine.batchName(), primary.id(), primary.tradingsymbol());
        skipped++;
        continue;
      }
      ExitEvaluator.Position position =
          new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, primary.entryPrice(), entryIndex);
      Optional<ExitEvaluator.ExitDecision> exit =
          ExitEvaluator.evaluate(strat.definition(), bank, position, series.size() - 1);
      if (exit.isPresent()) {
        if (deadline.expired()) {
          return new ExitResult(closed, skipped, refusalReasons, true);
        }
        if (emitExit(
            doctrine,
            strat,
            primary,
            lots,
            series.get(series.size() - 1),
            exit.get(),
            effectSession(requiredBarDate))) {
          closed++;
        }
      } else {
        cacheGoverningStop(
            doctrine, primary, strat.definition(), bank, position, series.size() - 1, entryIndex);
      }
    }
    return new ExitResult(closed, skipped, refusalReasons, false);
  }

  /**
   * M40 cross-vendor review Critical 3 fix, round 3 (owner ruling, 2026-08-02 — supersedes round 1's
   * {@code stop_loss} write and round 2's dedicated column, both reverted): on a bar where the
   * position did NOT exit, CACHES a tighter (never looser — enforced in the cache itself, see {@code
   * EmissionGuard#cacheManasGoverningStop}) governing stop for aggregate-risk accounting, IN MEMORY
   * ONLY — never a database write, never {@code paper_positions.stop_loss}, never any new column.
   * {@code stop_loss} is ALSO the intraday disaster-stop the paper module's 15-second bracket poller
   * reads with no book filter; both earlier drafts of this fix risked or would have coupled the risk
   * figure to that intraday-exit surface, so M40 ships EXIT-NEUTRAL — this call cannot change what
   * {@code PaperBracketEvaluator} (or anything else reading {@code stop_loss}) ever sees. {@link
   * SwingDoctrine#governingStop}'s default is {@code null} (a safe no-op) for every family except
   * Manas, whose trail rule IS its exit_rules trailing_stop (see that method's javadoc). Fail-soft,
   * mirroring the Major-4 fix for the risk-cap audit: this batch is the position's ONLY exit
   * evaluator, so an accounting failure here must never propagate and skip a later position's stop
   * evaluation this run.
   */
  private void cacheGoverningStop(
      SwingDoctrine doctrine,
      SignalRepository.SignalRow primary,
      StrategyDefinition definition,
      IndicatorBank bank,
      ExitEvaluator.Position position,
      int lastIndex,
      int entryIndex) {
    if (emissionGuard.isEmpty()) {
      return;
    }
    try {
      BigDecimal governingStop = doctrine.governingStop(definition, bank, position, lastIndex, entryIndex);
      if (governingStop != null) {
        emissionGuard
            .get()
            .cacheManasGoverningStop(doctrine.book(), EX, primary.tradingsymbol(), "BUY", governingStop);
      }
    } catch (RuntimeException e) {
      log.warn(
          "{} swing: governing-stop cache failed for {} (accounting only, exit unaffected): {}",
          doctrine.batchName(), primary.tradingsymbol(), e.getMessage());
    }
  }

  private void recordMixedLotRefusal(
      SwingDoctrine doctrine, LocalDate sessionDate, String symbol, String reason) {
    if (refusals != null) {
      try {
        refusals.record(doctrine.batchName(), sessionDate, symbol, reason);
      } catch (RuntimeException e) {
        log.error(
            "{} swing: failed to persist refusal {} for {} {}",
            doctrine.batchName(), reason, sessionDate, symbol, e);
      }
    }
    try {
      events.publishEvent(
          new SwingBatchAlert(
              doctrine.batchName(),
              doctrine.alertLabel() + " " + reason + " refusal",
              sessionDate + " " + reason + " — refusing as-of evaluation; no approximate money effect was emitted."));
    } catch (RuntimeException e) {
      log.warn("{} swing refusal alert failed: {}", doctrine.batchName(), e.getMessage());
    }
  }

  private static String mixedLotReason(String symbol) {
    return "MIXED_PRE_POST_LOTS:" + symbol;
  }

  private static String postSessionLotReason(String symbol) {
    return "POST_SESSION_LOT_PRESENT:" + symbol;
  }

  private LocalDate effectSession(LocalDate requiredBarDate) {
    return requiredBarDate != null ? requiredBarDate : LocalDate.now(clock.withZone(IST));
  }

  /** Returns only lots that existed at the pinned session; an unpinned run keeps the live set intact. */
  private static List<SignalRepository.SignalRow> lotsAsOf(
      List<SignalRepository.SignalRow> lots, LocalDate requiredBarDate) {
    if (requiredBarDate == null) {
      return lots;
    }
    return lots.stream()
        .filter(lot -> !lot.generatedAt().withOffsetSameInstant(IST).toLocalDate().isAfter(requiredBarDate))
        .toList();
  }

  /**
   * Re-fetches a held anchor's daily series once, bypassing (and on success repairing) the per-run
   * cache — the fail-soft empty from a transient REST hiccup must not stand between an open position
   * and its only daily stop evaluation.
   */
  private List<EngineCandle> retryFetch(
      SwingDoctrine doctrine, String symbol, Map<String, List<EngineCandle>> cache,
      LocalDate requiredBarDate) {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(IST);
    List<EngineCandle> fresh =
        truncateToSession(
            candles.fetch(EX, symbol, IV, now.minusDays(doctrine.warmupDays()), now), symbol,
            requiredBarDate);
    if (!fresh.isEmpty()) {
      cache.put(symbol, fresh);
    }
    return fresh;
  }

  /**
   * The catch-up staleness rule: with a session pinned ({@code requiredBarDate} non-null), the series
   * is TRUNCATED to end at that session's daily bar — every later bar is dropped. Both passes read only
   * the last bar, so an unpinned late run would decide off whatever bar is newest — for an EXIT, a
   * settle at the WRONG day's close, the exact harm the catch-up exists to undo. Truncating makes the
   * decision identical to what the on-time run would have computed off the session's own bar, whether
   * the catch-up runs that night or several sessions later (the intervening days moved the price, but
   * the position should have exited on the session, so its later moves are irrelevant). When the
   * session's own bar is absent (a data gap, or older than the warmup window), the series drops to
   * empty — a counted, alerted skip ("STOP NOT EVALUATED TODAY"), retryable, never a settle off the
   * wrong bar. {@code null} — the scheduled path — returns the series untouched (byte-identical).
   */
  private static List<EngineCandle> truncateToSession(
      List<EngineCandle> series, String symbol, LocalDate requiredBarDate) {
    if (requiredBarDate == null || series.isEmpty()) {
      return series;
    }
    // Bars are ascending by bucket. Walk from the newest down to the session's bar.
    for (int i = series.size() - 1; i >= 0; i--) {
      LocalDate barDate =
          series.get(i).bucketStart().withOffsetSameInstant(IST).toLocalDate();
      if (barDate.equals(requiredBarDate)) {
        return i == series.size() - 1 ? series : List.copyOf(series.subList(0, i + 1));
      }
      if (barDate.isBefore(requiredBarDate)) {
        break; // walked past the session without finding its bar — it is missing
      }
    }
    log.warn(
        "swing catch-up: {} has no daily bar for the pinned session {} — series dropped (retryable,"
            + " not settled off a newer bar)",
        symbol, requiredBarDate);
    return List.of();
  }

  private boolean emitExit(
      SwingDoctrine doctrine,
      SwingStrategy strat,
      SignalRepository.SignalRow primary,
      List<SignalRepository.SignalRow> lots,
      EngineCandle bar,
      ExitEvaluator.ExitDecision exit,
      LocalDate effectSession) {
    OffsetDateTime generatedAt = bar.bucketStart().withOffsetSameInstant(IST);
    // the paper close_reason taxonomy is UPPERCASE (STOP_LOSS / TRAILING_STOP / SIGNAL_EXIT / …)
    String reason = exit.type().toUpperCase(Locale.ROOT);
    List<Long> lotIds = lots.stream().map(SignalRepository.SignalRow::id).distinct().toList();
    long id =
        tx.execute(
            status -> {
              // Discovery runs INSIDE the effect transaction, behind the per-anchor advisory lock
              // (cross-vendor round 4 TOCTOU): outside the transaction, a concurrent /taken paper
              // open landing between the read and the commit was persisted as a stale empty
              // SKIPPED and the position orphaned. Under the lock the open either committed first
              // (discovery sees it) or waits and then sees the EXPIRED anchor and refuses. A
              // discovery failure throws here, rolling back before anything durable.
              final List<Long> targetPositionIds;
              if (paperEffects == null) {
                targetPositionIds = List.of();
              } else {
                signals.lockAnchors(lotIds);
                targetPositionIds = paperEffects.openPositionIdsForSignals(lotIds);
              }
              if (paperEffects != null
                  && !paperEffects.expectExit(
                      doctrine.batchName(),
                      effectSession,
                      primary.id(),
                      reason,
                      bar.close(),
                      targetPositionIds)) {
                return -1L;
              }
              long newId =
                  signals.insert(
                      strat.versionId(), EX, primary.tradingsymbol(), IV, "EXIT", "SELL", bar.close(),
                      null, null, primary.compositeScore(), primary.scoreBreakdown().toString(),
                      generatedAt, generatedAt.plusMinutes(doctrine.ttlMinutes()), reason);
              // A pyramided position closes ALL lots at once (§3.5.D): expire every lot of the symbol
              // so no add lingers as a phantom active anchor once its shared position is gone. A
              // single-lot symbol expires its one anchor (identical to the per-anchor path).
              for (SignalRepository.SignalRow lot : lots) {
                signals.transition(lot.id(), "EXPIRED");
              }
              if (paperEffects != null) {
                paperEffects.bindExit(doctrine.batchName(), effectSession, primary.id(), newId);
              }
              return newId;
            });
    if (id <= 0) {
      return false;
    }
    publisher.publish(
        id, strat.versionId(), strat.name(), strat.slug(), strat.version(), strat.checksum(),
        doctrine.book(), EX, primary.tradingsymbol(), IV, "EXIT", "SELL", bar.close(), null, null,
        primary.compositeScore(), primary.scoreBreakdown(), generatedAt);
    // Settle the paper position at the fresh DAILY-BAR close — the equities don't tick, so an LTP
    // close would book breakeven. Fire a close for EACH lot's linked signal so the shared averaged
    // position closes regardless of which lot's order opened it (closeForSignal is a CAS: first wins,
    // the rest no-op). A single-lot symbol fires exactly one.
    for (SignalRepository.SignalRow lot : lots) {
      events.publishEvent(new SignalExited(lot.id(), id, reason, bar.close()));
    }
    log.info(
        "{} swing EXIT #{} {} {} at {} ({}{})",
        doctrine.batchName(), id, strat.slug(), primary.tradingsymbol(), bar.close(), reason,
        lots.size() > 1 ? " — " + lots.size() + " lots" : "");
    return true;
  }

  // ---- shared helpers -----------------------------------------------------------------------

  /**
   * Builds the indicator bank for one symbol over its daily {@code series} with the doctrine's
   * per-symbol context seeds as flat context series — the same context-close mechanism the golden
   * runner uses. One flat context bar per {@code contextSeeds} entry (name → value). Public so the
   * sell-decision service + unit tests can build a bank the same way.
   */
  public static IndicatorBank buildBank(
      StrategyDefinition definition, String symbol, List<EngineCandle> series,
      Map<String, BigDecimal> contextSeeds) {
    SeriesKey primaryKey = new SeriesKey(EX, symbol, definition.primaryTimeframe());
    EngineSeries primary = new EngineSeries(primaryKey);
    for (EngineCandle candle : series) {
      primary.append(candle);
    }
    OffsetDateTime start = series.get(0).bucketStart();
    Map<String, EngineSeries> ctx = new HashMap<>();
    for (Map.Entry<String, BigDecimal> seed : contextSeeds.entrySet()) {
      ctx.put(seed.getKey(), flat(seed.getKey(), start, seed.getValue()));
    }
    SeriesProvider provider =
        key -> key.equals(primaryKey) ? primary : ctx.get(key.tradingsymbol());
    return IndicatorBank.build(
        definition, new StrategyDefinition.InstrumentRef(EX, symbol), provider);
  }

  /** A single flat context bar at {@code at} carrying {@code value} (null → 0) — read by contextLevel. */
  private static EngineSeries flat(String symbol, OffsetDateTime at, BigDecimal value) {
    EngineSeries s = new EngineSeries(new SeriesKey(EX, symbol, IV));
    BigDecimal v = value == null ? BigDecimal.ZERO : value;
    s.append(new EngineCandle(at, v, v, v, v, 0L, null));
    return s;
  }

  private List<EngineCandle> series(
      SwingDoctrine doctrine, String symbol, Map<String, List<EngineCandle>> cache,
      LocalDate requiredBarDate) {
    return cache.computeIfAbsent(
        symbol,
        s -> {
          OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(IST);
          return truncateToSession(
              candles.fetch(EX, s, IV, now.minusDays(doctrine.warmupDays()), now), s,
              requiredBarDate);
        });
  }

  private List<SwingStrategy> loadPublishedSwingStrategies(SwingDoctrine doctrine) {
    List<SwingStrategy> out = new ArrayList<>();
    for (StrategyRepository.StrategyRow strategy : registry.listAll()) {
      if (!strategy.enabled() || strategy.publishedVersionId() == null) {
        continue;
      }
      Optional<StrategyRepository.VersionRow> versionRow =
          registry.findVersionById(strategy.publishedVersionId());
      if (versionRow.isEmpty()) {
        continue;
      }
      try {
        StrategyDefinition definition = StrategyCompiler.compile(versionRow.get().config());
        if (!"swing".equals(definition.session().style())) {
          continue;
        }
        // Scope this engine to its OWN funnel universe: only strategies whose universe.mode matches
        // are evaluated here, so the two swing families never evaluate each other's universe.
        if (!doctrine.universeMode()
            .equals(versionRow.get().config().path("universe").path("mode").asText())) {
          continue;
        }
        out.add(
            new SwingStrategy(
                strategy.publishedVersionId(), strategy.slug(), strategy.name(),
                versionRow.get().version(), versionRow.get().checksum(),
                doctrine.setupToken(versionRow.get().config(), strategy.slug()),
                UniverseResolver.includesOnDeck(versionRow.get().config().path("universe")),
                definition));
      } catch (RuntimeException e) {
        log.warn(
            "{} swing strategy {} failed to compile — skipped: {}",
            doctrine.batchName(), strategy.slug(), e.getMessage());
      }
    }
    return out;
  }
}
