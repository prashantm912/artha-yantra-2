package in.arthayantra.gateway.ws;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal STOMP-subset frame codec (CD-3): CONNECT/STOMP, CONNECTED, SUBSCRIBE, UNSUBSCRIBE,
 * MESSAGE, DISCONNECT, ERROR + EOL heartbeats — sufficient for {@code @stomp/stompjs}. The
 * servlet-only {@code @EnableWebSocketMessageBroker} is deliberately NOT used (the gateway is
 * WebFlux).
 */
public record StompFrame(String command, Map<String, String> headers, String body) {

  private static final char NUL = '\0';

  /** True when the raw text is a bare EOL heartbeat, not a frame. */
  public static boolean isHeartbeat(String raw) {
    return raw.isEmpty() || raw.equals("\n") || raw.equals("\r\n");
  }

  /** Parses one frame from wire text (without enclosing transport framing). */
  public static StompFrame parse(String raw) {
    String text = raw;
    int nul = text.indexOf(NUL);
    if (nul >= 0) {
      text = text.substring(0, nul);
    }
    String[] lines = text.split("\n", -1);
    String command = lines[0].trim();
    Map<String, String> headers = new LinkedHashMap<>();
    int i = 1;
    for (; i < lines.length; i++) {
      String line = lines[i].replace("\r", "");
      if (line.isEmpty()) {
        i++;
        break;
      }
      int colon = line.indexOf(':');
      if (colon > 0) {
        headers.putIfAbsent(line.substring(0, colon), line.substring(colon + 1));
      }
    }
    StringBuilder body = new StringBuilder();
    for (; i < lines.length; i++) {
      if (body.length() > 0) {
        body.append('\n');
      }
      body.append(lines[i]);
    }
    return new StompFrame(command, headers, body.toString());
  }

  /** Serializes to wire text including the trailing NUL. */
  public String serialize() {
    StringBuilder out = new StringBuilder(command).append('\n');
    headers.forEach((k, v) -> out.append(k).append(':').append(v).append('\n'));
    return out.append('\n').append(body).append(NUL).toString();
  }

  /** Convenience builder. */
  public static StompFrame of(String command, Map<String, String> headers, String body) {
    return new StompFrame(command, new LinkedHashMap<>(headers), body == null ? "" : body);
  }
}
