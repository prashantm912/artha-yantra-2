package in.arthayantra.marketdata.constituents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Static index → constituent-symbols reference, seeded once from the NSE index factsheets
 * ({@code reference/index-constituents.json}). Drives the Futures OI Buzz heatmap's constituent set
 * without depending on accrued membership snapshots (the owner chose a static reference to avoid the
 * survivorship-bias / pre-accrual gaps of {@link IndexConstituentsController}). Symbols that lack a
 * monthly future are filtered downstream, so a non-F&O member is harmless.
 */
@Component
public class StaticIndexConstituents {

  private final Map<String, List<String>> byIndex;

  /** Loads the classpath reference once at startup; a missing/garbled file is a hard boot failure. */
  public StaticIndexConstituents(ObjectMapper mapper) {
    try (InputStream in = new ClassPathResource("reference/index-constituents.json").getInputStream()) {
      this.byIndex = mapper.readValue(in, new TypeReference<LinkedHashMap<String, List<String>>>() {});
    } catch (IOException e) {
      throw new IllegalStateException("cannot load reference/index-constituents.json", e);
    }
  }

  /** The indices with a seeded constituent list (the OI Buzz selector options). */
  public Set<String> indices() {
    return byIndex.keySet();
  }

  /** Constituent symbols for an index, or an empty list when the index is unknown. */
  public List<String> symbols(String index) {
    return byIndex.getOrDefault(index, List.of());
  }
}
