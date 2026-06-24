package in.arthayantra.marketdata.upstox.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;

/**
 * Builds the Upstox v3 WS control message JSON (Wave U3) —
 * {@code {"guid":"<id>","method":"sub","data":{"mode":"<mode>","instrumentKeys":[...]}}} for a
 * subscribe, and the {@code mode}-less {@code unsub} variant for an unsubscribe. The message is sent
 * over the socket as a BINARY frame (the verified v3 protocol quirk — the proven OpenAlgo reference
 * encodes the JSON to UTF-8 and sends it with the binary opcode). Pure + deterministic given an
 * injected guid, so the exact wire shape is unit-asserted without a socket.
 */
public final class UpstoxSubscriptionMessage {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private UpstoxSubscriptionMessage() {}

  /** A fresh 20-char guid (Upstox caps the guid length; the reference truncates a dashless UUID). */
  public static String newGuid() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  /** The {@code sub} control message for {@code instrumentKeys} in {@code mode}. */
  public static byte[] subscribe(String guid, String mode, List<String> instrumentKeys) {
    ObjectNode root = base(guid, "sub", instrumentKeys);
    ((ObjectNode) root.get("data")).put("mode", mode);
    return toBytes(root);
  }

  /** The {@code unsub} control message for {@code instrumentKeys} (no {@code mode}). */
  public static byte[] unsubscribe(String guid, List<String> instrumentKeys) {
    return toBytes(base(guid, "unsub", instrumentKeys));
  }

  private static ObjectNode base(String guid, String method, List<String> instrumentKeys) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("guid", guid);
    root.put("method", method);
    ObjectNode data = root.putObject("data");
    ArrayNode keys = data.putArray("instrumentKeys");
    instrumentKeys.forEach(keys::add);
    return root;
  }

  private static byte[] toBytes(ObjectNode root) {
    try {
      return MAPPER.writeValueAsBytes(root);
    } catch (JsonProcessingException e) {
      // Building a tiny ObjectNode of strings cannot fail; surface defensively rather than swallow.
      throw new IllegalStateException("failed to serialise Upstox subscription message", e);
    }
  }
}
