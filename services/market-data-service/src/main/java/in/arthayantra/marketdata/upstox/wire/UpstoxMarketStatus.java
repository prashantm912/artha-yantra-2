package in.arthayantra.marketdata.upstox.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire mirror of Upstox {@code GET /v2/market/status/{exchange}} — the live session-phase for one
 * exchange ({@code NSE} / {@code BSE} / {@code MCX}), returned login-free on the 1-yr analytics token
 * (the same side-channel as {@link UpstoxMarketQuote}). The {@code data} block carries the exchange
 * echo, the phase enum ({@code PRE_OPEN_START} / {@code PRE_OPEN_END} / {@code NORMAL_OPEN} /
 * {@code NORMAL_CLOSE} / {@code CLOSING_START} / {@code CLOSING_END}) and a last-updated epoch-millis.
 * Every documented field is mirrored even when unused (the {@code kite/wire} convention);
 * {@code @JsonIgnoreProperties} so a field Upstox adds never crashes the live status feed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxMarketStatus(String status, Data data) {

  /** The per-exchange status payload — exchange echo + the phase enum + a last-updated instant. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(
      String exchange,
      String status,
      @JsonProperty("last_updated") Long lastUpdated) {}
}
