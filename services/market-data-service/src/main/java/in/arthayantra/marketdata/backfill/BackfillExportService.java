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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private static final String INTERVAL = "1m";

  /** A built export artifact: bytes + a download filename + its content type. */
  public record Export(byte[] body, String filename, String contentType) {}

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
    if (bars.size() > MAX_ROWS) {
      bars = bars.subList(0, MAX_ROWS);
    }
    return switch (fmt) {
      case "csv" -> new Export(csv(bars).getBytes(StandardCharsets.UTF_8), symbol + ".csv", "text/csv");
      case "json" -> new Export(json(bars), symbol + ".json", "application/json");
      default -> throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "format must be csv or json");
    };
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
