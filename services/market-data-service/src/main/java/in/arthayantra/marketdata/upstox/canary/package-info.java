/**
 * Off-critical-path Upstox contract drift detection (ADR-0002 U7 + W-U4 cutover-prep) — the twin of
 * {@code openalgo.canary}. A daily self-scheduled {@link
 * in.arthayantra.marketdata.upstox.canary.UpstoxContractCanary} GETs the consumed Upstox endpoints
 * with the analytics token, diffs the raw JSON against {@code upstox-contract-manifest.json}
 * (CONSUMED-field sentinels, not a full mirror), and ntfy-alerts on missing/type-changed fields, so a
 * Upstox rename/removal is caught off the live path. Coverage spans the Market-Information endpoints
 * (U7) AND the live-capture shapes the W-U4 cutover flips onto — {@code /v2/option/chain}, {@code
 * /v2/market-quote/quotes}, and the v3 {@code /feed/market-data-feed/authorize} GET (the WS feed
 * frames are binary protobuf, guarded off-band by the {@code .proto} + {@code FeedFrameDecoderTest}).
 * The wire SHAPE is also guarded at build time by {@code UpstoxWireContractTest}. Dormant unless
 * {@code artha.upstox.canary-enabled=true}.
 */
package in.arthayantra.marketdata.upstox.canary;
