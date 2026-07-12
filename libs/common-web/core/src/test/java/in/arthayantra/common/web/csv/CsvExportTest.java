package in.arthayantra.common.web.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** Unit tests for the shared CSV export helper: RFC-4180 escaping + the loud-truncation contract. */
class CsvExportTest {

  @Test
  void escapesOnlyWhenNeeded() {
    assertThat(CsvExport.escape(null)).isEmpty();
    assertThat(CsvExport.escape("plain")).isEqualTo("plain");
    assertThat(CsvExport.escape(42)).isEqualTo("42");
    assertThat(CsvExport.escape("a,b")).isEqualTo("\"a,b\"");
    assertThat(CsvExport.escape("she said \"hi\"")).isEqualTo("\"she said \"\"hi\"\"\"");
    assertThat(CsvExport.escape("line1\nline2")).isEqualTo("\"line1\nline2\"");
  }

  @Test
  void writesHeaderAndRowsWithCrlfAndTruncationHeaders() {
    ResponseEntity<byte[]> resp =
        CsvExport.writer(10, "a", "b")
            .row(1, "x")
            .row(2, "y,z")
            .download("out.csv");

    String body = new String(resp.getBody(), StandardCharsets.UTF_8);
    assertThat(body).isEqualTo("a,b\r\n1,x\r\n2,\"y,z\"\r\n");
    assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"out.csv\"");
    assertThat(resp.getHeaders().getFirst(CsvExport.HEADER_TRUNCATED)).isEqualTo("false");
    assertThat(resp.getHeaders().getFirst(CsvExport.HEADER_ROWS)).isEqualTo("2");
    assertThat(resp.getHeaders().getContentType().toString()).startsWith("text/csv");
  }

  @Test
  void capsAtMaxRowsAndFlagsTruncationLoud() {
    CsvExport.Writer w = CsvExport.writer(2, "n");
    w.row(1).row(2).row(3).row(4); // 4 rows into a 2-row cap

    ResponseEntity<byte[]> resp = w.download("capped.csv");
    assertThat(w.truncated()).isTrue();
    assertThat(w.rowCount()).isEqualTo(2);
    assertThat(new String(resp.getBody(), StandardCharsets.UTF_8)).isEqualTo("n\r\n1\r\n2\r\n");
    assertThat(resp.getHeaders().getFirst(CsvExport.HEADER_TRUNCATED)).isEqualTo("true");
    assertThat(resp.getHeaders().getFirst(CsvExport.HEADER_ROWS)).isEqualTo("2");
  }
}
