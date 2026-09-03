package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Wire mirror of one row of the Upstox public instrument master
 * ({@code https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz} — a gzipped
 * JSON array, one object per listed instrument). Only the fields the F&amp;O key resolver needs are
 * mapped; {@code @JsonIgnoreProperties(ignoreUnknown=true)} so a field Upstox ADDS never crashes the
 * parse (the {@code kite/wire} anti-corruption convention — see {@code kite/wire/package-info.java}).
 * NEVER enable global {@code FAIL_ON_UNKNOWN_PROPERTIES} on the mapper that reads this type.
 *
 * <p>Field semantics (verified against the live file 2026-06-24):
 *
 * <ul>
 *   <li>{@code segment} — the broker segment, e.g. {@code NSE_FO} / {@code BSE_FO} (the F&amp;O rows),
 *       {@code NSE_EQ}, {@code NSE_INDEX}; the resolver keeps only the {@code *_FO} rows.
 *   <li>{@code instrument_key} — the target {@code NSE_FO|<token>} / {@code BSE_FO|<token>} key.
 *   <li>{@code instrument_type} — {@code FUT} / {@code CE} / {@code PE}.
 *   <li>{@code asset_symbol} — the underlying ROOT symbol (e.g. {@code NIFTY}, {@code BANKNIFTY},
 *       {@code RELIANCE}); equals the Kite dump {@code name} stored on our F&amp;O rows. {@code name}
 *       itself is the full instrument name (a company name for stocks) — NOT a reliable join key.
 *   <li>{@code expiry} — epoch MILLIS at the contract's end-of-day IST instant ({@code 23:59:59 IST});
 *       converted by the resolver to the IST {@link java.time.LocalDate}.
 *   <li>{@code strike_price} — the option strike ({@code 0.0} for futures).
 *   <li>{@code lot_size} — the contract's tradable market lot (e.g. NIFTY 65 for 2026). Present on
 *       the F&amp;O rows; {@code null} for the non-F&amp;O rows (index/equity) the resolver skips.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxInstrumentMaster(
    String segment,
    String name,
    @JsonProperty("asset_symbol") String assetSymbol,
    @JsonProperty("underlying_symbol") String underlyingSymbol,
    @JsonProperty("instrument_key") String instrumentKey,
    @JsonProperty("instrument_type") String instrumentType,
    @JsonProperty("trading_symbol") String tradingSymbol,
    // ⚠️ H26 U-A2. The exchange's OWN token, and the measured identity key: it joins our
    // instruments table 100.00% on both NSE_EQ and NSE_FO, where a trading_symbol join matches
    // only ~28% of equities.
    //
    // ⚠️ ON THE WIRE THIS IS A JSON **STRING**, NOT A NUMBER — `"758718"`. An earlier version of
    // this comment justified the boxed Long by "a segment that omits it must parse to null", and
    // that reason was FALSE: `computed` 2026-09-03 over the live master, all 117,344 rows carry the
    // key and NONE omits it. The real reason to box is the 13 rows that carry the EMPTY STRING
    // (10 GLOBAL_INDEX + 3 GLOBAL_INDICATOR), which Jackson's default empty-string coercion turns
    // into null. A primitive would be a compile error there, not a silent 0 — but the null still
    // matters downstream, and `indexNseCash` skips it.
    //
    // ⚠️ That coercion is a GLOBAL Jackson default this class does not own, and mapping this field
    // moved it out of the `ignoreUnknown` bucket where it could never break anything. If the
    // default ever changed, every reload would throw and the F&O cache would stop warming — loudly
    // (reload() logs and keeps the prior cache) but permanently. `parsesTheEmptyExchangeTokenAsNull`
    // pins it, so that change fails a test here instead of in production.
    @JsonProperty("exchange_token") Long exchangeToken,
    // Carried for provenance and for the equity cross-check; Upstox addresses NSE_EQ keys as
    // NSE_EQ|<ISIN>. Not used as identity — an ISIN survives a rename, which is exactly why it
    // cannot be a primary key here.
    @JsonProperty("isin") String isin,
    Long expiry,
    @JsonProperty("strike_price") BigDecimal strikePrice,
    @JsonProperty("lot_size") Integer lotSize) {}
