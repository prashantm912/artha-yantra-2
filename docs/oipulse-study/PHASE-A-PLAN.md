# Phase A — Playwright API Capture (After-Hours)

**Goal:** Confirm every API endpoint schema across all 53 pages using Playwright network capture +
JS-evaluated Vue state. Replace "inferred" entries in docs with confirmed request body + response
field shapes.

**When:** Any time (market closed OK — EOD data available on all pages).
**Tool chain:** Playwright MCP (`browser_network_requests` + `browser_evaluate`) + OiPulse session
cookies saved to `docs/oipulse-study/.oipulse-state.json` (gitignored).

---

## Auth Setup (one-time)

1. Load Playwright tools via ToolSearch
2. Navigate to `https://www.oipulse.com/app/login` (or root — redirects)
3. Fill email + password via `browser_fill_form`
4. Confirm redirect to `/app/dashboard`
5. Save storage state to `docs/oipulse-study/.oipulse-state.json`
6. All subsequent navigations load this state — no re-login needed

---

## Per-Page Process (repeat for each page)

```
1. browser_navigate(url)
2. browser_wait_for(networkIdle or 3s)
3. browser_network_requests() → filter POST requests to api.oipulse.com
4. For each captured request:
   a. Record endpoint path
   b. Record request body (exact JSON params)
   c. Record response: status, envelope {status,msg,data}, first 1-2 data rows
   d. Record field names + inferred types (st*/in*/ob*/ar* prefixes)
5. browser_evaluate(Vue state extraction script):
   el = document.querySelector('[class*="container"], main, #app')
   keys = Object.keys(el).find(k => k.startsWith('__vue'))
   vm = el[keys]
   // walk up until domain-specific keys found
   → extract: filter values, dropdown options, socket channel names
6. Compare captured vs existing doc:
   - New endpoint? Add it.
   - Request body differs? Correct it.
   - Response fields differ? Correct them.
   - Filter options more complete? Expand them.
7. Update the page's .md with confirmed data. Mark confirmed fields with note if previously inferred.
```

---

## Pages — Priority Order

### Tier 1: High-value (complex APIs, most likely to have inferred gaps)

| # | Page | URL | Doc file | Key API namespace |
|---|---|---|---|---|
| 1 | Connecting Dots | `/app/connecting-dots` | `features/connecting-dots.md` | `connecting-dots/*` |
| 2 | Vix & Index | `/app/vix-price` | `features/vix-index.md` | `vix-price/*` |
| 3 | Futures OI Analysis | `/app/futures-analysis` | `futures/oi-analysis.md` | `futures/*` |
| 4 | Futures OI Chart | `/app/futures-analysis/oi-chart` | `futures/oi-chart.md` | `futures/*alldataforchart` |
| 5 | Futures OI Spurt | `/app/futures-analysis/oi-spurt` | `futures/oi-spurt.md` | `futures/*spurtdata` |
| 6 | Futures OI Buzz | `/app/futures-analysis/oi-buzz` | `futures/oi-buzz.md` | `heatmap/*` |
| 7 | Banks Analysis | `/app/futures-analysis/banks-analysis` | `futures/banks-analysis.md` | `bank-analysis/*` |
| 8 | Options OI Analysis | `/app/options-analysis` | `options/oi-analysis.md` | `options/*` |
| 9 | Options Chain | `/app/options-analysis/options-chain` | `options/options-chain.md` | `options/*chain` |
| 10 | Options OI Chart | `/app/options-analysis/chart` | `options/oi-chart.md` | `options/*chart` |
| 11 | OI Statistics | `/app/options-analysis/oi-stats` | `options/oi-statistics.md` | `options/*stats` |
| 12 | Straddle Chart | `/app/strategies/straddle-chart` | `strategies/straddle-chart.md` | `strategy/*straddle` |

### Tier 2: Medium-value (filter-heavy, dropdown enumeration, secondary APIs)

