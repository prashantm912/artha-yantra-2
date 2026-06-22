package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.marketdata.constituents.StaticIndexConstituents;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Futures OI Buzz heatmap (oipulse "Futures Heatmap"): the constituent % change map + its selector. */
@RestController
@RequestMapping("/api/v1/market/futures")
public class OiBuzzController {

  private final OiBuzzService service;
  private final StaticIndexConstituents constituents;

  public OiBuzzController(OiBuzzService service, StaticIndexConstituents constituents) {
    this.service = service;
    this.constituents = constituents;
  }

  /** The index selector options (the seeded constituent reference keys). */
  @GetMapping("/oi-buzz-indices")
  public Map<String, Object> indices() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("items", List.copyOf(constituents.indices()));
    return response;
  }

  /** The constituent heatmap for one index: tiles (gainers first) + advance/decline + feed time. */
  @GetMapping("/oi-buzz-heatmap")
  public OiBuzzService.Heatmap heatmap(@RequestParam String name) {
    return service.heatmap(name);
  }
}
