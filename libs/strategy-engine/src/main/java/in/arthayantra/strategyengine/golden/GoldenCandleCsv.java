package in.arthayantra.strategyengine.golden;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The FROZEN candle encoding (docs/golden-vectors.md §2): header row, exact decimal strings into
 * BigDecimal (never a double hop), IST bucket starts with the explicit +05:30 offset, identity
 * by (exchange, tradingsymbol). One harness, one format — the Stage D replay engine reads
 * exactly this code path.
 */
public final class GoldenCandleCsv {

  /** The frozen header. */
  public static final String HEADER = "exchange,tradingsymbol,interval,bucket,open,high,low,close,volume";

  private GoldenCandleCsv() {}

  /** Parses one fixture file's lines. */
  public static List<EngineCandle> parse(BufferedReader reader) {
    try {
      String header = reader.readLine();
      if (!HEADER.equals(header)) {
        throw new IllegalArgumentException("unexpected golden candle header: " + header);
      }
      List<EngineCandle> candles = new ArrayList<>();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        String[] cells = line.split(",", -1);
        candles.add(
            new EngineCandle(
                OffsetDateTime.parse(cells[3]),
                new BigDecimal(cells[4]),
                new BigDecimal(cells[5]),
                new BigDecimal(cells[6]),
                new BigDecimal(cells[7]),
                Long.parseLong(cells[8])));
      }
      return candles;
    } catch (IOException e) {
      throw new UncheckedIOException("golden candle fixture unreadable", e);
    }
  }

  /** Renders one row in the frozen encoding. */
  public static String row(String exchange, String tradingsymbol, EngineCandle candle) {
    return exchange + ',' + tradingsymbol + ",1m," + candle.bucketStart() + ','
        + candle.open().toPlainString() + ',' + candle.high().toPlainString() + ','
        + candle.low().toPlainString() + ',' + candle.close().toPlainString() + ','
        + candle.volume();
  }
}
