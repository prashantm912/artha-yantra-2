package in.arthayantra.marketdata.feed;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The bus tick (A.7.1): stable identity, exact-decimal price (serialized as a string), IST
 * timestamp, and the per-instrument monotonic {@code seq} consumers use to drop
 * reconnect-overlap duplicates ({@code seq <= lastSeen}).
 */
public record NormalizedTick(
    String exchange,
    String tradingsymbol,
    @Schema(type = "string") BigDecimal lastPrice,
    long cumulativeDayVolume,
    @Schema(types = {"integer", "null"}) Long openInterest,
    OffsetDateTime timestamp,
    long seq) {}
