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

## Live QA results — 2026-06-21 (Claude-in-Chrome vs live oipulse SENSEX chain)

**Confirmed MATCHING the live page:** the 18-column count + exact order (CALL `OI Int·OI%·OI·OI Chng·
IV·LTP·LTP%·LTP Chg` | Strike | PUT mirror | PCR Ratio); side-coloured OI bars (red CALL / green PUT);
green/red ΔOI; the OI-Int 4-state **colour semantics** (L.B.→green, S.B.→red, S.C.→blue, L.U.→amber);
ATM cream-tint row; per-strike PCR; signed-toned OI%/LTP%/LTP Chg; Go + Column Setting controls.

**Fixed after QA (this commit):**
- **IV shown as percent** (×100) — oipulse renders IV `13.96`, our fraction `0.14` was wrong. Now ×100.
- **Optional columns** → Delta / Volume / **Intrinsic** (the renderable subset of oipulse's Column-Setting
  set). Dropped Bid/Ask — oipulse has no such columns.

## Remaining divergences / gaps (surface to owner; some need a decision or a backend endpoint)

- **OI-Int badge** — FIXED to oipulse's abbreviation + arrow (`↑ L.B.` / `↓ S.B.` / `↑ S.C.` / `↓ L.U.`),
  full label kept as the accessible name (`aria-label`) + tooltip. Remaining divergence: oipulse uses a
  **solid colour fill**; ours keeps a **ring outline** (a deliberate WCAG-contrast choice — solid fills
  fail AA on some theme/severity combos). Colour semantics already matched.
- **INDIA VIX header** = WIRED (2026-06-21): `GET /api/v1/market/vix` (the pinned INDIA VIX index quote →
  LTP + day OHLC + change) now fills the header as `INDIA VIX 12.97 (+2.37%)`, with DH/DL/DO in the
  tooltip. 422 → `—` off-hours / in mock (no VIX quote). Was a Wave-3 gap; pulled forward.
- **Header detail**: oipulse header = INDIA VIX · Total PCR (+chg) · Underlying (name + LTP + chg% +
  timestamp). It does **NOT** show ATM / Days-to-Expiry chips (ours adds them as extras). Add PCR-change +
  underlying-change% + timestamp; ATM/DTE are an over-spec vs the live page.
- **Interval set** = `1m/3m/5m/15m/30m/60m` only (backend `OiInterval`). oipulse default is **Full Day** +
  2h/4h/10m/custom-time — need an `OiInterval` extension (deferred).
- **Grouped Name select** (Index / Stocks): flat list for now (cosmetic).
- **Strike column tan bg** + stronger ATM row; **max-ΔOI cell** = filled green/red bg (ours = accent ring).
- **No `+` prefix** on positives in oipulse (colour only). Ours adds `+` — an intentional a11y improvement
  (sign not colour-only); kept.
- **Remaining optional cols** (IV Chng / O=H / O=L / Premium / Combine-Premium / Straddle / Chart) deferred:
  need an IV-delta field, a strike-session-stats join, or a chart widget.
- **Strike click → chart sub-view** deferred (Wave-4 openalgo-chart). ATM row tinted, not yet clickable.
- **Greeks** computed in `black76-math` server-side (§17.9), NOT oipulse's server values — permanent,
  intended divergence (parity).

## Value-verify pass — 2026-07-01 (live-vs-live, SENSEX) — DRIVEN
Driven live vs oipulse's Options Chain. Header matches: **Total PCR 1.515 vs 1.5193 ✓**, **INDIA VIX
13.3875 vs 13.38 ✓**, spot 77011 vs 77016 ✓. ATM 77000: **PUT OI 3,528,160 EXACT**, CALL OI within 0.5%
(live skew), CE/PE LTP within a few points. 18-col layout + side-coloured OI bars + ATM tint + OI-Int badges
match. **IV divergence** (expected greeks class): ours assigns a single per-strike black76 IV to both legs
(CE==PE = 16.22%), oipulse shows distinct per-leg server IV (13.7 / 15.25). One **actionable finding
surfaced here:**

- **F1 — ✅ FIXED (#399): `/market/options/chain-table` now honours History mode.** It used to drop the
  History date and return the **live** chain (`asOf`=today, `spot`=live 24011, not 06-30's 23907). Now, when
  `mode=history` + a date, the chain is pivoted from the session's captured `options_chain_snapshots` (via
  `HistoricalOiReader`) — greeks null on history (the snapshot projection carries IV only, so the Delta
  column is blank in History). Live mode is byte-unchanged. Live-verified: History 2026-06-30 NIFTY →
  spot **23913.55**, asOf **2026-06-30T15:15**, pcr 0.8837, greeks null. See
  `phase-4-wave1-value-verify-runbook.md` (Part A findings, F1).
