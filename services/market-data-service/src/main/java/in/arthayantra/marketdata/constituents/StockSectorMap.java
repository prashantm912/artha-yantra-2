package in.arthayantra.marketdata.constituents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Static stock → sector (NSE "Industry") reference, seeded once from the NSE index factsheets
 * ({@code reference/stock-sectors.json}, ~500 symbols). Drives the Industry column of the Equity
 * Returns screener and the Sector pages without a live sector feed. A symbol absent from the map
 * (a thin small-cap) simply has no sector ({@code null}).
 */
@Component
public class StockSectorMap {

  private final Map<String, String> bySymbol;

  public StockSectorMap(ObjectMapper mapper) {
    try (InputStream in = new ClassPathResource("reference/stock-sectors.json").getInputStream()) {
      this.bySymbol = mapper.readValue(in, new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (IOException e) {
      throw new IllegalStateException("cannot load reference/stock-sectors.json", e);
    }
  }

  /** The stock's sector, or {@code null} when the symbol is not in the reference. */
  public String sector(String symbol) {
    return bySymbol.get(symbol);
  }
}
