package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.insights.InsightGenerator.GenerationContext;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * The insight engine (INT design §9.2): orchestrates the registered generators per trigger, stamps
 * engine identity + config hash, and persists idempotently (§2.5.1 dedupe upsert). Generators are
 * pure; ALL IO (trust reads, book-heat reads, persistence) is here at the edge — so the generators
 * stay unit-testable against fixtures and the whole set replays byte-identically (§10.1).
 *
 * <p><b>I1 is shadow mode.</b> Insights are written as rows only — NO ntfy/Telegram/WS delivery
 * (that arms in I3/I4). Dedupe (one OPEN row per {@code dedupe_key}, refreshed in place) is the noise
 * control that runs now; {@code cooldown_until} is recorded for the delivery floors that arm later.
 */
@Service
public class InsightEngine {

  private static final Logger log = LoggerFactory.getLogger(InsightEngine.class);
  private static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);

  private final List<InsightGenerator> generators;
  private final InsightRepository repository;
  private final TrustService trustService;
  private final BookHeatReader bookHeatReader;
  private final ContextClient contextClient;
  private final RejectionReader rejectionReader;
  private final PortfolioReader portfolioReader;
  private final StrategyEvidenceReader strategyEvidenceReader;
  private final InsightPublisher publisher;
  private final InsightProperties props;
  private final EngineStamp stamp;
  private final ObjectMapper objectMapper;
  private final java.time.Clock clock;
  private final MeterRegistry meters;

  /** Wires the generator catalog (the injected list IS the registry) + the IO edges. */
  public InsightEngine(
      List<InsightGenerator> generators,
      InsightRepository repository,
      TrustService trustService,
      BookHeatReader bookHeatReader,
      ContextClient contextClient,
      RejectionReader rejectionReader,
      PortfolioReader portfolioReader,
      StrategyEvidenceReader strategyEvidenceReader,
      InsightPublisher publisher,
      InsightProperties props,
      EngineStamp stamp,
      ObjectMapper objectMapper,
      java.time.Clock clock,
      MeterRegistry meters) {
    this.generators = generators;
    this.repository = repository;
    this.trustService = trustService;
    this.bookHeatReader = bookHeatReader;
    this.contextClient = contextClient;
    this.rejectionReader = rejectionReader;
    this.portfolioReader = portfolioReader;
    this.strategyEvidenceReader = strategyEvidenceReader;
    this.publisher = publisher;
    this.props = props;
    this.stamp = stamp;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.meters = meters;
  }

  /**
   * On a fired signal: compute its priority insight. Runs on the notifier async pool (the
   * {@code PaperMarginAnnotator} precedent — a String bean name, no {@code notifier} import, no
   * Modulith cycle) so the trust fan-out never sits on the signal-eval thread. Fully fail-soft — an
   * insight failure must never perturb the live signal path.
   */
  @Async("notifierExecutor")
  @EventListener
  public void onSignalEmitted(SignalEmitted ev) {
    try {
      OffsetDateTime now = OffsetDateTime.now(clock);
      TrustSnapshot snap = trustService.snapshot();
      TrustSnapshot.FamilyTrust signalTrust = trustService.signalTrust(snap);
      SignalPriorityInputs in = toInputs(ev, signalTrust, now);
      run(GenerationContext.forSignal(in, now), now);
    } catch (RuntimeException e) {
      log.warn("insight signal-priority generation failed for signal {}: {}", ev.signalId(), e.toString());
    }
  }

  /** The data-trust sweep (called by {@link InsightSweeper}). Fail-soft. */
  public void runTrustSweep() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    run(GenerationContext.forTrust(trustService.snapshot(), now), now);
  }

  /**
   * The risk sweep (called by {@link InsightSweeper}): RISK_HEAT (book-heat read) + RISK_STALE_TICK
   * (open bracketed positions over stalled feed instruments, the §12 interim health/data trigger).
   * Fail-soft.
   */
  public void runRiskSweep() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    StaleTickSnapshot staleTick = portfolioReader.staleTickScan(contextClient.staleFeedKeys());
    run(GenerationContext.forRisk(bookHeatReader.read(), staleTick, now), now);
  }

  /**
   * The 15-min context sweep (called by {@link InsightSweeper}): CONTEXT_SHIFT + MARKET_STRUCTURE
   * (digest crossings, per configured underlying + the day-context one-call) + REJECTION_NEARMISS
   * (the session's rejections closest to firing). Every digest read is fail-soft — an absent digest
   * simply yields no crossing for that underlying (§6.5 honest degrade). Fail-soft overall.
   */
  public void runContextSweep() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    List<MarketContext.OptionsShift> shifts = new java.util.ArrayList<>();
    for (String underlying : props.context().underlyings()) {
      contextClient.optionsShift(exchangeFor(underlying), underlying).ifPresent(shifts::add);
    }
    MarketContext market = new MarketContext(shifts, contextClient.marketStructure().orElse(null));
    run(GenerationContext.forContext(market, rejectionReader.nearMissScan(), now), now);
  }

  /** The EOD sweep (called by {@link InsightSweeper}): REJECTION_RAIL_TREND + HYGIENE. Fail-soft. */
  public void runEodSweep() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    run(GenerationContext.forEod(rejectionReader.railTrendScan(), portfolioReader.hygieneScan(), now), now);
  }

  /** The T-1 expiry sweep (called by {@link InsightSweeper}): EXPIRY_EVENT. Fail-soft. */
  public void runExpirySweep() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    run(GenerationContext.forExpiry(portfolioReader.expiryScan(), now), now);
  }

  /** The weekly quality-report job (called by {@link InsightSweeper}): QUALITY_REPORT. Fail-soft. */
  public void runQualityReport() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    run(GenerationContext.forQuality(repository.qualityStats(props), now), now);
  }

  /**
   * The 21:10 strategy-evidence sweep (called by {@link InsightSweeper}, after the 21:00 graduation
   * evaluator): STRATEGY_EVIDENCE. Reads today's graduation board vs yesterday's persisted snapshot
   * (§5.2), writes today's snapshot + any crossing insight. Fail-soft.
   */
  public void runStrategyEvidenceSweep() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    run(GenerationContext.forStrategyEvidence(strategyEvidenceReader.strategyEvidenceScan(), now), now);
  }

  /**
   * The sell-decision sweep (called by {@link InsightSweeper}, after the swing batches persist V037):
   * SELL_DECISION over the unacknowledged SELL rows (§5.3). Fail-soft.
   */
  public void runSellDecisionSweep() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    run(GenerationContext.forSellDecision(strategyEvidenceReader.sellDecisionScan(), now), now);
  }

  /** The index exchange for an underlying's insight scope (SENSEX → BSE, else NSE). */
  private static String exchangeFor(String underlying) {
    return underlying != null && underlying.toUpperCase(java.util.Locale.ROOT).contains("SENSEX")
        ? "BSE"
        : "NSE";
  }

  /** Run every generator over the context and persist each candidate (idempotent upsert). */
  private void run(GenerationContext ctx, OffsetDateTime now) {
    for (InsightGenerator gen : generators) {
      List<InsightCandidate> candidates;
      try {
        candidates = gen.generate(ctx);
      } catch (RuntimeException e) {
        log.warn("insight generator {} failed: {}", gen.type(), e.toString());
        continue;
      }
      for (InsightCandidate c : candidates) {
        persist(c, now);
      }
    }
  }

  private void persist(InsightCandidate c, OffsetDateTime now) {
    try {
      OffsetDateTime cooldownUntil = c.cooldownMinutes() == null ? null : now.plusMinutes(c.cooldownMinutes());
      Insight row =
          new Insight(
              UUID.randomUUID(),
              now,
              c.type().name(),
              c.severity().name(),
              c.scope(),
              c.title(),
              c.explanation(),
              toJson(c.evidence()),
              c.priority(),
              c.priorityDetail() == null ? null : objectMapper.valueToTree(c.priorityDetail()),
              c.dataTrust().name(),
              c.trustReasons(),
              c.dedupeKey(),
              cooldownUntil,
              c.suppressed(),
              Insight.Status.OPEN.name(),
              c.expiresAt(),
              stamp.engineVersion(),
              stamp.configHash());
      repository.insertOrRefresh(row);
      meters.counter("ay_insights_generated_total", "type", c.type().name()).increment();
      if (c.suppressed()) {
        meters.counter("ay_insights_suppressed_total").increment();
      }
      // Stage-1 WS delivery (§9.3): the publisher no-ops in shadow mode (flag default OFF), so nothing
      // pushes until the owner arms it — the durable row above is always the record of truth.
      publisher.publish(row);
    } catch (RuntimeException e) {
      log.warn("insight persist failed ({}): {}", c.dedupeKey(), e.toString());
    }
  }

  private JsonNode toJson(List<Evidence> evidence) {
    return objectMapper.valueToTree(evidence == null ? List.of() : evidence);
  }

  /**
   * Maps a {@code SignalEmitted} to the pure priority inputs. Family = scalp side-channel present →
   * scalper (option leg) else swing (the emitted symbol). Track-record + risk are NOT wired on the
   * emit path in I1 → both degrade to their §3.2 neutral priors (documented in the evidence).
   */
  private SignalPriorityInputs toInputs(SignalEmitted ev, TrustSnapshot.FamilyTrust trust, OffsetDateTime now) {
    boolean scalper = ev.scalp() != null;
    String family = scalper ? "scalper" : "swing";
    String tradingsymbol = scalper && ev.scalp().tradeable() != null ? ev.scalp().tradeable() : ev.tradingsymbol();
    String side = scalper && ev.scalp().optionSide() != null ? ev.scalp().optionSide() : ev.side();
    java.math.BigDecimal strike = scalper ? ev.scalp().strike() : null;
    java.math.BigDecimal confluence = scalper ? ev.scalp().confluence() : null;
    Integer minutesToClose = null;
    if (scalper) {
      ZonedDateTime istNow = clock.instant().atZone(Ist.ZONE);
      if (istNow.toLocalTime().isBefore(SESSION_CLOSE)) {
        minutesToClose = (int) Duration.between(istNow.toLocalTime(), SESSION_CLOSE).toMinutes();
      }
    }
    return new SignalPriorityInputs(
        ev.signalId(),
        family,
        ev.exchange(),
        tradingsymbol,
        side,
        strike,
        ev.composite(),
        ev.threshold(),
        confluence,
        null, // swing RS percentile: §13 row 12 — absent on the API today → context neutral 0.5 + note
        scalper ? null : "RS unavailable on API",
        null, // track-record PF: not wired on the emit path in I1 → neutral prior
        null,
        null,
        false,
        null, // risk heat: not wired on the emit path in I1 → neutral prior
        null,
        false,
        null,
        0L, // just fired → freshest
        minutesToClose,
        trust.state(),
        trust.reasons(),
        now.toString());
  }
}
