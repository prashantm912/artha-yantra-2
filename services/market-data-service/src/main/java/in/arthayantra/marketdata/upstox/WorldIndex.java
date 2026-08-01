package in.arthayantra.marketdata.upstox;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One global index's live snapshot for the World Indices page — the master roster row (name / country /
 * key / trading symbol / latency) joined with its live Upstox quote (LTP + day OHLC + net change +
 * derived %change + as-of). The domain record the {@code WorldIndicesController} serves; the wire DTOs
 * ({@link UpstoxGlobalInstrument}, {@link in.arthayantra.marketdata.upstox.wire.UpstoxMarketQuote}) stay
 * module-internal and never cross the controller boundary.
 *
 * <p>{@code changePct} is derived {@code net_change / (ltp - net_change) * 100} (the prev-day close is
 * {@code ltp - net_change}); {@code null} when the quote is missing or the prev close is zero. Numeric
 * fields are {@code null} for an index whose live quote did not resolve (the row still lists from the
 * master so the page shows the full universe).
 *
 * <p>⚠️ {@code key} is the ONLY component guaranteed non-null. {@code isGlobalIndex} admits a master
 * row on {@code instrumentKey != null && segment == "GLOBAL_INDEX"} and nothing else, and the
 * remaining master fields are forwarded to this record unchanged — so {@code name}, {@code country},
 * {@code tradingSymbol} and {@code latency} are all nullable in practice. That is not theoretical:
 * {@code UpstoxGlobalInstrumentWireContractTest} pins a GLOBAL_INDEX row carrying only segment, name
 * and instrument_key as an ACCEPTED wire shape. Do not "tidy" these annotations away without also
 * tightening the filter — the schema they produce is consumed as generated TypeScript.
 */
public record WorldIndex(
    @Schema(types = {"string", "null"})
    String name,
    @Schema(types = {"string", "null"})
    String country,
    String key,
    @Schema(types = {"string", "null"})
    String tradingSymbol,
    @Schema(type = "string", types = {"string", "null"})
    BigDecimal ltp,
    @Schema(type = "string", types = {"string", "null"})
    BigDecimal prevClose,
    @Schema(type = "string", types = {"string", "null"})
    BigDecimal netChange,
    @Schema(type = "string", types = {"string", "null"})
    BigDecimal changePct,
    @Schema(type = "string", types = {"string", "null"})
    BigDecimal open,
    @Schema(type = "string", types = {"string", "null"})
    BigDecimal high,
    @Schema(type = "string", types = {"string", "null"})
    BigDecimal low,
    @Schema(types = {"string", "null"})
    OffsetDateTime asOf,
    @Schema(types = {"string", "null"})
    String latency) {}
