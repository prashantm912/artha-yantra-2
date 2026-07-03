/**
 * DataHealthCanary (roadmap F4): the in-code live watcher between "container healthy" and "the
 * data the signal engine eats is flowing" — per-instrument tick/bar divergence (the 2026-07-03
 * CandleBuilder-poison signature the global FeedWatchdog is blind to), OI capture freshness
 * probes, ntfy alerting with cooldown, and the {@code /api/v1/market/health/data} read surface
 * the dashboard strip and the 09:42 live-health agent consume.
 */
package in.arthayantra.marketdata.canary;
