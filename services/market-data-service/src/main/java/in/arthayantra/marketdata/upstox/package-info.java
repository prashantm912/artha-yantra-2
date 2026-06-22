/**
 * Upstox Market-Information analytics side-channel (ADR-0002). A second sanctioned, port-isolated
 * Upstox coupling (after ADR-0001's historical importer): {@code GET /v2/market/{fii,dii,oi,
 * change-oi,max-pain,pcr}} consumed via a hand-rolled REST client + full-mirror wire DTOs (the same
 * anti-corruption pattern as {@code kite/wire}), behind a dedicated, secret-file analytics token, with
 * the existing NSE scrapers kept as the swap-out fallback. Dormant by default ({@code
 * artha.upstox.analytics.enabled=false}); the U1 entitlement probe gates the rest of the build.
 */
package in.arthayantra.marketdata.upstox;
