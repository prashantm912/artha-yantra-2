# Phase 4 / Wave 1 — Faithful Options Chain (manual test + live-QA gate)

Page: React `OptionsChainPage` → `/options/options-chain` (`frontend-react/`). Backend feed:
`GET /api/v1/market/options/chain-table` (live black76 greeks + interval deltas). Plan authority:
§20.7 (acceptance criteria) + §20.8 (the standing live-oipulse QA gate).

## How to run (dev, against the mock stack)

`frontend-react/` is NOT gateway-wired until the cutover, so run the Vite dev server against a
running mock stack:

1. Bring up the mock stack: `./ay.ps1 up` (mock profile) — gateway on 8080.
2. Seed a covered window so the chain + a snapshot pair exist (the deltas need ≥2 captured buckets):
   POST `/api/v1/market/options/snapshot {"underlying":"NIFTY 50"}` a couple of times a few minutes
   apart, or use a live window with accrued captures.
3. `cd frontend-react && npm run dev` → http://127.0.0.1:4300 (proxies `/api` → 8080). Log in with
   the owner password; navigate to Options Chain.

## Automated coverage (already green)

- `OptionsChainTable.spec.tsx` — mirrored CALL|strike|PUT structure, OI-Int badge, ATM sr-only cue,
  per-strike PCR, optional-column toggling.
- `ValueDeltaCell.spec.tsx` — signed tone (+green / −red), null dash, zero (no sign/tone).
- Backend: `OptionsChainIntegrationTest.chainTableOverlaysIntervalDeltasOnLiveGreeks`,
  `OptionsAnalyticsControllerIntegrationTest.strikeSeriesReturnsOnlyTheChosenStrikeBuckets`.
- `npm run lint` / `test:ci` / `build` all green.

## Live oipulse side-by-side QA gate (§20.8 — MANDATORY before "done")

Open the owner's logged-in oipulse Options Chain in Chrome (Claude-in-Chrome) and compare cell-for-cell
against `docs/oipulse-study/options/options-chain.md`:

- [ ] **Columns (18 visible, exact order §20.7.1):** CALL `OI Int · OI% · OI · OI Chng · IV · LTP ·
      LTP% · LTP Chg` | **Strike** | PUT mirror | **PCR**.
- [ ] **Colours carry the signal:** OI bars **red CALL / green PUT**; ΔOI **green(+)/red(−)** bars;
      per-row **OI-Int 4-state badge**; **ATM-row cream tint**; **ITM** leg tint; **max-OI / max-ΔOI /
      max-Vol** cell highlights; **LTP flash** on change.
- [ ] **Controls:** grouped Name select · Expiry · Interval · Mode · **Go** button · **Column Setting**.
- [ ] **Header strip:** Total PCR · ATM · Days-to-Expiry · underlying LTP. (Max-pain/Sentiment must NOT
      appear here — they belong to OI Statistics / Active Strikes.)

## Documented divergences / deferrals (record any new ones here)

These are deliberate substitutions or data gaps, not bugs — surface to the owner during live QA:

- **INDIA VIX header** = `—` (gap): India VIX is captured as a pinned index but has no read endpoint
  yet (Wave-3 §20.2). Header slot rendered, value pending.
- **Underlying DH/DL/DO** = pending: `chain-table` carries only `spot` (LTP), not the underlying's
  day OHLC. Header shows LTP only.
- **Total PCR prev/chg** = current only: `chain-table` has the current PCR; prev + change need a PCR
  history join (deferred).
- **Interval set** = `1m/3m/5m/15m/30m/60m` only (the backend `OiInterval` enum). oipulse's
  Full-Day / 2h / 4h / 10m / custom-time are NOT offered — they need an `OiInterval` extension (deferred).
- **Grouped Name select** (Index / Stocks optgroups): the shared `FilterBar` uses a flat list; grouping
  deferred (cosmetic).
- **Column Setting optional set** = Delta / Volume / Bid / Ask (real chain-table data). oipulse's
  O=H / O=L / Premium / Intrinsic / IV-Chng are deferred (O=H/O=L need a strike-session-stats join).
- **Strike click → chart sub-view**: oipulse opens a per-strike chart; deferred (no chart widget yet —
  Wave-4 openalgo-chart). The ATM row is tinted but not yet clickable.
- **Greeks** are computed in `black76-math` server-side (§17.9), NOT oipulse's server values — a
  permanent, intended divergence (parity).
