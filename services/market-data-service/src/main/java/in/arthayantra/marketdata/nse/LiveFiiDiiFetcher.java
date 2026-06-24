package in.arthayantra.marketdata.nse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.feeds.FiiDiiFetcher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Parses NSE {@code /api/fiidiiTradeReact} (B-1b). */
@Component
@Profile("live")
public class LiveFiiDiiFetcher implements FiiDiiFetcher {

  private static final DateTimeFormatter NSE_DATE =
      DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

  private final NseHttpClient client;
  private final ObjectMapper mapper;

  public LiveFiiDiiFetcher(NseHttpClient client, ObjectMapper mapper) {
    this.client = client;
    this.mapper = mapper;
  }

  @Override
  public List<FiiDiiRow> fetchLatest() {
    try {
      JsonNode arr = mapper.readTree(client.get("/api/fiidiiTradeReact"));
      List<FiiDiiRow> rows = new ArrayList<>();
      for (JsonNode n : arr) {
        rows.add(
            new FiiDiiRow(
                LocalDate.parse(n.path("date").asText(), NSE_DATE),
                n.path("category").asText(),
                new BigDecimal(n.path("buyValue").asText()),
                new BigDecimal(n.path("sellValue").asText()),
                new BigDecimal(n.path("netValue").asText())));
      }
      return rows;
    } catch (Exception parseFailed) {
      throw new IllegalStateException("NSE FII/DII fetch/parse failed: " + parseFailed.getMessage(), parseFailed);
    }
  }
}
