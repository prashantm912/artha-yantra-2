package in.arthayantra.marketdata.bse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.bse.BseCorporateActionFetcher.BseCaRecord;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Parses the BSE DefaultData/w corporate-actions JSON into BseCaRecords. */
class LiveBseCorporateActionFetcherTest {

  private static final String JSON =
      """
      [
        {"scrip_code":544726,"short_name":"KOTYARK","Ex_date":"24 Jun 2026","Purpose":"Bonus issue 10:1","exdate":"20260624","long_name":"Kotyark Industries Ltd"},
        {"scrip_code":500570,"short_name":"TATAMOTORS","Purpose":"Stock  Split From Rs.10/- to Rs.2/-","exdate":"20260601","long_name":"Tata Motors"},
        {"scrip_code":500001,"short_name":"NODATE","Purpose":"Dividend - Rs. 2.5","exdate":""}
      ]
      """;

  @Test
  void parsesScripExDateAndPurposeAndSkipsRowsWithoutExDate() {
    BseCorporateActionFetcher fetcher =
        new LiveBseCorporateActionFetcher(new StubBse(JSON), "https://api.x", new ObjectMapper());

    List<BseCaRecord> rows =
        fetcher.fetchRecent(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

    assertThat(rows).extracting(BseCaRecord::shortName).containsExactly("KOTYARK", "TATAMOTORS");
    BseCaRecord bonus = rows.get(0);
    assertThat(bonus.scripCode()).isEqualTo("544726");
    assertThat(bonus.exDate()).isEqualTo(LocalDate.of(2026, 6, 24));
    assertThat(bonus.purpose()).isEqualTo("Bonus issue 10:1");
  }

  /** BseHttpClient stub returning canned JSON for any URL. */
  static class StubBse extends BseHttpClient {
    private final String body;

    StubBse(String body) {
      super(RestClient.builder(), "https://x");
      this.body = body;
    }

    @Override
    public String getAbsolute(String url) {
      return body;
    }
  }
}
