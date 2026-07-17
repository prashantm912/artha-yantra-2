package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.signals.SignalRepository;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
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
      BigDecimal avgEntryPrice,
      BigDecimal markPrice,
      BigDecimal unrealizedPnl,
      BigDecimal realizedPnl,
      String status,
      OffsetDateTime openedAt,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      String buyingPowerWarning) {

    /** A copy carrying the non-blocking buying-power warning (A12). */
    PositionDto withWarning(String warning) {
      return new PositionDto(
          id, exchange, tradingsymbol, side, qty, avgEntryPrice, markPrice, unrealizedPnl,
          realizedPnl, status, openedAt, stopLoss, takeProfit, warning);
    }
  }

  /** A closed trade. */
  public record TradeDto(
      long id,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal avgEntryPrice,
      BigDecimal realizedPnl,
      OffsetDateTime openedAt,
      OffsetDateTime closedAt) {}

  /** The itemized statutory cost legs of one fill (recomputed for display; {@link PaperFillService#costs}). */
  public record FeeBreakdown(
      BigDecimal brokerage,
      BigDecimal stt,
      BigDecimal exchangeTxn,
      BigDecimal gst,
      BigDecimal stamp,
      BigDecimal sebi,
      BigDecimal total) {}

  /** One order leg of a position (entry / averaged add / exit) with its fill-audit + recomputed fees. */
  public record OrderLeg(
      long orderId,
      Long signalId,
      String side,
      long qty,
      String status,
      OffsetDateTime placedAt,
      OffsetDateTime filledAt,
      BigDecimal fillPrice,
      String fillSimulator,
      BigDecimal slippageApplied,
      FeeBreakdown fees) {}

  /** The signal that opened a position (audit H5), with its family enrichment side-channels. */
  public record OpeningSignal(
      long signalId,
      String status,
      String side,
      BigDecimal entryPrice,
      BigDecimal stopLoss,
      BigDecimal target,
      BigDecimal compositeScore,
      OffsetDateTime generatedAt,
      JsonNode scalperDetail,
      JsonNode minerviniDetail,
      JsonNode manasAroraDetail) {}

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
      BigDecimal avgEntryPrice,
      BigDecimal markPrice,
      BigDecimal unrealizedPnl,
      BigDecimal realizedPnl,
      String status,
      OffsetDateTime openedAt,
      OffsetDateTime closedAt,
      String closeReason,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      Long advisedLots,
      BigDecimal marginSnapshot,
      BigDecimal marginPct,
      Integer subaccountIdx,
      Long openingSignalId,
      OpeningSignal openingSignal,
      List<OrderLeg> orders) {}

  /** The result of a bracket edit: the previous levels (for the audit trail) + the refreshed detail. */
  public record BracketEdit(
      BigDecimal previousStopLoss, BigDecimal previousTakeProfit, PositionDetail detail) {}

  private final PaperOrderRepository orders;
  private final PaperPositionRepository positions;
  private final PaperFillService fills;
  private final LastTickReader lastTick;
  private final InstrumentMetaClient instruments;
  private final SignalRepository signals;
  private final PaperAccountService accountService;
  private final BookResolver books;
  private final RiskService risk;
  private final ApplicationEventPublisher events;
  private final PaperStaleTickAlerter staleTicks;
  private final PaperOrderRejectionRecorder rejections;
  private final BigDecimal perTradeRiskPct;
  /** Audit V3: a fill priced off a last tick older than this is fiction — rejected DATA_STALE. */
  private final Duration tickMaxAge;
  /**
   * HOLD (task_94f40cf6): a manual signal-take on a NON-swing book whose signal is older than this
   * is rejected SIGNAL_STALE — the setup's thesis is gone even though the fill would price fresh.
   * Zero/negative disables the gate; swing books are always exempt. Separate from {@link #tickMaxAge}.
   */
  private final Duration signalTakeMaxAge;
  private final TransactionTemplate txTemplate;

  /** Wires the ledger collaborators. */
  public PaperService(
      PaperOrderRepository orders,
      PaperPositionRepository positions,
      PaperFillService fills,
      LastTickReader lastTick,
      InstrumentMetaClient instruments,
      SignalRepository signals,
      PaperAccountService accountService,
      BookResolver books,
      RiskService risk,
      ApplicationEventPublisher events,
      PaperStaleTickAlerter staleTicks,
      PaperOrderRejectionRecorder rejections,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Value("${artha.paper.risk.per-trade-risk-pct:1.0}")
          BigDecimal perTradeRiskPct,
      @org.springframework.beans.factory.annotation.Value("${artha.paper.tick-max-age-seconds:15}")
          long tickMaxAgeSeconds,
      @org.springframework.beans.factory.annotation.Value(
              "${artha.paper.signal-take-max-age-minutes:60}")
          long signalTakeMaxAgeMinutes) {
    this.orders = orders;
    this.positions = positions;
    this.fills = fills;
    this.lastTick = lastTick;
    this.instruments = instruments;
    this.signals = signals;
    this.accountService = accountService;
    this.books = books;
    this.risk = risk;
    this.events = events;
    this.staleTicks = staleTicks;
    this.rejections = rejections;
    this.perTradeRiskPct = perTradeRiskPct;
    this.tickMaxAge = Duration.ofSeconds(tickMaxAgeSeconds);
    this.signalTakeMaxAge = Duration.ofMinutes(signalTakeMaxAgeMinutes);
    this.txTemplate = new TransactionTemplate(transactionManager);
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
   * that re-throws the gate's own 422 from INSIDE the fill transaction, so the fill rolls back and the
   * refusal still leaves zero trace. Without this the narrow TOCTOU window would leak exactly the orphan
   * the gate exists to block.
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
    return orders
        .keyForClientOrderId(book, clientOrderId)
        .flatMap(k -> positions.findLatestForKey(book, k.exchange(), k.tradingsymbol(), k.side()))
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
    return BookResolver.MINERVINI.equals(book) || BookResolver.MANAS_ARORA.equals(book);
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
      SignalRepository.SignalRow signal =
          signals
              .find(request.signalId())
              .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_SIGNAL, "no such signal"));
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
    InstrumentMeta meta = instruments.meta(exchange, tradingsymbol);
    Fill fill = fills.fill(Side.valueOf(side), request.qty(), reference, meta);
    orders.insertFilled(
        book, request.signalId(), exchange, tradingsymbol, side, request.qty(), fill.fillPrice(),
        fills.simulatorId(), fill.slippageApplied(), null, null, request.clientOrderId(), refSource,
        refTickAgeMs, signalGeneratedAt);
    Long advisedLots = advisedLots(book, fill.fillPrice(), request.stopLoss());
    upsertPosition(
        book, exchange, tradingsymbol, side, request.qty(), fill.fillPrice(),
        request.stopLoss(), request.takeProfit(), request.subaccountIdx(), advisedLots,
        request.signalId());
    // F9: after the ledger commits, price the position's SPAN margin (fail-soft, off the txn) and stamp
    // margin_snapshot/margin_pct. The event fires only if a row exists (an averaged add still re-prices).
    Optional<PositionRow> opened = positions.findOpen(book, exchange, tradingsymbol, side);
    if (opened.isPresent()) {
      PositionRow row = opened.get();
      events.publishEvent(
          new PaperPositionOpened(row.id(), book, exchange, tradingsymbol, side, row.qty()));
    }
    String warning =
        accountService.buyingPowerWarning(
            book,
            accountService.usageFor(
                meta, exchange, tradingsymbol, side, fill.fillPrice(), request.qty()));
    return positions
        .findOpen(book, exchange, tradingsymbol, side)
        .map(row -> toPositionDto(row).withWarning(warning))
        .orElseThrow(() -> new ApiException(500, ErrorCodes.INTERNAL_ERROR, "position not opened"));
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
      Long openingSignalId) {
    Optional<PositionRow> existing = positions.findOpen(book, exchange, tradingsymbol, side);
    if (existing.isPresent()) {
      // averaging onto an open position keeps its original bracket levels AND its original
      // sub-account (set at first open) — a later add never re-charges the trade to a new account.
      // It likewise KEEPS its original opening_signal_id (audit H5): a pyramid add of the same
      // strategy must not re-attribute the position, and it can never span two strategies (the
      // per-book open-key means only one strategy holds a given key open at a time).
      PositionRow row = existing.get();
      long newQty = row.qty() + qty;
      BigDecimal newAvg =
          row.avgEntryPrice()
              .multiply(BigDecimal.valueOf(row.qty()))
              .add(fillPrice.multiply(BigDecimal.valueOf(qty)))
              .divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP);
      positions.updateOpen(row.id(), newQty, newAvg);
    } else {
      positions.insertOpen(
          book, exchange, tradingsymbol, side, qty, fillPrice, stopLoss, takeProfit, subaccountIdx,
          advisedLots, openingSignalId);
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
    settle(pos, price, "MANUAL");
    return positions
        .find(id)
        .map(this::toTradeDto)
        .orElseThrow(() -> new ApiException(500, ErrorCodes.INTERNAL_ERROR, "close failed"));
  }

  /**
   * The shared close path (manual close, the 15:45 sweep, bracket SL/TP, engine exit). {@code
   * @Transactional} + public so the ONE external caller that isn't already in a transaction — {@link
   * PaperBracketEvaluator} on the @Scheduled thread — runs in a transaction; without it the
   * {@code PaperPositionClosed} event was published outside any tx and its AFTER_COMMIT listeners
   * (the TAKEN-anchor resolver + auto-journal) silently never fired. Self-invoking
   * callers (closePosition/closeForSignal/markToCloseIntraday) simply join their own tx.
   */
  @Transactional
  public BigDecimal settle(PositionRow pos, BigDecimal price, String closeReason) {
    return doSettle(pos, price, closeReason, false);
  }

  /** Expiry settlement at intrinsic/spot — exercise STT leg, no slippage (Phase 43B). Tx: see {@link #settle}. */
  @Transactional
  public BigDecimal settleExpiry(PositionRow pos, BigDecimal reference, boolean exercise) {
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
  private BigDecimal doSettle(PositionRow pos, BigDecimal price, String closeReason, boolean exercise) {
    InstrumentMeta meta = instruments.meta(pos.exchange(), pos.tradingsymbol());
    Side exitSide = "BUY".equals(pos.side()) ? Side.SELL : Side.BUY;
    // P1-5 fill-reference provenance on the EXIT leg too: CALLER = an explicit settle price (manual
    // close / swing daily close / expiry intrinsic); LIVE_TICK = the last real tick fallback (used at
    // ANY age on a close — #694), with its wall-clock age recorded.
    BigDecimal reference = price;
    String refSource = reference != null ? "CALLER" : null;
    Long refTickAgeMs = null;
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
      if (age != null && age.compareTo(tickMaxAge) > 0) {
        staleTicks.staleSettleUsed(pos, closeReason, age);
      }
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
      return realized;
    }
    orders.insertFilled(
        pos.book(), null, pos.exchange(), pos.tradingsymbol(), exitSide.name(), pos.qty(), exit.fillPrice(),
        fills.simulatorId(), exit.slippageApplied(), null, null, null, refSource, refTickAgeMs, null);
    // Auto-journal hook: the journal module listens AFTER_COMMIT (so a journal failure can never
    // roll back the close). Publishing inside the close tx is fine — delivery is deferred to commit.
    events.publishEvent(new PaperPositionClosed(pos.id(), realized, closeReason));
    return realized;
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
    List<OrderLeg> legs =
        orders
            .legsForPosition(
                row.book(), row.exchange(), row.tradingsymbol(), row.openedAt(), row.closedAt())
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

  /** Daily realized-equity curve + win rate / expectancy over a book's closed trades (null → all). */
  public Map<String, Object> pnl(String book) {
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
    List<Map<String, Object>> points = new ArrayList<>();
    BigDecimal cumulative = BigDecimal.ZERO;
    for (Map.Entry<LocalDate, BigDecimal> entry : byDay.entrySet()) {
      cumulative = cumulative.add(entry.getValue());
      points.add(Map.of("date", entry.getKey().toString(), "equity", cumulative));
    }
    int total = chrono.size();
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("realizedTotal", realizedTotal.setScale(2, RoundingMode.HALF_UP));
    summary.put("trades", total);
    summary.put("winRate", total == 0 ? null : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP));
    summary.put(
        "expectancy",
        total == 0 ? null : realizedTotal.divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP));
    return Map.of("points", points, "summary", summary);
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
        settle(pos, price, closeReason);
        closed++;
      } catch (Exception e) {
        log.warn("signal-exit close failed for position {}: {}", pos.id(), e.getMessage());
      }
    }
    return closed;
  }

  /** 15:45 IST mark-to-close: settle every OPEN intraday position so it does not carry overnight. */
  @Transactional
  public int markToCloseIntraday() {
    int closed = 0;
    for (PositionRow pos : positions.intradayOpen()) {
      try {
        settle(pos, null, "INTRADAY_MTM");
        closed++;
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
}
