package in.arthayantra.marketdata.upstox.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire mirror of Upstox {@code GET /v3/feed/market-data-feed/authorize} (Wave U3) — the
 * authorize-GET that returns the one-time, pre-authorized {@code wss://...} URL for the v3
 * market-data WebSocket, on the 1-yr analytics token (proven login-free 2026-06-24). The whole
 * documented response is {@code {"status":..., "data":{"authorized_redirect_uri":"wss://..."}}}; the
 * single {@code data} field is mirrored per the {@code kite/wire} convention, and
 * {@code @JsonIgnoreProperties} keeps a field Upstox adds from crashing the live feed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxFeedAuthorize(String status, Data data) {

  /** The authorize payload — the single-use wss URL the socket connects to. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(@JsonProperty("authorized_redirect_uri") String authorizedRedirectUri) {}
}
