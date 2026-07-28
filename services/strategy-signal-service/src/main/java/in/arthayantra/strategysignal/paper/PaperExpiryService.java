package in.arthayantra.strategysignal.paper;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.ContractInfoClient.ContractInfo;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.NotifyTarget;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.notifier.NotificationRepository;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The derivative paper lifecycle (A11 / FP-2): expiry-day settlement so no derivative paper position
 * outlives its contract, plus the T-1 roll-or-close push. Index options cash-settle at intrinsic vs
 * the spot LTP (a DOCUMENTED approximation of the official settlement price — Kite exposes no
 * settlement feed); index futures cash-settle at spot; stock F&O closes with a physical-settlement
 * warning (delivery is never modeled). Settlement runs through the normal close path (realized + the
 * expiry STT leg from the shared engine fee schedule), stamping {@code close_reason}.
 */
@Service
public class PaperExpiryService {

  private static final Logger log = LoggerFactory.getLogger(PaperExpiryService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final PaperService paper;
  private final PaperPositionRepository positions;
  private final ContractInfoClient contracts;
  private final LastTickReader lastTick;
  private final ExpirySpotReader expirySpot;
  private final PaperStaleTickAlerter staleTicks;
  private final NotifierClient notifier;
  private final NotificationRepository notifications;
  private final Clock clock;
  /**
   * The #694 close-path staleness bound (shared with {@code PaperService.tickMaxAge} via the same
   * {@code artha.paper.tick-max-age-seconds} knob): a settlement spot OLDER than this is USED anyway
   * (never refused) but counted + alerted as a stale settle — exits take the best available truth.
   */
  private final Duration tickMaxAge;
  // T-1 dedup per (positionId, IST day): one roll-or-close push per position per day
  private final Set<String> t1Sent = ConcurrentHashMap.newKeySet();

  /** Wires the lifecycle collaborators. */
  public PaperExpiryService(
      PaperService paper,
      PaperPositionRepository positions,
      ContractInfoClient contracts,
      LastTickReader lastTick,
      ExpirySpotReader expirySpot,
      PaperStaleTickAlerter staleTicks,
      NotifierClient notifier,
      NotificationRepository notifications,
      Clock clock,
      @Value("${artha.paper.tick-max-age-seconds:15}") long tickMaxAgeSeconds) {
    this.paper = paper;
    this.positions = positions;
    this.contracts = contracts;
    this.lastTick = lastTick;
    this.expirySpot = expirySpot;
    this.staleTicks = staleTicks;
    this.notifier = notifier;
    this.notifications = notifications;
    this.clock = clock;
    this.tickMaxAge = Duration.ofSeconds(tickMaxAgeSeconds);
  }

  /** Settles every OPEN derivative paper position whose contract expires today (post-close). */
  public int settleExpiries() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    int settled = 0;
    for (PositionRow pos : positions.listOpen()) {
      Optional<ContractInfo> info = contracts.contract(pos.exchange(), pos.tradingsymbol());
      if (info.isEmpty() || !today.equals(info.get().expiry())) {
        continue;
      }
      try {
        // Count only what THIS pass settled. A concurrent closer winning the CAS returns false, and
        // counting it anyway reported someone else's exit as this sweep's own (§9-05).
        if (settleOne(pos, info.get())) {
          settled++;
        }
      } catch (Exception e) {
        log.warn("expiry settlement failed for position {}: {}", pos.id(), e.getMessage());
      }
    }
    return settled;
  }

