package in.arthayantra.marketdata.upstox;

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
 */
public record WorldIndex(
    String name,
    String country,
    String key,
    String tradingSymbol,
    BigDecimal ltp,
    BigDecimal prevClose,
    BigDecimal netChange,
    BigDecimal changePct,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    OffsetDateTime asOf,
    String latency) {}
