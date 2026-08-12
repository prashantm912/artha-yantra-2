package in.arthayantra.marketdata.upstox;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * One exchange's live session phase for the Pre-Open Market page — the {@code NSE} / {@code BSE} /
 * {@code MCX} status read from Upstox {@code GET /v2/market/status/{exchange}}. The domain record the
 * {@code PreOpenController} serves; the wire DTO ({@link in.arthayantra.marketdata.upstox.wire.UpstoxMarketStatus})
 * stays module-internal and never crosses the controller boundary.
 *
 * <p>{@code status} is the raw Upstox phase enum ({@code PRE_OPEN_START}, {@code PRE_OPEN_END},
 * {@code NORMAL_OPEN}, {@code NORMAL_CLOSE}, {@code CLOSING_START}, {@code CLOSING_END}), or
 * {@code "UNKNOWN"} when the exchange call failed (a failed exchange degrades to UNKNOWN, never throws).
 * {@code preOpen} is {@code true} iff the phase starts with {@code "PRE_OPEN"} (the 09:00–09:15 IST
 * pre-open window). {@code asOf} is the Upstox {@code last_updated} instant (IST offset), or null when
 * unknown.
 */
public record MarketStatus(
    String exchange,
    String status,
    boolean preOpen,
    // null on the unknown() path — a missing/!status response degrades to status "UNKNOWN" with no
    // timestamp rather than failing the page (UpstoxMarketStatusClient:261)
    @Schema(types = {"string", "null"}) OffsetDateTime asOf) {}
