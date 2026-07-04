# Minervini universe = full NSE EQ, low-cap-only (hard gates; reject F&O / large-cap)

Status: accepted (2026-07-04)

## Context

The Minervini SEPA screener
([plan](../superpowers/plans/2026-07-04-minervini-sepa-implementation-plan.md)) ranks a universe of Indian cash
equities and computes a **cross-sectional RS-rank** (percentile across that universe). The choice of universe is
load-bearing: it sets the RS-rank denominator, the backtest survivorship surface, and which names can ever surface.

The conventional convenient choice is a clean liquid index (NIFTY 500). But Minervini's own edge is explicitly in
**small/mid-cap young leaders with a small float** (§4.1 "95% Club": ~95% of past big winners had a small float;
§3.1 target profile = small/mid-cap, small float). Restricting to NIFTY 500 would filter out exactly the
superperformers the method hunts. The owner is emphatic on this: *"Low market cap is a boon. I ONLY trade low
market cap stocks because only these can show a dramatic rise in a short period. This is why I don't do large cap
and derivatives."*

## Decision

**Universe = the full NSE EQ list (~3,500), narrowed by hard low-cap + liquidity gates; RS-rank computed across
the filtered set.** NIFTY-500 scoping is an *optional* toggle, **default OFF**.

Hard gates (all configurable, all always-on):

| Gate | Default | Config key |
|---|---|---|
| min price | ₹30 | `min_price` |
| free-float market cap | < ₹5,000 cr | `max_free_float_mcap_cr` |
| free-float % of total | < 35% (tightly-held = small float) | `max_free_float_pct` |
| exclude F&O-listed | true | `exclude_fno` |
| avg-50d turnover | ≥ `liquidity_multiple × capital × max_name_pct` (= ₹9.375L/day default: `liquidity_multiple` lowered 100→25 on 2026-07-05 per the v6 turnover-floor sweep — ₹37.5L was a local-minimum floor at every book size; see `docs/strategies/minervini-swing-backtest-results.md` §6c) | `liquidity_multiple` |
| min history | ≥ 200 sessions | — |

Free-float % and free-float market cap come from the Upstox Share Holdings + Key Ratios endpoints
([ADR-0004](0004-minervini-fundamentals-via-upstox-api.md)); F&O membership from the NFO instrument list. Unknown
market cap → a manual-checklist item, not a silent drop.

## Consequences

- The screener can surface genuine small-caps *before* they become index constituents — the whole point of the
  method — while the liquidity floor (which scales with the owner's capital × max position size) keeps every
  candidate tradeable at the owner's size.
- The **low-cap gate depends on the Upstox fundamentals feed** (ADR-0004) for market-cap / free-float. Without it
  the gate cannot run; there is no price-only substitute for market cap.
- The **RS-rank denominator is this filtered set**, so RS numbers are not comparable to a NIFTY-500-scoped run;
  the toggle changes their meaning. Documented so future sessions don't compare across scopes.
- **Survivorship bias:** the full-EQ backtest surface has no delisted-membership history in bhavcopy — a vanished
  symbol reads as NA. Accepted and documented, not engineered around.
- Deviates from the conventional NIFTY-500 default; this ADR records why (the small-cap edge is the strategy).

## Alternatives considered

- **NIFTY 500** — clean, liquid, no market-cap-data dependency. Rejected: amputates the small-cap edge that is
  Minervini's actual source of return. Kept as an optional toggle for the owner to A/B later.
- **F&O-eligible list (~180 names)** — rejected: all large-cap, the exact set the owner excludes.
- **Total market cap instead of free-float** — rejected: free float (tradeable shares) is the Minervini factor;
  total cap includes locked promoter holding that never trades.
