# Live-market eyeball — 2026-07-06 (mid-session, owner AFK)

Scheduled task `m0706-midsession-512-eyeball`. Run ~13:45–14:00 IST, market open (Mon).
Read-only except the owner-approved on-demand stock-chain warm (Task 4) and this doc.
Stack healthy (all 12 containers up); live capture fresh (chains at 13:47 IST).

---

## Task 1 — #512 Active-Strike sentiment reconcile (RECOMMENDATION ONLY; owner ratifies)

### The two AY numbers (source: `ActiveStrikeService`)
- **`sentimentPct`** — the ΔOI-**flow** number: `100·(ΣpeΔOI − ΣceΔOI) / Σ(ceOi+peOi)` over top-5 active strikes.
- **`sentimentLevelPct`** — the **level** number (oipulse convention): `100·(ΣputOI − ΣcallOI) / ΣputOI` over top-5 active strikes.

**Which the GATE reads today:** the **ΔOI-flow `sentimentPct`** — confirmed at
`MarketOiClient.java:664` (`json.path("sentimentPct")` → `Sentiment.level()`/`.slope()`).
`sentimentLevelPct` is carried alongside in the response but the gate does not read it.

### Live AY numbers (in-container market-data `:8081`, asOf 13:45 IST)

| Index | flow `sentimentPct` (gate) | level `sentimentLevelPct` |
|---|---|---|
| NIFTY 50 (exp 2026-07-07) | **+0.34 %** | **+45.48 %** |
| SENSEX (exp 2026-07-09) | **−0.25 %** | **+45.47 %** |

Per-3-min-bucket series (last 6 buckets):

- **NIFTY flow:** −0.51, +0.12, −0.31, +0.12, −0.42, +0.34 → **sign-flips every bucket, hovers at 0**.
- **NIFTY level:** 46.04, 46.26, 45.79, 45.59, 45.13, 45.48 → **stable +45–46 %**.
- **SENSEX flow:** −0.01, −0.02, +0.40, 0.00, +0.20, −0.25 → noisy, ~0.
- **SENSEX level:** 46.42, 46.28, 46.49, 45.83, 45.86, 45.47 → stable.

### oipulse (authenticated Browser 1 — `/app/options-analysis/active-strikes-oi`)
- **NIFTY "Active Strike Sentiment %"**: **≈ +215 %** at 13:53 IST — **positive and stable** in the
  +200–300 % band all afternoon. Left chart: Put OI (red) ≈ 85 M ≫ Call OI (green) ≈ 28 M → put-heavy = bullish.
- **SENSEX: not available on oipulse** — the active-strike page is NSE-indices-only
  (BANKNIFTY/FINNIFTY/MIDCPNIFTY/NIFTY, no SENSEX).
- **oipulse formula** (confirmed live 2026-06-18, `PHASE-B-FINDINGS.md §6`, fitted EXACT):
  `Sentiment % = (ΣPut OI − ΣCall OI)/ΣPut OI × 100`, over the **single** server-picked active strike.
  → This IS the AY `sentimentLevelPct` formula. It is NOT the AY flow number (oipulse never computes flow).

### Three-way compare (NIFTY, ~13:45–13:53 IST)

| Source | Number | Sign | Character |
|---|---|---|---|
| oipulse Active Strike Sentiment % | **≈ +215 %** | + | positive, stable band |
| AY `sentimentLevelPct` (level) | **+45.48 %** | + | positive, stable |
| AY `sentimentPct` (flow — gate) | **+0.34 %** | ± | oscillates ~0, **flips sign each bucket** |

Magnitude gap (215 % vs 45 %) is explained by strike-set: oipulse uses the **single** active strike
(put-heavy → ratio runs high); AY sums **top-5** → an aggregate PCR-like ~45 %. Both are **positive and
stable → same direction**. The flow number sits at zero and whipsaws — it tracks neither oipulse nor price.

### RECOMMENDATION
**Switch the sentiment gate to read `sentimentLevelPct` (the LEVEL number).** Reasons:
1. **Calibration** — it is the exact formula oipulse plots, so Siva's cheat-sheet thresholds (which were
   read off oipulse) apply directly to it. The flow number has **no** oipulse counterpart and no calibrated thresholds.
