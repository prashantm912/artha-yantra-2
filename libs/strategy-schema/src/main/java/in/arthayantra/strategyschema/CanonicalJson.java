package in.arthayantra.strategyschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The D18 canonical form: object keys in lexicographic order, numbers rendered in normalized
 * plain notation (no exponent, no trailing zeros — {@code 1.20} and {@code 1.2} canonicalize
 * identically, {@code 2.0} renders as {@code 2}), strings JSON-escaped. The SHA-256 over the
 * canonical UTF-8 bytes is the immutability/dedupe guard; the golden requirement is that
 * re-canonicalizing — including after key re-ordering — yields an identical hash.
 */
public final class CanonicalJson {

  private CanonicalJson() {}

  /** Converts a SafeConstructor object graph to a Jackson tree with canonical number nodes. */
  public static JsonNode toJsonNode(Object value) {
    if (value == null) {
      return NullNode.getInstance();
    }
    if (value instanceof Map<?, ?> map) {
      ObjectNode node = JsonNodeFactory.instance.objectNode();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() == null) {
          throw new StrategyParseException("null mapping keys are not allowed");
        }
        node.set(String.valueOf(entry.getKey()), toJsonNode(entry.getValue()));
      }
      return node;
    }
    if (value instanceof List<?> list) {
      ArrayNode node = JsonNodeFactory.instance.arrayNode(list.size());
      for (Object item : list) {
        node.add(toJsonNode(item));
      }
      return node;
    }
    if (value instanceof Boolean b) {
      return BooleanNode.valueOf(b);
    }
    if (value instanceof Integer || value instanceof Long || value instanceof Short
        || value instanceof Byte) {
      return LongNode.valueOf(((Number) value).longValue());
    }
    if (value instanceof BigInteger bi) {
      return BigIntegerNode.valueOf(bi);
    }
    if (value instanceof Double || value instanceof Float) {
      return DecimalNode.valueOf(normalize(BigDecimal.valueOf(((Number) value).doubleValue())));
    }
    if (value instanceof BigDecimal bd) {
      return DecimalNode.valueOf(normalize(bd));
    }
    if (value instanceof Date date) {
      // SafeConstructor resolves unquoted ISO timestamps; canonical form is the ISO instant string
      return TextNode.valueOf(DateTimeFormatter.ISO_INSTANT.format(date.toInstant()));
    }
    if (value instanceof String s) {
      return TextNode.valueOf(s);
    }
    if (value instanceof byte[]) {
      throw new StrategyParseException("binary YAML content is not allowed");
    }
    return TextNode.valueOf(String.valueOf(value));
  }

  /** Normalized number form: trailing zeros stripped, never negative scale (no exponents). */
  static BigDecimal normalize(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }

  /** Writes the canonical JSON text for a tree. */
  public static String write(JsonNode node) {
    StringBuilder sb = new StringBuilder(256);
    append(node, sb);
    return sb.toString();
  }

  /** SHA-256 hex (lowercase) over the canonical UTF-8 bytes. */
  public static String sha256Hex(String canonicalJson) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void append(JsonNode node, StringBuilder sb) {
    if (node.isObject()) {
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      node.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
      sb.append('{');
      boolean first = true;
      for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        sb.append(TextNode.valueOf(entry.getKey()).toString()).append(':');
        append(entry.getValue(), sb);
      }
      sb.append('}');
      return;
    }
    if (node.isArray()) {
      sb.append('[');
      List<JsonNode> items = new ArrayList<>(node.size());
      node.forEach(items::add);
      for (int i = 0; i < items.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        append(items.get(i), sb);
      }
      sb.append(']');
      return;
    }
    if (node.isTextual()) {
      sb.append(TextNode.valueOf(node.textValue()).toString());
      return;
    }
    if (node.isNumber()) {
      if (node.isIntegralNumber()) {
        sb.append(node.bigIntegerValue().toString());
      } else {
        sb.append(normalize(node.decimalValue()).toPlainString());
      }
      return;
    }
    if (node.isBoolean()) {
      sb.append(node.booleanValue());
      return;
    }
    sb.append("null");
  }
}
