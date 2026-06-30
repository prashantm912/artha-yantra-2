package in.arthayantra.marketdata.nse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.nse.NseAnnouncementFetcher.Announcement;
import org.junit.jupiter.api.Test;

class LiveNseAnnouncementFetcherTest {

  private static final ObjectMapper M = new ObjectMapper();

  private static JsonNode node(String json) {
    try {
      return M.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void mapsTheConfirmedNseRow() {
    // The BIRLACORPN row confirmed in docs/oipulse-study/equity/announcement.md, NSE field names.
    Announcement a =
        LiveNseAnnouncementFetcher.mapRow(
            node(
                """
                {
                  "symbol": "BIRLACORPN",
                  "sm_name": "Birla Corporation Limited",
                  "desc": "Copy of Newspaper Publication",
                  "attchmntText": "Birla Corporation Limited has informed the Exchange about Copy of Newspaper Publication.",
                  "attchmntFile": "https://nsearchives.nseindia.com/corporate/BIRLACORP1.pdf",
                  "an_dt": "16-Jun-2026 14:48:50"
                }
                """));
    assertThat(a).isNotNull();
    assertThat(a.symbol()).isEqualTo("BIRLACORPN");
    assertThat(a.company()).isEqualTo("Birla Corporation Limited");
    assertThat(a.subject()).isEqualTo("Copy of Newspaper Publication");
    assertThat(a.detail()).startsWith("Birla Corporation Limited has informed");
    assertThat(a.fileLink()).isEqualTo("https://nsearchives.nseindia.com/corporate/BIRLACORP1.pdf");
    assertThat(a.date()).isEqualTo("2026-06-16");
    assertThat(a.time()).isEqualTo("14:48:50");
  }

  @Test
  void rowWithoutSymbolIsDropped() {
    assertThat(LiveNseAnnouncementFetcher.mapRow(node("{\"desc\":\"x\"}"))).isNull();
  }

  @Test
  void nullAndDashFieldsBecomeNull() {
    Announcement a =
        LiveNseAnnouncementFetcher.mapRow(
            node("{\"symbol\":\"INFY\",\"desc\":\"-\",\"attchmntFile\":null,\"an_dt\":\"\"}"));
    assertThat(a.symbol()).isEqualTo("INFY");
    assertThat(a.subject()).isNull();
    assertThat(a.fileLink()).isNull();
    assertThat(a.date()).isEmpty();
    assertThat(a.time()).isEmpty();
  }

  @Test
  void splitDateTimeParsesNseFormat() {
    assertThat(LiveNseAnnouncementFetcher.splitDateTime("16-Jun-2026 14:48:50"))
        .containsExactly("2026-06-16", "14:48:50");
  }

  @Test
  void splitDateTimeFallsBackOnUnparseable() {
    assertThat(LiveNseAnnouncementFetcher.splitDateTime("2026-06-16 09:00"))
        .containsExactly("2026-06-16", "09:00");
    assertThat(LiveNseAnnouncementFetcher.splitDateTime("oddstamp"))
        .containsExactly("oddstamp", "");
    assertThat(LiveNseAnnouncementFetcher.splitDateTime(null)).containsExactly("", "");
  }
}
