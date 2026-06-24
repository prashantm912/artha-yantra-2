/**
 * Neutral external-feed ports — the source-agnostic interfaces (and their DTO records) that decouple
 * the {@code nse} scrapers from the {@code upstox} analytics side-channel. Both {@code nse} and {@code
 * upstox} depend on these ports; this package depends on NEITHER, so it breaks the
 * {@code nse → upstox → nse} module cycle (an NSE consumer reads a port, an Upstox adapter implements
 * it). Holds the FII/DII cash + FII-derivative fetcher ports (NSE-primary, Upstox swap-out) and the
 * per-stock {@code NewsSource} port (Upstox-only).
 */
package in.arthayantra.marketdata.feeds;
