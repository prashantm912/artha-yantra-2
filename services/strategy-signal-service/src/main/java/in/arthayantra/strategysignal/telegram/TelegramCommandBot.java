package in.arthayantra.strategysignal.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategysignal.paper.PaperService;
import in.arthayantra.strategysignal.paper.RiskService;
import in.arthayantra.strategysignal.paper.RiskSettingsRepository;
import in.arthayantra.strategysignal.signals.DotHealthCanary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The two-way Telegram command surface (roadmap F6) — the owner's away-from-desk lever. Read
 * commands: {@code /status} (canaries + kill switch + open count), {@code /pnl}, {@code /positions},
 * {@code /help}. Mutating commands — {@code /pause} (risk kill switch ON), {@code /resume} (OFF),
 * {@code /flatten} (paper mark-to-close NOW, the 15:45 sweep's exact code path) — require a
 * {@code /confirm} within 60 s, land one {@code bot_commands} audit row each, and reuse EXISTING
 * levers only (this module adds no new trading behaviour).
 *
 * <p>Safety posture: dormant unless {@code artha.telegram.commands-enabled} AND the shared bot
 * token AND an allowlisted chat id are all set (fail-closed like ntfy, audit P0-6). Messages from
 * any other chat are ignored SILENTLY (no reply, no audit — a stranger learns nothing). Updates
 * pending from before boot are drained unprocessed (a stale /flatten must never replay). Every
 * failure is caught — the bot can never break the signal path; paper trading runs identically with
 * the bot disabled.
 */
@Component
public class TelegramCommandBot {

  private static final Logger log = LoggerFactory.getLogger(TelegramCommandBot.class);
  static final Duration CONFIRM_WINDOW = Duration.ofSeconds(60);

  private final TelegramApi api;
  private final PaperService paper;
  private final RiskService risk;
  private final RiskSettingsRepository riskSettings;
  private final DotHealthCanary dotHealth;
  private final JdbcTemplate jdbc;
  private final RestClient marketData;
  private final Clock clock;
  private final boolean enabled;
  private final Set<String> allowedChats;

  private volatile long nextOffset = 0;
  private volatile boolean bootDrained = false;
  private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();

  private record PendingAction(String command, Instant expiresAt) {}

  /** Wires the API, the existing paper/risk levers, the canaries and the audit sink. */
  public TelegramCommandBot(
      TelegramApi api,
      PaperService paper,
      RiskService risk,
      RiskSettingsRepository riskSettings,
      DotHealthCanary dotHealth,
      JdbcTemplate jdbc,
      RestClient.Builder builder,
      Clock clock,
      @Value("${artha.marketdata.base-url}") String marketDataBaseUrl,
      @Value("${artha.telegram.commands-enabled:false}") boolean enabled,
      @Value("${artha.notifier.telegram-chat-id:}") String chatId) {
    this.api = api;
    this.paper = paper;
    this.risk = risk;
    this.riskSettings = riskSettings;
    this.dotHealth = dotHealth;
    this.jdbc = jdbc;
    this.marketData = builder.baseUrl(marketDataBaseUrl).build();
    this.clock = clock;
    this.enabled = enabled;
    this.allowedChats =
        chatId.isBlank() ? Set.of() : Set.of(chatId.split("\\s*,\\s*"));
  }

  /** The 3 s command poll; dormant without token+flag+allowlist. */
  @Scheduled(fixedDelay = 3_000, initialDelay = 15_000)
  public void poll() {
    if (!enabled || !api.configured() || allowedChats.isEmpty()) {
      return;
    }
    try {
      List<JsonNode> updates = api.getUpdates(nextOffset);
      if (updates.isEmpty()) {
        bootDrained = true;
        return;
      }
      long maxId = nextOffset - 1;
      for (JsonNode update : updates) {
        maxId = Math.max(maxId, update.path("update_id").asLong());
        if (bootDrained) {
          handle(update);
        }
      }
      nextOffset = maxId + 1;
      if (!bootDrained) {
        // first non-empty poll after boot: everything above was queued BEFORE we came up —
        // acknowledged (offset advanced) but never executed. A stale /flatten must not replay.
        log.info("telegram bot drained {} pre-boot update(s) unprocessed", updates.size());
        bootDrained = true;
      }
    } catch (RuntimeException e) {
      log.warn("telegram poll failed: {}", e.toString());
    }
  }

  void handle(JsonNode update) {
    JsonNode message = update.path("message");
    String chatId = message.path("chat").path("id").asText("");
    String text = message.path("text").asText("").trim();
    if (chatId.isBlank() || text.isBlank() || !allowedChats.contains(chatId)) {
      return; // silence — an unknown sender learns nothing, not even that the bot exists
    }
    String command = text.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
    try {
      switch (command) {
        case "/status" -> reply(chatId, command, status());
        case "/pnl" -> reply(chatId, command, pnl());
        case "/positions" -> reply(chatId, command, positions());
        case "/pause", "/resume", "/flatten" -> stage(chatId, command);
        case "/confirm" -> confirm(chatId);
        default -> api.sendMessage(chatId, help());
      }
    } catch (RuntimeException e) {
      log.warn("telegram command {} failed: {}", command, e.toString());
      audit(chatId, command, "ERROR: " + e.getMessage());
      api.sendMessage(chatId, "command failed: " + e.getMessage());
    }
  }

  private void stage(String chatId, String command) {
    pending.put(chatId, new PendingAction(command, clock.instant().plus(CONFIRM_WINDOW)));
    audit(chatId, command, "STAGED");
    api.sendMessage(
        chatId,
        switch (command) {
          case "/pause" -> "PAUSE new paper entries (risk kill switch ON). Reply /confirm within 60s.";
          case "/resume" -> "RESUME paper entries (risk kill switch OFF). Reply /confirm within 60s.";
          default -> "FLATTEN: close ALL open intraday paper positions at mark now. Reply /confirm within 60s.";
        });
  }

  private void confirm(String chatId) {
    PendingAction action = pending.remove(chatId);
    if (action == null || clock.instant().isAfter(action.expiresAt())) {
      audit(chatId, "/confirm", action == null ? "NOTHING_PENDING" : "EXPIRED");
      api.sendMessage(chatId, action == null ? "nothing pending to confirm" : "confirmation window expired — reissue the command");
      return;
    }
    switch (action.command()) {
      case "/pause" -> {
        riskSettings.upsert(RiskService.KILL_SWITCH, "{\"enabled\":true}");
        audit(chatId, "/pause", "EXECUTED");
        api.sendMessage(chatId, "paused — kill switch ON, no new paper entries");
      }
      case "/resume" -> {
        riskSettings.upsert(RiskService.KILL_SWITCH, "{\"enabled\":false}");
        audit(chatId, "/resume", "EXECUTED");
        api.sendMessage(chatId, "resumed — kill switch OFF");
      }
      default -> {
        int closed = paper.markToCloseIntraday();
        audit(chatId, "/flatten", "EXECUTED: closed " + closed);
        api.sendMessage(chatId, "flattened — closed " + closed + " open paper position(s) at mark");
      }
    }
  }

  private String status() {
    StringBuilder out = new StringBuilder();
    out.append("data canary: ").append(dataCanaryLine()).append('\n');
    DotHealthCanary.DotHealth dots = dotHealth.evaluate();
    long dead =
        dots.dots().stream().filter(d -> d.required() && !d.alive()).count();
    out.append("dot canary: ")
        .append(dead == 0 ? "required dots alive" : dead + " REQUIRED dot(s) DEAD")
        .append(" (").append(dots.rowsInspected()).append(" rejections inspected)\n");
    out.append("entries: ").append(risk.entryAllowed() ? "allowed" : "BLOCKED (kill switch / risk cap)").append('\n');
    out.append("open paper positions: ").append(paper.openPositions().size());
    return out.toString();
  }

  private String dataCanaryLine() {
    try {
      JsonNode health =
          marketData.get().uri("/api/v1/market/health/data").retrieve().body(JsonNode.class);
      if (health == null) {
        return "unreachable";
      }
      return health.path("status").asText("?")
          + (health.path("marketOpen").asBoolean(false) ? "" : " (market closed)")
          + ", " + health.path("tickedTokens").asInt(0) + " tokens";
    } catch (RuntimeException e) {
      return "unreachable (" + e.getMessage() + ")";
    }
  }

  @SuppressWarnings("unchecked")
  private String pnl() {
    Map<String, Object> summary = (Map<String, Object>) paper.pnl().get("summary");
    return "realized total: ₹" + summary.get("realizedTotal")
        + "\ntrades: " + summary.get("trades")
        + "\nwin rate: " + orDash(summary.get("winRate"))
        + "\nexpectancy: ₹" + orDash(summary.get("expectancy"));
  }

  private String positions() {
    List<PaperService.PositionDto> open = paper.openPositions();
    if (open.isEmpty()) {
      return "no open paper positions";
    }
    StringBuilder out = new StringBuilder("open paper positions:\n");
    for (PaperService.PositionDto p : open) {
      out.append(p.side()).append(' ').append(p.tradingsymbol())
          .append(" x").append(p.qty())
          .append(" @ ").append(p.avgEntryPrice())
          .append(" (uPnL ").append(orDash(p.unrealizedPnl())).append(")\n");
    }
    return out.toString().stripTrailing();
  }

  private static String orDash(Object v) {
    return v == null ? "—" : String.valueOf(v);
  }

  private static String help() {
    return "commands: /status /pnl /positions · guarded: /pause /resume /flatten (then /confirm within 60s)";
  }

  private void reply(String chatId, String command, String text) {
    audit(chatId, command, "OK");
    api.sendMessage(chatId, text);
  }

  private void audit(String chatId, String command, String outcome) {
    try {
      jdbc.update(
          "INSERT INTO bot_commands (chat_id, command, outcome) VALUES (?, ?, ?)",
          chatId, command, outcome);
    } catch (RuntimeException e) {
      log.warn("bot command audit failed: {}", e.getMessage());
    }
  }
}
