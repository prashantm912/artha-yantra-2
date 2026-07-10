# Minervini fundamentals + market-cap via the Upstox Company Fundamentals API

Status: accepted (2026-07-04)

## Context

The Minervini SEPA implementation plan
([`docs/superpowers/plans/archive/2026-07-04-minervini-sepa-implementation-plan.md`](../superpowers/plans/archive/2026-07-04-minervini-sepa-implementation-plan.md))
needs per-stock fundamentals (quarterly EPS / sales / margins for "Code 33", ROE, P/E) **and** market-cap /
free-float data (for the owner's low-cap universe gate, [ADR-0005](0005-minervini-universe-low-cap-equities.md)).

The master-plan §9 ("openscreener Fundamentals Appliance") and the plan's own first draft both asserted that
**Upstox exposes no fundamentals**, so fundamentals had to come from a **Screener.in Playwright scraper**
(`openscreener`, MIT, run as an appliance) and market-cap had no source at all. That assertion was **wrong**:
verified 2026-07-04 against `https://upstox.com/developer/api-documentation/fundamentals/`, Upstox ships a
**Company Fundamentals API** — 8 endpoints (Company Profile, Balance Sheet, Cash Flow, Income Statement, Share
Holdings, Key Ratios, Corporate Actions, Competitors), keyed by **ISIN**, served on the **Analytics Token**
(long-lived, 1-year, read-only) that the platform already holds and uses for fii/dii/pcr/max-pain/margin.

There is no per-stock market-cap, shares-outstanding, or free-float column anywhere in the current schema
(`instruments`, `nse_eod_bhavcopy`, `fundamentals` V017 all lack it), so without a fundamentals feed the
low-cap gate is un-buildable.

## Decision

**Source Minervini fundamentals + market-cap from the Upstox Company Fundamentals API** (analytics token),
reversing master-plan §9's Screener.in-scraper decision.

- **Income Statement** → revenue, operating/net profit → sales growth + margin expansion.
- **Key Ratios** → EPS, ROE (§4.8 cutoff), P/E (§4.9 expansion), P/B (→ market cap).
- **Share Holdings** → promoter/FII/DII/public % → **free-float %** + free-float market cap (ADR-0005 gate).
- **Company Profile / Competitors** → sector classification + peers (§4.11 industry group).
- **Corporate Actions** → dividends/bonus/splits/rights (CA-adjustment cross-check).

Wire a hand-rolled `UpstoxFundamentalsClient` (RestClient, ADR-0002 anti-corruption pattern, parallel to
`UpstoxAnalyticsClient`) → domain records → the existing `marketdata.fundamentals` tall table + a small
market-cap/free-float table. ISIN is taken from the Upstox equity key `NSE_EQ|<ISIN>` via
`UpstoxEquityMasterClient`. Fundamentals thus becomes a **first-class required feed** (the low-cap *gate*
depends on it and is always on; the EPS/sales *confirmation* filter can still be toggled).

**Kept as fallback only:** the `openscreener` Screener.in scraper (master-plan §9) and the CSV backfill
(`tools/historical-import`) — for symbols Upstox does not cover or if the entitlement lapses.

## Consequences

- The plan's "single biggest gap" (fundamentals) largely dissolves; the low-cap universe gate becomes buildable.
- **Still unmodeled:** earnings-surprise (reported vs consensus, §4.8) and analyst estimate-revisions (§4.7 #6) —
  no free Indian consensus feed exists. The screen degrades gracefully around them (they were never gates).
- New coupling: the Minervini screener depends on the Upstox analytics entitlement (already funded). A build-time
  VERIFY must confirm the fundamentals endpoints ride the analytics token and expose a usable company-level
  market-cap field (Key Ratios P/B × book, or a direct field).
- Fundamentals data is **latest-restatement, not point-in-time** → it is **watchlist-only, never used in any
  backtest** (lookahead), per the plan §3-E.
- Reverses an "authoritative" master-plan section (§9). This ADR is the record of that reversal; §9 stays as the
  fallback design.

## Alternatives considered

- **openscreener (Screener.in scraper)** — master-plan §9's choice. Rejected as primary: a headless-browser
  scraper is slow, fragile (HTML drift), rate-limited, and a separate appliance to operate, versus a typed API on
  a token we already hold. Retained as fallback.
- **A paid fundamentals vendor** — unnecessary given Upstox covers everything except surprise/estimates, which no
  affordable Indian vendor supplies cleanly either.
