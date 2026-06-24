/**
 * Upstox v3 market-data-feed binary-protobuf decode (Wave U3) — internal to the {@code upstox}
 * module. The v3 WS streams {@code MarketDataFeedV3.FeedResponse} frames; {@link
 * in.arthayantra.marketdata.upstox.ws.FeedFrameDecoder} walks the wire format with protobuf-java's
 * low-level {@code CodedInputStream} (no {@code protoc}, no generated classes, no build plugin —
 * protoc download is fragile on the TLS-intercepting-AV box) into a domain-free {@link
 * in.arthayantra.marketdata.upstox.ws.DecodedFeed}. The reference schema is the sibling
 * {@code MarketDataFeedV3.proto}.
 *
 * <p>Nothing outside the {@code upstox} module imports this package: the exposed {@code
 * UpstoxMarketFeedClient} (top-level {@code upstox}) maps {@code DecodedFeed} to its own tick record
 * first — the same anti-corruption boundary as {@code upstox.wire} (the REST DTOs). The WS transport
 * (nv-websocket-client) and the authorize/subscribe protocol live in the client, not here; this
 * package owns ONLY the frame decode so it stays small and round-trip-testable in isolation.
 */
package in.arthayantra.marketdata.upstox.ws;
