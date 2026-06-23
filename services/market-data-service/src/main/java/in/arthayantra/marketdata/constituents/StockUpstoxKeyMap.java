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
 * Static stock symbol → Upstox instrument key ({@code NSE_EQ|<ISIN>}) reference, seeded once from the
 * NSE index factsheets' ISIN column ({@code reference/stock-upstox-keys.json}, ~500 symbols). Lets the
 * Upstox news feed ({@code GET /v2/news}) be requested by our symbols. Unknown symbol → {@code null}.
 */
@Component
public class StockUpstoxKeyMap {

  private final Map<String, String> bySymbol;

  public StockUpstoxKeyMap(ObjectMapper mapper) {
    try (InputStream in = new ClassPathResource("reference/stock-upstox-keys.json").getInputStream()) {
      this.bySymbol = mapper.readValue(in, new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (IOException e) {
      throw new IllegalStateException("cannot load reference/stock-upstox-keys.json", e);
    }
  }

  /** The Upstox instrument key for a stock symbol, or {@code null} when not in the reference. */
  public String key(String symbol) {
    return bySymbol.get(symbol.trim().toUpperCase());
  }
}
