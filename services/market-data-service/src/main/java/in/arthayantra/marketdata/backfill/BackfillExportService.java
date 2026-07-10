package in.arthayantra.marketdata.backfill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.upstox.ExpiredBackfillRepository;
import in.arthayantra.marketdata.upstox.ExpiredBackfillRepository.ExportContract;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/**
 * Backfilled-candle export for the B6 wizard. v1 is a SYNCHRONOUS per-contract export (one expired
 * option/future's 1m OHLCV+OI, capped at {@value #MAX_ROWS} rows ≈ a full contract life) — small and
 * safe, and exactly the slice the Part-2 value-verify needs. Per-expiry bulk + ZIP/Parquet (1M+ rows →
 * async streaming) is deferred (see the wave spec's open decisions); the B5 query console covers
 * arbitrary slices in the meantime.
 */
@Service
public class BackfillExportService {

  private static final int MAX_ROWS = 100_000;
  /** Per-expiry bulk cap: bounds the SYNCHRONOUS zip work (an option chain is ~100-300 contracts). */
  private static final int MAX_CONTRACTS = 500;
  private static final String INTERVAL = "1m";

  /**
   * A built export artifact: bytes + a download filename + its content type, plus the exported row count
   * and whether the {@value #MAX_ROWS}-row cap TRUNCATED the result. The truncation flag makes the old
   * SILENT clip explicit to the caller (audit §7.2.5 / §10 Phase-1) — the controller surfaces it as an
   * {@code X-Result-Truncated} header + an {@code admin_audit} row.
   */
  public record Export(
      byte[] body, String filename, String contentType, boolean truncated, long rowCount) {}

  private final CandleRepository candles;
  private final ExpiredBackfillRepository repo;
  private final ObjectMapper mapper;

  public BackfillExportService(
      CandleRepository candles, ExpiredBackfillRepository repo, ObjectMapper mapper) {
    this.candles = candles;
    this.repo = repo;
    this.mapper = mapper;
  }

  /** Distinct expiries for an underlying (newest first). */
  public List<LocalDate> expiries(String underlying) {
    return repo.expiriesFor(require(underlying, "underlying"));
  }

  /** Registered contracts for one (underlying, expiry). */
  public List<ExportContract> contracts(String underlying, LocalDate expiry) {
    if (expiry == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "expiry is required");
    }
    return repo.contractsFor(require(underlying, "underlying"), expiry);
  }

  /** Builds a CSV/JSON export of one contract's 1m candles over [fromDate, toDate]. */
  public Export export(
      String exchange, String symbol, LocalDate fromDate, LocalDate toDate, String format) {
    require(exchange, "exchange");
    require(symbol, "symbol");
    if (fromDate == null || toDate == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "from and to dates are required");
    }
    if (toDate.isBefore(fromDate)) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "to must be on or after from");
    }
    String fmt = format == null ? "csv" : format.toLowerCase(java.util.Locale.ROOT);
    OffsetDateTime from = fromDate.atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = toDate.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);
    List<Candle> bars = candles.range(exchange, symbol, INTERVAL, from, to);
    boolean truncated = bars.size() > MAX_ROWS;
    if (truncated) {
      bars = bars.subList(0, MAX_ROWS);
    }
    long rowCount = bars.size();
    return switch (fmt) {
      case "csv" ->
          new Export(csv(bars).getBytes(StandardCharsets.UTF_8), symbol + ".csv", "text/csv", truncated, rowCount);
      case "json" -> new Export(json(bars), symbol + ".json", "application/json", truncated, rowCount);
      default -> throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "format must be csv or json");
    };
  }

  /**
   * A ZIP of the per-contract CSV/JSON exports of EVERY registered contract of one (underlying,
   * expiry) — the whole option chain in one download (the Part-2 premium-replay value-verify inspects
   * a full expiry, previously one contract at a time). Synchronous, bounded to {@value #MAX_CONTRACTS}
   * contracts (each entry still row-capped by {@link #export}); a larger expiry is the async-job follow-up.
   */
  public Export exportBulk(
      String underlying, LocalDate expiry, LocalDate fromDate, LocalDate toDate, String format) {
    List<ExportContract> chain = contracts(underlying, expiry); // validates underlying + expiry
    if (chain.isEmpty()) {
      throw new ApiException(
          404, ErrorCodes.NOT_FOUND_RESOURCE, "no registered contracts for " + underlying + " " + expiry);
    }
    if (chain.size() > MAX_CONTRACTS) {
      throw new ApiException(
          400,
          ErrorCodes.VALIDATION_FAILED,
          chain.size() + " contracts exceeds the " + MAX_CONTRACTS
              + "-contract sync bulk cap — narrow the expiry or use the per-contract export");
    }
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    boolean truncated = false;
    long rowCount = 0;
    try (ZipOutputStream zip = new ZipOutputStream(buf)) {
      for (ExportContract c : chain) {
        Export one = export(c.exchange(), c.tradingsymbol(), fromDate, toDate, format);
        truncated |= one.truncated(); // any contract clipped by the row cap flags the whole bundle
        rowCount += one.rowCount();
        zip.putNextEntry(new ZipEntry(one.filename()));
        zip.write(one.body());
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new ApiException(500, ErrorCodes.INTERNAL_ERROR, "bulk export zip failed");
    }
    String name = require(underlying, "underlying").replace(' ', '_') + "_" + expiry + ".zip";
    return new Export(buf.toByteArray(), name, "application/zip", truncated, rowCount);
  }

  private static String csv(List<Candle> bars) {
    StringBuilder sb = new StringBuilder();
    sb.append("openalgo_symbol,date,time,timestamp,open,high,low,close,volume,oi\n");
    for (Candle b : bars) {
      sb.append(b.tradingsymbol()).append(',')
          .append(b.bucket().toLocalDate()).append(',')
          .append(b.bucket().toLocalTime()).append(',')
          .append(b.bucket()).append(',')
          .append(b.open()).append(',')
          .append(b.high()).append(',')
          .append(b.low()).append(',')
          .append(b.close()).append(',')
          .append(b.volume()).append(',')
          .append(b.oi() == null ? "" : b.oi())
          .append('\n');
    }
    return sb.toString();
  }

  private byte[] json(List<Candle> bars) {
    List<Map<String, Object>> out = new ArrayList<>(bars.size());
    for (Candle b : bars) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("openalgo_symbol", b.tradingsymbol());
      row.put("timestamp", b.bucket().toString());
      row.put("open", b.open());
      row.put("high", b.high());
      row.put("low", b.low());
      row.put("close", b.close());
      row.put("volume", b.volume());
      row.put("oi", b.oi());
      out.add(row);
    }
    try {
      return mapper.writeValueAsBytes(out);
    } catch (JsonProcessingException e) {
      throw new ApiException(500, ErrorCodes.INTERNAL_ERROR, "export serialization failed");
    }
  }

  private static String require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, name + " is required");
    }
    return value;
  }
}
