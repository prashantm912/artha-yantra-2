package in.arthayantra.marketdata.constituents;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Mock fetcher: the bundled {@code ind_nifty100list.csv} in NSE's published format
 * ({@code Company Name,Industry,Symbol,Series,ISIN Code}) — every symbol resolves in the CD-14
 * mock instrument master, so the credential-free path exercises the same shape as the live NSE
 * fetcher.
 */
@Component
@Profile("!live")
public class MockIndexConstituentsFetcher implements IndexConstituentsFetcher {

  private static final String RESOURCE = "/nse-constituents/ind_nifty100list.csv";

  @Override
  public Optional<List<Constituent>> fetch(String indexName) {
    if (!"NIFTY 100".equalsIgnoreCase(indexName)) {
      return Optional.empty();
    }
    try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("mock constituents fixture missing: " + RESOURCE);
      }
      List<Constituent> rows = new ArrayList<>();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line = reader.readLine(); // header
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          String[] cells = line.split(",", -1);
          if (cells.length < 4 || !"EQ".equalsIgnoreCase(cells[3].trim())) {
            continue;
          }
          rows.add(new Constituent("NSE", cells[2].trim().toUpperCase(Locale.ROOT)));
        }
      }
      return Optional.of(List.copyOf(rows));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read mock constituents fixture", e);
    }
  }
}
