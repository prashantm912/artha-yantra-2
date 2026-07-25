package in.arthayantra.marketdata.candles;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One OHLCV bar (B-7 row shape) — exact decimals, IST bucket start. */
public record Candle(
    String exchange,
    String tradingsymbol,
    String interval,
    OffsetDateTime bucket,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume,
    @Schema(types = {"integer", "null"}) Long oi,
    String source) {}
