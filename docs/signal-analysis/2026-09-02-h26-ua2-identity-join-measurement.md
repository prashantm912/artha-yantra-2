# Receipt — how Upstox and Kite instruments actually join (H26 U-A2 scoping)

**Date measured: 2026-09-02.** Read-only, against the public auth-free Upstox master
(`assets.upstox.com/market-quote/instruments/exchange/complete.json.gz`, 3,247,625 bytes, 116,779
rows) and the live `marketdata.instruments`.

Settles open question 2 of `2026-09-02-h26-ua2-shadow-sync-plan.md`, and in doing so undercuts the
parent plan's central premise.

⚠️ **Re-derive before relying on these. One snapshot, one day, NSE only.**

## Verdict

**Nothing needs to synthesise a Kite tradingsymbol.** A grammar-free join key exists on both halves
and matched **100%** on the measured population. The parent plan's "grammar-synthesis shadow diff —
the crux inside the crux" rests on a premise that does not hold as stated.

## What the master actually carries

Every row has `isin`, `trading_symbol`, `exchange_token`, `instrument_key`, `instrument_type`,
`segment`, `lot_size`, `name`, `tick_size`, `freeze_quantity`, `qty_multiplier`, `exchange`.

⚠️ **`trading_symbol` and `isin` are ALREADY in our DTO** (`UpstoxInstrumentMaster`) and already
parsed on every load — the F&O indexer simply ignores them. So this is a retention change, not a
new download, and not a new parser.

## NSE equities (`NSE_EQ`, 9,694 rows)

| join | result |
|---|---|
| `exchange_token` → our `exchange_token` | **9,694 / 9,694 — 100.00%** |
| `trading_symbol` → our `tradingsymbol` | 2,654 — **27%** |
| rows carrying an ISIN | 9,694 — 100% |

**A naive symbol join would mis-identify ~73% of NSE equities.** The disagreement is entirely the
series-suffix convention: Kite appends the series (`749RJ35-SG`, `KCK-ST`, and the familiar `-BE`),
Upstox keeps the bare symbol and carries the series separately in `instrument_type`.

That is a derivable rule, and it is exact:

> Kite symbol = `trading_symbol` when the series is EQ, else `trading_symbol + "-" + instrument_type`

| | rows |
|---|---|
| bare symbol matched | 2,651 |
| `<symbol>-<instrument_type>` matched | 7,043 |
| **still unmatched** | **0** |
| **coverage** | **100.00%** |

⚠️ **This also generalises H29/H36.** The `-BE` twin is not a special case — it is one instance of a
Kite-wide series-suffix convention that covers `SG` (4,307), `N0` (989), `SM` (448), `BE` (236),
`GS` (132) and `ST` (119) in this snapshot.

So equities have **two independent grammar-free mechanisms that cross-check each other**: the token
join, and the suffix rule. Neither requires synthesis.

## NSE F&O (`NSE_FO`, 31,836 rows)

| join | result |
|---|---|
| `exchange_token` → our `exchange_token` | **31,836 / 31,836 — 100.00%** |
| structured tuple `(underlying, type, expiry, strike)` | 28,300 — 88.89% *(see below)* |

⚠️ **The 88.89% IS A HARNESS ARTIFACT, NOT A PRODUCTION GAP, and it was nearly recorded as one.**
Every miss was an index underlying — NIFTY (1,537), BANKNIFTY (883), MIDCPNIFTY (711), FINNIFTY
(405). Our table stores those as `NIFTY 50` etc. via `UnderlyingRef`, which CLAUDE.md documents as a
load-bearing reconciler; my ad-hoc join did not apply it. The check that caught it: our table has
**zero** rows with `underlying_tradingsymbol = 'NIFTY'`, so a "missing" underlying that is 100%
absent is a mapping problem, not a data problem.

**The real distinction that survives:** the tuple join needs a name reconciler; `exchange_token`
needs none.

## What this means for U-A2

- The **equity half needs no synthesis and no soak** — it needs a token join plus the suffix rule,
  both measurable to 100% today.
- The **F&O half already has a working join** in `UpstoxFnoMasterClient`, and a simpler one available.
- The shadow soak therefore changes character: from *"validate a risky synthesis"* to *"confirm a
  measured join stays at 100% across sessions and an expiry roll"*. That is a smaller and much safer
  unit, and it is still worth doing — a join that is 100% today can degrade on an expiry roll or a
  rename, which is exactly what a soak is for.

## What is NOT claimed

- **NSE only.** `BSE_EQ` (12,744 rows) and `BSE_FO` (4,130) are unmeasured; BSE is where the
  BE-suffix rule was already known not to apply, so it must be measured separately.
- **One snapshot.** Expiry rolls and renames are precisely the events that could break a join, and
  none occurred during this measurement.
- **Direction not fully measured.** 10,232 of our active NSE rows against 9,694 Upstox `NSE_EQ`, and
  31,840 NFO against 31,836 — so a small population of ours has no Upstox counterpart. Which rows,
  and why, is unmeasured and matters for tombstone scoping.
- **`exchange_token` uniqueness is not proven.** 30 of our active NSE tokens map to more than one
  row; that is small but non-zero, and a join key with duplicates needs a tie-break rule.
