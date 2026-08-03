package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.signals.SignalRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The paper-trading ledger (Q1). Orders fill through the shared {@link PaperFillService} (engine
 * JAR), positions average on the §F.6 partial-unique open key, realized P&amp;L is the exact signed
 * cash of the entry + exit legs (both priced by the JAR), and unrealized P&amp;L is computed live
 * from the Redis last-tick map — never stored.
 */
@Service
public class PaperService {

  private static final Logger log = LoggerFactory.getLogger(PaperService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  /**
   * Open-order request (from a signal, or a manual entry); optional SL/TP bracket levels. {@code book}
   * is the paper book to charge; null → resolved from the signal's strategy family, or {@code MANUAL}
   * for a hand order with no signal. {@code clientOrderId} is the OPTIONAL audit-V2 idempotency key,
   * set ONLY on the manual {@code POST /orders} path — the engine/taken path leaves it null.
   */
  public record OrderRequest(
      Long signalId,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal price,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      Integer subaccountIdx,
      String book,
      String clientOrderId) {

    /** Pre-V2 10-arg form: book set, no idempotency key (the manual path before audit V2). */
    public OrderRequest(
        Long signalId,
        String exchange,
        String tradingsymbol,
        String side,
        long qty,
        BigDecimal price,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        Integer subaccountIdx,
        String book) {
      this(signalId, exchange, tradingsymbol, side, qty, price, stopLoss, takeProfit, subaccountIdx, book, null);
    }

    /** E10 9-arg form: sub-account set, book resolved from the signal. */
    public OrderRequest(
        Long signalId,
        String exchange,
        String tradingsymbol,
        String side,
        long qty,
        BigDecimal price,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        Integer subaccountIdx) {
      this(signalId, exchange, tradingsymbol, side, qty, price, stopLoss, takeProfit, subaccountIdx, null, null);
    }

    /** A copy charged to {@code idx} — used when the sub-account is assigned under the book lock. */
    OrderRequest withSubaccountIdx(Integer idx) {
      return new OrderRequest(
          signalId, exchange, tradingsymbol, side, qty, price, stopLoss, takeProfit, idx, book,
          clientOrderId);
    }

    /** Pre-E10 8-arg form: no sub-account (manual / non-scalper order leaves the ledger key NULL). */
    public OrderRequest(
        Long signalId,
        String exchange,
        String tradingsymbol,
        String side,
        long qty,
        BigDecimal price,
        BigDecimal stopLoss,
        BigDecimal takeProfit) {
      this(signalId, exchange, tradingsymbol, side, qty, price, stopLoss, takeProfit, null, null, null);
    }
  }

  /** An open position with its live mark-to-market (and an optional buying-power warning on open). */
  public record PositionDto(
      long id,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      @Schema(type = "string") BigDecimal avgEntryPrice,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal markPrice,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal unrealizedPnl,
      @Schema(type = "string") BigDecimal realizedPnl,
      String status,
      OffsetDateTime openedAt,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal stopLoss,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal takeProfit,
      @Schema(types = {"string", "null"}) String buyingPowerWarning) {

    /** A copy carrying the non-blocking buying-power warning (A12). */
    PositionDto withWarning(String warning) {
      return new PositionDto(
          id, exchange, tradingsymbol, side, qty, avgEntryPrice, markPrice, unrealizedPnl,
          realizedPnl, status, openedAt, stopLoss, takeProfit, warning);
    }
  }

  /**
   * A closed trade. {@code closedAt} is NON-nullable, unlike the same-named field on {@link
   * PositionDetail} (which describes OPEN positions too, where it is genuinely null and stays
   * annotated as such). {@link PositionDto} has no {@code closedAt} at all — an earlier draft of
   * this javadoc named it here and the cross-vendor review caught that. Every row that reaches this
   * DTO has {@code status='CLOSED'} — {@link #trades} reads only
   * {@code listClosed}, and {@link #closePosition} re-reads AFTER a won close — and {@link
   * PaperPositionRepository#close} is the only writer of that status, setting {@code closed_at=now()}
   * in the same atomic UPDATE. V055 pins the invariant in the DATABASE so a future writer cannot
   * silently falsify this type.
   */
  public record TradeDto(
      long id,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      @Schema(type = "string") BigDecimal avgEntryPrice,
      @Schema(type = "string") BigDecimal realizedPnl,
      OffsetDateTime openedAt,
      OffsetDateTime closedAt) {}

  /** The itemized statutory cost legs of one fill (recomputed for display; {@link PaperFillService#costs}). */
  public record FeeBreakdown(
      @Schema(type = "string") BigDecimal brokerage,
      @Schema(type = "string") BigDecimal stt,
      @Schema(type = "string") BigDecimal exchangeTxn,
      @Schema(type = "string") BigDecimal gst,
      @Schema(type = "string") BigDecimal stamp,
      @Schema(type = "string") BigDecimal sebi,
      @Schema(type = "string") BigDecimal total) {}

  /** One order leg of a position (entry / averaged add / exit) with its fill-audit + recomputed fees. */
  public record OrderLeg(
      long orderId,
      @Schema(types = {"integer", "null"}) Long signalId,
      String side,
      long qty,
      String status,
      OffsetDateTime placedAt,
      @Schema(types = {"string", "null"}) OffsetDateTime filledAt,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal fillPrice,
      @Schema(types = {"string", "null"}) String fillSimulator,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal slippageApplied,
      FeeBreakdown fees) {}

  /** The signal that opened a position (audit H5), with its family enrichment side-channels. */
  public record OpeningSignal(
      long signalId,
      String status,
      String side,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal entryPrice,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal stopLoss,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal target,
      @Schema(type = "string") BigDecimal compositeScore,
      OffsetDateTime generatedAt,
      @Schema(types = {"object", "null"}) JsonNode scalperDetail,
      @Schema(types = {"object", "null"}) JsonNode minerviniDetail,
      @Schema(types = {"object", "null"}) JsonNode manasAroraDetail) {}

  /**
   * The full provenance of one paper position (Phase-2 §6.4/§6.5): the position + live MTM, its F9
   * advisory (advised-vs-actual lots, margin snapshot/%), the E10 sub-account, the opening signal +
   * its family detail, and the ordered entry/exit legs with a recomputed fee breakdown. Assembled for
   * the detail pane; the ordered legs + opening signal ARE the trade-chain timeline.
   */
  public record PositionDetail(
      long id,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      @Schema(type = "string") BigDecimal avgEntryPrice,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal markPrice,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal unrealizedPnl,
      @Schema(type = "string") BigDecimal realizedPnl,
      String status,
      OffsetDateTime openedAt,
      @Schema(types = {"string", "null"}) OffsetDateTime closedAt,
      @Schema(types = {"string", "null"}) String closeReason,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal stopLoss,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal takeProfit,
      @Schema(types = {"integer", "null"}) Long advisedLots,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal marginSnapshot,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal marginPct,
      @Schema(types = {"integer", "null"}) Integer subaccountIdx,
      @Schema(types = {"integer", "null"}) Long openingSignalId,
      @Schema(types = {"object", "null"}) OpeningSignal openingSignal,
      List<OrderLeg> orders) {}

  /** The result of a bracket edit: the previous levels (for the audit trail) + the refreshed detail. */
  public record BracketEdit(
      BigDecimal previousStopLoss, BigDecimal previousTakeProfit, PositionDetail detail) {}

  private final PaperOrderRepository orders;
  private final PaperPositionLotRepository lots;
  private final PaperPositionRepository positions;
  private final PaperFillService fills;
  private final LastTickReader lastTick;
  private final InstrumentMetaClient instruments;
  private final SignalRepository signals;
  private final PaperAccountService accountService;
  private final BookResolver books;
  private final RiskService risk;
  private final ScalperAccountModel accounts;
  private final ApplicationEventPublisher events;
  private final PaperStaleTickAlerter staleTicks;
  private final PaperOrderRejectionRecorder rejections;
  private final ManasGoverningStopCache governingStopCache;
  private final BigDecimal perTradeRiskPct;
  /** V057 savepoint-scoped template: a failed lot tag rolls back ONLY the tag, never the fill. */
  private final TransactionTemplate lotTagTemplate;
  /** V057 fail-soft counter — a non-zero value means attribution has a KNOWN hole. */
  private final Counter lotTagFailures;
  /** V057 attribution read: rows + coverage in ONE REPEATABLE_READ snapshot, never two. */
  private final TransactionTemplate attributionTemplate;
  /** Audit V3: a fill priced off a last tick older than this is fiction — rejected DATA_STALE. */
  private final Duration tickMaxAge;
  /**
   * HOLD (task_94f40cf6): a manual signal-take on a NON-swing book whose signal is older than this
   * is rejected SIGNAL_STALE — the setup's thesis is gone even though the fill would price fresh.
   * Zero/negative disables the gate; swing books are always exempt. Separate from {@link #tickMaxAge}.
   */
  private final Duration signalTakeMaxAge;
  /**
   * V058 / option D (owner-approved 2026-08-03): the books whose OPEN-position key is STRATEGY-SCOPED
   * — two strategies entering the same {@code (exchange, tradingsymbol, side)} hold SEPARATE rows with
   * their own brackets and their own exits instead of averaging into one.
   *
   * <p><b>EMPTY BY DEFAULT — the mechanism ships DISARMED.</b> Arming it changes realised P&amp;L on
   * every co-fired trade (each twin then exits on its own doctrine instead of on the pointwise minimum
   * of both), which is an owner decision on live money, not a deploy-time default. With the set empty
   * every fill stamps {@code strategy_id = NULL} and the whole feature — index, joins, sub-account
   * inheritance — collapses to the pre-V058 behaviour exactly.
   */
  private final java.util.Set<String> strategyScopedBooks;

  private final TransactionTemplate txTemplate;

  /** Wires the ledger collaborators, registering the M4 MTM-blind visibility gauge. */
  @Autowired
  public PaperService(
      PaperOrderRepository orders,
      PaperPositionLotRepository lots,
      PaperPositionRepository positions,
      PaperFillService fills,
      LastTickReader lastTick,
      InstrumentMetaClient instruments,
      SignalRepository signals,
      PaperAccountService accountService,
      BookResolver books,
      RiskService risk,
      ScalperAccountModel accounts,
      ApplicationEventPublisher events,
      PaperStaleTickAlerter staleTicks,
      PaperOrderRejectionRecorder rejections,
      ManasGoverningStopCache governingStopCache,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Value("${artha.paper.risk.per-trade-risk-pct:1.0}")
          BigDecimal perTradeRiskPct,
      @org.springframework.beans.factory.annotation.Value("${artha.paper.tick-max-age-seconds:15}")
          long tickMaxAgeSeconds,
      @org.springframework.beans.factory.annotation.Value(
              "${artha.paper.signal-take-max-age-minutes:60}")
          long signalTakeMaxAgeMinutes,
      @org.springframework.beans.factory.annotation.Value("${artha.paper.strategy-scoped-books:}")
          String strategyScopedBooks,
      MeterRegistry meterRegistry) {
    this.orders = orders;
    this.lots = lots;
    this.positions = positions;
    this.fills = fills;
    this.lastTick = lastTick;
    this.instruments = instruments;
    this.signals = signals;
    this.accountService = accountService;
    this.books = books;
    this.risk = risk;
    this.accounts = accounts;
    this.events = events;
    this.staleTicks = staleTicks;
    this.rejections = rejections;
    this.governingStopCache = governingStopCache;
    this.perTradeRiskPct = perTradeRiskPct;
    this.tickMaxAge = Duration.ofSeconds(tickMaxAgeSeconds);
    this.signalTakeMaxAge = Duration.ofMinutes(signalTakeMaxAgeMinutes);
    // Same comma-list parse as PaperStaleTickAlerter's eod-managed-books, blank ⇒ empty ⇒ disarmed.
    this.strategyScopedBooks =
        strategyScopedBooks == null || strategyScopedBooks.isBlank()
            ? java.util.Set.of()
            : java.util.Arrays.stream(strategyScopedBooks.trim().split("\\s*,\\s*"))
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    this.txTemplate = new TransactionTemplate(transactionManager);
    this.lotTagTemplate = new TransactionTemplate(transactionManager);
    // A real JDBC savepoint. DataSourceTransactionManager enables nested transactions by default;
    // without this a failed insert would poison the OUTER transaction and PostgreSQL would refuse
    // every later statement in the fill, which is the very outage this fail-soft path prevents.
    this.lotTagTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    this.attributionTemplate = new TransactionTemplate(transactionManager);
    this.attributionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.attributionTemplate.setReadOnly(true);
    this.lotTagFailures =
        Counter.builder("ay_paper_lot_tag_failures_total")
            .description("V057 per-signal lot tags that failed; the fill committed without one")
            .register(meterRegistry);
    // M4 (#128): "how many OPEN positions are structurally MTM-blind (no live tick) RIGHT NOW" —
    // DERIVED, not tracked. A prior transition-tracked Set<Long> design (cross-vendor review round
    // 1) fixed the poll-frequency problem but round 2 found it could permanently retain a closed
    // position (a settlement racing a stale DTO read could re-add an id AFTER the close's own
    // removal — the Set is thread-safe, but that ordering is not the same as CORRECT), never
    // purged on reset() (PaperPositionRepository.deleteAll has no matching cleanup), and started
    // EMPTY after a restart (under-reporting until every position was re-observed). A derived
    // value cannot drift: every metric read re-queries the CURRENT open-position set + CURRENT
    // tick state directly (see #countMtmBlindPositions), so there is no persistent state to race,
    // purge, or rebuild.
    meterRegistry.gauge("ay_paper_mtm_blind_positions", this, PaperService::countMtmBlindPositions);
  }

  /**
   * Test-only convenience (pre-V058 signature): the strategy-scoped-books set defaults to EMPTY —
   * i.e. DISARMED, pre-V058 behaviour — so every existing direct-construction call site that already
   * supplies its own registry keeps compiling byte-identical.
   */
  public PaperService(
      PaperOrderRepository orders,
      PaperPositionLotRepository lots,
      PaperPositionRepository positions,
      PaperFillService fills,
      LastTickReader lastTick,
      InstrumentMetaClient instruments,
      SignalRepository signals,
      PaperAccountService accountService,
      BookResolver books,
      RiskService risk,
      ScalperAccountModel accounts,
      ApplicationEventPublisher events,
      PaperStaleTickAlerter staleTicks,
      PaperOrderRejectionRecorder rejections,
      ManasGoverningStopCache governingStopCache,
      PlatformTransactionManager transactionManager,
      BigDecimal perTradeRiskPct,
      long tickMaxAgeSeconds,
      long signalTakeMaxAgeMinutes,
      MeterRegistry meterRegistry) {
    this(
        orders, lots, positions, fills, lastTick, instruments, signals, accountService, books, risk,
        accounts, events, staleTicks, rejections, governingStopCache, transactionManager,
        perTradeRiskPct, tickMaxAgeSeconds, signalTakeMaxAgeMinutes, "", meterRegistry);
  }

  /**
   * Test-only convenience (pre-M4 signature): a private, unshared registry absorbs the MTM-blind
   * gauge registration, so every existing direct-construction call site keeps compiling
   * byte-identical.
   */
  public PaperService(
      PaperOrderRepository orders,
      PaperPositionLotRepository lots,
      PaperPositionRepository positions,
      PaperFillService fills,
      LastTickReader lastTick,
      InstrumentMetaClient instruments,
      SignalRepository signals,
      PaperAccountService accountService,
      BookResolver books,
      RiskService risk,
      ScalperAccountModel accounts,
      ApplicationEventPublisher events,
      PaperStaleTickAlerter staleTicks,
      PaperOrderRejectionRecorder rejections,
      ManasGoverningStopCache governingStopCache,
      PlatformTransactionManager transactionManager,
      BigDecimal perTradeRiskPct,
      long tickMaxAgeSeconds,
      long signalTakeMaxAgeMinutes) {
    this(
        orders, lots, positions, fills, lastTick, instruments, signals, accountService, books, risk,
        accounts, events, staleTicks, rejections, governingStopCache, transactionManager,
        perTradeRiskPct, tickMaxAgeSeconds, signalTakeMaxAgeMinutes, "", new SimpleMeterRegistry());
  }

  /**
   * The MANUAL open path (audit V1): a hand ticket (`POST /api/v1/paper/orders`) must pass the SAME
   * per-book risk governor the engine-emitted entry already clears at emission ({@code
   * PaperEmissionGuard.entryAllowed} → {@code SignalEngine.emitEntry}) — kill switch / max-open /
   * daily-loss / profit-target / deployment / heat. Before this, a manual ticket filled with the kill
   * switch ON. A veto is a 422 naming the blocking rail; NEW exposure only — exits are never gated.
   *
   * <p>The gate runs OUTSIDE the fill transaction on purpose: {@code entryVeto} has non-transactional
   * side-effects on a trip (the in-memory per-day dedup, the ntfy push, AND the {@code risk_audit} row),
   * so it must NOT be rolled back by the veto-throw — otherwise the dedup would be poisoned while the DB
   * audit row vanished. The taken/emission path decouples gate-from-fill the same way. Once the gate
   * passes, the fill runs via {@code txTemplate} (REQUIRED/default attributes — equivalent to the
   * bare {@code @Transactional} the proxy path supplies), which keeps the AFTER_COMMIT F9 margin
   * annotation firing. NOTE: this in-class call BYPASSES the proxy, so {@code openOrder}'s annotation
   * is inert on the manual path — attributes added to it later (isolation/rollbackFor/timeout) would
   * apply to the taken path only; mirror them here (or extract the fill to a proxied bean).
   */
  public PositionDto openManualOrder(OrderRequest request) {
    String book = bookFor(request);
    // Audit V2 idempotency: a duplicate submission (network retry / double-click) carrying the SAME
    // clientOrderId must return the ORIGINAL position, never a second fill. This check runs BEFORE the
    // governor ON PURPOSE — the original submission already cleared the gate, so a replay must return
    // the original even if a governor has TRIPPED since (a retry after a kill-switch flip must not 422
    // an order that already filled).
    if (request.clientOrderId() != null) {
      replayFor(book, request.clientOrderId())
          .ifPresent(
              original -> {
                throw new DuplicateOrderException(original);
              });
    }
    // A linked EXIT row is a close advisory, never a proposition to open new exposure. This guard applies
    // to every book; only the separate age gate below exempts swing books.
    // HOLD (task_94f40cf6) signal-freshness gate: a signal TAKEN by hand hours after emission fills at
    // today's tick, but the SIGNAL's thesis is stale (a scalper's option premium has moved, the setup
    // is gone). Reject a too-old signal-linked take with 422 SIGNAL_STALE (age + limit in the body).
    // This is SEPARATE from the #694 TICK-freshness doctrine (that guards the FILL price; this guards
    // the SIGNAL) — an entry needs fresh truth on BOTH axes. SWING books (minervini / manas-arora) are
    // EXEMPT: their EOD signals are meant to be taken the NEXT session, so a ~1-day age is normal. A
    // hand ticket with no signalId (a MANUAL order) has no signal to age-check. Runs AFTER the
    // idempotency replay (a replay of an already-filled order must return the original even if the
    // signal has since aged out) and, like the governor, OUTSIDE the fill txn.
    if (request.signalId() != null) {
      long signalId = request.signalId();
      signals
          .find(signalId)
          .ifPresent(
              signal -> {
                if (!"ENTRY".equals(signal.signalType())) {
                  throw new ApiException(
                      422,
                      ErrorCodes.VALIDATION_FAILED,
                      "signal #" + signalId + " is " + signal.signalType()
                          + " and cannot open a paper position",
                      Map.of("signalId", signalId, "signalType", signal.signalType()));
                }
                if (signalTakeMaxAge.compareTo(Duration.ZERO) > 0 && !isSwingBook(book)) {
                  Duration age = Duration.between(signal.generatedAt().toInstant(), java.time.Instant.now());
                  if (age.compareTo(signalTakeMaxAge) > 0) {
                    throw new ApiException(
                        422,
                        ErrorCodes.SIGNAL_STALE,
                        "signal #" + signalId + " is " + age.toMinutes() + "m old (max "
                            + signalTakeMaxAge.toMinutes() + "m for book " + book
                            + ") — refusing a stale-signal take",
                        Map.of(
                            "signalId", signalId,
                            "book", book,
                            "signalAgeMinutes", age.toMinutes(),
                            "maxAgeMinutes", signalTakeMaxAge.toMinutes()));
                  }
                }
              });
    }
    // task_6f1372da dead-anchor gate: a hand ticket whose anchor can no longer reach TAKEN (EXPIRED by
    // the 15:45 sweep, DISMISSED) would open a position the live engine can NEVER exit — activeEntry
    // resolves an exit anchor only in ACTIVE/TAKEN, so no exit pass would ever run for it. Refusing is
    // the #694 doctrine applied to the signal axis: a manual ticket is an ENTRY, and "entries need fresh
    // truth (you can always NOT enter)" — the cost of refusing is one missed trade; the cost of filling
    // is an un-exitable position. It is NOT merely logged, because nothing downstream can see the shape:
    // PaperReconciliationRepository.strandedCarryPositions:176 needs a persisted opposite-side EXIT to
    // EXISTS against, and a dead anchor means the engine never evaluates an exit, so no EXIT row is ever
    // written. Already-TAKEN is deliberately NOT refused (auto-paper or a prior take anchored it — the
    // anchor is live, which is all the exit passes need).
    //
    // Placement: BEFORE the fill, matching the freshness gate + the governor — a refusal must leave zero
    // trace (contrast openOrder's DATA_STALE, which fills-then-throws and deliberately records the
    // attempt via REQUIRES_NEW). Before the governor too, so a dead-anchor ticket cannot burn a governor
    // trip (its ntfy push + risk_audit row) on an order that was never going to fill. Ordered AFTER the
    // freshness gate so every case SIGNAL_STALE already catches keeps its exact code, unchanged.
    if (request.signalId() != null) {
      long signalId = request.signalId();
      signals.find(signalId).ifPresent(signal -> requireTakeableAnchor(signalId, signal.status()));
    }
    risk.entryVeto(book)
        .ifPresent(
            rail -> {
              throw new ApiException(
                  422,
                  ErrorCodes.RISK_ENTRY_BLOCKED,
                  "entry blocked by risk governor (" + rail + ") on book " + book,
                  Map.of("book", book, "rail", rail));
            });
    try {
      return txTemplate.execute(
          status -> {
            PositionDto opened = openOrder(request);
            anchorTaken(request.signalId());
            return opened;
          });
    } catch (org.springframework.dao.DuplicateKeyException race) {
      // Concurrent duplicate: two submits with the same (book, clientOrderId) both passed the pre-check;
      // the uq_paper_orders_client_order_id partial-unique index let exactly one INSERT win and rolled
      // back the loser's whole fill txn. Return the winner's position (committed by the time the loser's
      // blocked INSERT surfaced the 23505). A null clientOrderId means this 23505 came from some OTHER
      // constraint (e.g. the open-position key) — not an idempotency collision — so re-throw untouched.
      if (request.clientOrderId() == null) {
        throw race;
      }
      PositionDto winner = replayFor(book, request.clientOrderId()).orElseThrow(() -> race);
      throw new DuplicateOrderException(winner);
    }
  }

  /**
   * Anchors a hand ticket's signal: CAS ACTIVE→TAKEN, exactly what the two other open paths already do
   * ({@code SignalsController.taken:130} for the explicit take, {@code AutoPaperListener:57} for the
   * auto path) — the manual ticket IS the owner taking the signal, so it must leave the same state.
   *
   * <p><b>Why this matters (the orphan it fixes):</b> the manual path left the anchor ACTIVE, and the
   * two 15:45 sweeps filter style INCONSISTENTLY — {@code SignalRepository.expireAllActive:256}
   * expires every ACTIVE row whose style is NOT swing, while {@code
   * PaperPositionRepository.intradayOpen:351} closes only style = {@code intraday}. A btst /
   * expiry_day / positional ticket therefore had its anchor swept to EXPIRED while its position stayed
   * OPEN, and {@code SignalRepository.activeEntry:166-178} anchors only ACTIVE/TAKEN — so the engine
   * could never emit that position's exit, ever. At TAKEN the sweep's {@code status='ACTIVE'} predicate
   * no longer matches, the anchor survives, and the exit passes keep running until {@link
   * TakenSignalResolver} resolves it TAKEN→EXPIRED on close. {@code intraday} was accidentally safe
   * (both sweeps fire) and {@code swing} is excluded from the signal sweep; the gap was the carry
   * styles — and btst + expiry_day are both live-enabled.
   *
   * <p><b>Placement is load-bearing.</b> This runs INSIDE the fill transaction and AFTER the fill, so
   * the anchor flips if and ONLY if the position actually opened: a DATA_STALE / no-price / lot-size
   * throw rolls the CAS back with the fill, and a CAS-first ordering would have stranded the anchor at
   * TAKEN with no position to resolve it (nothing ever closes → {@link TakenSignalResolver} never
   * fires → the anchor suppresses re-entry forever). The §7.2.1 status frame is published AFTER_COMMIT
   * ({@code SignalStatusListener}), so a rolled-back CAS never emits a phantom frame.
   *
   * <p><b>Losing the CAS.</b> Already TAKEN (auto-paper or a prior take won the race) is a correct
   * no-op — the anchor is live either way, which is all the exit passes need. Any other state means the
   * dead-anchor gate's read raced the 15:45 sweep (or a dismiss) landing between the gate and this CAS;
   * that re-throws the gate's own 422 from INSIDE the fill transaction, so the fill and the anchor
   * transition both roll back. NOTE the refusal is not literally traceless on this path: an earlier
   * {@code risk.entryVeto} audit row is written OUTSIDE the transaction by design and survives (the
   * pre-fill gate, which runs before the governor, IS traceless). Without this CAS-side re-throw the
   * narrow TOCTOU window would leak exactly the orphan the gate exists to block.
   */
  private void anchorTaken(Long signalId) {
    if (signalId == null) {
      return; // an ad-hoc ticket (book MANUAL) has no anchor to take — nothing to transition.
    }
    if (signals.transitionIf(signalId, "ACTIVE", "TAKEN")) {
      return;
    }
    String status = signals.find(signalId).map(SignalRepository.SignalRow::status).orElse(null);
    if (!"TAKEN".equals(status)) {
      requireTakeableAnchor(signalId, status);
    }
  }

  /**
   * Refuses a hand ticket whose anchor signal cannot open exposure: only ACTIVE (this ticket takes it)
   * and TAKEN (something already took it) leave an anchor the engine's exit passes can resolve. Shared
   * by the pre-fill gate and the CAS race-loser so both refusals render the identical D8 envelope.
   * {@code VALIDATION_FAILED} + a {@code Map} of the signal id and its blocking state mirrors the
   * sibling EXIT-signal guard — two adjacent guards in one method must not disagree about the shape of
   * "this signal cannot open a paper position". Not {@code SIGNAL_STALE}: that code's contract is the
   * signal's AGE (its details carry the age + the per-book limit), and this is a STATE refusal — a btst
   * signal fires at the 15:20 pre-close and is swept at 15:45, well inside the 60-minute age limit.
   */
  private static void requireTakeableAnchor(long signalId, String status) {
    if ("ACTIVE".equals(status) || "TAKEN".equals(status)) {
      return;
    }
    throw new ApiException(
        422,
        ErrorCodes.VALIDATION_FAILED,
        "signal #" + signalId + " is " + status + " and can no longer anchor a paper position — the"
            + " engine resolves exits only through an ACTIVE/TAKEN entry, so this fill would open a"
            + " position it could never exit",
        Map.of("signalId", signalId, "signalStatus", String.valueOf(status)));
  }

  /**
   * The position an idempotent replay must return: resolve the {@code clientOrderId} to its FILLED
   * order's {@link PaperOrderRepository.OrderKey}, then that key's most-recent position (see {@link
   * PaperPositionRepository#findLatestForKey}). Empty when the key was never filled.
   */
  private Optional<PositionDto> replayFor(String book, String clientOrderId) {
    // V058 (cross-vendor review Major 4): on a scoped book the key alone resolves to the NEWEST row,
    // so replaying twin A's ticket after twin B opened would return B's id/qty/brackets as if they
    // were A's fill — a silently wrong 200, not an error. Scope the read-back to the strategy that
    // actually placed THIS clientOrderId.
    java.util.UUID scope =
        strategyScopedBooks.contains(book)
            ? orders
                .strategyIdForClientOrderId(book, clientOrderId)
                .orElse(PaperPositionRepository.UNATTRIBUTED_SCOPE)
            : null;
    return orders
        .keyForClientOrderId(book, clientOrderId)
        .flatMap(
            k -> positions.findLatestForKey(book, k.exchange(), k.tradingsymbol(), k.side(), scope))
        .map(this::toPositionDto);
  }

  /**
   * The book to charge: explicit on the request, else the signal's strategy family, else {@code MANUAL}
   * (§F.6 first-recognised-family-tag convention). Shared by the manual gate + the fill so both resolve
   * the identical book.
   */
  private String bookFor(OrderRequest request) {
    return request.book() != null
        ? request.book()
        : request.signalId() != null ? books.bookForSignal(request.signalId()) : BookResolver.MANUAL;
  }

  /**
   * Swing books (minervini / manas-arora) are exempt from the signal-freshness gate — their EOD
   * signals fire post-close and are meant to be taken the NEXT session, so a ~1-day-old take is
   * normal, not stale. Every other book (scalper / other) is age-gated.
   */
  private static boolean isSwingBook(String book) {
    // Byte-identical to the previous inline test (BookResolver.MINERVINI/MANAS_ARORA ARE
    // Books.MINERVINI/MANAS_ARORA, BookResolver:20-21), now expressed through the one authority so
    // this freshness exemption and PaperStaleTickAlerter's eod-managed-books alert suppression
    // cannot drift apart — the drift that class's own comment warns about, previously unenforced
    // (PR #1251).
    return Books.eodManaged().contains(book);
  }

  /**
   * Opens a scalper entry, assigning its E10 sub-account UNDER the same book lock and transaction that
   * validates and writes it.
   *
   * <p>Selecting first and locking later is a race in its own right: two concurrent takes both read an
   * idle account 1, both pick it, and the writer — now correctly serialized — REFUSES the second
   * against account 1's allocation instead of routing it to idle account 2 (cross-vendor round 4). A
   * capital-aware picker only helps if it reads capital nobody else is about to claim, so the pick has
   * to happen inside the same critical section as the check and the write.
   */
  @Transactional
  public PositionDto openScalperOrder(OrderRequest request) {
    lockAnchorsBeforeBook(request);
    return openOrder(request.withSubaccountIdx(assignScalperSubAccount()));
  }

  /**
   * The straddle form of {@link #openScalperOrder}: assigns the sub-account ONCE and charges BOTH legs
   * to it.
   *
   * <p>Assigning per leg would re-route leg 2 — leg 1's capital is already visible inside this
   * transaction, so a capital-aware picker would deliberately send leg 2 somewhere else, breaking the
   * documented invariant that a straddle's two legs share one sub-account (they are one position).
   */
  @Transactional
  public List<PositionDto> openScalperPair(OrderRequest first, OrderRequest second) {
    lockAnchorsBeforeBook(first, second);
    int idx = assignScalperSubAccount();
    return openPair(first.withSubaccountIdx(idx), second.withSubaccountIdx(idx));
  }

  /**
   * Takes the SIGNAL-ANCHOR lock before anything here touches the BOOK lock — the one global lock
   * order for every entry path.
   *
   * <p>{@code openOrder} already takes the anchor lock (for the swing-exit TOCTOU) and then the book
   * lock. These scalper wrappers acquire the book lock first, to assign the sub-account inside it, so
   * without this they would run {@code book → anchor} while a signal-linked manual open runs
   * {@code anchor → book}. Two concurrent entries on the same scalper signal would then deadlock and
   * PostgreSQL would abort one of them — on the money path (cross-vendor round 5). Ordering is the
   * only defence: taking the anchor first here makes every wrapper agree.
   *
   * <p>Re-taking the same advisory lock later in {@code openOrder} is free — it is transaction-scoped
   * and re-entrant within the session.
   */
  private void lockAnchorsBeforeBook(OrderRequest... requests) {
    List<Long> anchors = new ArrayList<>();
    for (OrderRequest r : requests) {
      if (r.signalId() != null) {
        anchors.add(r.signalId());
      }
    }
    if (!anchors.isEmpty()) {
      // lockAnchors distincts + sorts internally, so a pair sharing one signal locks once.
      signals.lockAnchors(anchors);
    }
  }

  /** Takes the book lock, then picks the least-deployed unfrozen sub-account inside it. */
  private int assignScalperSubAccount() {
    positions.lockBookCapital(BookResolver.SCALPER);
    return accounts.nextFreeAccount();
  }

  /**
   * Opens BOTH legs of a two-leg pair (the #11 straddle) ATOMICALLY — either both positions exist or
   * neither does.
   *
   * <p>Load-bearing, and it is the capital caps that made it so. The legs used to open through two
   * independent transactions, which was survivable while nothing could refuse the second one. Once
   * {@code openOrder} enforces the deployment cap and the sub-account ceiling, a refused SECOND leg
   * would leave the FIRST one open — turning a delta-neutral straddle into an unintended NAKED
   * directional position. That is strictly worse than the cap breach the refusal prevents: a breach is
   * a sizing error, a naked leg is a different strategy (cross-vendor round 3).
   *
   * <p>Both calls are in-class, so they BYPASS the proxy and simply join this transaction (the same
   * self-invocation property {@code openManualOrder} documents). Two consequences, both wanted: leg 2's
   * cap check sees leg 1's uncommitted write, so the pair is projected cumulatively; and the per-book
   * advisory lock {@code openOrder} takes is held to commit, so no third order can interleave between
   * the legs.
   */
  @Transactional
  public List<PositionDto> openPair(OrderRequest first, OrderRequest second) {
    return List.of(openOrder(first), openOrder(second));
  }

  /** Simulates an entry; fills via {@code ltp_slippage/v1} against the next-tick LTP + cost model. */
  @Transactional
  public PositionDto openOrder(OrderRequest request) {
    String exchange = request.exchange();
    String tradingsymbol = request.tradingsymbol();
    String side = request.side();
    BigDecimal signalEntry = null;
    OffsetDateTime signalGeneratedAt = null;
    if (request.signalId() != null) {
      // Serialize against the swing EXIT's target discovery BEFORE reading the anchor (cross-vendor
      // round 4 TOCTOU): both sides take the same per-anchor advisory lock inside their transaction,
      // so this open either commits before the exit's discovery reads (the exit binds this position)
      // or blocks until the exit committed — and then sees the EXPIRED anchor below and refuses,
      // instead of opening a position whose only exit path has already run.
      signals.lockAnchors(List.of(request.signalId()));
      SignalRepository.SignalRow signal =
          signals
              .find(request.signalId())
              .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_SIGNAL, "no such signal"));
      if ("EXPIRED".equals(signal.status())) {
        throw new ApiException(
            422,
            ErrorCodes.VALIDATION_FAILED,
            "signal #" + request.signalId()
                + " is EXPIRED — its exit already settled, so a position opened now could never be"
                + " closed by the engine",
            Map.of("signalId", request.signalId(), "status", signal.status()));
      }
      // STRUCTURAL INVARIANT — an EXIT never opens exposure. The callers are guarded too
      // (openManualOrder + SignalsController.taken), but this is the layer that actually WRITES the
      // position, so the assert belongs here: a future SignalTaken publisher or a direct openOrder
      // caller would otherwise silently reopen the defect that put a live SELL into the long-only
      // manas-arora book on 2026-07-12. Free — the row is already loaded.
      if (!"ENTRY".equals(signal.signalType())) {
        throw new ApiException(
            422,
            ErrorCodes.VALIDATION_FAILED,
            "signal #" + request.signalId() + " is " + signal.signalType()
                + " and cannot open a paper position",
            Map.of("signalId", request.signalId(), "signalType", signal.signalType()));
      }
      // Prefer the request's EXPLICIT instrument when given (the #11 straddle opens its PE leg with an
      // explicit symbol/side/price while still linking the fill to the parent signal); fall back to the
      // signal's primary leg otherwise — backward-identical for every directional take (passes nulls).
      exchange = exchange != null ? exchange : signal.exchange();
      tradingsymbol = tradingsymbol != null ? tradingsymbol : signal.tradingsymbol();
      side = side != null ? side : signal.side();
      signalEntry = signal.entryPrice();
      signalGeneratedAt = signal.generatedAt();
    }
    if (exchange == null || tradingsymbol == null || side == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "exchange, tradingsymbol and side are required");
    }
    if (request.qty() <= 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "qty must be positive");
    }
    // Audit V3 tick freshness: an explicit request price always wins (the caller supplied it). Otherwise
    // fall to the live last tick — but ONLY when it is fresh: a fill priced off a stale LTP is fiction, so
    // a tick older than tickMaxAge is rejected DATA_STALE, never silently substituted. With no tick at all
    // a signal take still fills at its own entry price; nothing available stays the existing DATA_STALE.
    // P1-5 fill-reference provenance: record WHICH price the fill was struck against (CALLER = an
    // explicit request price / gate-captured premium / swing close; LIVE_TICK = the Redis last tick;
    // SIGNAL_ENTRY = the signal's own entry as last resort) and, for a LIVE_TICK, how fresh it was.
    BigDecimal reference = request.price();
    String refSource = reference != null ? "CALLER" : null;
    Long refTickAgeMs = null;
    if (reference == null) {
      Optional<LastTickReader.TickView> tick = lastTick.lastTick(exchange, tradingsymbol);
      if (tick.isPresent()) {
        Duration age = tick.get().age();
        if (age != null && age.compareTo(tickMaxAge) > 0) {
          // P1-4: durably record the refused attempt (auto-takes swallow this throw as a log line) —
          // fail-soft + REQUIRES_NEW so it survives the fill rollback and never masks the DATA_STALE.
          recordStaleRejectQuietly(request, exchange, tradingsymbol, side, age.toMillis());
          throw new ApiException(
              422,
              ErrorCodes.DATA_STALE,
              "last tick for " + exchange + ":" + tradingsymbol + " is " + age.toSeconds()
                  + "s old (max " + tickMaxAge.toSeconds() + "s) — refusing to fill at a stale price",
              Map.of(
                  "exchange", exchange, "tradingsymbol", tradingsymbol, "tickAgeSeconds", age.toSeconds()));
        }
        reference = tick.get().price();
        refSource = "LIVE_TICK";
        refTickAgeMs = age == null ? null : age.toMillis();
      } else {
        reference = signalEntry;
        refSource = reference != null ? "SIGNAL_ENTRY" : null;
      }
    }
    if (reference == null) {
      recordNoPriceRejectQuietly(request, exchange, tradingsymbol, side);
      throw new ApiException(422, ErrorCodes.DATA_STALE, "no price available to fill " + exchange + ":" + tradingsymbol);
    }
    // The book to charge: explicit on the request, else the signal's strategy family, else MANUAL.
    String book = bookFor(request);
    // V058 / option D: on a STRATEGY-SCOPED book, the strategy that owns this fill joins the open key,
    // so a co-firing twin opens its OWN row instead of averaging into the first one's. null on every
    // unscoped book AND on a signal-less hand ticket (nothing to attribute) — and a null here makes
    // every path below byte-identical to pre-V058.
    // Cross-vendor review Critical 3: on a scoped book this is NEVER null. A NULL row there would be
    // matched by EVERY strategy's exit (NULL is the wildcard arm of each scoped predicate), so a
    // signal-less hand ticket — or a signal whose strategy cannot be resolved — takes the explicit
    // UNATTRIBUTED_SCOPE sentinel instead. PaperStrategyScopeGuard refuses to boot if a scoped book
    // ever holds a NULL row anyway, so the wildcard stays confined to unscoped books.
    java.util.UUID positionStrategyId =
        !strategyScopedBooks.contains(book)
            ? null
            : request.signalId() == null
                ? PaperPositionRepository.UNATTRIBUTED_SCOPE
                : books
                    .strategyIdForSignal(request.signalId())
                    .orElse(PaperPositionRepository.UNATTRIBUTED_SCOPE);
    InstrumentMeta meta = instruments.meta(exchange, tradingsymbol);
    Fill fill = fills.fill(Side.valueOf(side), request.qty(), reference, meta);
    // The deployment cap, projected against what this fill ACTUALLY costs. Placed here — at the sole
    // writer, after the fill is struck — because this is the first and only point where all three
    // inputs exist together: the resolved instrument, the final quantity, and the SLIPPAGE-ADJUSTED
    // fill price. Gating at emission cannot use any of them (the leg and size do not exist yet), and
    // gating in openManualOrder covers one of the four doors.
    //
    // Being at the writer is what makes the straddle correct for free: each leg opens through its own
    // openOrder call in its own transaction, so leg 2 projects against a capitalUsed that ALREADY
    // includes committed leg 1. A projection computed once at emission prices one leg and admits two.
    //
    // This is NOT a re-run of the governor — see RiskService.deploymentWouldCross. It is a pure
    // read-and-compare with no audit row, no ntfy and no dedup, so the taken path stays ungated in the
    // sense that test pins (nothing is double-charged).
    // Valued the SAME way capitalUsed values every open position — accountService.usageFor applies
    // SPAN (or the flat margin-pct fallback) to futures and SHORT options, the premium to long
    // options, full notional to equities. Raw `price × qty` would be wrong here: it compares a
    // notional candidate against a margin-based sum, overstating a short option ~8× (0.12) and a
    // future ~6.7× (0.15). Long options and equities are identical either way, which is exactly why
    // the scalper path and every test still passed with the naive form.
    //
    // Computed ONCE and reused for the buying-power warning below, which already made this same call
    // on every open — so this is one FEWER external SPAN round-trip per fill, not one more.
    BigDecimal projectedCost =
        accountService.usageFor(
            meta, exchange, tradingsymbol, side, fill.fillPrice(), request.qty());
    // Serialize this book's check-plus-write. Both caps below read UNLOCKED aggregates before the
    // position is persisted, so without this two concurrent opens on the same book each observe the
    // same pre-write usage, both pass, and both commit past the cap — a hard limit two callers can
    // straddle is not a limit (cross-vendor round 3). Transaction-scoped, so for a straddle (whose
    // legs share one transaction) it is held across BOTH legs.
    positions.lockBookCapital(book);
    // M40 cross-vendor review Critical 1+2 fix (2026-08-02): the AUTHORITATIVE aggregate open-risk
    // check, under the same lock, against the ACTUAL fill/stop this write is about to persist — not
    // SwingBatchEngine's emission-time estimate off the candle close (see RiskService
    // #manasAggregateRiskCheck's javadoc for exactly which gaps this closes). Manas-only; a no-op
    // read for every other book. Round 7 (owner-approved, 2026-08-02): the result is a TYPED
    // outcome, not a boolean — only CALCULATED_BREACH may be audited via recordPyramidRiskCapBreach
    // (which consumes the per-day dedup key); every other refusal reason gets its own message and
    // NEVER touches that audit/dedup, so an accidental unsupported-side/uncomputable refusal can
    // never suppress a later, genuine breach the same day. Round 7 hardening (Codex, owner-approved,
    // 2026-08-02): a switch EXPRESSION, not an if/else-if — every ManasRiskOutcome constant must
    // yield a value here, so adding a 6th outcome later without a matching case is a COMPILE ERROR,
    // not a silent fall-through into a branch that was never meant to carry it (the exact shape of
    // the round-6 Critical this whole method exists to prevent, one level out). Deliberately no
    // `default` — if that error ever fires, the fix is to add a real case, not to silence it.
    RiskService.ManasRiskOutcome riskOutcome =
        risk.manasAggregateRiskCheck(
            book, exchange, tradingsymbol, side, request.qty(), fill.fillPrice(), request.stopLoss());
    ApiException riskRefusal =
        switch (riskOutcome) {
          case ADMIT -> null;
          case CALCULATED_BREACH -> {
            String detail =
                "fill-time aggregate risk for " + tradingsymbol + " would breach the "
                    + risk.manasAggregateRiskCapPct().toPlainString()
                    + "% portfolio open-risk cap (fill " + fill.fillPrice().toPlainString() + ")";
            risk.recordPyramidRiskCapBreach(book, tradingsymbol, detail);
            yield new ApiException(
                422,
                ErrorCodes.RISK_ENTRY_BLOCKED,
                "entry blocked by risk governor (" + RiskService.PYRAMID_RISK_CAP + ") on book "
                    + book + " — " + detail,
                Map.of("book", book, "rail", RiskService.PYRAMID_RISK_CAP));
          }
          case UNSUPPORTED_SIDE -> uncomputableRiskRefusal(
              book, tradingsymbol, "an unsupported side (Manas is long-only; no live SELL row"
                  + " exists to verify short-risk arithmetic against)");
          case UNDEFINED_GOVERNING_STOP -> uncomputableRiskRefusal(
              book, tradingsymbol, "an undefined governing stop somewhere in the book (neither a"
                  + " cached trail nor a persisted stop_loss)");
          case NON_POSITIVE_EQUITY -> uncomputableRiskRefusal(
              book, tradingsymbol, "non-positive book equity (a percentage of it is undefined)");
        };
    if (riskRefusal != null) {
      throw riskRefusal;
    }
    if (risk.deploymentWouldCross(book, projectedCost)) {
      String detail =
          "projected " + projectedCost.toPlainString()
              + " would cross cap "
              + risk.deploymentCap(book).map(BigDecimal::toPlainString).orElse("n/a");
      // Durable like every other refusal on this path: REQUIRES_NEW + fail-soft, so the trace survives
      // the rollback this throw causes and can never mask the 422 itself.
      recordDeploymentBlockedQuietly(request, exchange, tradingsymbol, side, detail);
      throw new ApiException(
          422,
          ErrorCodes.RISK_ENTRY_BLOCKED,
          "entry blocked by risk governor (" + RiskService.MAX_DEPLOYMENT_PCT + ") on book " + book
              + " — " + detail,
          Map.of("book", book, "rail", RiskService.MAX_DEPLOYMENT_PCT));
    }
    // The per-sub-account allocation, against the EFFECTIVE account — the one this fill will actually
    // be charged to. For an add onto an open key that is the EXISTING row's sub-account, not the
    // request's: upsertPosition averages into that row and keeps its original idx, so validating the
    // requested idx would check an account the money never reaches. That mismatch is what let two
    // correlated adds sit past one account's ceiling while the picker believed load was spread.
    Integer effectiveSubAccount =
        positions
            .openSubAccountIdx(book, exchange, tradingsymbol, side)
            .orElse(request.subaccountIdx());
    if (effectiveSubAccount != null
        && accounts.wouldExceedSubAccount(book, effectiveSubAccount, projectedCost)) {
      String detail =
          "projected " + projectedCost.toPlainString()
              + " would cross sub-account " + effectiveSubAccount + " allocation "
              + accounts.subAccountAllocation(book, effectiveSubAccount).toPlainString();
      recordDeploymentBlockedQuietly(request, exchange, tradingsymbol, side, detail);
      throw new ApiException(
          422,
          ErrorCodes.RISK_ENTRY_BLOCKED,
          "entry blocked by risk governor (sub_account_allocation) on book " + book + " — " + detail,
          Map.of(
              "book", book,
              "rail", "sub_account_allocation",
              "subaccountIdx", effectiveSubAccount));
    }
    long orderId =
        orders.insertFilled(
            book, request.signalId(), exchange, tradingsymbol, side, request.qty(), fill.fillPrice(),
            fills.simulatorId(), fill.slippageApplied(), null, null, request.clientOrderId(), refSource,
            refTickAgeMs, signalGeneratedAt);
    Long advisedLots = advisedLots(book, fill.fillPrice(), request.stopLoss());
    upsertPosition(
        book, exchange, tradingsymbol, side, request.qty(), fill.fillPrice(),
        request.stopLoss(), request.takeProfit(), effectiveSubAccount, advisedLots,
        request.signalId(), positionStrategyId);
    // F9: after the ledger commits, price the position's SPAN margin (fail-soft, off the txn) and stamp
    // margin_snapshot/margin_pct. The event fires only if a row exists (an averaged add still re-prices).
    Optional<PositionRow> opened = openLotFor(book, exchange, tradingsymbol, side, positionStrategyId);
    if (opened.isPresent()) {
      PositionRow row = opened.get();
      // V057 per-signal lot tag, written HERE because this is the first point where both ids exist:
      // the fill's order id (minted three statements up) and the position id (minted or found by
      // upsertPosition). Everything upstream knows only one of the two.
      //
      // This is the whole point of the change. upsertPosition AVERAGES a second open on an already
      // open key into the existing row and KEEPS its original opening_signal_id, so a position built
      // by two strategies firing on the same bar credits exactly one — measured live on 2026-08-03,
      // where all 10 closed scalper positions are 50/50 blends of scalp-golden-crossover-* and
      // scalp-connect-the-dots-* and a GROUP BY slug reports the latter at n=0. The averaging is
      // deliberate and is NOT changed here; the lot row simply keeps the per-fill truth it discards.
      tagLotFailSoft(
          row.id(), orderId, request.signalId(), book, exchange, tradingsymbol, side, request.qty(),
          fill.fillPrice());
      events.publishEvent(
          new PaperPositionOpened(row.id(), book, exchange, tradingsymbol, side, row.qty()));
    }
    // Same projected usage the deployment/sub-account checks above already computed — reused rather
    // than recomputed, so the SPAN client is called once per fill instead of twice.
    String warning = accountService.buyingPowerWarning(book, projectedCost);
    return openLotFor(book, exchange, tradingsymbol, side, positionStrategyId)
        .map(row -> toPositionDto(row).withWarning(warning))
        .orElseThrow(() -> new ApiException(500, ErrorCodes.INTERNAL_ERROR, "position not opened"));
  }

  /**
   * The open lot this fill landed on: {@code strategyId}'s own row on a strategy-scoped book, else
   * whatever is open on the key. Read back AFTER the write, so on a scoped book it must resolve the
   * SAME row {@code upsertPosition} touched — the unscoped read would return the OLDEST sibling and
   * the caller would get another strategy's position back (its qty, its brackets, its id in the F9
   * margin event and in the returned DTO).
   */
  private Optional<PositionRow> openLotFor(
      String book, String exchange, String tradingsymbol, String side, java.util.UUID strategyId) {
    return strategyId == null
        ? positions.findOpen(book, exchange, tradingsymbol, side)
        : positions.findOpenForStrategy(book, exchange, tradingsymbol, side, strategyId);
  }

  /**
   * Read-back quantity for one signal's open paper legs. Swing effect retries use this immediately
   * before claiming and after attempting an open so a fill-then-throw cannot be averaged again.
   */
  @Transactional(readOnly = true)
  public long openQuantityForSignal(long signalId) {
    return positions.openForSignal(signalId).stream().mapToLong(PositionRow::qty).sum();
  }

  /** True when no open paper leg remains linked to the signal. */
  @Transactional(readOnly = true)
  public boolean hasOpenForSignal(long signalId) {
    return !positions.openForSignal(signalId).isEmpty();
  }

  /**
   * Writes the V057 per-signal lot tag, FAIL-SOFT behind a SAVEPOINT.
   *
   * <p>⚠️ This ran inside the opening transaction with no guard in the first cut, and that was a
   * Critical (cross-vendor review 2). The reasoning was that {@code sum(lots.qty) = position.qty} is
   * what makes a decomposition trustworthy, so a lost lot must be impossible. The reasoning was
   * right about the invariant and wrong about the price: a throw here rolls back the order AND the
   * position, while {@code AutoPaperListener} has ALREADY transitioned the signal to {@code TAKEN}
   * and {@code PaperSignalListener} only LOGS the failure with no scalper retry. So a failure in a
   * purely observational table could destroy a real paper trade and strand its signal with nothing
   * to reopen it. Attribution integrity is not worth a lost position — and the failure mode is not
   * theoretical: {@code uq_paper_position_lots_order} was tripped by hand during this PR's own
   * testing.
   *
   * <p>{@code PROPAGATION_NESTED} is a real JDBC savepoint, so a failed tag rolls back ONLY the lot
   * insert and the surrounding fill commits untouched. Plain try/catch would not do: the failed
   * statement would otherwise poison the outer transaction and PostgreSQL would refuse every
   * subsequent statement in it.
   *
   * <p>Loud, never silent: {@code ay_paper_lot_tag_failures_total} increments and the failure is
   * logged at ERROR with the ids needed to reconstruct the missing row by hand. The consequence is
   * stated plainly rather than hidden — {@code sum(lots.qty) = position.qty} is an invariant THAT A
   * LOGGED FAILURE CAN VIOLATE, and the attribution read's {@code coverage} block is exactly what
   * surfaces the resulting gap.
   */
  private void tagLotFailSoft(
      long positionId,
      long orderId,
      Long signalId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal fillPrice) {
    try {
      lotTagTemplate.executeWithoutResult(
          status ->
              lots.insert(
                  positionId, orderId, signalId, book, exchange, tradingsymbol, side, qty,
                  fillPrice));
    } catch (RuntimeException e) {
      lotTagFailures.increment();
      log.error(
          "V057 lot tag FAILED (fill is committed, attribution row is missing — coverage will report"
              + " this position as partially untagged): positionId={} orderId={} signalId={} book={}"
              + " {}:{} {} qty={}",
          positionId, orderId, signalId, book, exchange, tradingsymbol, side, qty, e);
    }
  }

  private void upsertPosition(
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal fillPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      Integer subaccountIdx,
      Long advisedLots,
      Long openingSignalId,
      java.util.UUID strategyId) {
    Optional<PositionRow> existing =
        openLotFor(book, exchange, tradingsymbol, side, strategyId);
    if (existing.isPresent()) {
      // averaging onto an open position keeps its original bracket levels AND its original
      // sub-account (set at first open) — a later add never re-charges the trade to a new account.
      // It likewise KEEPS its original opening_signal_id (audit H5): a pyramid add of the same
      // strategy must not re-attribute the position.
      //
      // ⚠️ CORRECTED 2026-08-03. This comment used to end "and it can never span two strategies (the
      // per-book open-key means only one strategy holds a given key open at a time)". That is FALSE,
      // and the live rows disprove it: the second open does not TAKE the key, it averages into it,
      // so two different strategies routinely share one position. Measured on live `artha` — signals
      // 128 (scalp-golden-crossover-nifty) and 129 (scalp-connect-the-dots-nifty) fired on the same
      // 11:15 bar at the same price ~2s apart, and position 47 carries qty 130 (65 + 65) with
      // opening_signal_id 128. All 10 closed scalper positions have that shape.
      //
      // The averaging itself is correct and unchanged. What the false premise cost was ATTRIBUTION:
      // opening_signal_id credits one strategy for a trade two of them built, so a GROUP BY slug
      // reports the other at n=0 and no amount of further accrual separates them. V057's
      // paper_position_lots records the (signal, qty, price) of each contributing fill instead —
      // see the lot write in openOrder.
      //
      // V058 / option D goes one step further and makes the original claim TRUE — but only for a
      // book listed in artha.paper.strategy-scoped-books, where findOpenForStrategy refuses to see a
      // sibling strategy's lot so a twin INSERTs its own row instead of averaging. Everywhere else
      // (the DEFAULT, since that property is empty) the claim stays FALSE and V057's lots remain the
      // only way to decompose the merge. The two are complementary, not alternatives: lots recover
      // attribution from a merge that already happened; the scoped key prevents the merge, which is
      // the only thing that also separates the two strategies' EXITS.
      //
      // A genuine SAME-strategy pyramid add still averages, on scoped and unscoped books alike:
      // strategyId is the stable strategies.id (never the version), so it survives a republish.
      PositionRow row = existing.get();
      long newQty = row.qty() + qty;
      BigDecimal newAvg =
          row.avgEntryPrice()
              .multiply(BigDecimal.valueOf(row.qty()))
              .add(fillPrice.multiply(BigDecimal.valueOf(qty)))
              .divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP);
      positions.updateOpen(row.id(), newQty, newAvg);
      // Round 4 fix (cross-vendor review Critical 1, 2026-08-02): an averaging add changes this
      // row's qty/avg in place (same id) — any cached governing stop was computed by the daily exit
      // pass against the PRE-average avg/qty and must not silently keep governing the newly-blended
      // position. Evict rather than let it linger: the risk calc falls back to the persisted (wider,
      // conservative) stopLoss until the next exit-pass run recomputes a fresh trail for the new
      // shape — the safe direction for a stale-vs-missing cache entry either way.
      governingStopCache.evict(row.id());
    } else {
      // `subaccountIdx` is openOrder's EFFECTIVE account — the one an already-open lot on this key
      // established, else the request's own. Byte-identical to passing the request's idx before
      // V058 (an INSERT then implied nothing was open on the key, so the two were always equal);
      // on a scoped book it is the sibling-inheritance rule that keeps a co-firing pair charged to
      // ONE sub-account, exactly as the merged row was.
      positions.insertOpen(
          book, exchange, tradingsymbol, side, qty, fillPrice, stopLoss, takeProfit, subaccountIdx,
          advisedLots, openingSignalId, strategyId);
    }
  }

  /** F9 advisory sizing for a book's open (short-circuits the equity read when there is no stop). */
  private Long advisedLots(String book, BigDecimal fillPrice, BigDecimal stopLoss) {
    if (stopLoss == null || fillPrice == null) {
      return null;
    }
    return advisedLots(accountService.equity(book), perTradeRiskPct, fillPrice, stopLoss);
  }

  /**
   * F9 advisory: the risk-based quantity a {@code riskPct}-of-equity / stop-distance sizing would
   * suggest — {@code floor(riskPct% × equity ÷ |fill − stop|)}. Advisory only (never overrides the
   * actual qty). {@code null} when a required input is missing or the stop distance is non-positive
   * (nothing to risk-size against). Pure + package-visible for unit coverage.
   */
  static Long advisedLots(
      BigDecimal equity, BigDecimal riskPct, BigDecimal fillPrice, BigDecimal stopLoss) {
    if (equity == null || riskPct == null || fillPrice == null || stopLoss == null) {
      return null;
    }
    BigDecimal stopDistance = fillPrice.subtract(stopLoss).abs();
    if (stopDistance.signum() <= 0) {
      return null;
    }
    BigDecimal riskBudget = equity.multiply(riskPct).divide(BigDecimal.valueOf(100));
    // longValue (not longValueExact): advisory only — it must never throw and roll back a real fill.
    return riskBudget.divide(stopDistance, 0, RoundingMode.DOWN).longValue();
  }

  /**
   * Round 7 (owner-approved, 2026-08-02): builds the refusal for a Manas
   * {@link RiskService.ManasRiskOutcome} value that means "could not be safely calculated" —
   * {@code UNSUPPORTED_SIDE} / {@code UNDEFINED_GOVERNING_STOP} / {@code NON_POSITIVE_EQUITY}.
   * Deliberately NEVER calls {@link RiskService#recordPyramidRiskCapBreach} — that rail, and its
   * per-day dedup key, are reserved for a genuine {@code CALCULATED_BREACH} (see that method's
   * javadoc for why conflating the two silently suppressed a later, real breach the same day).
   */
  private ApiException uncomputableRiskRefusal(String book, String tradingsymbol, String reason) {
    String detail =
        "fill-time aggregate risk for " + tradingsymbol + " could not be safely calculated: "
            + reason + " — refusing rather than admitting an unverifiable fill";
    return new ApiException(
        422,
        ErrorCodes.RISK_ENTRY_BLOCKED,
        "entry blocked by risk governor (" + RiskService.MANAS_RISK_UNCOMPUTABLE + ") on book "
            + book + " — " + detail,
        Map.of("book", book, "rail", RiskService.MANAS_RISK_UNCOMPUTABLE));
  }

  /**
   * P1-4 reject capture (fail-soft): a stale-tick refusal on the entry fill. Wraps the REQUIRES_NEW
   * recorder so a ledger hiccup never masks the {@code DATA_STALE} the order path is about to throw.
   */
  private void recordStaleRejectQuietly(
      OrderRequest request, String exchange, String tradingsymbol, String side, long tickAgeMs) {
    try {
      rejections.recordStaleTick(
          request.signalId(), bookFor(request), exchange, tradingsymbol, side, request.qty(),
          tickAgeMs, tickMaxAge.toMillis());
    } catch (RuntimeException e) {
      log.warn("paper_order_rejections (stale) not written for {}:{}: {}", exchange, tradingsymbol, e.getMessage());
    }
  }

  /** P1-4 reject capture (fail-soft): no reference price was available to strike the entry fill. */
  private void recordDeploymentBlockedQuietly(
      OrderRequest request, String exchange, String tradingsymbol, String side, String detail) {
    try {
      rejections.recordDeploymentBlocked(
          request.signalId(), bookFor(request), exchange, tradingsymbol, side, request.qty(), detail);
    } catch (RuntimeException e) {
      log.warn(
          "paper_order_rejections (deployment-blocked) not written for {}:{}: {}",
          exchange, tradingsymbol, e.getMessage());
    }
  }

  private void recordNoPriceRejectQuietly(
      OrderRequest request, String exchange, String tradingsymbol, String side) {
    try {
      rejections.recordNoPrice(
          request.signalId(), bookFor(request), exchange, tradingsymbol, side, request.qty());
    } catch (RuntimeException e) {
      log.warn("paper_order_rejections (no-price) not written for {}:{}: {}", exchange, tradingsymbol, e.getMessage());
    }
  }

  /** Closes a position at the stated price (or the last tick); realized = exit + entry-basis cash. */
  @Transactional
  public TradeDto closePosition(long id, BigDecimal price) {
    PositionRow pos =
        positions.find(id).orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no such position"));
    if (!"OPEN".equals(pos.status())) {
      throw new ApiException(409, ErrorCodes.CONFLICT_POSITION_CLOSED, "position already closed");
    }
    if (settle(pos, price, "MANUAL").isEmpty()) {
      // Lost the CAS between the status read above and the close. Returning the winner's trade here
      // made PaperController write a MANUAL_CLOSE audit row for a close this request never performed
      // — the same 409 the pre-read would have raised a moment earlier, just later (§9-05 review).
      throw new ApiException(409, ErrorCodes.CONFLICT_POSITION_CLOSED, "position already closed");
    }
    return positions
        .find(id)
        .map(this::toTradeDto)
        .orElseThrow(() -> new ApiException(500, ErrorCodes.INTERNAL_ERROR, "close failed"));
  }

  /** Reads only the local book key needed by the manual-close audit, without detail enrichment or HTTP. */
  public String positionBook(long id) {
    return positions
        .findBook(id)
        .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no such position"));
  }

  /**
   * The shared close path (manual close, the 15:45 sweep, bracket SL/TP, engine exit, expiry).
   *
   * <p><b>THE INVARIANT: every close must run inside a transaction.</b> Without one the {@code
   * PaperPositionClosed} event publishes outside any tx and its AFTER_COMMIT listeners — the
   * TAKEN-anchor resolver and auto-journal — <b>silently never fire</b>. Nothing throws; the close
   * simply loses its side effects, which is why this is spelled out rather than left to the
   * annotation.
   *
   * <p>⚠️ {@code @Transactional} here covers EXTERNAL callers only, because Spring's proxy is
   * bypassed on self-invocation (the same trap that silently dropped a tx in {@code RegistryService},
   * PF-01 round-6 #2). So the rule for adding a caller is:
   * <ul>
   *   <li><b>External</b> (another bean, e.g. {@link PaperBracketEvaluator} on the @Scheduled thread,
   *       or {@code PaperExpiryService}) — nothing to do, the proxy applies. An expiry batch
   *       deliberately gets one tx PER position so a single refusal cannot roll back the rest.</li>
   *   <li><b>Self-invoking</b> (a sibling method on this class) — <b>that method MUST carry its own
   *       {@code @Transactional}</b>, because this one's is bypassed entirely.</li>
   * </ul>
   *
   * <p>Verified 2026-07-30 across all 10 call sites (§9b close-out): the four self-invoking callers
   * — {@code closePosition}, {@code closeForSignal}, {@code closeForPosition}, {@code
   * markToCloseIntraday} — are each {@code @Transactional}, and both external callers go through the
   * proxy. Stated as a RULE rather than a caller list because the previous list had already drifted:
   * it named three self-invoking callers when there were four, and called
   * {@code PaperBracketEvaluator} "the ONE external caller" after {@code PaperExpiryService} became
   * a second.
   *
   * <p><b>Returns empty when THIS call did not perform the close</b> — a concurrent closer won the
   * CAS below. That distinction used to be invisible: the realized amount came back either way, so a
   * caller could not tell "I closed it" from "someone else already had". {@link
   * PaperBracketEvaluator} consequently counted and logged closes it never performed (architecture
   * candidate §9-05, the CAS-leaks-onto-callers interface leak).
   */
  @Transactional
  public Optional<BigDecimal> settle(PositionRow pos, BigDecimal price, String closeReason) {
    return doSettle(pos, price, closeReason, false);
  }

  /** Expiry settlement at intrinsic/spot — exercise STT leg, no slippage (Phase 43B). Tx: see {@link #settle}. */
  @Transactional
  public Optional<BigDecimal> settleExpiry(PositionRow pos, BigDecimal reference, boolean exercise) {
    return doSettle(pos, reference, "EXPIRY_SETTLEMENT", exercise);
  }

  /**
   * Audit V3 / research-fidelity P1-6: NO breakeven fabrication. The exit reference is an explicit
   * price (manual close, swing daily-close, expiry intrinsic/spot) or the last known REAL tick at
   * ANY age — NEVER {@code avgEntryPrice}, which booked a fictional 0-P&amp;L exit for a leg that
   * never ticked. The freshness asymmetry with {@code openOrder} is deliberate: <b>entries need
   * fresh truth (you can always NOT enter), exits need the best available truth (you cannot refuse
   * to leave forever)</b> — so an engine exit, the 15:45 sweep and a weekend manual close all
   * flatten off the last real price even when it is stale (counted via
   * {@code ay_paper_stale_settle_total} + a once-per-(position, IST day) alert — visible, never
   * silent, never refused). Only when NO tick has EVER been seen for the symbol does the settle
   * refuse: counter + ntfy + 422 DATA_STALE, leaving the position OPEN for the next pass (the
   * automated callers catch + log; the manual close surfaces the 422).
   */
  private Optional<BigDecimal> doSettle(
      PositionRow pos, BigDecimal price, String closeReason, boolean exercise) {
    InstrumentMeta meta = instruments.meta(pos.exchange(), pos.tradingsymbol());
    Side exitSide = "BUY".equals(pos.side()) ? Side.SELL : Side.BUY;
    // P1-5 fill-reference provenance on the EXIT leg too: CALLER = an explicit settle price (manual
    // close / swing daily close / expiry intrinsic); LIVE_TICK = the last real tick fallback (used at
    // ANY age on a close — #694), with its wall-clock age recorded.
    BigDecimal reference = price;
    String refSource = reference != null ? "CALLER" : null;
    Long refTickAgeMs = null;
    Duration staleAge = null;
    if (reference == null) {
      Optional<LastTickReader.TickView> tick = lastTick.lastTick(pos.exchange(), pos.tradingsymbol());
      if (tick.isEmpty()) {
        staleTicks.settleRefused(pos, closeReason);
        throw new ApiException(
            422,
            ErrorCodes.DATA_STALE,
            "no tick has ever been seen for " + pos.exchange() + ":" + pos.tradingsymbol()
                + " — left OPEN, not settled at breakeven",
            Map.of("positionId", pos.id(), "closeReason", closeReason));
      }
      reference = tick.get().price();
      refSource = "LIVE_TICK";
      Duration age = tick.get().age();
      refTickAgeMs = age == null ? null : age.toMillis();
      // NOT alerted here: the "settled off a stale tick" record is only true if this call goes on to
      // WIN the CAS below. Emitting it first made a race loser report a stale settlement it never
      // performed (§9-05 review) — the same class of false record this change exists to remove.
      staleAge = age != null && age.compareTo(tickMaxAge) > 0 ? age : null;
    }
    Fill exit =
        exercise
            ? fills.settlementFill(exitSide, pos.qty(), reference, meta)
            : fills.fill(exitSide, pos.qty(), reference, meta);
    Fill entryBasis = fills.costBasis(Side.valueOf(pos.side()), pos.qty(), pos.avgEntryPrice(), meta);
    BigDecimal realized = exit.netValue().add(entryBasis.netValue()).setScale(4, RoundingMode.HALF_UP);
    // CAS close FIRST: only the thread that actually flips OPEN→CLOSED (rowcount==1) books the exit
    // fill + fires the closed event. A concurrent closer (e.g. the 15s bracket poll racing an engine
    // exit) that lost the CAS returns without inserting a duplicate exit order or double-publishing.
    if (positions.close(pos.id(), realized, closeReason) == 0) {
      // Lost the CAS — EMPTY, not the realized amount. The caller must be able to tell that it did
      // not close this position, or it reports someone else's exit as its own (§9-05).
      return Optional.empty();
    }
    // M40 Critical 3 fix, round 3 (2026-08-02), keyed by id round 4 (cross-vendor review Critical 1):
    // drop this position's in-memory governing-stop cache entry on a REAL close (past the CAS, so a
    // race loser never evicts on someone else's close). A re-open of the same
    // (book,exchange,tradingsymbol,side) key mints a NEW row id, so it could never read this entry
    // anyway once the cache is keyed by id — this eviction is memory hygiene, not a correctness
    // requirement, unlike round 3's tuple-keyed version where it was load-bearing. A no-op for any
    // id never cached (every non-Manas book).
    governingStopCache.evict(pos.id());
    if (staleAge != null) {
      staleTicks.staleSettleUsed(pos, closeReason, staleAge);
    }
    orders.insertFilled(
        pos.book(), null, pos.exchange(), pos.tradingsymbol(), exitSide.name(), pos.qty(), exit.fillPrice(),
        fills.simulatorId(), exit.slippageApplied(), null, null, null, refSource, refTickAgeMs, null);
    // Auto-journal hook: the journal module listens AFTER_COMMIT (so a journal failure can never
    // roll back the close). Publishing inside the close tx is fine — delivery is deferred to commit.
    events.publishEvent(new PaperPositionClosed(pos.id(), realized, closeReason));
    return Optional.of(realized);
  }

  /** Open positions with mark-to-market P&amp;L ({@code book} null → all books; unrealized never stored). */
  public List<PositionDto> openPositions(String book) {
    return positions.listOpen(book).stream().map(this::toPositionDto).toList();
  }

  /**
   * The full detail + provenance of one position (Phase-2 §6.4/§6.5): live MTM (OPEN only), the F9
   * advisory + E10 sub-account, the opening signal with its family side-channel, and the ordered
   * entry/exit legs each carrying a recomputed statutory fee breakdown (the trade-chain timeline).
   */
  public PositionDetail positionDetail(long id) {
    PaperPositionRepository.DetailRow row =
        positions
            .findDetail(id)
            .orElseThrow(
                () -> new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no such position"));
    InstrumentMeta meta = instruments.meta(row.exchange(), row.tradingsymbol());
    // Live MTM only while OPEN — a closed position's realized P&L is final + stored on the row.
    BigDecimal mark = null;
    BigDecimal unrealized = null;
    if ("OPEN".equals(row.status())) {
      mark = lastTick.lastPrice(row.exchange(), row.tradingsymbol()).orElse(null);
      if (mark != null) {
        BigDecimal move =
            "BUY".equals(row.side())
                ? mark.subtract(row.avgEntryPrice())
                : row.avgEntryPrice().subtract(mark);
        unrealized = move.multiply(BigDecimal.valueOf(row.qty())).setScale(2, RoundingMode.HALF_UP);
      }
    }
    // V058 (round-2 review Major 2): attribute the trade chain to THIS position's strategy, so a
    // co-fired sibling's entry fills stop appearing in this position's detail pane. Settle legs
    // carry no signal and remain shared until #1259's V057 exact linkage — see legsForPosition.
    List<OrderLeg> legs =
        orders
            .legsForPosition(
                row.book(), row.exchange(), row.tradingsymbol(), row.openedAt(), row.closedAt(),
                positions.strategyIdOf(row.id()).orElse(null))
            .stream()
            .map(o -> toOrderLeg(o, meta))
            .toList();
    return new PositionDetail(
        row.id(), row.book(), row.exchange(), row.tradingsymbol(), row.side(), row.qty(),
        row.avgEntryPrice(), mark, unrealized, row.realizedPnl(), row.status(), row.openedAt(),
        row.closedAt(), row.closeReason(), row.stopLoss(), row.takeProfit(), row.advisedLots(),
        row.marginSnapshot(), row.marginPct(), row.subaccountIdx(), row.openingSignalId(),
        openingSignal(row.openingSignalId()), legs);
  }

  private OpeningSignal openingSignal(Long signalId) {
    if (signalId == null) {
      return null;
    }
    return signals
        .find(signalId)
        .map(
            s ->
                new OpeningSignal(
                    s.id(), s.status(), s.side(), s.entryPrice(), s.stopLoss(), s.target(),
                    s.compositeScore(), s.generatedAt(), s.scalperDetail(), s.minerviniDetail(),
                    s.manasAroraDetail()))
        .orElse(null);
  }

  private OrderLeg toOrderLeg(PaperOrderRepository.OrderRow o, InstrumentMeta meta) {
    FeeBreakdown fees = null;
    if (o.fillPrice() != null) {
      var c = fills.costs(Side.valueOf(o.side()), o.qty(), o.fillPrice(), meta);
      fees =
          new FeeBreakdown(
              c.brokerage(), c.stt(), c.exchangeTxn(), c.gst(), c.stamp(), c.sebi(), c.total());
    }
    return new OrderLeg(
        o.id(), o.signalId(), o.side(), o.qty(), o.status(), o.placedAt(), o.filledAt(),
        o.fillPrice(), o.fillSimulator(), o.slippageApplied(), fees);
  }

  /**
   * Manually edits an OPEN position's bracket levels (Phase-2, HOLD-tier — it changes what the live
   * 15s evaluator will act on). At least one of {@code stopLoss}/{@code takeProfit} is set; a {@code
   * null} field leaves that level unchanged (partial edit). Sanity is validated against the last REAL
   * tick at ANY age (audit #694: a LEVEL EDIT is not a fill — it never settles, so tick staleness must
   * NOT refuse it; only NO tick at all skips the check): a level that would immediately fire at the
   * current LTP is rejected 422, reusing the evaluator's own {@link PaperBracketEvaluator#breach}
   * inequalities so acceptance exactly matches live behaviour. Persisted via a CAS on OPEN; the
   * previous levels are returned for the {@code paper_admin_audit} trail. NEVER touches settle/fill
   * freshness semantics.
   */
  @Transactional
  public BracketEdit editBrackets(long id, BigDecimal stopLoss, BigDecimal takeProfit) {
    PaperPositionRepository.DetailRow row =
        positions
            .findDetail(id)
            .orElseThrow(
                () -> new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no such position"));
    if (!"OPEN".equals(row.status())) {
      throw new ApiException(409, ErrorCodes.CONFLICT_POSITION_CLOSED, "position already closed");
    }
    BigDecimal ltp = lastTick.lastPrice(row.exchange(), row.tradingsymbol()).orElse(null);
    if (ltp != null) {
      // Validate ONLY the field(s) being set, each against the current LTP — checking a merged pair
      // could false-reject an edit to one level because the OTHER (unchanged) level sits near the LTP.
      if (stopLoss != null) {
        rejectIfAlreadyHit(row.side(), stopLoss, null, ltp, "stopLoss", stopLoss);
      }
      if (takeProfit != null) {
        rejectIfAlreadyHit(row.side(), null, takeProfit, ltp, "takeProfit", takeProfit);
      }
    }
    if (positions.updateBrackets(id, stopLoss, takeProfit) == 0) {
      // Lost a CAS race to a concurrent close between the OPEN check and the UPDATE.
      throw new ApiException(409, ErrorCodes.CONFLICT_POSITION_CLOSED, "position already closed");
    }
    return new BracketEdit(row.stopLoss(), row.takeProfit(), positionDetail(id));
  }

  private void rejectIfAlreadyHit(
      String side, BigDecimal stopLoss, BigDecimal takeProfit, BigDecimal ltp, String field,
      BigDecimal level) {
    if (PaperBracketEvaluator.breach(side, stopLoss, takeProfit, ltp) != null) {
      throw new ApiException(
          422,
          ErrorCodes.VALIDATION_FAILED,
          field + " " + level.toPlainString() + " would trigger immediately against the current LTP "
              + ltp.toPlainString() + " for a " + side + " position",
          Map.of(
              "field", field, "level", level.toPlainString(), "ltp", ltp.toPlainString(), "side",
              side));
    }
  }

  /** The closed-trade ledger for a book ({@code book} null → all; optionally one tradingsymbol). */
  public List<TradeDto> trades(
      String book, OffsetDateTime from, OffsetDateTime to, String tradingsymbol, int limit, int offset) {
    return positions.listClosed(book, from, to, tradingsymbol, limit, offset).stream()
        .map(this::toTradeDto)
        .toList();
  }

  /**
   * The per-strategy decomposition of a book's paper trading ({@code book} null → all books) — V057.
   *
   * <p>Answers the question {@code GROUP BY opening_signal_id} cannot: when two strategies fire on
   * the same bar and the second {@code openPosition} AVERAGES into the first's position, the row
   * credits only the opener. This walks {@code paper_position_lots} instead, so each contributing
   * fill is attributed to the signal that actually caused it, on a FILL BASIS — each lot's pro-rata
   * share of the pooled result plus its own entry edge against the blended basis, so a strategy that
   * entered better reads better.
   *
   * <p><b>Group totals reconstruct the book's realized P&amp;L only over FULLY TAGGED positions</b>,
   * and then not bit-for-bit: the stored {@code avg_entry_price} is rounded to 4dp, leaving a
   * residual that {@link PaperPositionLotRepository#attribution} allocates deterministically. A
   * PARTIALLY tagged position — every position that predates V057, i.e. all of them on launch day —
   * contributes only its tagged share by design, with the remainder visible in {@code coverage}.
   *
   * <p>Always returns {@link PaperViews.Attribution#coverage()} beside the rows, and the coverage is
   * not decoration — no position opened before V057 carries lots, so the day this ships the rows are
   * EMPTY while the book holds real trades. Reading the rows without the coverage would turn "not
   * yet instrumented" into "never traded", which is the exact class of false negative this change
   * exists to remove.
   */
  public PaperViews.Attribution attribution(String book) {
    // ONE snapshot for both reads (cross-vendor review 3). They are two statements, and under the
    // default READ_COMMITTED each takes its own snapshot — so an open or close landing between them
    // yields a response whose rows and coverage describe DIFFERENT database states, e.g. coverage
    // counting a position whose lots the rows do not include. Since coverage exists precisely to be
    // read against the rows, an inconsistency between them is worse than either being slightly
    // stale. REPEATABLE_READ + readOnly makes both statements see one snapshot; the reads are two
    // small aggregates over a few dozen rows, so the cost is negligible.
    return attributionTemplate.execute(status -> readAttribution(book));
  }

  /** The two reads, executed together inside {@link #attributionTemplate}'s single snapshot. */
  private PaperViews.Attribution readAttribution(String book) {
    List<PaperViews.AttributionRow> rows =
        lots.attribution(book).stream()
            .map(
                r ->
                    new PaperViews.AttributionRow(
                        r.slug(),
                        r.book(),
                        r.closedPositions(),
                        r.closedQty(),
                        r.openQty(),
                        r.attributedRealizedPnl()))
            .toList();
    PaperPositionLotRepository.Coverage c = lots.coverage(book);
    return new PaperViews.Attribution(
        rows,
        new PaperViews.AttributionCoverage(
            c.closedPositions(),
            c.closedPositionsTagged(),
            c.closedQty(),
            c.closedQtyTagged(),
            c.openPositions(),
            c.openPositionsTagged(),
            c.openQty(),
            c.openQtyTagged()));
  }

  /** Daily realized-equity curve + win rate / expectancy over a book's closed trades (null → all). */
  public PaperViews.Pnl pnl(String book) {
    List<PositionRow> closed = positions.listClosed(book, null, null, null, 500, 0);
    // listClosed is newest-first; walk oldest-first for the cumulative curve
    List<PositionRow> chrono = new ArrayList<>(closed);
    java.util.Collections.reverse(chrono);
    Map<LocalDate, BigDecimal> byDay = new java.util.TreeMap<>();
    BigDecimal realizedTotal = BigDecimal.ZERO;
    int wins = 0;
    for (PositionRow row : chrono) {
      realizedTotal = realizedTotal.add(row.realizedPnl());
      if (row.realizedPnl().signum() > 0) {
        wins++;
      }
      LocalDate day = row.closedAt() == null ? LocalDate.now(IST) : row.closedAt().atZoneSameInstant(IST).toLocalDate();
      byDay.merge(day, row.realizedPnl(), BigDecimal::add);
    }
    List<PaperViews.EquityPoint> points = new ArrayList<>();
    BigDecimal cumulative = BigDecimal.ZERO;
    for (Map.Entry<LocalDate, BigDecimal> entry : byDay.entrySet()) {
      cumulative = cumulative.add(entry.getValue());
      points.add(new PaperViews.EquityPoint(entry.getKey().toString(), cumulative));
    }
    int total = chrono.size();
    PaperViews.PnlSummary summary =
        new PaperViews.PnlSummary(
            realizedTotal.setScale(2, RoundingMode.HALF_UP),
            total,
            total == 0
                ? null
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP),
            total == 0
                ? null
                : realizedTotal.divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP));
    return new PaperViews.Pnl(points, summary);
  }

  /** How many positions + orders a {@link #reset} wiped — carried to the paper-admin audit row (V14). */
  public record ResetResult(int positionsDeleted, int ordersDeleted) {}

  /**
   * Wipes a book's paper ledger ({@code book} null → all books; confirm-guarded). Returns the deleted
   * position/order counts so the caller can audit the destructive action (audit §7.1/§7.2.5).
   */
  @Transactional
  public ResetResult reset(String book, boolean confirm) {
    if (!confirm) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "reset requires confirm=true");
    }
    int positionsDeleted = positions.deleteAll(book);
    int ordersDeleted = orders.deleteAll(book);
    return new ResetResult(positionsDeleted, ordersDeleted);
  }

  /**
   * Closes every OPEN position linked to a signal — the engine-EXIT hook (a TAKEN entry's exit must
   * resolve its paper position). Settles at the instrument's live LTP (the null-price fallback in
   * {@code doSettle}), with the engine's exit cause as {@code close_reason}. No-op when nothing is
   * open for the signal.
   */
  @Transactional
  public int closeForSignal(long signalId, String closeReason) {
    return closeForSignal(signalId, closeReason, null);
  }

  /**
   * Closes every OPEN position linked to a signal at an EXPLICIT settlement price ({@code null} → the
   * live-LTP fallback in {@code doSettle}). The Phase-9 Minervini swing batch passes the fresh
   * daily-bar close: its equities do not tick, so the LTP fallback would otherwise settle every swing
   * close at the entry price (breakeven) and lose the real daily-close exit.
   */
  @Transactional
  public int closeForSignal(long signalId, String closeReason, BigDecimal price) {
    int closed = 0;
    for (PositionRow pos : positions.openForSignal(signalId)) {
      try {
        // Count only what THIS pass closed — a lost CAS is someone else's exit (§9-05 review).
        if (settle(pos, price, closeReason).isPresent()) {
          closed++;
        }
      } catch (Exception e) {
        log.warn("signal-exit close failed for position {}: {}", pos.id(), e.getMessage());
      }
    }
    return closed;
  }

  /**
   * Closes one exact paper-position id for a durable swing effect. Unlike {@link #closeForSignal}, this
   * path never resolves the target through the reusable book/symbol/side key, so a later re-entry cannot
   * be settled by an old effect. Returns one when the target is already closed or this call settles it.
   */
  @Transactional
  public int closeForPosition(long positionId, String closeReason, BigDecimal price) {
    PositionRow pos = positions.find(positionId).orElse(null);
    if (pos == null) {
      return 0;
    }
    if (!"OPEN".equals(pos.status())) {
      return 1;
    }
    try {
      settle(pos, price, closeReason);
      return 1;
    } catch (Exception e) {
      log.warn("exact paper-position close failed for {}: {}", positionId, e.getMessage());
      return 0;
    }
  }

  /** 15:45 IST mark-to-close: settle every OPEN intraday position so it does not carry overnight. */
  @Transactional
  public int markToCloseIntraday() {
    int closed = 0;
    for (PositionRow pos : positions.intradayOpen()) {
      try {
        // Count only what THIS pass closed — a lost CAS is someone else's exit (§9-05 review).
        if (settle(pos, null, "INTRADAY_MTM").isPresent()) {
          closed++;
        }
      } catch (Exception e) {
        log.warn("mark-to-close failed for position {}: {}", pos.id(), e.getMessage());
      }
    }
    return closed;
  }

  private PositionDto toPositionDto(PositionRow row) {
    BigDecimal mark = lastTick.lastPrice(row.exchange(), row.tradingsymbol()).orElse(null);
    BigDecimal unrealized = null;
    if (mark != null) {
      BigDecimal move =
          "BUY".equals(row.side())
              ? mark.subtract(row.avgEntryPrice())
              : row.avgEntryPrice().subtract(mark);
      unrealized = move.multiply(BigDecimal.valueOf(row.qty())).setScale(2, RoundingMode.HALF_UP);
    }
    return new PositionDto(
        row.id(), row.exchange(), row.tradingsymbol(), row.side(), row.qty(), row.avgEntryPrice(),
        mark, unrealized, row.realizedPnl(), row.status(), row.openedAt(),
        row.stopLoss(), row.takeProfit(), null);
  }

  private TradeDto toTradeDto(PositionRow row) {
    return new TradeDto(
        row.id(), row.exchange(), row.tradingsymbol(), row.side(), row.qty(), row.avgEntryPrice(),
        row.realizedPnl(), row.openedAt(), row.closedAt());
  }

  /**
   * M4 (#128) visibility gauge value: a fresh count of OPEN positions with no live tick, queried
   * DIRECTLY from the authoritative sources (the position table + the Redis last-tick map) on
   * every metric read — never cached, never tracked. Called by Micrometer whenever the gauge is
   * scraped/read, decoupled from how often the UI polls {@link #positionDetail}/{@link
   * #openPositions} (which stay display-only and never touch this method at all).
   */
  private double countMtmBlindPositions() {
    return positions.listOpen().stream()
        .filter(p -> lastTick.lastPrice(p.exchange(), p.tradingsymbol()).isEmpty())
        .count();
  }
}
