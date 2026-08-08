package in.arthayantra.marketdata.bse;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.bse.BseBhavcopyFetcher.BseBhavRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Parses the BSE UDiFF equity bhavcopy: header-name mapping, STK filter, quote-aware, content-sniff. */
class LiveBseBhavcopyFetcherTest {

  private static final String HEADER =
      "TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,"
          + "FininstrmActlXpryDt,StrkPric,OptnTp,FinInstrmNm,OpnPric,HghPric,LwPric,ClsPric,LastPric,"
          + "PrvsClsgPric,UndrlygPric,SttlmPric,OpnIntrst,ChngInOpnIntrst,TtlTradgVol,TtlTrfVal,"
          + "TtlNbOfTxsExctd,SsnId,NewBrdLotQty,Rmks,Rsvd1,Rsvd2,Rsvd3,Rsvd4";

  private static final String RELIANCE =
      "2026-06-19,2026-06-19,CM,BSE,STK,500325,INE002A01018,RELIANCE,A,,,,,"
          + "RELIANCE INDUSTRIES LTD.,1331.00,1338.40,1305.50,1309.35,1309.35,1327.75,,,,,"
          + "1053238,1389501083.00,29482,,1,,,,,";
  private static final String TCS =
      "2026-06-19,2026-06-19,CM,BSE,STK,532540,INE467B01029,TCS,A,,,,,"
          + "TATA CONSULTANCY SERVICES LTD.,3400.00,3450.00,3380.00,3420.55,3420.55,3399.00,,,,,"
          + "200000,684110000.00,15000,,1,,,,,";
  // an index-derivative row in the combined CM file — must be filtered out (FinInstrmTp != STK)
  private static final String INDEX_FUT =
      "2026-06-19,2026-06-19,CM,BSE,IDF,830259,,SENSEX,,2026-06-25,2026-06-25,0,,"
          + "SENSEX FUT,85000.00,85500.00,84800.00,85200.00,85200.00,85100.00,85150.00,85200.00,"
          + "12000,500,12000,9876543.00,5000,,10,,,,,";
  // a quoted instrument name carrying a comma — the splitter must not let it shift the OHLC
  private static final String COMMA_NAME =
      "2026-06-19,2026-06-19,CM,BSE,STK,500001,INE000A01010,COMMACO,B,,,,,"
          + "\"ACME INDUSTRIES, LTD.\",50.00,52.00,49.00,51.25,51.25,49.80,,,,,"
          + "1000,51250.00,42,,1,,,,,";

  @Test
  void parsesStkRowsByHeaderNameAndSkipsNonEquities() {
    BseBhavcopyFetcher fetcher =
        new LiveBseBhavcopyFetcher(new StubBse(udiff()), "https://x", new SimpleMeterRegistry());

    List<BseBhavRow> rows = fetcher.fetchForDate(LocalDate.of(2026, 6, 19));

    assertThat(rows).extracting(BseBhavRow::ticker).containsExactlyInAnyOrder("RELIANCE", "TCS", "COMMACO");

    BseBhavRow rel = rows.stream().filter(r -> r.ticker().equals("RELIANCE")).findFirst().orElseThrow();
    assertThat(rel.scripCode()).isEqualTo("500325");
    assertThat(rel.isin()).isEqualTo("INE002A01018");
    assertThat(rel.series()).isEqualTo("A");
    assertThat(rel.open()).isEqualByComparingTo("1331.00");
    assertThat(rel.close()).isEqualByComparingTo("1309.35");
    assertThat(rel.totalTradedQty()).isEqualTo(1053238L);
    assertThat(rel.noOfTrades()).isEqualTo(29482L);
  }

  @Test
  void quotedCommaInNameDoesNotShiftOhlc() {
    BseBhavcopyFetcher fetcher =
        new LiveBseBhavcopyFetcher(new StubBse(udiff()), "https://x", new SimpleMeterRegistry());

    BseBhavRow comma =
        fetcher.fetchForDate(LocalDate.of(2026, 6, 19)).stream()
            .filter(r -> r.ticker().equals("COMMACO"))
            .findFirst()
            .orElseThrow();

    assertThat(comma.open()).isEqualByComparingTo("50.00");
    assertThat(comma.close()).isEqualByComparingTo("51.25");
  }

  @Test
  void htmlHomepageOnNonTradingDayYieldsEmpty() {
    String html = "<!DOCTYPE html>\n<html><head><title>BSE</title></head><body>LIVE Market</body></html>";
    BseBhavcopyFetcher fetcher =
        new LiveBseBhavcopyFetcher(new StubBse(html), "https://x", new SimpleMeterRegistry());

    assertThat(fetcher.fetchForDate(LocalDate.of(2026, 6, 20))).isEmpty();
  }

  /**
   * The trade date is read from the file's own {@code TradDt}, so a server answering 200 with a
   * DIFFERENT day's file would store that day's rows and leave the requested day permanently
   * missing (the NSE archive demonstrably does exactly this on holiday URLs). Refuse the payload.
   */
  @Test
  void refusesAFileDatedForADifferentDayThanTheOneRequested() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    BseBhavcopyFetcher fetcher = new LiveBseBhavcopyFetcher(new StubBse(udiff()), "https://x", registry);

    // The stub serves the same 19-Jun rows for EVERY URL.
    assertThat(fetcher.fetchForDate(LocalDate.of(2026, 6, 22))).isEmpty();
    assertThat(fetcher.fetchForDate(LocalDate.of(2026, 6, 19))).hasSize(3);

    // Same rationale as the NSE twin: the refusal is invisible to every downstream health surface,
    // so the counter is what makes a SYSTEMATIC refusal distinguishable from an empty fetch. The
    // BSE tag is separate because the two feeds fail independently.
    assertThat(
            registry.get("ay_bhavcopy_misdated_payload_total").tag("exchange", "BSE").counter().count())
        .as("one refusal, and exactly one — the correctly-dated fetch must not increment it")
        .isEqualTo(1.0);
  }

  private static String udiff() {
    return HEADER + "\n" + RELIANCE + "\n" + TCS + "\n" + INDEX_FUT + "\n" + COMMA_NAME + "\n";
  }

  /** BseHttpClient stub returning a canned body for any URL. */
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
