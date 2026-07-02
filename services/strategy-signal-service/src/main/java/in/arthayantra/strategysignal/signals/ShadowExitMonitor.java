package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.scalper.MarketOiClient;
import in.arthayantra.strategysignal.scalper.StrikePicker;
import in.arthayantra.strategysignal.signals.ShadowPositionRepository.ShadowRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The shadow-book exit sweep (signal-analysis §7.2) — the {@code StraddleExitMonitor} pattern: a
 * LIVE-only scheduled poll that closes OPEN {@code shadow_positions} the way the real exit
 * machinery would, so every rejected-then-shadowed entry ends with an honest PnL label.
 *
 * <p>Exit coverage (v1, in priority order): the premium STOP_LOSS / TAKE_PROFIT brackets (the
 * YAML's premium_pct rules resolved at open), the STRUCTURAL_STOP (index-future level, read
 * against the signal future's last tick), and the 15:12 SQUARE_OFF. Indicator-driven exits
 * (trend-flip / signal-exit) need the engine's bar state and are deliberately NOT replicated —
 * the per-session findings state this caveat. A row left OPEN from a prior day (stack down at
 * square-off) closes as STALE with no PnL (no honest exit price exists).
 *
 * <p>Option LTP source: the shared {@code ticks:last} Redis hash first; when the leg is not
 * ticker-subscribed, the option-root chain snapshot (per-strike LTP) as a fallback — one chain
 * read per underlying per sweep, cached within the sweep. A missing price never force-closes
 * (fail-safe, like the straddle monitor); the row waits for the next poll.
 */
@Component
public class ShadowExitMonitor {

  private static final Logger log = LoggerFactory.getLogger(ShadowExitMonitor.class);
  private static final LocalTime OPEN = LocalTime.of(9, 15);
  private static final LocalTime CLOSE = LocalTime.of(15, 30);

  private final ShadowPositionRepository shadows;
  private final StringRedisTemplate redis;
  private final MarketOiClient oiClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final boolean enabled;
  private final LocalTime squareOff;

  /** Wires the shadow ledger + the two LTP sources. */
  public ShadowExitMonitor(
      ShadowPositionRepository shadows,
      StringRedisTemplate redis,
      MarketOiClient oiClient,
      ObjectMapper objectMapper,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value(
              "${artha.scalper.shadow-book.enabled:false}")
          boolean enabled,
      @org.springframework.beans.factory.annotation.Value(
              "${artha.scalper.shadow-book.square-off:15:12}")
          String squareOff) {
    this.shadows = shadows;
    this.redis = redis;
    this.oiClient = oiClient;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.enabled = enabled;
    this.squareOff = LocalTime.parse(squareOff);
  }

  /** Polls during market hours; the scheduler never runs in a deterministic replay (parity-safe). */
  @Scheduled(fixedDelayString = "${artha.scalper.shadow-book.poll-ms:60000}")
  public void poll() {
    if (!enabled) {
      return;
    }
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(Ist.ZONE));
    LocalTime t = now.toLocalTime();
    if (t.isBefore(OPEN) || t.isAfter(CLOSE)) {
      return;
    }
    evaluate(t, now.toLocalDate());
  }

  /** The core sweep (testable with a pinned IST wall-clock). */
  void evaluate(LocalTime nowIst, LocalDate todayIst) {
    List<ShadowRow> open = shadows.open();
    if (open.isEmpty()) {
      return;
    }
    boolean pastSquareOff = !nowIst.isBefore(squareOff);
    Map<String, Map<String, BigDecimal>> chainLtpByUnderlying = new HashMap<>();
    for (ShadowRow row : open) {
      try {
        // A prior-day leftover has no honest exit price any more — label it STALE, PnL null.
        if (row.openedAt().atZoneSameInstant(Ist.ZONE).toLocalDate().isBefore(todayIst)) {
          shadows.close(row.id(), "STALE", null, null, null);
          continue;
        }
        BigDecimal ltp = optionLtp(row, chainLtpByUnderlying);
        // Structural stop reads the SIGNAL future's tick — independent of the option LTP.
        if (row.structuralStop() != null && structuralStopHit(row)) {
          if (ltp != null) {
            close(row, "STRUCTURAL_STOP", ltp);
          }
          continue;
        }
        if (ltp == null) {
          continue; // fail-safe: no price, no exit — wait for the next poll
        }
        if (row.stopLoss() != null && ltp.compareTo(row.stopLoss()) <= 0) {
          close(row, "STOP_LOSS", ltp);
        } else if (row.takeProfit() != null && ltp.compareTo(row.takeProfit()) >= 0) {
          close(row, "TAKE_PROFIT", ltp);
        } else if (pastSquareOff) {
          close(row, "SQUARE_OFF", ltp);
        }
      } catch (RuntimeException e) {
        log.warn("shadow exit sweep: row {} failed: {}", row.id(), e.toString());
      }
    }
  }

  private void close(ShadowRow row, String reason, BigDecimal exitLtp) {
    BigDecimal points = exitLtp.subtract(row.entryLtp()).setScale(2, RoundingMode.HALF_UP);
    BigDecimal pct =
        row.entryLtp().signum() == 0
            ? null
            : points
                .multiply(new BigDecimal("100"))
                .divide(row.entryLtp(), 2, RoundingMode.HALF_UP);
    shadows.close(row.id(), reason, exitLtp, points, pct);
    log.info(
        "shadow close: {} {} {} {} @ {} → {} pts ({}%) [rail {}]",
        row.strategySlug(), row.side(), row.tradingsymbol(), reason, exitLtp, points, pct,
        row.blockingRail());
  }

  /** True when the signal future's last tick has crossed the structural stop against the side. */
  private boolean structuralStopHit(ShadowRow row) {
    if (row.signalExchange() == null || row.signalTradingsymbol() == null) {
      return false;
    }
    Optional<BigDecimal> fut = lastTick(row.signalExchange(), row.signalTradingsymbol());
    if (fut.isEmpty()) {
      return false;
    }
    return "CE".equals(row.side())
        ? fut.get().compareTo(row.structuralStop()) <= 0
        : fut.get().compareTo(row.structuralStop()) >= 0;
  }

  /** The option leg's LTP: last tick first, then the option-root chain (cached per sweep). */
  private BigDecimal optionLtp(ShadowRow row, Map<String, Map<String, BigDecimal>> chainCache) {
    Optional<BigDecimal> tick = lastTick(row.exchange(), row.tradingsymbol());
    if (tick.isPresent()) {
      return tick.get();
    }
    Map<String, BigDecimal> chain =
        chainCache.computeIfAbsent(row.underlying(), this::chainLtps);
    return chain.get(row.tradingsymbol());
  }

  private Map<String, BigDecimal> chainLtps(String underlying) {
    try {
      return oiClient.chain(underlying).map(c -> byTradingsymbol(c.candidates())).orElse(Map.of());
    } catch (RuntimeException e) {
      log.warn("shadow exit sweep: chain LTP fallback failed for {}: {}", underlying, e.toString());
      return Map.of();
    }
  }

  private static Map<String, BigDecimal> byTradingsymbol(List<StrikePicker.Candidate> candidates) {
    Map<String, BigDecimal> out = new HashMap<>();
    for (StrikePicker.Candidate c : candidates) {
      if (c.tradingsymbol() != null && c.ltp() != null) {
        out.put(c.tradingsymbol(), c.ltp());
      }
    }
    return out;
  }

  /**
   * The shared {@code ticks:last} hash market-data writes (keyed {@code EXCHANGE:TRADINGSYMBOL}) —
   * the same D-cache read the paper ledger uses (a tiny local copy: the signals slice cannot import
   * the paper slice's {@code LastTickReader}).
   */
  private Optional<BigDecimal> lastTick(String exchange, String tradingsymbol) {
    Object json = redis.opsForHash().get("ticks:last", exchange + ":" + tradingsymbol);
    if (json == null) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(objectMapper.readTree(json.toString()).path("lastPrice").asText(null))
          .map(BigDecimal::new);
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