| # | Page | URL | Doc file | Key API namespace |
|---|---|---|---|---|
| 13 | Options OI Spurt | `/app/options-analysis/oi-spurt` | `options/oi-spurt.md` | `options/*spurt` |
| 14 | Options Premium | `/app/options-analysis/option-premium` | `options/options-premium.md` | `options/*premium` |
| 15 | Options Chart | `/app/options-analysis/options-chart` | `options/options-chart.md` | `options/*chart` |
| 16 | Trending OI | `/app/options-analysis/trending-oi` | `options/trending-oi.md` | `trending-oi-static/*` |
| 17 | Trending OI PA | `/app/options-analysis/trending-oi-with-pa` | `options/trending-oi-pa.md` | `trending-oi-static/*` |
| 18 | Big OI Movement | `/app/options-analysis/big-oi-movement` | `options/big-oi-movement.md` | `big-oi-movement/*` |
| 19 | Active Strikes OI | `/app/options-analysis/active-strikes-oi` | `options/active-strikes-oi.md` | `active-strike-oi/*` |
| 20 | Active Strikes IV | `/app/options-analysis/active-strikes-iv` | `options/active-strikes-iv.md` | `active-strike-oi/*` |
| 21 | Interval Wise OI | `/app/options-analysis/interval-wise-oi` | `options/interval-wise-oi.md` | `interval-wise-oi/*` |
| 22 | Multiple OI Chart | `/app/options-analysis/multiple-oi-chart` | `options/multiple-oi-chart.md` | `options/*` |
| 23 | Strangle Chart | `/app/strategies/strangle-chart` | `strategies/strangle-chart.md` | `strategy/*strangle` |
| 24 | Strategy Builder | `/app/strategies/strategy-builder` | `strategies/strategy-builder.md` | `strategy-builder/*` |
| 25 | Calendar Spread | `/app/strategies/calender-spread` | `strategies/calendar-spread.md` | `strategy/*` |
| 26 | Multi Leg Price | `/app/strategies/multi-leg-price` | `strategies/multi-leg-price.md` | `strategy/*` |
| 27 | Open High Strategy | `/app/options-analysis/open-high-strategy` | `strategies/open-high-strategy.md` | `open-high-strategy/*` |
| 28 | OI Expiry Strategy | `/app/options-analysis/oi-expiry-strategy` | `strategies/oi-expiry-strategy.md` | `opt-eod-oi-analysis/*` |
| 29 | Index Contribution | `/app/index-contribution` | `equity/index-contribution.md` | `index-contribution/*` |
| 30 | Sector Stats | `/app/equity/sector-stats` | `equity/sector-stats.md` | `equity/*` |
| 31 | Sector Heatmap | `/app/equity/sector-wise-heatmap` | `equity/sector-heatmap.md` | `equity/*heatmap` |
| 32 | Equity Returns | `/app/equity/equity-returns` | `equity/equity-returns.md` | `equity/*returns` |
| 33 | Delivery Data | `/app/equity/delivery-data` | `equity/delivery-data.md` | `equity/*delivery` |
| 34 | Announcement | `/app/equity/announcement` | `equity/announcement.md` | `equity/*announcement` |
| 35 | FII Capital Market | `/app/fii-dii/capital-market` | `fii-dii/capital-market.md` | `fii-dii/*capital` |
| 36 | FII Derivative Stats | `/app/fii-dii/fii-derivative-stats` | `fii-dii/fii-derivative-stats.md` | `fii-dii/*derivative` |
| 37 | Participant Wise OI | `/app/fii-dii/participant-wise-oi` | `fii-dii/participant-wise-oi.md` | `fii-dii/*participant` |
| 38 | FII Long Short Ratio | `/app/fii-dii/fii-long-short-ratio` | `fii-dii/fii-long-short-ratio.md` | `fii-dii/*lsr` |

### Tier 3: Lower-value (simple APIs already well-captured, or no API)

| # | Page | URL | Doc file | Action |
|---|---|---|---|---|
| 39 | Futures Pre-Open | `/app/futures-analysis/pre-open-market` | `futures/pre-open-market.md` | Confirm pre-open request body |
| 40 | Futures Market Movers | `/app/futures-analysis/market-movers` | `futures/market-movers.md` | Confirm endpoint |
| 41 | Futures EOD OI Analyzer | `/app/futures-analysis/eod-oi-analyzer` | `futures/eod-oi-analyzer.md` | Confirm endpoint + row schema |
| 42 | Equity Pre-Open | `/app/equity/pre-open-market` | `equity/pre-open-market.md` | Confirm endpoint |
| 43 | Equity Open High Low | `/app/equity/open-high-strategy` | `equity/open-high-low.md` | Confirm endpoint |
| 44 | Advance Chart | `/app/advance-chart` | `advance-chart/advance-chart.md` | Confirm UDF endpoints |
| 45 | Multiframe Chart | `/app/multi-frame-advance-chart` | `advance-chart/multiframe-chart.md` | Confirm UDF variants |
| 46 | World Indices | `/app/world-indices` | `features/world-indices.md` | ✅ CONFIRMED — verify only |
| 47 | Dashboard | `/app/dashboard` | `features/dashboard.md` | ✅ CONFIRMED — verify ticker API |
| 48 | Multiple Window | `/app/multiple-window` | `features/multiple-window.md` | No API — layout only |
| 49 | Risk Calculator | `/app/risk-calculator` | `features/risk-calculator.md` | Client-only — no API expected |
| 50 | Plans | `/app/plans` | `features/plans.md` | Confirm `user-tool-plan/*` endpoint |
| 51 | Event Days | `/app/event-days` | `features/event-days.md` | Static images — skip |
| 52 | Market Holidays | `/app/market-holidays` | `features/market-holidays.md` | Confirm `market-view/*` endpoint |
| 53 | Update Logs | `/app/website-update-logs` | `features/update-logs.md` | Confirm endpoint if any |

---

## Vue State Extraction Script (reusable)

