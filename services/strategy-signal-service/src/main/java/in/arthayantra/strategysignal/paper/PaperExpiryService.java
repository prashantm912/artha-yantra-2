package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.ContractInfoClient.ContractInfo;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.NotifyTarget;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.notifier.NotificationRepository;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  private final NotifierClient notifier;
  private final NotificationRepository notifications;
  private final Clock clock;
  // T-1 dedup per (positionId, IST day): one roll-or-close push per position per day
  private final Set<String> t1Sent = ConcurrentHashMap.newKeySet();

  /** Wires the lifecycle collaborators. */
  public PaperExpiryService(
      PaperService paper,
      PaperPositionRepository positions,
      ContractInfoClient contracts,
      LastTickReader lastTick,
      NotifierClient notifier,
      NotificationRepository notifications,
      Clock clock) {
    this.paper = paper;
    this.positions = positions;
    this.contracts = contracts;
    this.lastTick = lastTick;
    this.notifier = notifier;
    this.notifications = notifications;
    this.clock = clock;
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
        settleOne(pos, info.get());
        settled++;
      } catch (Exception e) {
        log.warn("expiry settlement failed for position {}: {}", pos.id(), e.getMessage());
      }
    }
    return settled;
  }

  /** Settles one position per its underlying type (index option / index future / stock F&O). */
  public void settleOne(PositionRow pos, ContractInfo info) {
    if (!info.indexUnderlying()) {
      // stock F&O: physical delivery is never modeled — close with the warning
      log.warn(
          "stock F&O paper position {} {}:{} closed at expiry — a real position would go to physical"
              + " delivery / auction (never modeled)",
          pos.id(),
          pos.exchange(),
          pos.tradingsymbol());
      BigDecimal mark = lastTick.lastPrice(pos.exchange(), pos.tradingsymbol()).orElse(pos.avgEntryPrice());
      paper.settleExpiry(pos, mark, false);
      return;
    }
    BigDecimal spot =
        lastTick.lastPrice(info.spotExchange(), info.spotSymbol()).orElse(pos.avgEntryPrice());
    if (info.instrumentClass() == InstrumentClass.OPTION) {
      paper.settleExpiry(pos, intrinsic(info.optionType(), spot, info.strike()), true);
    } else {
      // index future: cash-settle at the spot LTP at expiry close
      paper.settleExpiry(pos, spot, false);
    }
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
        positions.notifyTargetFor(pos.exchange(), pos.tradingsymbol(), pos.side());
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
