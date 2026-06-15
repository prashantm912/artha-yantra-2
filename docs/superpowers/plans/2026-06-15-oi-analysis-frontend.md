# OI Analysis Frontend (Phase 3-lite + Phase 4 archetype-1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the 4 live Phase-2 OI-analytics endpoints into two new oipulse-parity pages — Options OI Analysis and Futures OI Analysis — plus the minimal shared foundation (`ay-data-bar` + a cross-route `SymbolContextStore`).

**Architecture:** Two NgRx SignalStores — `SymbolContextStore` (the universal control-bar selection: mode·name·date·expiry·interval, persisted to localStorage) and `OiAnalyticsStore` (REST reads of the 4 endpoints, decimal-string-safe, folded to a CE/PE strike grid). Two standalone OnPush pages reload via `effect()` on context change. One shared `ay-data-bar` in-cell magnitude bar.

**Tech Stack:** Angular 21 zoneless · PrimeNG 21 (`p-select`/`p-table` scrollable/`p-tag`/`p-button`) · `@ngrx/signals` · vitest + jsdom · `core/decimal.ts` (BigDecimal-as-string).

**Scope decisions (deviations from the master plan, intentional):**
- **No `ay-oi-int-badge` in this increment.** The 4-state OI-Interpretation needs a *price delta*; `oi-analysis` returns one bucket (no Δprice). Defer the badge to the `/spurt` follow-on (which carries `ltpDelta`). CLAUDE.md "no speculative/unused code" > master-plan "build foundation once".
- **No charts.** Tables + stat cards only — avoids the zoneless ECharts/LWC surface for a first increment. Charts land with OI-Stats archetype later.
- **Dedicated `SymbolContextStore`, not an extension of `SessionStore`.** SRP — keep auth/theme store uncoupled from market-symbol selection.
- **Wire-type gotcha:** the generated `.d.ts` types BigDecimal as `number`, but Jackson serializes BigDecimal **as JSON strings** at runtime (backend ITs assert `"1.5000"`). Hand-type every decimal field as `string` and format via `decimal.ts`. `long` fields (oi/ceOi/peOi) stay `number`.

**No gateway change** — `/api/v1/market/**` is a generic wildcard proxy + `/api/**` auth; the 4 GETs work as-is.

---

## File Structure

- Create `frontend-ui/src/app/shared/data-bar.ts` (+ `.spec.ts`) — `ay-data-bar`.
- Create `frontend-ui/src/app/stores/symbol-context.store.ts` (+ `.spec.ts`) — `SymbolContextStore`.
- Create `frontend-ui/src/app/stores/oi-analytics.store.ts` (+ `.spec.ts`) — `OiAnalyticsStore` + `foldStrikes`.
- Create `frontend-ui/src/app/pages/oi/oi-control-bar.ts` — `OiControlBar`.
- Create `frontend-ui/src/app/pages/oi/oi-options-page.ts` (+ `.spec.ts`) — `OiOptionsPage`.
- Create `frontend-ui/src/app/pages/oi/oi-futures-page.ts` (+ `.spec.ts`) — `OiFuturesPage`.
- Modify `frontend-ui/src/app/app.routes.ts` — add `oi`, `oi/options`, `oi/futures`.
- Modify `frontend-ui/src/app/shell/app-shell.ts` — add two nav anchors.

**Verify trio** (PowerShell `Push-Location frontend-ui`): `npm run lint` + `npm run test:ci` + `npm run build`.

---

## Task 1: `ay-data-bar`

**Files:** Create `frontend-ui/src/app/shared/data-bar.ts`, Test `frontend-ui/src/app/shared/data-bar.spec.ts`

- [ ] Step 1 — Write the failing spec: value 50 / max 100 → `--ay-bar-w: 50%`; value > max clamps to 100%; tone class applied; label rendered.
- [ ] Step 2 — Implement: OnPush standalone, inputs `value`/`max` required numbers, `label` string, `tone` `'bull'|'bear'|'neutral'`; `pct = clamp(abs(value)/max*100, 0, 100)`; template `<span class="bar" [class]="tone()" [style.--ay-bar-w.%]="pct()">…label…</span>`; CSS `::before` width `var(--ay-bar-w)` tinted via `color-mix(in srgb, var(--ay-bull|bear|text-muted) 22%, transparent)` (no raw hex — Stylelint).
- [ ] Step 3 — `npm run test:ci` green; commit.

## Task 2: `SymbolContextStore`

**Files:** Create `frontend-ui/src/app/stores/symbol-context.store.ts`, Test `.spec.ts`

State `{ name='NIFTY 50', expiry: string|null=null, interval='3m', mode:'live'|'history'='live', date: string|null=null, expiries: string[]=[] }`. Export `OI_INTERVALS = ['1m','3m','5m','15m','30m','60m']`. Methods `setName` (clears expiry, persists, `loadExpiries()`), `setExpiry`, `setInterval`, `setMode`, `setDate`, `loadExpiries()` (`GET /api/v1/instruments/{name}/expiries` → defaults expiry to `[0]`). Persist `ay.oi.{name,expiry,interval}`; `onInit` hydrate + `loadExpiries()`.

- [ ] Step 1 — Spec: setInterval persists to localStorage; hydrate reads it; setName clears expiry + fires the expiries GET (flush) + defaults expiry.
- [ ] Step 2 — Implement mirroring `SessionStore` localStorage idiom.
- [ ] Step 3 — test:ci green; commit.

