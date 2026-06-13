package in.arthayantra.strategysignal.registry;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SERVER-side structured diff between two canonical config trees (client-side diff is an
 * explicit FAIL — C-2.10). Paths use the alias/type selector style where array elements carry
 * them ({@code indicators[alias=ema_fast].params.period}), positional indices otherwise, so
 * diffs survive reordering exactly like optimizer paths do.
 */
public final class ConfigDiff {

  /** One structured operation. */
  public record Op(String path, String op, String before, String after) {}

  private ConfigDiff() {}

  /** Computes per-path add/remove/change operations. */
  public static List<Op> diff(JsonNode from, JsonNode to) {
    List<Op> ops = new ArrayList<>();
    walk(from, to, "", ops);
    return ops;
  }

  /** A short human-readable headline for the audit row. */
  public static String summary(List<Op> ops) {
    if (ops.isEmpty()) {
      return "no content change";
    }
    long changed = ops.stream().filter(o -> o.op().equals("change")).count();
    long added = ops.stream().filter(o -> o.op().equals("add")).count();
    long removed = ops.stream().filter(o -> o.op().equals("remove")).count();
    StringBuilder sb = new StringBuilder();
    sb.append(changed).append(" changed, ").append(added).append(" added, ")
        .append(removed).append(" removed");
    ops.stream().limit(3).forEach(o -> sb.append("; ").append(o.path()));
    return sb.toString();
  }

  private static void walk(JsonNode from, JsonNode to, String path, List<Op> ops) {
    if (from == null && to == null) {
      return;
    }
    if (from == null) {
      ops.add(new Op(path, "add", null, render(to)));
      return;
    }
    if (to == null) {
      ops.add(new Op(path, "remove", render(from), null));
      return;
    }
    if (from.isObject() && to.isObject()) {
      Set<String> keys = new LinkedHashSet<>();
      from.fieldNames().forEachRemaining(keys::add);
      to.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) {
        walk(from.get(key), to.get(key), path.isEmpty() ? key : path + "." + key, ops);
      }
      return;
    }
    if (from.isArray() && to.isArray()) {
      String selectorKey = selectorKey(from, to);
      if (selectorKey != null) {
        walkKeyedArray(from, to, path, selectorKey, ops);
      } else {
        int max = Math.max(from.size(), to.size());
        for (int i = 0; i < max; i++) {
          walk(
              i < from.size() ? from.get(i) : null,
              i < to.size() ? to.get(i) : null,
              path + "[" + i + "]",
              ops);
        }
      }
      return;
    }
    if (!from.equals(to)) {
      ops.add(new Op(path, "change", render(from), render(to)));
    }
  }

  /** alias for indicators-style arrays, type for exit-rules-style arrays; null otherwise. */
  private static String selectorKey(JsonNode from, JsonNode to) {
    for (String key : new String[] {"alias", "type"}) {
      if (allHaveUnique(from, key) && allHaveUnique(to, key)) {
        return key;
      }
    }
    return null;
  }

  private static boolean allHaveUnique(JsonNode array, String key) {
    if (array.isEmpty()) {
      return true;
    }
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode element : array) {
      if (!element.isObject() || !element.hasNonNull(key)) {
        return false;
      }
      if (!seen.add(element.get(key).asText())) {
        return false;
      }
    }
    return true;
  }

  private static void walkKeyedArray(
      JsonNode from, JsonNode to, String path, String selectorKey, List<Op> ops) {
    Set<String> keys = new LinkedHashSet<>();
    from.forEach(e -> keys.add(e.get(selectorKey).asText()));
    to.forEach(e -> keys.add(e.get(selectorKey).asText()));
    for (String key : keys) {
      walk(
          findBySelector(from, selectorKey, key),
          findBySelector(to, selectorKey, key),
          path + "[" + selectorKey + "=" + key + "]",
          ops);
    }
  }

  private static JsonNode findBySelector(JsonNode array, String selectorKey, String value) {
    for (JsonNode element : array) {
      if (value.equals(element.path(selectorKey).asText(null))) {
        return element;
      }
    }
    return null;
  }

  private static String render(JsonNode node) {
    return node.isTextual() ? node.textValue() : node.toString();
  }
}
