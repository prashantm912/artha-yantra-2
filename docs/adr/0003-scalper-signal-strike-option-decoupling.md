# Scalper signal / strike-anchor / option-execution three-way decoupling (SENSEX off NIFTY-fut)

Status: accepted (2026-06-26)

## Context

The Siva scalper strategies are instrument-AGNOSTIC: each applies to **(NIFTY-future signal → NIFTY
options)** AND **(NIFTY-future signal → SENSEX options)**. The signal is computed on the **NIFTY index
future** in BOTH pairings — SENSEX options are traded off the *NIFTY-future* signal (a correlation play,
SENSEX ≈ NIFTY), NOT a SENSEX-index signal.

The Part-2 premium-as-primary backtest engine (`OptionsPremiumReplay`) originally used ONE
`(exchange, tradingsymbol)` for three different jobs:

1. the **signal series** the indicators/entry/exit run on,
2. the **strike-reference spot** that picks the ATM option, and
3. the **option-execution root** (which chain's legs are bought).

2b-E2 split (3) out (`universe.signal_underlying` vs `universe.underlying`). But (1) and (2) were still
fused: the ATM strike is selected from the **signal bar's close**. For a SENSEX strategy the signal is
NIFTY-fut (~24 000) while SENSEX trades ~80 000, so selecting a SENSEX strike from the NIFTY-fut price
picks a non-existent / wildly-OTM strike. The decoupling is incomplete without separating the
strike-reference spot from the signal.

Three independent calendars are in play and must never be conflated:

| Series | Calendar | Role |
|---|---|---|
| `NIFTY-FUT-CONT` | NIFTY monthly roll | signal / direction (both variants) |
| `SENSEX-FUT-CONT` | SENSEX monthly roll | SENSEX spot → strike anchor (SENSEX variant) |
| SENSEX option | SENSEX weekly | the contract actually traded |

## Decision

**Fully decouple the three roles.** A scalper variant carries up to three instrument references on its
`options_of_underlying` universe:

- `signal_underlying` (2b-E2) — the series indicators/entry/exit run on. Default = `underlying`.
- `strike_reference` (2b-E2b, this ADR) — the spot the ATM strike is anchored on. **Optional; default =
  the signal series.** Explicit instrumentRef, NOT derived, so existing goldens (which anchor strikes on
  the signal/index price) stay byte-identical and only an opt-in SENSEX variant changes behaviour.
- `underlying` — the option-execution root (which chain's legs are bought).

**Each index anchors strikes on its own front future** — the same rule NIFTY already uses (NIFTY strikes
are picked from the NIFTY-fut price). SENSEX strikes are picked from `SENSEX-FUT-CONT` (built read-time by
the generic 2b-E1 backfill: `root=SENSEX, underlyingExchange=BSE`). A front-month future ≈ spot, so the
continuous future is a clean ATM anchor; the ±3 strike window + delta refinement absorb the roll-day
basis gap.

**Rejected — options-parity ATM** (ATM = strike where CE≈PE): a second, divergent strike rule only for
SENSEX, new selector logic, per-entry chain scans, thin-wing edge cases. The front-future rule keeps ONE
consistent principle across indices and reuses the existing `nearestStrike(spot)` selector + the Black-76
greeks forward.

**Rejected — derive the strike reference from the option root** (auto-map NIFTY→NIFTY-FUT-CONT etc.):
existing NIFTY-options goldens anchor on the NIFTY **index** price; auto-switching them to NIFTY-fut would
break parity. The explicit-field default preserves it.

**SENSEX premium band** (the live `StrikePicker` liquidity filter, `ScalperConfig` §0B) is hardcoded
per-index = **300–800** (≈ sampled SENSEX near-ATM CE premiums; the verified BankNifty band does NOT
follow spot-ratio scaling, so the band is empirically set per index, not derived). The **backtest selector
ignores the band** (nearest-strike-to-spot only), so this is a live-only (2c) constant; it is added now
for live-readiness, refined on forward paper.

**OI-confluence gate index is forkable per variant.** Each strategy registers three versions:
NIFTY-options, SENSEX-options gating on **NIFTY** OI (the signal-driver), and SENSEX-options gating on
**SENSEX** OI (the traded chain) — `oi_confluence_gate.index`. The two SENSEX versions are an A/B left to
forward paper; the slots are pre-created for every strategy (even ones whose gate is currently off) so a
future market regime can enable the gate without restructuring.

## Consequences

- A SENSEX scalper backtests/trades correctly: NIFTY-fut drives timing+direction; SENSEX-fut anchors the
  strike; the SENSEX option is filled/P&L'd on its own premium.
- New optional schema field `universe.strike_reference`; absent → today's behaviour (parity holds).
- `SENSEX-FUT-CONT` must be backfilled (no new code — 2b-E1 backfill is generic on underlying). The
  synthetic CONT row is stored under the front contract's OWN exchange = **BFO** (SENSEX F&O), so the
  `strike_reference` instrumentRef is `{exchange: BFO, tradingsymbol: SENSEX-FUT-CONT}` even though the
  backfill is keyed `underlyingExchange=BSE` (that stamps the synthetic's underlying, not its own exchange).
- The 12 scalpers become **36 registry variants** (12 × {NIFTY, SENSEX·NIFTY-OI, SENSEX·SENSEX-OI}).
- The live OI-gate (`ScalperConfluenceGate` §12.3) is a separate path from the backtest gate; honouring
  the `index` override on the live side is a 2c follow-up (the backtest A/B already distinguishes the two).