## Task 3: `OiAnalyticsStore` + `foldStrikes`

**Files:** Create `frontend-ui/src/app/stores/oi-analytics.store.ts`, Test `.spec.ts`

Hand-typed interfaces (decimals=string): `OiStats{pcr:string|null,maxPain:string|null,ceOi:number,peOi:number,asOf:string}`, `StrikeView{strike:string,ceOi:number,peOi:number}`, `ActiveStrikes{sentimentPct:string|null,items:StrikeView[],asOf:string}`, `OiStrikePoint{bucket,strike,optionType:'CE'|'PE',ltp:string|null,oi:number|null,oiChange:number|null,iv:string|null,spot:string|null}`, `FuturesOiPoint{bucket,tradingsymbol,ltp:string|null,oi:number|null,oiChange:number|null}`, `OiChainRow{strike,ce:LegCell|null,pe:LegCell|null,spot:string|null}`.

`foldStrikes(points)` — group by strike, CE/PE → cells, carry spot, sort by `compareDecimal`. Methods: `loadOptions()` (guard name+expiry; 3 parallel GETs — `oi-stats`+`active-strikes` silence DATA_GAP toast via `SILENCE_ERROR_TOAST`, `oi-analysis` → `{items}`), `loadFutures()` (GET futures/oi-analysis, no expiry param). Computed `chainRows`, `maxOptionOi`, `maxFuturesOi`, `spot`.

- [ ] Step 1 — Spec: `foldStrikes` merges CE+PE, sorts, carries spot; `loadOptions` fires 3 calls + maps; 422 on oi-stats → `oiStats=null`; `loadFutures` maps items.
- [ ] Step 2 — Implement.
- [ ] Step 3 — test:ci green; commit.

## Task 4: `OiControlBar`

**Files:** Create `frontend-ui/src/app/pages/oi/oi-control-bar.ts`

Selector `ay-oi-control-bar`, input `showExpiry=true`. Underlying `p-select` (MarketStore.underlyings fallback `['NIFTY 50','NIFTY BANK']`) → `ctx.setName`; `@if(showExpiry())` expiry `p-select` (`ctx.expiries()`) → `ctx.setExpiry`; interval `p-select` (`OI_INTERVALS`) → `ctx.setInterval`; live/history toggle `p-button` → `ctx.setMode`; `@if history` native `<input type="date">` → `ctx.setDate`. Constructor `market.loadUnderlyings()`.

- [ ] Step 1 — Implement (no separate spec; covered by page specs). Commit.

## Task 5: `OiOptionsPage`

**Files:** Create `frontend-ui/src/app/pages/oi/oi-options-page.ts`, Test `.spec.ts`

Inject `OiAnalyticsStore`+`SymbolContextStore`. `effect()` reads name/expiry/interval/mode/date → `store.loadOptions()`. Template: sr-only h1; `<ay-oi-control-bar />`; stats line (PCR `formatDecimal(pcr,4)`, Max pain `formatDecimal(maxPain,2)`, CE/PE OI, asOf, sentiment `%`, `'—'` on null); scrollable `p-table` `[value]="store.chainRows()"` cols `CE OI(ay-data-bar) | CE ΔOI | CE IV | CE LTP | Strike | PE LTP | PE IV | PE ΔOI | PE OI(ay-data-bar)`; ITM highlight via `compareDecimal(strike, spot)`; empty message.

- [ ] Step 1 — Spec: seed `ay.oi.expiry` in localStorage, mount, flush expiries + 3 endpoint responses, assert a strike row + PCR text render.
- [ ] Step 2 — Implement.
- [ ] Step 3 — test:ci green; commit.

## Task 6: `OiFuturesPage`

**Files:** Create `frontend-ui/src/app/pages/oi/oi-futures-page.ts`, Test `.spec.ts`

Inject same stores. `effect()` reads name/interval/mode/date → `store.loadFutures()`. Template: sr-only h1; `<ay-oi-control-bar [showExpiry]="false" />`; scrollable `p-table` `[value]="store.futures()"` cols `Contract | LTP | OI(ay-data-bar) | Δ OI(ay-data-bar signed)`; empty message.

- [ ] Step 1 — Spec: mount, flush futures/oi-analysis, assert a contract row renders.
- [ ] Step 2 — Implement.
- [ ] Step 3 — test:ci green; commit.

## Task 7: Routes + nav

**Files:** Modify `app.routes.ts`, `app-shell.ts`

- [ ] Add `{path:'oi',pathMatch:'full',redirectTo:'oi/options'}`, `oi/options`→`OiOptionsPage`, `oi/futures`→`OiFuturesPage` to the shell children.
- [ ] Add `<a routerLink="/oi/options">Options OI</a>` + `<a routerLink="/oi/futures">Futures OI</a>` after the Futures nav link.
- [ ] Commit.

## Task 8: Verify + review + PR

- [ ] `npm run lint` + `npm run test:ci` + `npm run build` all green.
- [ ] Adversarial review (ui-a11y-reviewer + decimal-wire correctness + general).
- [ ] Open PR; squash-merge.

## Self-review checklist
- Every decimal field typed `string`, formatted via `decimal.ts` (never `Number()` for money). ✔
- `p-table` scrollable, NOT virtual (zoneless 0-rows). ✔
- `pi-*` icons only on `p-button` (a11y accessible-name). ✔
- `SILENCE_ERROR_TOAST` on the expected-empty DATA_GAP calls. ✔
- No raw hex outside `styles.scss`. ✔
