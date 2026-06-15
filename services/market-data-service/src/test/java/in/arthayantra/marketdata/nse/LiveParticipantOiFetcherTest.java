package in.arthayantra.marketdata.nse;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.nse.ParticipantOiFetcher.ParticipantOiRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Parses the spike-captured NSE participant-OI CSV into typed rows. */
class LiveParticipantOiFetcherTest {

  // The real fao_participant_oi CSV shape: title (date) line, header line, then one row per type.
  private static final String CSV =
      "\"\"Participant wise Open Interest (no. of contracts) in Equity Derivatives as on Jun 12,"
          + " 2026\"\",,,,,,,,,,,,,,\n"
          + "Client Type,Future Index Long,Future Index Short,Future Stock Long,Future Stock Short,"
          + "Option Index Call Long,Option Index Put Long,Option Index Call Short,Option Index Put"
          + " Short,Option Stock Call Long,Option Stock Put Long,Option Stock Call Short,Option"
          + " Stock Put Short,Total Long Contracts,Total Short Contracts\n"
          + "Client,258005,77605,3198550,218213,2499577,3015372,2476544,3727671,2327573,714466,"
          + "1298216,1093239,12013543,8891488\n"
          + "DII,75317,13508,391708,4699485,10634,18986,400,51,768,33521,390751,12639,530934,"
          + "5116834\n"
          + "FII,39971,283594,4122991,3339091,623418,1194064,832438,651452,211652,314663,359343,"
          + "202290,6506758,5668207\n"
          + "Pro,56144,54730,927953,384413,1054697,1441958,878944,1291206,1057309,1090295,1548992,"
          + "844777,5628357,5003062\n"
          + "TOTAL,429437,429437,8641202,8641202,4188325,5670380,4188325,5670380,3597302,2152945,"
          + "3597302,2152945,24679592,24679592\n";

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T13:30:00Z"), ZoneOffset.UTC);

  @Test
  void parsesNseParticipantOiCsv() {
    ParticipantOiFetcher fetcher =
        new LiveParticipantOiFetcher(new StubClient(CSV), "https://archives", CLOCK);

    List<ParticipantOiRow> rows = fetcher.fetchLatest();

    assertThat(rows).hasSize(5);
    ParticipantOiRow fii =
        rows.stream().filter(r -> r.clientType().equals("FII")).findFirst().orElseThrow();
    assertThat(fii.date()).isEqualTo(LocalDate.of(2026, 6, 12));
    assertThat(fii.futureIndexLong()).isEqualTo(39971L);
    assertThat(fii.futureIndexShort()).isEqualTo(283594L);
    assertThat(fii.optionIndexCallLong()).isEqualTo(623418L);
    assertThat(fii.optionIndexPutShort()).isEqualTo(651452L);
    assertThat(fii.totalLongContracts()).isEqualTo(6506758L);
    assertThat(fii.totalShortContracts()).isEqualTo(5668207L);

    ParticipantOiRow dii =
        rows.stream().filter(r -> r.clientType().equals("DII")).findFirst().orElseThrow();
    assertThat(dii.futureStockShort()).isEqualTo(4699485L);
  }

  /** NseHttpClient stub returning canned CSV for any archive URL. */
  static class StubClient extends NseHttpClient {
    private final String body;

    StubClient(String body) {
      super(RestClient.builder(), "https://x");
      this.body = body;
    }

    @Override
    public String getAbsolute(String url) {
      return body;
    }
  }
}
