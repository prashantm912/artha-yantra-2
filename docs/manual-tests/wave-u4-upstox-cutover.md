# Manual test / runbook — Wave U4 Upstox live-stack cutover

Wave U4 is the **cutover**: flip the three live-capture sources — option-chain OI, spot/FUT quotes,
and the WS live ticker — from **Kite** to **direct Upstox** (the login-free 1-yr analytics token).
Everything U1–U3 built is additive + flag-gated and **already on `main`, default Kite, undeployed**.
This runbook is the exact flip sequence, the validation gates, and the rollback.

> **This is the OWNER + DEPLOY-gated step.** Nothing here is automated and nothing flips a default in
> code. The cutover-prep that ships WITH this runbook is: the contract canary now covers the
> live-capture shapes (`UpstoxContractCanary` + `upstox-contract-manifest.json`), and the OI A/B-diff
> tool (`tools/upstox/oi-ab-diff.sh`). The flip itself is below — do it deliberately, off-hours.

## Hard gate before ANY flip — backfill must be idle

A source flip needs a **market-data restart**, which kills the running expired-OI backfill THREAD.

> **SOFTENED 2026-06-25.** The backfill now **self-resumes** on restart: `ExpiredBackfillAutoResume`
> (#178) fires a coverage-aware resume on every boot (skips already-complete contracts → no progress
> lost), and the per-leg-timeout fix (#194) stops a hung leg from stalling the run. So a flip-restart
> no longer needs a manual re-POST and loses no backfill data — it continues where it left off. Still
> prefer **off-hours**: the resume re-walks the registry + contends with live capture, and a flip is a
> deliberate change best done calm. Confirm state with `GET /api/v1/market/admin/expired-backfill/status`.

## Prereqs (all three flips share these)

- LIVE stack up, instruments synced (`POST /api/v1/market/instruments/sync`).
- The Upstox analytics token secret file present: `/run/secrets/upstox_analytics_token` (the same
  long-lived 1-yr token U1/U2/U3 already use). It is NOT a daily-login token — capture survives a
  missed Kite login, which is the whole point of the cutover.
- `ARTHA_UPSTOX_ANALYTICS_ENABLED=true` — the option-chain/quote/WS clients require the analytics
  wiring. The chain + quote + ticker flags are no-ops without it.
- Recommended: `ARTHA_UPSTOX_CANARY_ENABLED=true` so the daily contract canary (now covering the
  live-capture shapes) ntfy-alerts on any Upstox-side field drift after the cutover.

## The flags (env var ← `artha.marketdata.source.*`, all default `kite`/`nse`/`native`)

| Capability | Spring property | Env var | Flip to | Gate |
|---|---|---|---|---|
| OI option-chain | `…source.optionchain` | `ARTHA_MD_SOURCE_OPTIONCHAIN` | `upstox` | OI A/B reconciles (§B below) |
| Spot/FUT quotes | `…source.quotes` | `ARTHA_MD_SOURCE_QUOTES` | `upstox` | quotes sane vs Kite |
| WS live ticker | `…source.ticker` | `ARTHA_MD_SOURCE_TICKER` | `upstox` | tick-latency A/B (U3 runbook) |

> **Compose passthrough gap — CLOSED 2026-06-25 (PR #197).** `deploy/docker-compose.yml` now passes
> `ARTHA_MD_SOURCE_TICKER: ${ARTHA_MD_SOURCE_TICKER:-kite}` alongside the other `ARTHA_MD_SOURCE_*`
> entries, so the ticker flip is a one-line `.env` change (`ARTHA_MD_SOURCE_TICKER=upstox`) like the
> other two — no inline `up -d` workaround needed. Default `kite` (no behaviour change until flipped).

## Recommended cutover ORDER (lowest risk → highest)

Flip ONE capability at a time, validate, then the next — never all three in one restart.

1. **`source.optionchain` → upstox** (LOW risk: additive, NSE-official both sides, A/B-validated).
2. **`source.quotes` → upstox** (MED risk: virtual-thread fan-out, no batch endpoint for some keys;
   Kite stays the fallback for unmapped keys).
3. **`source.ticker` → upstox** (HIGH risk: the scalp-latency gate — see the **U3 runbook**
   `docs/manual-tests/wave-u3-upstox-ws-ticker.md`, which is the authority for the ticker A/B; only
   flip after Upstox tick staleness ≤ Kite at p95 over ≥1 session).

---

## A. OI option-chain cutover (`ARTHA_MD_SOURCE_OPTIONCHAIN=upstox`)

### A.1 Validate FIRST with the OI A/B-diff tool (read-only, no flip)

`tools/upstox/oi-ab-diff.sh` diffs the per-strike OI from the **direct-Upstox `/v2/option/chain`**
against the **Kite OI already captured** in `marketdata.options_chain_snapshots` (the live Kite path,
untouched). Both are NSE/BSE-official, so they must reconcile before the flip. Run during market hours
(the Kite side must be fresh).

```bash
# from repo root, against the LIVE stack; EXPIRY is the option expiry to diff (ISO)
EXPIRY=2026-06-30 bash tools/upstox/oi-ab-diff.sh
# knobs: TOL_PCT (per-leg %diff WARN threshold, default 5), FRESH_MIN (Kite-row staleness, default 15)
```

It prints, per underlying (NIFTY 50 + SENSEX):
- **CANARY** — Upstox total OI > 0 (the chain actually returns OI on the analytics token).
- **PARITY** — a strike-by-strike `upstox_oi | kite_db_oi | diff%` table; legs over `TOL_PCT` flagged
  `<-WARN`. The token is read INSIDE the container (never leaves the box); the slim image has no
  python, so JSON is parsed on the host — same plumbing discipline as `tools/openalgo/oi-parity-check.sh`.

**PASS the flip gate only when** both CANARY and PARITY are PASS (no over-tolerance legs) for the
scalped underlyings, over a live session. A handful of stale legs (`STALE/-`) outside market hours is
expected — re-run during a session.

### A.2 Flip + recreate market-data (off-hours, backfill idle)

```powershell
# live values; mock = artha_mock / 1 (see CLAUDE.md). Set in .env or inline:
$env:ARTHA_DB_NAME='artha'; $env:ARTHA_REDIS_DB='0'
$env:ARTHA_UPSTOX_ANALYTICS_ENABLED='true'
$env:ARTHA_MD_SOURCE_OPTIONCHAIN='upstox'
docker compose -f deploy/docker-compose.yml --env-file .env up -d market-data-service
```
(Or set `ARTHA_MD_SOURCE_OPTIONCHAIN=upstox` + `ARTHA_UPSTOX_ANALYTICS_ENABLED=true` in `.env` and
`./ay.ps1 up`.)

### A.3 Confirm

- Market-data logs show the Upstox chain source bound (`UpstoxOptionChainQuoteSource`), not the Kite
  `QuoteGateway` per-strike path. No `no Upstox instrument_key for underlying …` warnings for the
  scalped indices.
- Fresh `options_chain_snapshots` rows keep accruing (the capture cadence is unchanged; only the
  source moved). Re-run `oi-ab-diff.sh` — the Upstox side is now also the DB side, so it should be a
  near-exact self-match (diff ≈ 0 within the snapshot interval).

---

## B. Spot/FUT quotes cutover (`ARTHA_MD_SOURCE_QUOTES=upstox`)

1. Off-hours, backfill idle, analytics enabled.
2. Flip + recreate (as A.2, with `ARTHA_MD_SOURCE_QUOTES=upstox`).
3. Confirm spot/FUT quotes flow on the same Redis channels and the values track Kite (spot-check
   `last_price`/OHLC for the index spots + pinned futures against the chart). Unmapped keys (some F&O
   FUT/option symbols) fall back to Kite — that is by design (the token→Upstox-key map covers indices
   + NSE cash today; the F&O key bridge is the follow-up).

---

## C. WS ticker cutover (`ARTHA_MD_SOURCE_TICKER=upstox`) — the §17.3 latency gate

**The ticker cutover is governed by the U3 runbook** —
`docs/manual-tests/wave-u3-upstox-ws-ticker.md` is the authority for the tick-latency A/B. Do NOT
flip the ticker until that gate is green:

- Run the live A/B (`ay_upstox_ws_tick_latency` median + p95 vs the Kite-WS baseline) over ≥1 session.
- **Flip eligible only if** Upstox tick staleness ≤ Kite at p95 for the scalp instruments, with no
  reconnect storms (`ay_kite_ws_reconnects_total` flat).
- Remember the F&O key-map gap (U3 runbook "Known gap"): index/spot latency is measurable today; a
  full option-strike scalp A/B needs the F&O token→Upstox-key map.

Then flip (add the `ARTHA_MD_SOURCE_TICKER` compose passthrough first — see the gap note above) and
confirm `upstox ws connected` in the logs + ticks on the shared Redis channels.

---

## Rollback (any capability, any time)

The Kite impls are kept as `@ConditionalOnProperty` fallbacks (owner directive 6f/6g — **never
deleted**). To revert, flip the flag back to `kite` and recreate market-data:

```powershell
$env:ARTHA_DB_NAME='artha'; $env:ARTHA_REDIS_DB='0'
$env:ARTHA_MD_SOURCE_OPTIONCHAIN='kite'   # and/or QUOTES / TICKER = kite
docker compose -f deploy/docker-compose.yml --env-file .env up -d market-data-service
```

No data is lost: the candle store, `options_chain_snapshots`, and the `SubscriptionRegistry` are all
source-agnostic. A revert is just another restart — same backfill-idle caveat applies.

## Post-cutover monitoring

- Leave `ARTHA_UPSTOX_CANARY_ENABLED=true`: the daily `UpstoxContractCanary` now probes
  `/v2/option/chain`, `/v2/market-quote/quotes`, and `/v3/feed/market-data-feed/authorize` and
  ntfy-alerts (critical) if Upstox renames/removes a consumed field — so a wire break is caught off
  the live path. (`ay_upstox_contract_drift_total` is the metric.)
- Greeks stay computed in `black76-math` regardless of source (the Upstox greeks are a cross-check,
  never the source of record).
```
