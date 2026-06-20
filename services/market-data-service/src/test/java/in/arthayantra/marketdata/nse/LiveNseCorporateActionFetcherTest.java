package in.arthayantra.marketdata.nse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.nse.NseCorporateActionFetcher.CaRecord;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Parses the NSE corporate-actions JSON into CaRecords; tolerates wrapping + missing ex-dates. */
class LiveNseCorporateActionFetcherTest {

  private static final String BARE_ARRAY =
      """
      [
        {"symbol":"CUB","series":"EQ","isin":"INE491A01021","subject":"Bonus 1:3","exDate":"12-Jun-2026"},
        {"symbol":"TRENT","series":"EQ","isin":"INE849A01020","subject":"Bonus 1:2","exDate":"04-Jun-2026"},
        {"symbol":"NODATE","series":"EQ","subject":"Annual General Meeting","exDate":"-"}
      ]
      """;

  @Test
  void parsesBareArrayAndSkipsRowsWithoutExDate() {
    NseCorporateActionFetcher fetcher =
        new LiveNseCorporateActionFetcher(new StubNse(BARE_ARRAY), "https://x", new ObjectMapper());

    List<CaRecord> rows =
        fetcher.fetchRecent(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

    assertThat(rows).extracting(CaRecord::symbol).containsExactly("CUB", "TRENT");
    CaRecord cub = rows.get(0);
    assertThat(cub.isin()).isEqualTo("INE491A01021");
    assertThat(cub.subject()).isEqualTo("Bonus 1:3");
    assertThat(cub.exDate()).isEqualTo(LocalDate.of(2026, 6, 12));
  }

  @Test
  void unwrapsDataEnvelope() {
    String wrapped = "{\"data\":" + BARE_ARRAY + "}";
    NseCorporateActionFetcher fetcher =
        new LiveNseCorporateActionFetcher(new StubNse(wrapped), "https://x", new ObjectMapper());

    assertThat(fetcher.fetchRecent(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
        .extracting(CaRecord::symbol)
        .containsExactly("CUB", "TRENT");
  }

  /** NseHttpClient stub returning canned JSON for the cookie-seeded call. */
  static class StubNse extends NseHttpClient {
    private final String body;

    StubNse(String body) {
      super(RestClient.builder(), "https://x");
      this.body = body;
    }

    @Override
    public String getWithCookieSeed(String apiPath, String referer) {
      return body;
    }
  }
}
