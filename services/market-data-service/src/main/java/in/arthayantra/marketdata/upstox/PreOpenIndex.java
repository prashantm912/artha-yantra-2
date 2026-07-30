package in.arthayantra.marketdata.upstox;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One index's pre-open snapshot for the Pre-Open Market page — a Nifty 50 / Nifty Bank / Nifty Fin
 * Service / Sensex quote read from Upstox {@code GET /v2/market-quote/quotes}. During the 09:00–09:15
 * IST pre-open window these carry the indicative pre-open price; otherwise the last / closing price
 * (the page is useful 24/7). The domain record the {@code PreOpenController} serves; the wire DTO
 * ({@link in.arthayantra.marketdata.upstox.wire.UpstoxMarketQuote}) stays module-internal.
 *
 * <p>{@code changePct} is derived {@code net_change / (ltp - net_change) * 100} (the prev close is
 * {@code ltp - net_change}); {@code null} when the quote is missing or the prev close is zero. Numeric
 * fields are {@code null} for an index whose live quote did not resolve (the row still lists by name).
 */
public record PreOpenIndex(
    String name,
    String key,
    // all four are null when the live tick did not resolve — the index still lists so the pre-open
    // table shows the full set (UpstoxMarketStatusClient:233)
    @Schema(types = {"number", "null"}) BigDecimal ltp,
    @Schema(types = {"number", "null"}) BigDecimal prevClose,
    @Schema(types = {"number", "null"}) BigDecimal netChange,
    @Schema(types = {"number", "null"}) BigDecimal changePct,
    OffsetDateTime asOf) {}
