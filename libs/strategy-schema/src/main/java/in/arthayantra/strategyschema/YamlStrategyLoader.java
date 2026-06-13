package in.arthayantra.strategyschema;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * YAML → tree loading under the CD-5 posture: SnakeYAML in {@link SafeConstructor} mode (no
 * arbitrary type instantiation), 256 KB byte cap, alias-bomb guard. The result is a Jackson tree
 * built through {@link CanonicalJson} so number rendering is already canonical.
 */
public final class YamlStrategyLoader {

  /** The gateway-aligned body cap (COMMON §12.4). */
  public static final int MAX_BYTES = 256 * 1024;

  private YamlStrategyLoader() {}

  /** Parses a strategy YAML document into a canonical-number Jackson tree. */
  public static JsonNode load(String yaml) {
    if (yaml == null || yaml.isBlank()) {
      throw new StrategyParseException("document is empty");
    }
    if (yaml.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new StrategyParseException("document exceeds the 256 KB limit");
    }
    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(50);
    options.setAllowRecursiveKeys(false);
    // a duplicate mapping key collapses last-wins at parse time, so the canonical tree + checksum
    // would certify a value the author never saw. Reject it (DuplicateKeyException is a
    // RuntimeException, caught and wrapped below).
    options.setAllowDuplicateKeys(false);
    options.setCodePointLimit(MAX_BYTES);
    Object root;
    try {
      root = new Yaml(new SafeConstructor(options)).load(yaml);
    } catch (RuntimeException e) {
      throw new StrategyParseException("unparseable YAML: " + firstLine(e.getMessage()), e);
    }
    if (!(root instanceof Map)) {
      throw new StrategyParseException("top level must be a mapping");
    }
    try {
      return CanonicalJson.toJsonNode(root);
    } catch (StrategyParseException e) {
      throw e;
    } catch (RuntimeException e) {
      // toJsonNode runs OUTSIDE the load() guard above; a non-finite scalar (or any other
      // construction surprise) must surface as a clean parse error, never an unhandled 500.
      throw new StrategyParseException("unparseable YAML: " + firstLine(e.getMessage()), e);
    }
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "(no detail)";
    }
    int nl = message.indexOf('\n');
    return nl < 0 ? message : message.substring(0, nl);
  }
}
