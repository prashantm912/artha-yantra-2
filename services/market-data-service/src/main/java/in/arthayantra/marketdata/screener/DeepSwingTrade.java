package in.arthayantra.marketdata.screener;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One closed swing lot as the DEEP_SWING job wire shape (research-fidelity audit P0-3): the fields
 * BOTH deep-sims' {@code BtTrade} records share, mapped once so the backtest-service worker can
 * persist per-trade {@code backtest_trades} rows without depending on either family's internal record.
 * The deep-sim exposes a % model (no share count / rupee P&amp;L per lot) — the worker maps {@code
 * pnlPct} + entry/exit prices onto the trade row on a 1-share basis. {@code exitReason} is the
 * deep-sim's REAL exit attribution (STOP_LOSS / TRAILING_STOP / …), which the candle path lacks (B11).
 * Decimals cross the wire as JSON strings (the global Jackson config).
 */
public record DeepSwingTrade(
    String symbol,
    String setup,
    LocalDate entryDate,
    @Schema(type = "string") BigDecimal entryPrice,
    LocalDate exitDate,
    @Schema(type = "string") BigDecimal exitPrice,
    @Schema(type = "string") BigDecimal pnlPct,
    int barsHeld,
    String exitReason,
    @Schema(type = "string") BigDecimal rsRankAtEntry) {}