```javascript
// Run via browser_evaluate on each page
(function() {
  // Walk DOM to find Vue root with domain data
  function findVue(selector) {
    const candidates = document.querySelectorAll(selector);
    for (const el of candidates) {
      const key = Object.keys(el).find(k => k.startsWith('__vue'));
      if (!key) continue;
      let vm = el[key];
      // Walk up component tree until domain keys found
      while (vm) {
        const d = vm.$data || {};
        const keys = Object.keys(d);
        if (keys.length > 2) return { keys, sample: Object.fromEntries(keys.slice(0,10).map(k => [k, typeof d[k]])) };
        vm = vm.$parent;
      }
    }
    return null;
  }
  return findVue('[class*="container"]') || findVue('main') || findVue('#app');
})()
```

---

## Doc Update Protocol

For each page, only update if Playwright data differs from existing doc:

- **New endpoint found** → add to "Data source / API" section
- **Request body param differs** → correct in the request block
- **Response field missing/wrong** → correct field name / type
- **Filter dropdown options enumerated** → expand the filter table row
- **Confirmed something previously marked "inferred"** → add `(confirmed via Playwright {date})`
- **No change** → leave doc untouched (don't bump files for no reason)

---

## Progress Tracking

Update this table as pages complete:

| Page | API captured | Vue state | Doc updated | Notes |
|---|---|---|---|---|
| Connecting Dots | ☐ | ☐ | ☐ | |
| Vix & Index | ☐ | ☐ | ☐ | |
| Futures OI Analysis | ☐ | ☐ | ☐ | |
| Futures OI Chart | ☐ | ☐ | ☐ | |
| Futures OI Spurt | ☐ | ☐ | ☐ | |
| Futures OI Buzz | ☐ | ☐ | ☐ | |
| Banks Analysis | ☐ | ☐ | ☐ | |
| Options OI Analysis | ☐ | ☐ | ☐ | |
| Options Chain | ☐ | ☐ | ☐ | |
| Options OI Chart | ☐ | ☐ | ☐ | |
| OI Statistics | ☐ | ☐ | ☐ | |
| Straddle Chart | ☐ | ☐ | ☐ | |
| Options OI Spurt | ☐ | ☐ | ☐ | |
| Options Premium | ☐ | ☐ | ☐ | |
| Options Chart | ☐ | ☐ | ☐ | |
| Trending OI | ☐ | ☐ | ☐ | |
| Trending OI PA | ☐ | ☐ | ☐ | |
| Big OI Movement | ☐ | ☐ | ☐ | |
| Active Strikes OI | ☐ | ☐ | ☐ | |
| Active Strikes IV | ☐ | ☐ | ☐ | |
| Interval Wise OI | ☐ | ☐ | ☐ | |
| Multiple OI Chart | ☐ | ☐ | ☐ | |
| Strangle Chart | ☐ | ☐ | ☐ | |
| Strategy Builder | ☐ | ☐ | ☐ | |
| Calendar Spread | ☐ | ☐ | ☐ | |
| Multi Leg Price | ☐ | ☐ | ☐ | |
| Open High Strategy | ☐ | ☐ | ☐ | |
| OI Expiry Strategy | ☐ | ☐ | ☐ | |
| Index Contribution | ☐ | ☐ | ☐ | |
| Sector Stats | ☐ | ☐ | ☐ | |
| Sector Heatmap | ☐ | ☐ | ☐ | |
| Equity Returns | ☐ | ☐ | ☐ | |
| Delivery Data | ☐ | ☐ | ☐ | |
| Announcement | ☐ | ☐ | ☐ | |
| FII Capital Market | ☐ | ☐ | ☐ | |
| FII Derivative Stats | ☐ | ☐ | ☐ | |
| Participant Wise OI | ☐ | ☐ | ☐ | |
| FII Long Short Ratio | ☐ | ☐ | ☐ | |
| Futures Pre-Open | ☐ | ☐ | ☐ | |
| Futures Market Movers | ☐ | ☐ | ☐ | |
| Futures EOD OI Analyzer | ☐ | ☐ | ☐ | |
| Equity Pre-Open | ☐ | ☐ | ☐ | |
| Equity Open High Low | ☐ | ☐ | ☐ | |
| Advance Chart | ☐ | ☐ | ☐ | |
| Multiframe Chart | ☐ | ☐ | ☐ | |
| World Indices | ✅ | ✅ | ✅ | Confirmed session 2 |
| Dashboard | ✅ | ✅ | ✅ | Confirmed session 2 |
| Multiple Window | ☐ | ☐ | — | No API |
| Risk Calculator | ☐ | ☐ | — | Client-only |
| Plans | ☐ | ☐ | ☐ | |
| Event Days | — | — | — | Static images, skip |
| Market Holidays | ☐ | ☐ | ☐ | |
| Update Logs | ☐ | ☐ | ☐ | |

---

## Gitignore entry required

Add to `.gitignore` (root):
```
docs/oipulse-study/.oipulse-state.json
```
