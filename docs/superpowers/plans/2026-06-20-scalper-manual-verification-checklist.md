# Scalper Manual-Verification Checklist — Backend done, UI deferred to React

**Goal:** Surface the manual-only parts of the Siva scalper method (the parts code cannot decide — news, S/R, regime, parabolic, VIX-abnormal, global cues, "one good trade") so they ride with each scalper signal and a human verifies + confirms before the trade is taken.

**Status (2026-06-20):**
- **Backend = DONE** (committed on `feat/scalper-track2`). The data the frontend needs is produced and exposed.
- **Frontend = DEFERRED to the React migration (Phase 4).** The existing Angular `frontend-ui` is being replaced (UI locked to React per `docs/superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md`), so the checklist UI is built **once, in React**, not in throwaway Angular. The consumer contract is specified below for that work to pick up.

**Architecture:** The checklist is a fixed, doc-referenced list the engine stamps onto each scalper signal via the existing V009 `scalper_detail` jsonb side-channel (parity-safe — outside the frozen `score_breakdown`, NULL for non-scalper signals). The signals REST `dto()` returns a `Map<String,Object>`, so exposing the side-channel adds response keys **without** a springdoc spec drift. No engine/golden/ExitEvaluator change; no new Flyway migration; no Kite/OpenAlgo touch.

---

## Backend — DONE (4 commits)

| Commit | What |
|---|---|
| `da4e529` (+ `99cf838` quality fix) | `ScalperManualChecks` — the canonical 7-item human-verification list + `appendTo(ObjectNode)` + `ScalperManualChecksTest` |
| `d6da6b9` | one-line stamp in `SignalEngine.scalperDetailJson` → every scalper ENTRY signal carries `manual_checks` on its side-channel |
| `541d1b7` | `SignalsController.dto()` exposes `tradeableExchange` / `tradeableTradingsymbol` / `scalperDetail`; `SignalsControllerTest` (incl. the non-scalper NULL case); Map-return ⇒ no springdoc drift |

**The 7 manual checks** (`ScalperManualChecks.CHECKS`, ASCII-only, doc refs into the in-repo consolidated strategy):

| key | label | doc_ref |
|---|---|---|
| `news_clear` | No market-moving news or event against this trade (news overrides the data). | 2.13 |
| `level_respected` | Price is reacting at the right support/resistance zone, not mid-range or into a wall. | 4.11 |
| `not_parabolic` | Entry is not chasing a parabolic or vertical move. | 3.1 |
| `regime_ok` | Market regime suits the setup (trending, not choppy or range-bound). | 3.10 |
| `vix_normal` | India VIX is not abnormally spiking (gap and whipsaw risk). | 4.6 |
| `global_cues_ok` | Global cues are not against the trade (DOW futures, Asian indices, crude, USD). | 4.7 |
| `clean_setup` | This is a clean 'one good trade' setup, not a forced or marginal entry. | 3.1 |

Each also carries an `assist` hint (where to look). The list lives server-side so the doc refs version with the consolidated strategy and non-scalper signals get nothing.

---

## Consumer contract for the React UI (Phase 4)

When the React signals surface is built, it consumes the signals REST API exactly as the Angular client did; the scalper additions are:

### Shape now returned by `GET /api/v1/signals/{id}` (and the list/active items)

The DTO gained three keys (all NULL for non-scalper signals):

```jsonc
{
  // ...existing fields (id, exchange, tradingsymbol, side, entryPrice, stopLoss, ...)
  "tradeableExchange": "NFO",                 // the option to trade (signal is keyed on the future)
  "tradeableTradingsymbol": "NIFTY24JUN24000CE",
  "scalperDetail": {                          // raw V009 jsonb (snake_case inner keys), null if not a scalper signal
    "side": "CE",
    "underlying": "NIFTY",
    "expiry": "2026-06-26",
    "tradeable": "NIFTY24JUN24000CE",
    "strike": 24000,
    "option_ltp": 120.0,
    "iv": 0.14,
    "delta": 0.62,
    "confluence_aggregate": 0.78,             // 0..1 — the automated confluence score
    "dots": [                                 // the 14 AUTOMATED confluence dots (read-only context)
      { "dot": "vwap", "weight": 2.5, "supports": true },
      { "dot": "supertrend", "weight": 1.0, "supports": true }
      // ...vwma, psar, rsi, volume, futures_oi, underlying_oi, trending_cross,
      //    sentiment, breadth, vix, basis, iv_rank
    ],
    "manual_checks": [                        // the 7 HUMAN-ONLY checks to render + gate on
      { "key": "news_clear", "label": "No market-moving news...", "doc_ref": "2.13", "assist": "Scan your news feed..." }
      // ...6 more
    ]
  }
}
```

### What React must do

1. **Hydrate on select.** The live push channel may omit the side-channel; on selecting a signal, `GET /api/v1/signals/{id}` to get the full `scalperDetail`. (Cheap, exactly when the detail view opens.)
2. **Render, for a scalper signal** (`scalperDetail != null`):
   - the chosen option summary (`side · tradeable · Δdelta · IV · confluence_aggregate%`),
   - the automated `dots` read-only (e.g. a tag per dot, green when `supports` — this is the "assist" that makes each human check a glance),
   - the `manual_checks` as a checkbox per item, each showing `label`, `§doc_ref`, and `assist`.
3. **Gate the "Take" action.** Disable Take until **every** `manual_checks` item is ticked. Invariant: `status=TAKEN ⇒ owner confirmed all manual checks`, so no new request field / no contract change is needed (avoids a `TakenRequest` schema drift). Re-arm (clear ticks, re-disable) whenever the selected signal changes.
4. **Non-scalper signals** (`scalperDetail == null`): no checklist, Take ungated — unchanged behaviour.

### Design notes (carry into the React build)
- **Inline checklist, not a confirm-dialog on click** — scalping is latency-sensitive; an extra modal per trade is friction. Take enables live as boxes are ticked.
- **Per-check server audit deferred** — recording WHICH boxes were ticked would add a `TakenRequest` field (request-schema drift + TS regen). Add only if an override/exception trail is needed.
- **Higher-value follow-up** — auto-fetching the check *inputs* (live news headlines, DOW futures level, S/R zones rendered on the chart) turns judgment into a glance. Separate slice; needs new feeds.

---

## Verification

- Backend focused tests (`ScalperManualChecksTest`, `SignalsControllerTest`) green; `ContractCaptureTest` shows no spec drift (Map return).
- Full `-pl services/strategy-signal-service -am verify` (Modulith + JaCoCo) to run before the PR.
- UI manual-test deferred — folds into the React Phase-4 signals-cockpit acceptance, not Angular.

## What was reverted
The Angular implementation (`scalper-confirm-panel.ts/.spec`, `signals.store.ts` side-channel types + `hydrate`) was built then **reverted** (`git reset` of commits `e1af9b1`, `2747209`) — it targeted the wrong framework. The React UI implements the contract above instead.
