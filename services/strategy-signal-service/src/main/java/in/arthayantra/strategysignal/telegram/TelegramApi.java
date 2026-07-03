package in.arthayantra.strategysignal.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin Telegram Bot-API client for the command bot (roadmap F6): {@code getUpdates} short-poll +
 * {@code sendMessage}. Hand-rolled {@code RestClient} like every other outbound integration (no
 * SDK); the base URL is configurable so tests WireMock it. The token is the SAME
 * {@code artha.notifier.telegram-bot-token} the one-way notifier uses — one bot, alerts + commands.
 */
@Component
public class TelegramApi {

  private static final Logger log = LoggerFactory.getLogger(TelegramApi.class);

  private final RestClient http;
  private final ObjectMapper objectMapper;
  private final String token;

  /** Wires the configurable API base (tests point it at WireMock) + the shared bot token. */
  public TelegramApi(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      @Value("${artha.telegram.api-base:https://api.telegram.org}") String apiBase,
      @Value("${artha.notifier.telegram-bot-token:}") String token) {
    this.http = builder.baseUrl(apiBase).build();
    this.objectMapper = objectMapper;
    this.token = token;
  }

  /** True when a token is configured (the bot is otherwise fully dormant). */
  public boolean configured() {
    return !token.isBlank();
  }

  /**
   * One {@code getUpdates} poll (timeout 0 — plain short poll every sweep). Returns the raw
   * {@code result} array; empty on any failure (the loop just tries again next sweep).
   */
  public List<JsonNode> getUpdates(long offset) {
    try {
      String body =
          http.get()
              .uri("/bot{token}/getUpdates?offset={offset}&timeout=0", token, offset)
              .retrieve()
              .body(String.class);
      JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
      JsonNode result = root.path("result");
      return result.isArray()
          ? java.util.stream.StreamSupport.stream(result.spliterator(), false).toList()
          : List.of();
    } catch (Exception e) {
      log.warn("telegram getUpdates failed: {}", e.getMessage());
      return List.of();
    }
  }

  /** Sends one plain-text reply; failures log and never propagate (a lost reply is harmless). */
  public void sendMessage(String chatId, String text) {
    try {
      http.post()
          .uri("/bot{token}/sendMessage", token)
          .body(Map.of("chat_id", chatId, "text", text))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      log.warn("telegram sendMessage failed: {}", e.getMessage());
    }
  }
}
