package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategysignal.paper.RiskSettingsRepository.Setting;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Global paper-risk limits (A12 / FP-42). Tripping the daily loss pauses ENTRY emission for the IST
 * day (exit/stop evaluation continues — the gate is consulted only by {@code emitEntry}); the kill
 * switch is one-click pause-all; {@code max_open_paper_positions} caps concurrency. Every trip / flip
 * writes a {@code risk_audit} row (trips deduped per IST day). Limits live on DB rows, never YAML.
 */
@Service
public class RiskService {

  /** The limit keys. */
  public static final String KILL_SWITCH = "kill_switch";
  public static final String MAX_OPEN = "max_open_paper_positions";
  public static final String DAILY_LOSS = "daily_loss_limit";
  public static final String DAILY_PROFIT_TARGET = "daily_profit_target";
  public static final String MAX_DEPLOYMENT_PCT = "max_deployment_pct";
  /** When ON, an emitted ENTRY auto-opens a paper position at the suggested qty (no manual take). */
  public static final String AUTO_PAPER_TRADE = "auto_paper_trade";

  private static final Logger log = LoggerFactory.getLogger(RiskService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final RiskSettingsRepository settings;
  private final PaperPositionRepository positions;
  private final PaperAccountService account;
  private final Clock clock;

  /** Per-day, per-cap trip dedup (key -> IST day it last tripped); re-armed on an {@code update}. */
  private final java.util.Map<String, LocalDate> trippedOn = new java.util.concurrent.ConcurrentHashMap<>();

  /** Wires the risk inputs. */
  public RiskService(
      RiskSettingsRepository settings,
      PaperPositionRepository positions,
      PaperAccountService account,
      Clock clock) {
    this.settings = settings;
    this.positions = positions;
    this.account = account;
    this.clock = clock;
  }

  /** Whether emitted ENTRY signals should auto-open a paper position (the auto-paper-trade toggle). */
  public boolean autoPaperTradeEnabled() {
    return boolFlag(AUTO_PAPER_TRADE);
  }

  /** Whether a new ENTRY may be emitted right now (the {@code emitEntry} gate). */
  public boolean entryAllowed() {
    if (boolFlag(KILL_SWITCH)) {
      return false;
    }
    Optional<Setting> maxOpen = settings.get(MAX_OPEN);
    if (enabled(maxOpen) && positions.openCount() >= maxOpen.get().value().path("value").asInt(Integer.MAX_VALUE)) {
      return false;
    }
    Optional<Setting> dailyLoss = settings.get(DAILY_LOSS);
    if (enabled(dailyLoss)) {
      BigDecimal limit = limitInr(dailyLoss.get().value());
      BigDecimal dayPnl = account.dayPnl();
      if (dayPnl.compareTo(limit.negate()) <= 0) {
        recordTrip(DAILY_LOSS, dayPnl, limit);
        return false;
      }
    }
    Optional<Setting> profitTarget = settings.get(DAILY_PROFIT_TARGET);
    if (enabled(profitTarget)) {
      BigDecimal target = limitInr(profitTarget.get().value());
      BigDecimal dayPnl = account.dayPnl();
      if (dayPnl.compareTo(target) >= 0) {
        recordTrip(DAILY_PROFIT_TARGET, dayPnl, target);
        return false;
      }
    }
    Optional<Setting> deployment = settings.get(MAX_DEPLOYMENT_PCT);
    if (enabled(deployment)) {
      // deployment is ALWAYS a % of equity (no mode field) — a live capital-state check, not a
      // one-shot day event, so it is audited directly each time it blocks (no per-day trip dedup).
      BigDecimal value = deployment.get().value().path("value").decimalValue();
      BigDecimal cap = account.equity().multiply(value).divide(BigDecimal.valueOf(100));
      BigDecimal used = account.capitalUsed();
      if (used.compareTo(cap) >= 0) {
        settings.audit(
            MAX_DEPLOYMENT_PCT,
            "TRIP",
            "open deployment " + used.toPlainString() + " ≥ cap " + cap.toPlainString());
        return false;
      }
    }
    return true;
  }

  /** Resolves a {@code {value, mode}} limit row to INR: {@code pct} → equity × value/100, else raw INR. */
  private BigDecimal limitInr(JsonNode node) {
    BigDecimal value = node.path("value").decimalValue();
    if ("pct".equalsIgnoreCase(node.path("mode").asText("inr"))) {
      return account.equity().multiply(value).divide(BigDecimal.valueOf(100));
    }
    return value;
  }

  private void recordTrip(String key, BigDecimal dayPnl, BigDecimal limit) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    if (!today.equals(trippedOn.get(key))) {
      trippedOn.put(key, today);
      settings.audit(
          key,
          "TRIP",
          "day P&L " + dayPnl.toPlainString() + " breached limit " + limit.toPlainString());
      log.warn("global risk cap {} tripped — ENTRY emission paused for {}", key, today);
    }
  }

  /** All limit rows for the settings panel. */
  public List<Setting> all() {
    return settings.all();
  }

  /** Recent trip/flip audit rows. */
  public List<java.util.Map<String, Object>> audit(int limit) {
    return settings.auditTail(limit);
  }

  /** Upserts a limit (audited as a flip). */
  public void update(String key, String valueJson) {
    settings.upsert(key, valueJson);
    settings.audit(key, "UPDATE", valueJson);
    trippedOn.remove(key); // re-arm this cap's per-day trip dedup on a limit change
  }

  private boolean boolFlag(String key) {
    return settings.get(key).map(s -> s.value().path("enabled").asBoolean(false)).orElse(false);
  }

  private static boolean enabled(Optional<Setting> setting) {
    return setting.map(s -> s.value().path("enabled").asBoolean(false)).orElse(false);
  }
}
