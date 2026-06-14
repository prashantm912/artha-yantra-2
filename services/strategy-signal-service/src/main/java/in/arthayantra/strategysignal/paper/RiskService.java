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

  /** The three limit keys. */
  public static final String KILL_SWITCH = "kill_switch";
  public static final String MAX_OPEN = "max_open_paper_positions";
  public static final String DAILY_LOSS = "daily_loss_limit";

  private static final Logger log = LoggerFactory.getLogger(RiskService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final RiskSettingsRepository settings;
  private final PaperPositionRepository positions;
  private final PaperAccountService account;
  private final Clock clock;

  private volatile LocalDate dailyLossTrippedOn;

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
      BigDecimal limit = dailyLossLimitInr(dailyLoss.get().value());
      BigDecimal dayPnl = account.dayPnl();
      if (dayPnl.compareTo(limit.negate()) <= 0) {
        recordTrip(dayPnl, limit);
        return false;
      }
    }
    return true;
  }

  private BigDecimal dailyLossLimitInr(JsonNode node) {
    BigDecimal value = node.path("value").decimalValue();
    if ("pct".equalsIgnoreCase(node.path("mode").asText("inr"))) {
      return account.equity().multiply(value).divide(BigDecimal.valueOf(100));
    }
    return value;
  }

  private void recordTrip(BigDecimal dayPnl, BigDecimal limit) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    if (!today.equals(dailyLossTrippedOn)) {
      dailyLossTrippedOn = today;
      settings.audit(
          DAILY_LOSS,
          "TRIP",
          "day P&L " + dayPnl.toPlainString() + " breached limit " + limit.toPlainString());
      log.warn("global daily-loss limit tripped — ENTRY emission paused for {}", today);
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
    if (DAILY_LOSS.equals(key)) {
      dailyLossTrippedOn = null; // re-arm the per-day trip dedup on a limit change
    }
  }

  private boolean boolFlag(String key) {
    return settings.get(key).map(s -> s.value().path("enabled").asBoolean(false)).orElse(false);
  }

  private static boolean enabled(Optional<Setting> setting) {
    return setting.map(s -> s.value().path("enabled").asBoolean(false)).orElse(false);
  }
}
