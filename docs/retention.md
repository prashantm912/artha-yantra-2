# Data retention & disk budget (Q4 record — Phase 16 / B-7)

**Policy: default no-drop.** Nothing in the marketdata schema carries a
TimescaleDB retention policy. The triggers below are REVIEW triggers, not
auto-deletes.

| Dataset | Policy | Rationale |
|---|---|---|
| `options_chain_snapshots` | **≥ 5-year floor (amendment A2)** — never dropped | The only irreplaceable dataset: Kite serves no historical options chains. Every row carries full solver provenance (`price_source`, `forward_price`, `risk_free_rate`), so a solver defect is a backfill migration, not data loss. |
| `candles` (1m/1d + CONT stitch) | Uncapped — DB-as-cache, re-fetchable | Kite serves ~10 y of minute history; a purge is a rate-limited re-backfill (~61 pages ≈ 21 s of 3/s budget per symbol). Compressed after 7 days. |
| Continuous aggregates (5m/15m/1h/1d/1w) | Derived — rebuildable by `refresh_continuous_aggregate` | Never the source of truth. |
| `roll_events`, `contract_spec_history`, `corporate_action_events` | Append-only, tiny | Audit trails; cost is rounding error. |
| Redis | Ephemeral by design (TTLs ≤ 60 s on chain keys; last-tick map bounded by instrument count) | D11: shared state only, DB is truth. |

## Disk budget (B-7)

Rough steady-state estimates at the default scope (NIFTY 50 + NIFTY BANK
chains, ~200 active equities, pinned FUT/VIX):

| Source | Growth | 1 year |
|---|---|---|
| Options snapshots (962 rows × ~75 passes/day × 2 underlyings, compressed) | ~25–40 MB/day raw → ~4–8 MB/day compressed | ~1.5–3 GB |
| 1m candles (~250 instruments × 375 bars/day, compressed) | ~2–4 MB/day | ~0.7–1.5 GB |
| Everything else | noise | < 0.5 GB |

**Review trigger: 50 GB total volume.** At that point review compression
ratios and snapshot scope (`ay status` + the `ay_hypertable_bytes` gauge are
the inputs) — the answer is widening disk or narrowing scope, never silent
retention.

Backups: the Stage A db-backup sidecar takes per-schema `pg_dump -Fc` at
00:30 IST with 14-day + 8-week rotation; restore drill quarterly.
