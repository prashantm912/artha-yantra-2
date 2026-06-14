package in.arthayantra.strategysignal.registry;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Phase 44 universe-resolution surface: resolves a strategy version's universe to the pinned
 * ordered list + checksum (REST-only — no {@code marketdata} grant). Feeds the editor's "Published
 * Universe (as of …)" label and the backtest submission's pin-by-copy.
 */
@RestController
@RequestMapping("/api/v1/strategies")
public class UniverseController {

  private final RegistryService registry;
  private final UniverseResolver resolver;

  /** Wires the registry + resolver. */
  public UniverseController(RegistryService registry, UniverseResolver resolver) {
    this.registry = registry;
    this.resolver = resolver;
  }

  /** Resolves the universe of a strategy version (latest published/draft, or an explicit version). */
  @GetMapping("/{id}/universe")
  public Map<String, Object> universe(
      @PathVariable UUID id, @RequestParam(required = false) String version) {
    JsonNode config = (JsonNode) registry.detail(id, version).get("config");
    UniverseResolver.ResolvedUniverse u = resolver.resolve(config);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mode", u.mode());
    out.put("asOf", u.asOf());
    out.put("constituentCount", u.items().size());
    out.put("checksum", u.checksum());
    out.put("survivorshipCaveat", u.survivorshipCaveat());
    out.put("items", u.items());
    return out;
  }
}
