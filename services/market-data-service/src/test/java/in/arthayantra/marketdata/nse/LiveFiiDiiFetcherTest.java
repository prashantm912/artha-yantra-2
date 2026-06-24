package in.arthayantra.marketdata.nse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.feeds.FiiDiiFetcher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parses the spike-captured NSE FII/DII JSON into typed rows. */
class LiveFiiDiiFetcherTest {

  private static final String JSON =
      "[{\"buyValue\":\"18877.03\",\"category\":\"DII\",\"date\":\"12-Jun-2026\","
          + "\"netValue\":\"5341.29\",\"sellValue\":\"13535.74\"},"
          + "{\"buyValue\":\"12064.61\",\"category\":\"FII/FPI\",\"date\":\"12-Jun-2026\","
          + "\"netValue\":\"-1082.18\",\"sellValue\":\"13146.79\"}]";

  @Test
  void parsesNseFiiDiiJson() {
    FiiDiiFetcher fetcher = new LiveFiiDiiFetcher(new StubClient(JSON), new ObjectMapper());

    List<FiiDiiFetcher.FiiDiiRow> rows = fetcher.fetchLatest();

    assertThat(rows).hasSize(2);
    FiiDiiFetcher.FiiDiiRow fii =
        rows.stream().filter(r -> r.category().equals("FII/FPI")).findFirst().orElseThrow();
    assertThat(fii.date()).isEqualTo(LocalDate.of(2026, 6, 12));
    assertThat(fii.buy()).isEqualByComparingTo(new BigDecimal("12064.61"));
    assertThat(fii.sell()).isEqualByComparingTo(new BigDecimal("13146.79"));
    assertThat(fii.net()).isEqualByComparingTo(new BigDecimal("-1082.18"));
  }

  /** NseHttpClient stub returning canned JSON (subclass; the client is concrete). */
  static class StubClient extends NseHttpClient {
    private final String body;

    StubClient(String body) {
      super(org.springframework.web.client.RestClient.builder(), "https://x");
      this.body = body;
    }

    @Override
    public String get(String path) {
      return body;
    }
  }
}
