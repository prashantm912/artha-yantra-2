package in.arthayantra.strategysignal.paper;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.TakeAdmission;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The {@link TakeAdmission} implementation: the lot admission the writers already assert, hoisted to
 * before the {@code ACTIVE→TAKEN} CAS.
 *
 * <p><b>It mirrors the writers, it does not invent a rule.</b> The two conditions are exactly
 * {@code PaperService#openOrder:881-909}'s ({@code lotSize <= 0} ⇒ {@code DATA_GAP}, {@code
 * qty % lotSize != 0} ⇒ {@code VALIDATION_FAILED}) and exactly {@code
 * LiveOrderService#onSignalTaken:91-104}'s, with the same codes.
 *
 * <p><b>There are TWO writers on one event, and their intents DIFFER — cross-vendor review round 1,
 * Critical.</b> A first cut modelled only the paper writer and could therefore re-open the very
 * defect this class exists to close. Both listen to the same {@code SignalTaken}, so a take is
 * admissible only if BOTH would accept it, and they disagree on two axes:
 *
 * <ul>
 *   <li><b>Quantity.</b> {@code PaperSignalListener#openStraddle} sizes both legs to {@link
 *       StraddleLegs#combinedQty}, which FLOORS to a whole lot. {@code LiveOrderService:98} checks
 *       the RAW {@code event.qty()}. With lot 65 and a take of 50, {@code combinedQty} floors to 65
 *       (admissible) while the live writer refuses 50 — commit the CAS on the paper intent alone and
 *       the anchor is stranded exactly as before.
 *   <li><b>Leg.</b> {@code openSingle:295-297} routes the tradeable leg only when {@code
 *       tradeableTradingsymbol != null <b>&&</b> scalperDetail != null}; {@code
 *       LiveOrderService:87} routes it on {@code tradeableTradingsymbol != null} ALONE. A signal
 *       with a tradeable symbol and no scalper side-channel is therefore checked against DIFFERENT
 *       INSTRUMENTS by the two writers.
 * </ul>
 *
 * <p>So the gate enumerates the actual per-writer {@link Intent}s and admits only if every one of
 * them passes. The set is ordered and de-duplicated, and instrument lookups are memoised for the
 * duration of one call — two lookups of the same key that must agree is not a check but a race, the
 * same reasoning {@code PaperService} records for folding its alignment check onto one {@code meta}.
 *
 * <p><b>The live intent is included only when live execution is ARMED</b>, because that is what the
 * writer does: {@code LiveOrderService:75-77} returns before any check while {@code
 * artha.scalper.execution} is {@code paper} (the default and the current deployed value). Including
 * it unconditionally would refuse takes the paper writer fills perfectly well — the straddle case
 * above is precisely such a take — and an over-refusal on a live money path is worse than the defect
 * being fixed. The gate arms exactly when the writer it models arms.
 *
 * <p>Same reason for the straddle degrade arm: with an unknown lot or a missing premium {@code
 * openStraddle} falls back to the single primary leg, so this falls back with it.
 *
 * <p><b>Deliberately fail-CLOSED by omission.</b> Nothing here catches: {@code
 * RestInstrumentMetaClient} already absorbs a transport miss into a proxy, and any other throw means
 * we could not decide. On the controller path that surfaces with the signal still {@code ACTIVE}; on
 * the auto path {@code AutoPaperListener}'s own {@code catch} logs it and the signal stays {@code
 * ACTIVE}. Both are the recoverable direction — "you can always NOT enter".
 */
@Component
public class InstrumentTakeAdmission implements TakeAdmission {

  private static final Logger log = LoggerFactory.getLogger(InstrumentTakeAdmission.class);

  /** Refusals by reason — the defect this closes was invisible precisely because it fail-softed. */
  private static final String REFUSED_METRIC = "ay_signal_take_admission_refused_total";

  /** One writer's order intent: the instrument it would route, at the quantity it would send. */
  /**
   * One order this take would create, plus WHICH writer creates it.
   *
   * <p>⚠️ {@code paper} was added 2026-08-29 (H44 round 3) and is NOT bookkeeping. The H44
   * closability rule is a PAPER rule -- it exists because a paper position with no tick cannot be
   * settled by any automatic paper exit. Applying it to a LIVE-only intent refuses a take on account
   * of a leg the paper writer never opens and {@code PaperService}'s own gate never sees, which is
   * precisely the writer-vs-admission disagreement {@code TakeAdmissionWriterAgreementTest} exists to
   * prevent: a gate that refuses takes the writers would have filled fine.
   *
   * <p>The shape that exposes it is the DIVERGENT LEG -- {@code tradeableTradingsymbol != null} with
   * a NULL {@code scalperDetail}. Paper then routes the PRIMARY (an equity), while live routes the
   * TRADEABLE (an option). An option-scoped paper rule applied blind would gate that live option.
   */
  private record Intent(String exchange, String tradingsymbol, int qty, boolean paper) {

    /** Same order, ignoring ownership -- the identity the pre-H44 de-duplication used. */
    boolean sameOrder(Intent other) {
      return qty == other.qty
          && java.util.Objects.equals(exchange, other.exchange)
          && java.util.Objects.equals(tradingsymbol, other.tradingsymbol);
    }
  }


  private final SignalRepository signals;
  private final InstrumentMetaClient instruments;
  private final MeterRegistry meters;
  private final boolean executionLive;

  /**
   * H44 mirror. This port exists because a refusal raised INSIDE the paper writer arrives too
   * late on the take path: {@code PaperSignalListener} catches it, compensates the stranded
   * anchor, and {@code SignalsController} still answers 200 with a detail body -- so an armed
   * gate would silently report SUCCESS for a take that opened nothing (cross-vendor review,
   * round 2). The verdict has to be reached BEFORE the ACTIVE->TAKEN CAS, which is here.
   */
  private final LastTickReader lastTick;

  private final boolean refuseNoTickEntries;

  /**
   * Nullable ON PURPOSE: the test-convenience constructor supplies none, and a DISARMED gate never
   * refuses so it never records. {@link #recordH44Refusal} null-checks rather than requiring every
   * direct-construction call site to grow a stub.
   */
  private final PaperOrderRejectionRecorder rejections;



  /** Wires the signal store, the instrument master, the refusal counter and the execution mode. */
  // Two public constructors exist (the second is the disarmed test convenience), so Spring must be
  // TOLD which one to inject through -- without this it looks for a no-arg constructor and every
  // context in the service fails to start.
  @org.springframework.beans.factory.annotation.Autowired
  public InstrumentTakeAdmission(
      SignalRepository signals,
      InstrumentMetaClient instruments,
      MeterRegistry meters,
      @Value("${artha.scalper.execution:paper}") String executionMode,
      LastTickReader lastTick,
      @Value("${artha.paper.refuse-no-tick-entries:false}") boolean refuseNoTickEntries,
      PaperOrderRejectionRecorder rejections) {
    this.signals = signals;
    this.instruments = instruments;
    this.meters = meters;
    this.executionLive = "live".equalsIgnoreCase(executionMode);
    this.lastTick = lastTick;
    this.refuseNoTickEntries = refuseNoTickEntries;
    this.rejections = rejections;
    // Fail FAST rather than silently disarm. The convenience constructor above passes a null reader
    // because a DISARMED gate never reads it; if anyone ever arms the flag through that path, this
    // refuses at construction instead of letting an armed safety gate quietly do nothing -- which is
    // the failure mode this whole item exists to prevent.
    if (refuseNoTickEntries && lastTick == null) {
      throw new IllegalArgumentException(
          "refuse-no-tick-entries is armed but no LastTickReader was supplied");
    }
  }

  /**
   * Test-only convenience (pre-H44 signature): the closability gate defaults to DISARMED, which is
   * its shipped default, so every existing direct-construction call site keeps compiling and keeps
   * asserting exactly what it asserted before. Mirrors the same trick PaperService uses for its own
   * @Value-injected flags.
   */
  public InstrumentTakeAdmission(
      SignalRepository signals,
      InstrumentMetaClient instruments,
      MeterRegistry meters,
      String executionMode) {
    this(signals, instruments, meters, executionMode, null, false, null);
  }

  @Override
  public Verdict admit(long signalId, Integer qty) {
    if (qty == null || qty <= 0) {
      // No order intent at all — every writer already returns without opening anything, so there is
      // nothing to admit. (A qty-less manual take still anchors TAKEN with no position; that is the
      // deliberate "I filled this at the broker myself" flow, not this defect.)
      return Verdict.ADMITTED;
    }
    SignalRepository.SignalRow row = signals.find(signalId).orElse(null);
    if (row == null) {
      // The callers already resolve existence themselves (404 / a warn + return). Not this port's
      // verdict to give.
      return Verdict.ADMITTED;
    }
    Map<String, InstrumentMeta> memo = new HashMap<>();
    for (Intent intent : intents(row, qty, memo)) {
      Verdict verdict = admitIntent(signalId, intent, memo);
      if (!verdict.admitted()) {
        return verdict;
      }
    }
    return Verdict.ADMITTED;
  }

  /**
   * Every order intent this take would create, in writer order: the PAPER writer's (a straddle pair
   * at {@code combinedQty}, else its single resolved leg at {@code qty}) followed by the LIVE
   * writer's (its OWN resolved leg at the RAW {@code qty}) when live execution is armed. De-duplicated
   * — the common directional case yields one intent, not two.
   */
  private Set<Intent> intents(
      SignalRepository.SignalRow row, int qty, Map<String, InstrumentMeta> memo) {
    LinkedHashSet<Intent> intents = new LinkedHashSet<>();
    boolean pairSized = false;
    Optional<StraddleLegs.Pair> straddle =
        row.scalperDetail() == null ? Optional.empty() : StraddleLegs.parse(row.scalperDetail());
    if (straddle.isPresent()) {
      StraddleLegs.Pair pair = straddle.get();
      long lot = meta(pair.ce().exchange(), pair.ce().tradingsymbol(), memo).lotSize();
      int pairQty =
          lot <= 0 ? 0 : StraddleLegs.combinedQty(qty, pair.ce().ltp(), pair.pe().ltp(), lot);
      if (pairQty > 0) {
        // BOTH legs open atomically at pairQty: a PE leg whose own lot is unknown or disagrees
        // fails openPair and strands the anchor exactly as a CE one would.
        // BOTH legs are PAPER intents: openPair IS the paper writer, so H44 applies to each.
        intents.add(new Intent(pair.ce().exchange(), pair.ce().tradingsymbol(), pairQty, true));
        intents.add(new Intent(pair.pe().exchange(), pair.pe().tradingsymbol(), pairQty, true));
        pairSized = true;
      }
    }
    if (!pairSized) {
      // PaperSignalListener#openSingle:295-297 — the tradeable leg needs BOTH conditions.
      boolean tradeable = row.tradeableTradingsymbol() != null && row.scalperDetail() != null;
      intents.add(
          new Intent(
              tradeable ? row.tradeableExchange() : row.exchange(),
              tradeable ? row.tradeableTradingsymbol() : row.tradingsymbol(),
              qty,
              true));
    }
    if (executionLive) {
      // LiveOrderService:87 — the tradeable leg on the symbol alone, at the RAW quantity.
      boolean hasTradeable = row.tradeableTradingsymbol() != null;
      Intent live =
          new Intent(
              hasTradeable ? row.tradeableExchange() : row.exchange(),
              hasTradeable ? row.tradeableTradingsymbol() : row.tradingsymbol(),
              qty,
              false);
      // De-duplicate on the ORDER, not on the record. Adding `paper` to Intent would otherwise
      // silently break the LinkedHashSet de-dup this has always relied on: in the COMMON
      // directional case paper and live resolve to the same leg at the same quantity, and that
      // must stay ONE intent -- owned by PAPER, because paper really does open it and H44 really
      // does apply. Only a genuinely live-only leg is added as a second, non-paper intent.
      if (intents.stream().noneMatch(existing -> existing.sameOrder(live))) {
        intents.add(live);
      }
    }
    return intents;
  }

  /** The two writer conditions on one intent, with the writers' own codes. */
  private Verdict admitIntent(long signalId, Intent intent, Map<String, InstrumentMeta> memo) {
    if (intent.exchange() == null || intent.tradingsymbol() == null) {
      // PaperService answers this with its own 400 ("exchange, tradingsymbol and side are
      // required"); an unresolvable leg is a malformed request, not an inadmissible lot.
      return Verdict.ADMITTED;
    }
    InstrumentMeta meta = meta(intent.exchange(), intent.tradingsymbol(), memo);
    if (meta.lotSize() <= 0) {
      return refuse(
          signalId,
          "unknown_lot",
          ErrorCodes.DATA_GAP,
          "no lot size in the instrument master for " + intent.exchange() + ":"
              + intent.tradingsymbol()
              + " — refusing to take a signal that could only fill at an assumed lot of 1",
          Map.of(
              "signalId", signalId,
              "exchange", intent.exchange(),
              "tradingsymbol", intent.tradingsymbol(),
              "instrumentClass", meta.instrumentClass().name()));
    }
    if (intent.qty() % meta.lotSize() != 0) {
      return refuse(
          signalId,
          "lot_misaligned",
          ErrorCodes.VALIDATION_FAILED,
          "qty " + intent.qty() + " is not a multiple of the lot size " + meta.lotSize() + " for "
              + intent.exchange() + ":" + intent.tradingsymbol(),
          Map.of(
              "signalId", signalId,
              "exchange", intent.exchange(),
              "tradingsymbol", intent.tradingsymbol(),
              "lotSize", meta.lotSize(),
              "qty", intent.qty()));
    }
    // H44, mirroring PaperService s closability gate so the take path cannot answer 200 for a
    // fill that will be refused. Same three properties as the writer s copy, deliberately:
    // OPTION-scoped (equities do not tick), armed by the same flag, and FAIL-CLOSED on a probe
    // error -- an entry may always be declined.
    // PAPER intents only -- see Intent.paper. A live-only option is not gated here because no
    // paper position is opened for it and PaperService s own H44 gate never sees it either.
    if (refuseNoTickEntries && intent.paper() && meta.instrumentClass() == InstrumentClass.OPTION) {
      boolean everTicked;
      try {
        everTicked = lastTick.lastTick(intent.exchange(), intent.tradingsymbol()).isPresent();
      } catch (RuntimeException probeFailed) {
        return refuseUnavailable(
            signalId,
            "closability_unknown",
            ErrorCodes.DATA_GAP,
            "cannot verify whether " + intent.exchange() + ":" + intent.tradingsymbol()
                + " has ever ticked — refusing the take (H44, fail-closed)",
            Map.of(
                "signalId", signalId,
                "exchange", intent.exchange(),
                "tradingsymbol", intent.tradingsymbol(),
                "qty", intent.qty()));
      }
      if (!everTicked) {
        return refuse(
            signalId,
            "never_ticked",
            ErrorCodes.DATA_GAP,
            "no tick has ever been seen for " + intent.exchange() + ":"
                + intent.tradingsymbol()
                + " — refusing to take a signal no automatic exit could settle (H44)",
            Map.of(
                "signalId", signalId,
                "exchange", intent.exchange(),
                "tradingsymbol", intent.tradingsymbol(),
                "qty", intent.qty()));
      }
    }
    return Verdict.ADMITTED;
  }

  /** One lookup per key per call: two reads that must agree is a race, not a check. */
  private InstrumentMeta meta(String exchange, String tradingsymbol, Map<String, InstrumentMeta> memo) {
    return memo.computeIfAbsent(
        exchange + "/" + tradingsymbol, k -> instruments.meta(exchange, tradingsymbol));
  }

  private Verdict refuse(
      long signalId, String reasonTag, String code, String reason, Map<String, Object> details) {
    meters.counter(REFUSED_METRIC, "reason", reasonTag).increment();
    log.warn(
        "take REFUSED before the ACTIVE→TAKEN transition for signal {} ({}): {}",
        signalId, code, reason);
    recordH44Refusal(signalId, reasonTag, details);
    return Verdict.refused(code, reason, details);
  }

  /** As {@link #refuse} but 503 — a dependency could not answer, so the caller may retry. */
  private Verdict refuseUnavailable(
      long signalId, String reasonTag, String code, String reason, Map<String, Object> details) {
    meters.counter(REFUSED_METRIC, "reason", reasonTag).increment();
    log.warn(
        "take REFUSED (dependency unavailable) before the ACTIVE→TAKEN transition for signal {}"
            + " ({}): {}",
        signalId, code, reason);
    recordH44Refusal(signalId, reasonTag, details);
    return Verdict.unavailable(code, reason, details);
  }

  /**
   * H44 refusals reached before the CAS must leave the SAME forensic row the writer would have.
   *
   * <p>⚠️ Round 3 found this missing: manual takes return before publishing and auto-takes return
   * before the CAS, so a refusal on this path never reached {@code PaperService} and produced only a
   * log line and a metric. The recorder contract PROMISES a durable row for an armed refusal, and
   * that promise was false for the take path — the ledger would have shown nothing for a refusal
   * that really happened.
   *
   * <p>Only the two H44 tags are recorded here. Lot/alignment refusals are deliberately left alone:
   * they are pre-existing behaviour, and widening the ledger under a review fix is scope creep.
   * Fail-soft — the caller is already refusing, and a forensic write must never be the thing that
   * changes the outcome.
   */
  private void recordH44Refusal(long signalId, String reasonTag, Map<String, Object> details) {
    if (rejections == null
        || !("never_ticked".equals(reasonTag) || "closability_unknown".equals(reasonTag))) {
      return;
    }
    try {
      String exchange = String.valueOf(details.get("exchange"));
      String tradingsymbol = String.valueOf(details.get("tradingsymbol"));
      long qty = details.get("qty") instanceof Number n ? n.longValue() : 0L;
      if ("never_ticked".equals(reasonTag)) {
        rejections.recordNeverTicked(
            signalId, null, exchange, tradingsymbol, null, qty,
            "refused at the take admission gate, before the ACTIVE→TAKEN transition");
      } else {
        rejections.recordClosabilityUnknown(
            signalId, null, exchange, tradingsymbol, null, qty,
            "tick store unreachable at the take admission gate — closability unverifiable");
      }
    } catch (RuntimeException e) {
      log.warn("paper_order_rejections not written for admission refusal on signal {}: {}",
          signalId, e.getMessage());
    }
  }
}
