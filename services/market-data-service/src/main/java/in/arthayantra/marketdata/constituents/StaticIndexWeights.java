package in.arthayantra.marketdata.constituents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Static index → (symbol → free-float weight %) reference, seeded once from the niftyindices index
 * factsheet weightage ({@code reference/index-weights.json}; NIFTY 50 / BANK / 200, rebalance dated in
 * the seed). Drives the Index Contribution page (contribution = weight × % change). Weights drift on
 * the quarterly rebalance — re-seed periodically.
 */
@Component
public class StaticIndexWeights {

  private final Map<String, Map<String, BigDecimal>> byIndex;

  public StaticIndexWeights(ObjectMapper mapper) {
    try (InputStream in = new ClassPathResource("reference/index-weights.json").getInputStream()) {
      this.byIndex =
          mapper.readValue(
              in, new TypeReference<LinkedHashMap<String, Map<String, BigDecimal>>>() {});
    } catch (IOException e) {
      throw new IllegalStateException("cannot load reference/index-weights.json", e);
    }
  }

  /** Symbol → weight% for an index, or an empty map when the index has no seeded weights. */
  public Map<String, BigDecimal> weights(String index) {
    return byIndex.getOrDefault(index, Map.of());
  }
}
