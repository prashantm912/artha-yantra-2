package in.arthayantra.marketdata.kite.wire;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A typed view of one Kite historical candle — the positional wire array decoded into named fields.
 * Kite sends {@code [timestamp, open, high, low, close, volume]} and, with {@code oi=1}, a trailing
 * {@code open_interest}. Timestamps arrive as {@code yyyy-MM-dd'T'HH:mm:ss+0530} (offset, no colon).
 */
public record KiteCandle(
    OffsetDateTime timestamp,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume,
    Long openInterest) {

  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

  /** Decodes one positional candle array; {@code openInterest} is null when absent or JSON null. */
  public static KiteCandle of(List<JsonNode> a) {
    return new KiteCandle(
        OffsetDateTime.parse(a.get(0).asText(), TS),
        new BigDecimal(a.get(1).asText()),
        new BigDecimal(a.get(2).asText()),
        new BigDecimal(a.get(3).asText()),
        new BigDecimal(a.get(4).asText()),
        a.get(5).asLong(),
        a.size() > 6 && !a.get(6).isNull() ? a.get(6).asLong() : null);
  }
}
