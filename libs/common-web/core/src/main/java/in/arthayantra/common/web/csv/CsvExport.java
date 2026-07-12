package in.arthayantra.common.web.csv;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The shared CSV-export helper (app-platform audit 2026-07-10 §10 Phase-3 "CSV export standard").
 * ONE builder for every CSV download across the platform so the truncation contract is identical
 * everywhere: a row cap, and when it clips the result the truncation is made LOUD (audit §7.2.5 / A9
 * — the same convention the backfill export established) via {@code X-Result-Truncated: true} +
 * {@code X-Result-Rows} headers rather than a silently clipped file. Callers query one row MORE than
 * the cap ({@code maxRows + 1}) so the builder can tell a clipped export from an exact-fit one.
 *
 * <p>RFC-4180 escaping: a cell containing a comma, double-quote, CR or LF is wrapped in double quotes
 * with embedded quotes doubled; {@code null} → empty. Money/decimals are written via {@code
 * String.valueOf} — pass the {@code BigDecimal}/{@code String} the repo already carries (the money-
 * as-string convention is preserved).
 */
public final class CsvExport {

  /** Default row cap — matches the backfill export's 100k synchronous ceiling. */
  public static final int DEFAULT_MAX_ROWS = 100_000;

  /** Loud-truncation header: {@code true} when the row cap clipped the export. */
  public static final String HEADER_TRUNCATED = "X-Result-Truncated";

  /** Loud-truncation header: the number of data rows actually written (excludes the header row). */
  public static final String HEADER_ROWS = "X-Result-Rows";

  private CsvExport() {}

  /** Opens a writer with the given column header and row cap. */
  public static Writer writer(int maxRows, String... header) {
    return new Writer(maxRows, header);
  }

  /**
   * Accumulates data rows up to {@code maxRows}; once the cap is reached further rows are dropped and
   * the export is flagged truncated. Not thread-safe (one writer per request).
   */
  public static final class Writer {
    private final StringBuilder sb = new StringBuilder();
    private final int maxRows;
    private long rowCount = 0;
    private boolean truncated = false;

    private Writer(int maxRows, String[] header) {
      this.maxRows = Math.max(1, maxRows);
      appendRow((Object[]) header);
    }

    /** Appends one data row, or flags truncation once the cap is reached (extra rows are dropped). */
    public Writer row(Object... cells) {
      if (rowCount >= maxRows) {
        truncated = true;
        return this;
      }
      rowCount++;
      appendRow(cells);
      return this;
    }

    /** Builds the {@code text/csv} download response with the loud-truncation headers. */
    public ResponseEntity<byte[]> download(String filename) {
      byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
          .header(HEADER_TRUNCATED, Boolean.toString(truncated))
          .header(HEADER_ROWS, Long.toString(rowCount))
          .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
          .body(body);
    }

    /** Whether the row cap clipped the result. */
    public boolean truncated() {
      return truncated;
    }

    /** Data rows written (excludes the header row). */
    public long rowCount() {
      return rowCount;
    }

    private void appendRow(Object[] cells) {
      for (int i = 0; i < cells.length; i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(escape(cells[i]));
      }
      sb.append("\r\n");
    }
  }

  /** RFC-4180 cell escaping; {@code null} → empty string. Package-private for the unit test. */
  static String escape(Object value) {
    if (value == null) {
      return "";
    }
    String s = String.valueOf(value);
    if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
      return s;
    }
    return '"' + s.replace("\"", "\"\"") + '"';
  }
}