  /**
   * Durable recovery for positions STRANDED past their expiry (audit AY-SL-04 follow-through). {@link
   * #settleExpiries} acts ONLY on the expiry date, so a settle that refused there — the spot never
   * ticked live (an unpinned spot / key mismatch / feed outage) — would otherwise stay OPEN forever,
   * exactly the invisible-strand class the #694 doctrine's review eliminated. This re-attempts every
   * OPEN derivative position whose contract already expired, settling it at the EXPIRY-SESSION spot
   * (the expiry-day close from history — NOT today's spot, since the contract cash-settled at the
   * expiry-day spot). Idempotent: a settled position is CLOSED and never re-seen; a position not yet
   * past expiry belongs to {@link #settleExpiries} and is skipped. Run from the evening scheduler; a
   * position whose expiry-session spot is still uncaptured is left durably OPEN and re-alerted each
   * pass (never fabricated, never settled at a wrong-session spot). Returns the count settled this run.
   */
  public int settlePastExpiries() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    int settled = 0;
    for (PositionRow pos : positions.listOpen()) {
      Optional<ContractInfo> info = contracts.contract(pos.exchange(), pos.tradingsymbol());
      // Skip non-derivatives (empty) and anything not yet strictly past expiry — the expiry-date
      // settle owns the expiry day; the instruments master tombstones (never deletes) expired
      // contracts, so a past-expiry contract still resolves here.
      if (info.isEmpty() || !info.get().expiry().isBefore(today)) {
        continue;
      }
      try {
        if (settlePastExpiryOne(pos, info.get())) {
          settled++;
        }
      } catch (Exception e) {
        log.warn("past-expiry recovery failed for position {}: {}", pos.id(), e.getMessage());
      }
    }
    return settled;
  }

  /**
   * Settles one stranded past-expiry position at its EXPIRY-SESSION reference, or leaves it durably
   * OPEN + re-alerts when that session's close was never captured. Returns true iff it settled. The
   * reference is resolved from HISTORY ({@link ExpirySpotReader}), never the live tick — the live tick
   * is now a later session and would settle at the wrong price. Mirrors {@link #settleOne}'s
   * per-underlying split (index option to intrinsic vs spot; index future to spot; stock F&amp;O to its
   * own close, physical delivery never modeled), but every branch refuses by leaving OPEN (never
   * fabricates from {@code avgEntryPrice}).
   */
  private boolean settlePastExpiryOne(PositionRow pos, ContractInfo info) {
    if (!info.indexUnderlying()) {
      // stock F&O: physical delivery is never modeled — settle off the option's OWN expiry-day close.
      Optional<BigDecimal> close =
          expirySpot.expirySessionClose(pos.exchange(), pos.tradingsymbol(), info.expiry());
      if (close.isEmpty()) {
        staleTicks.settleRefused(
            pos, "EXPIRY_SETTLEMENT_RECON", pos.exchange() + ":" + pos.tradingsymbol());
        return false;
      }
      log.warn(
          "stock F&O paper position {} {}:{} settled late off its expiry-day close — a real position"
              + " would go to physical delivery / auction (never modeled)",
          pos.id(), pos.exchange(), pos.tradingsymbol());
      return paper.settleExpiry(pos, close.get(), false).isPresent();
    }
    // Index derivatives cash-settle vs the EXPIRY-day SPOT close (a documented approximation).
    Optional<BigDecimal> spot =
        expirySpot.expirySessionClose(info.spotExchange(), info.spotSymbol(), info.expiry());
    if (spot.isEmpty()) {
      staleTicks.settleRefused(
          pos, "EXPIRY_SETTLEMENT_RECON", info.spotExchange() + ":" + info.spotSymbol());
      return false;
    }
    if (info.instrumentClass() == InstrumentClass.OPTION) {
      return paper.settleExpiry(pos, intrinsic(info.optionType(), spot.get(), info.strike()), true)
          .isPresent();
    }
    return paper.settleExpiry(pos, spot.get(), false).isPresent();
  }

  /**
   * Settles one position per its underlying type (index option / index future / stock F&O), honoring
   * the #694 close-path doctrine (audit AY-SL-04): the exit reference is the last REAL price at ANY
   * age — NEVER {@code avgEntryPrice}. Fabricating a settlement price from the entry cost is exactly
   * what the doctrine forbids ("never reintroduce an avgEntryPrice breakeven fallback on a close
   * path"), so a symbol that has NEVER ticked is left durably OPEN + alerted, not closed at fiction.
   */
  public boolean settleOne(PositionRow pos, ContractInfo info) {
    if (!info.indexUnderlying()) {
      // stock F&O: physical delivery is never modeled — close with the warning. A null reference
      // delegates the exit price to PaperService.doSettle's #694 fallback: the position's OWN last
      // REAL tick at any age, refusing (leaving it OPEN + alerting via PaperStaleTickAlerter) only
      // when NO tick has EVER been seen — the SAME no-fabrication path every other paper close runs.
      log.warn(
          "stock F&O paper position {} {}:{} closed at expiry — a real position would go to physical"
              + " delivery / auction (never modeled)",
          pos.id(),
          pos.exchange(),
          pos.tradingsymbol());
      return paper.settleExpiry(pos, null, false).isPresent();
    }
    // Index derivatives cash-settle vs the SPOT LTP (a documented approximation — Kite exposes no
    // settlement feed). Resolve the spot with the #694 close-path semantics (last REAL spot at any
    // age; refuse only when the spot has NEVER ticked) — see resolveSettlementSpot.
    BigDecimal spot = resolveSettlementSpot(pos, info);
    if (info.instrumentClass() == InstrumentClass.OPTION) {
      return paper.settleExpiry(pos, intrinsic(info.optionType(), spot, info.strike()), true).isPresent();
    }
    // index future: cash-settle at the spot LTP at expiry close
    return paper.settleExpiry(pos, spot, false).isPresent();
  }

  /**
   * The last REAL spot LTP to cash-settle an index derivative against, per the #694 close-path
   * doctrine and mirroring {@link PaperService}'s null-price settle branch (the same {@link
   * PaperStaleTickAlerter} idiom): the spot tick is used at ANY age (a stale spot is counted +
   * alerted via {@link PaperStaleTickAlerter#staleSettleUsed}, never refused), and settlement REFUSES
   * — counter + a spot-naming ntfy via {@link PaperStaleTickAlerter#settleRefused} + a 422 {@code
   * DATA_STALE} the {@code settleExpiries} loop catches, leaving the position OPEN — only when NO spot
   * tick has EVER been seen. It NEVER falls back to {@code avgEntryPrice}: a fabricated intrinsic on a
   * close path is precisely what audit AY-SL-04 / the #694 doctrine forbid. Unlike {@code doSettle}
   * (which reads the position's OWN tick) this reads the SPOT symbol — a different instrument than the
   * option/future being settled — so the refusal is applied here rather than delegated to {@code
   * doSettle}.
   *
   * <p><b>The refusal is terminal until repaired, not auto-retried.</b> {@code settleExpiries} only
   * acts on the position ON its expiry date, so there is NO automatic next pass: a spot that never
   * ticked for the WHOLE expiry session is a total feed outage, and the position stays OPEN until the
   * spot source is pinned and it is settled by hand (surfaced by the daily {@code settleRefused} page
   * naming the dead spot). That is the doctrine's accepted cost of never fabricating a close — a
   * visible stranded row beats a fictional exit. Reconciler coverage for an OPEN past-expiry position
   * is a possible follow-up (today the settle-refused alert is the only signal).
   */
  private BigDecimal resolveSettlementSpot(PositionRow pos, ContractInfo info) {
    Optional<LastTickReader.TickView> tick =
        lastTick.lastTick(info.spotExchange(), info.spotSymbol());
    if (tick.isEmpty()) {
      String spotKey = info.spotExchange() + ":" + info.spotSymbol();
      staleTicks.settleRefused(pos, "EXPIRY_SETTLEMENT", spotKey);
      throw new ApiException(
          422,
          ErrorCodes.DATA_STALE,
          "no spot tick has ever been seen for " + spotKey + " — index position " + pos.exchange()
              + ":" + pos.tradingsymbol() + " left OPEN, not settled at a fabricated price",
          Map.of(
              "positionId", pos.id(),
              "spotExchange", info.spotExchange(),
              "spotSymbol", info.spotSymbol()));
    }
    Duration age = tick.get().age();
    if (age != null && age.compareTo(tickMaxAge) > 0) {
      staleTicks.staleSettleUsed(pos, "EXPIRY_SETTLEMENT", age);
    }
    return tick.get().price();
  }

  /** Index-option intrinsic vs spot (a documented approximation of the official settlement price). */
  static BigDecimal intrinsic(String optionType, BigDecimal spot, BigDecimal strike) {
    if (spot == null || strike == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal value = "CE".equals(optionType) ? spot.subtract(strike) : strike.subtract(spot);
    return value.signum() > 0 ? value : BigDecimal.ZERO;
  }

  /** T-1 roll-or-close push for every open derivative position expiring tomorrow (deduped). */
  public int notifyExpiring() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    int sent = 0;
    for (PositionRow pos : positions.listOpen()) {
      Optional<ContractInfo> info = contracts.contract(pos.exchange(), pos.tradingsymbol());
      if (info.isEmpty() || ChronoUnit.DAYS.between(today, info.get().expiry()) != 1) {
        continue;
      }
      if (!t1Sent.add(pos.id() + "|" + today)) {
        continue; // already pushed for this position today
      }
      pushT1(pos);
      sent++;
    }
    return sent;
  }

  private void pushT1(PositionRow pos) {
    String title = "Paper position expires tomorrow";
    String message =
        pos.side() + " " + pos.qty() + " " + pos.exchange() + ":" + pos.tradingsymbol() + " — roll or close?";
    Optional<NotifyTarget> target =
        positions.notifyTargetFor(pos.book(), pos.exchange(), pos.tradingsymbol(), pos.side());
    String channel = target.map(NotifyTarget::channel).orElse("NTFY");
    try {
      if (notifier.configured(channel)) {
        notifier.send(channel, title, message);
      }
      target.ifPresent(t -> notifications.record(null, t.strategyId(), channel, "SENT", 1, message));
    } catch (Exception e) {
      target.ifPresent(t -> notifications.record(null, t.strategyId(), channel, "FAILED", 1, e.getMessage()));
      log.warn("T-1 paper push failed for position {}: {}", pos.id(), e.getMessage());
    }
  }
}
