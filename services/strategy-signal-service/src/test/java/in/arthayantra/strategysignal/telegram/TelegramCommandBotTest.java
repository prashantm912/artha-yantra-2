package in.arthayantra.strategysignal.telegram;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.paper.PaperService;
import in.arthayantra.strategysignal.paper.RiskService;
import in.arthayantra.strategysignal.paper.RiskSettingsRepository;
import in.arthayantra.strategysignal.signals.DotHealthCanary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * F6 safety posture: unknown chats are ignored silently, mutating commands execute only after a
 * fresh /confirm, pre-boot updates drain unprocessed, and every accepted command audits.
 */
class TelegramCommandBotTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String OWNER_CHAT = "111222333";

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-06-10T05:30:00Z"));
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  private final TelegramApi api = mock(TelegramApi.class);
  private final PaperService paper = mock(PaperService.class);
  private final RiskService risk = mock(RiskService.class);
  private final RiskSettingsRepository riskSettings = mock(RiskSettingsRepository.class);
  private final DotHealthCanary dotHealth = mock(DotHealthCanary.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

  private TelegramCommandBot bot(boolean enabled) {
    return new TelegramCommandBot(
        api, paper, risk, riskSettings, dotHealth, jdbc, RestClient.builder(), clock,
        "http://localhost:1", enabled, OWNER_CHAT);
  }

  private static JsonNode update(long id, String chatId, String text) {
    try {
      return MAPPER.readTree(
          "{\"update_id\":" + id + ",\"message\":{\"chat\":{\"id\":" + chatId + "},\"text\":\""
              + text + "\"}}");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void unknownChatIsIgnoredSilently() {
    bot(true).handle(update(1, "999", "/flatten"));
    verifyNoInteractions(paper, riskSettings, jdbc);
    verify(api, never()).sendMessage(anyString(), anyString());
  }

  @Test
  void mutatingCommandsExecuteOnlyAfterFreshConfirm() {
    TelegramCommandBot bot = bot(true);

    bot.handle(update(1, OWNER_CHAT, "/flatten"));
    verify(api).sendMessage(eq(OWNER_CHAT), contains("/confirm within 60s"));
    verify(paper, never()).markToCloseIntraday();

    when(paper.markToCloseIntraday()).thenReturn(3);
    bot.handle(update(2, OWNER_CHAT, "/confirm"));
    verify(paper).markToCloseIntraday();
    verify(api).sendMessage(eq(OWNER_CHAT), contains("closed 3"));
    verify(jdbc).update(anyString(), eq(OWNER_CHAT), eq("/flatten"), eq("EXECUTED: closed 3"));

    // a second bare /confirm has nothing pending
    bot.handle(update(3, OWNER_CHAT, "/confirm"));
    verify(api).sendMessage(eq(OWNER_CHAT), contains("nothing pending"));
    verify(paper, times(1)).markToCloseIntraday();
  }

  @Test
  void expiredConfirmDoesNothing() {
    TelegramCommandBot bot = bot(true);
    bot.handle(update(1, OWNER_CHAT, "/pause"));
    now.set(now.get().plusSeconds(61));
    bot.handle(update(2, OWNER_CHAT, "/confirm"));
    verify(riskSettings, never()).upsert(anyString(), anyString());
    verify(api).sendMessage(eq(OWNER_CHAT), contains("expired"));
  }

  @Test
  void pauseAndResumeFlipTheRiskKillSwitch() {
    TelegramCommandBot bot = bot(true);
    bot.handle(update(1, OWNER_CHAT, "/pause"));
    bot.handle(update(2, OWNER_CHAT, "/confirm"));
    verify(riskSettings).upsert(RiskService.KILL_SWITCH, "{\"enabled\":true}");

    bot.handle(update(3, OWNER_CHAT, "/resume"));
    bot.handle(update(4, OWNER_CHAT, "/confirm"));
    verify(riskSettings).upsert(RiskService.KILL_SWITCH, "{\"enabled\":false}");
  }

  @Test
  void statusRepliesWithCanariesAndAudits() {
    when(dotHealth.evaluate())
        .thenReturn(
            new DotHealthCanary.DotHealth(
                "t", true, 40,
                List.of(new DotHealthCanary.DotState("breadth", true, true, "ok"))));
    when(risk.entryAllowed()).thenReturn(true);
    when(paper.openPositions()).thenReturn(List.of());

    bot(true).handle(update(1, OWNER_CHAT, "/status"));

    verify(api).sendMessage(eq(OWNER_CHAT), contains("required dots alive"));
    verify(jdbc).update(anyString(), eq(OWNER_CHAT), eq("/status"), eq("OK"));
  }

  @Test
  void pnlFormatsTheSummary() {
    when(paper.pnl())
        .thenReturn(
            Map.of("points", List.of(), "summary",
                Map.of("realizedTotal", "1234.50", "trades", 4, "winRate", "0.75", "expectancy", "308.62")));
    bot(true).handle(update(1, OWNER_CHAT, "/pnl"));
    verify(api).sendMessage(eq(OWNER_CHAT), contains("1234.50"));
  }

  @Test
  void preBootUpdatesDrainUnprocessed() {
    when(api.configured()).thenReturn(true);
    // first poll: one STALE /flatten queued from before boot — acknowledged, never executed
    when(api.getUpdates(anyLong()))
        .thenReturn(List.of(update(10, OWNER_CHAT, "/flatten")))
        .thenReturn(List.of(update(11, OWNER_CHAT, "/status")));
    when(dotHealth.evaluate())
        .thenReturn(new DotHealthCanary.DotHealth("t", true, 0, List.of()));
    when(paper.openPositions()).thenReturn(List.of());

    TelegramCommandBot bot = bot(true);
    bot.poll();
    verify(api, never()).sendMessage(anyString(), anyString()); // stale /flatten NOT staged

    bot.poll();
    verify(api).getUpdates(11); // offset advanced past the drained update
    verify(api).sendMessage(eq(OWNER_CHAT), contains("open paper positions")); // live /status handled
  }

  @Test
  void disabledFlagKeepsTheBotFullyDormant() {
    when(api.configured()).thenReturn(true);
    bot(false).poll();
    verify(api, never()).getUpdates(anyLong());
  }

  @Test
  void unknownCommandGetsHelp() {
    bot(true).handle(update(1, OWNER_CHAT, "/wat"));
    verify(api).sendMessage(eq(OWNER_CHAT), contains("/pause /resume /flatten"));
  }

}
