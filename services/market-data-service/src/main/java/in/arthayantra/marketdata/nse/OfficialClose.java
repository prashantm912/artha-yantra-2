package in.arthayantra.marketdata.nse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One symbol's OFFICIAL NSE closing price for one settled session, straight off
 * {@code marketdata.nse_eod_bhavcopy} — the exchange's own published close, which INCLUDES the
 * 15:15–15:30 closing auction.
 *
 * <p>⚠️ It is NOT the same number as the {@code candles}@1d close for the same session, and that
 * gap is the whole reason this record exists (ledger H9). Kite's daily bar covers the CONTINUOUS
 * session only, so it stops at 15:15 and misses the auction that actually sets the official close.
 * Measured 2026-08-13 over the swing book's NSE symbols: <b>0 of 22</b> {@code source='KITE'} 1d
 * bars closed at {@code nse_eod_bhavcopy.close_price}, and <b>22 of 22</b> were short on volume
 * (92.95%–99.91% of the bhavcopy traded quantity).
 *
 * <p>{@code series} is carried on the wire deliberately: the table is MULTI-SERIES, and although
 * the reader admits ONLY the cash-equity universe — {@code EQ} preferred over {@code BE}, everything
 * else REJECTED IN SQL rather than ranked last (see
 * {@code NseEodBhavcopyRepository#officialClosesOn}) — a consumer that gets a surprising number can
 * still see which of the two it came from without a second query. A value other than {@code EQ} or
 * {@code BE} can never appear here; if one ever does, the predicate has been weakened.
 *
 * <p>Decimals ride the wire as JSON STRINGS — {@code ArthaJacksonAutoConfiguration} registers
 * {@code ToStringSerializer} for {@code BigDecimal} platform-wide, while springdoc would otherwise
 * infer {@code number}. {@code @Schema(type = "string")} is what makes the captured spec tell the
 * truth; verify by reading the CAPTURED SPEC, never by trusting the annotation.
 */
public record OfficialClose(
    String tradingsymbol,
    LocalDate tradeDate,
    @Schema(type = "string") BigDecimal closePrice,
    @Schema(type = "string", types = {"string", "null"}) BigDecimal lastPrice,
    String series) {}
