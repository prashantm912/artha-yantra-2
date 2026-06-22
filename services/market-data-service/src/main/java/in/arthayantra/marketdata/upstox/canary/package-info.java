/**
 * Off-critical-path Upstox Market-Information contract drift detection (ADR-0002 U7) — the twin of
 * {@code openalgo.canary}. A daily self-scheduled {@link
 * in.arthayantra.marketdata.upstox.canary.UpstoxContractCanary} GETs the consumed Market-Information
 * endpoints with the analytics token, diffs the raw JSON against {@code upstox-contract-manifest.json}
 * (CONSUMED-field sentinels, not a full mirror), and ntfy-alerts on missing/type-changed fields, so a
 * Upstox rename/removal is caught off the live path. The wire SHAPE is also guarded at build time by
 * {@code UpstoxWireContractTest}. Dormant unless {@code artha.upstox.canary-enabled=true}.
 */
package in.arthayantra.marketdata.upstox.canary;
