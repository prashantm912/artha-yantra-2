package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire mirror of one row of the Upstox public GLOBAL instrument master
 * ({@code https://assets.upstox.com/market-quote/instruments/exchange/global.json.gz} — a gzipped JSON
 * array, one object per listed global instrument). Carries the world-index/indicator roster that the
 * World Indices page lists (verified login-free against the live file 2026-06-24). Only the fields the
 * page needs are mapped; {@code @JsonIgnoreProperties(ignoreUnknown=true)} so a field Upstox ADDS never
 * crashes the parse — the {@code kite/wire} anti-corruption convention (see {@code wire/package-info.java}).
 * NEVER enable global {@code FAIL_ON_UNKNOWN_PROPERTIES} on the mapper that reads this type.
 *
 * <p>Field semantics (verified against the live file 2026-06-24):
 *
 * <ul>
 *   <li>{@code segment} — {@code GLOBAL_INDEX} (the world indices, e.g. HANG SENG, SGX NIFTY) or
 *       {@code GLOBAL_INDICATOR} (commodities like Brent {@code BZUSD}).
 *   <li>{@code name} — the human display name, e.g. {@code HANG SENG}.
 *   <li>{@code country} — the index's country, e.g. {@code Hong Kong}.
 *   <li>{@code instrument_key} — the quote key, e.g. {@code GLOBAL_INDEX|^HSI} (the {@code |} variant;
 *       the quotes response re-keys it with a {@code :}).
 *   <li>{@code trading_symbol} — the venue symbol, e.g. {@code ^HSI}.
 *   <li>{@code latency} — the publication latency string, e.g. {@code 900 Seconds}.
 *   <li>{@code exchange} — {@code GLOBAL}.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxGlobalInstrument(
    String segment,
    String name,
    String country,
    @JsonProperty("instrument_key") String instrumentKey,
    @JsonProperty("trading_symbol") String tradingSymbol,
    String exchange,
    String latency,
    Boolean weekly) {}