2. **Noise** — today the flow number sign-flipped every 3-min bucket around 0 (−0.51 … +0.34); the level
   held a steady +45–46 %. A gate on flow is whipsawed by 3-min ΔOI noise.
3. **Cross-check (HDFCBANK)** — level = **−82.64 %** (correctly flags call-heavy/bearish) vs flow **+0.09 %** (~0, no signal).

Optional secondary refinement: for exact oipulse-**magnitude** parity, compute the level over the rank-1
active strike (not top-5). But top-5 is smoother and monotone with oipulse, so top-5 level is a fine gate operand as-is.

> This is a recommendation. The gate was **not** changed.

---

## Task 2 — F1 champion/challenger variant books

Source: `GET /api/v1/signal-rejections/shadow-summary` (strategy-signal `:8082`) + `strategy.shadow_positions`.

| Variant | Open | Closed | pnlNet (₹) | Note |
|---|---|---|---|---|
| champion | 8 | 26 | **+5418.19** | 14 opened today |
| vol-12k5 | 0 | 1 | **−160.15** | |
| vol-off | 2 | 0 | null | no closed yet (0.00 realized) |
| composite-070 | — | — | — | **ZERO rows all-time** |

- **Net-₹ labels ARE populating** (champion +₹5418.19, vol-12k5 −₹160.15). ✅
- **composite-070 dormant:** it IS configured (`docker-compose.yml:421`, `compositeThreshold:0.70`) but has
  never opened a row. It only fires when a rejection's sole block is composite AND composite ≥ 0.70; composite
  is capped ~0.765 and most live rejections are volume-floor blocks → it rarely qualifies. **Likely expected,
  but it is unfalsified — flag for owner** (may want to loosen or drop it).
- **ANOMALY — flag for owner (not fixed):** champion `pnlPoints` = **−423.50** but `pnlNet` = **+5418.19**,
  with 2 wins / 24 losses. Net-₹ positive while points negative and win-rate low is suspicious — possibly the
  same class as the reconcile-loop bug already flagged (`task_fc239b57`). Worth a look at the net-₹ / cost
  aggregation.

---

## Task 3 — F3.1 breadth dot

`GET /api/v1/market/breadth/live?index=NIFTY 50` → **advances 26, declines 24, unchanged 0, total 50,
`live:true`, asOf 2026-07-06.** ✅ Non-zero and live intraday.
(SENSEX → 404: breadth is a NIFTY-50-universe rule per `BreadthController` §12.3 javadoc — expected, not a defect.)

---

## Task 4 — Stock-chain 2nd-symbol warm (#472)

Owner-approved on-demand warm. `POST /api/v1/market/options/stock-chain/warm?name=HDFCBANK` (market-data `:8081`):

- RUNNING → **DONE in ~15 s**: 93 legs, **22,822 rows** persisted, 48 strikes, latest bar 13:57 IST.
- Reader serves it: `active-strikes?name=HDFCBANK&expiry=2026-07-28` returns a full chain
  (level −82.64 %, call-heavy). ✅

Confirms the on-demand Upstox stock-chain OI warm works for a **liquid non-RELIANCE** symbol during market
hours (previously only verified on RELIANCE off-hours).

---

## Verdict (concise)
- **#512:** recommend the sentiment gate read **`sentimentLevelPct` (level)**, not the current flow number —
  the level is oipulse's exact formula (so Siva thresholds apply) and is far less noisy (flow sign-flipped
  every bucket today). Owner to ratify.
- **F1:** ALL-CLEAR on net-₹ population (champion/vol-12k5/vol-off). Two flags: composite-070 never opens
  (unfalsified), and champion pnlNet/pnlPoints sign disagreement (possible net-₹ aggregation bug).
- **F3.1:** ALL-CLEAR — breadth 26/24 live.
- **Stock-chain warm:** ALL-CLEAR — HDFCBANK warmed 22.8k rows, reader serves it.
