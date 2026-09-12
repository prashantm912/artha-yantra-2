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

⚠️ **CORRECTION 2026-09-02, same day: this receipt originally said "`trading_symbol` and `isin` are
ALREADY in our DTO". Only `trading_symbol` was.** `isin` was NOT in `UpstoxInstrumentMaster`, and
neither was `exchange_token` — which is the very key this document recommends as the identity join.
The error surfaced at the compiler when U-A2 tried to read them.

The corrected statement: the master **carries** all three fields on the wire and the DTO is
`@JsonIgnoreProperties(ignoreUnknown = true)`, so they were being silently DISCARDED on every load,
not parsed. Adding them is two record components — still small, still no new download and no new
parser — but "already parsed" was wrong, and a future reader sizing this work off that sentence
would have been misled.

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

## The three caveats, now measured

They were written as open. All three were closed the same day; the results change one of them from a
caveat into a **named hazard**.

### 1. `exchange_token` uniqueness — RESOLVED, the key is `(segment, exchange_token)`

30 active NSE tokens map to more than one row. They are **exactly 30 `INDICES` rows colliding with 30
`NSE` cash rows** — e.g. token `1001` is both `NIFTY 50` and the bond `94SFL28-YL`. Within each
segment the token is perfectly unique:

| segment | rows | distinct tokens |
|---|---|---|
| `NSE` (cash) | 10,096 | **10,096** |
| `INDICES` | 136 | **136** |

`NFO`, `BSE` and `BFO` are already unique without scoping. **So the join key is
`(segment, exchange_token)`**, and both sources segment natively (Upstox has `NSE_EQ` and
`NSE_INDEX` separately), so the collision never arises in a segment-scoped join.

### 2. BSE — measured, and every gap is the same segmentation difference

| population | joined by token |
|---|---|
| our BSE cash (12,819) | 12,744 — **99.41%** |
| our BFO (4,132) | 4,130 — **99.95%** |

⚠️ **Neither shortfall is a data gap.** The 75 BSE misses are **BSE INDICES** — `BANKEX`,
`MIDCAP INDEX`, `ALLCAP`, `BSE 1000`, `BSE 200 EQUAL WEIGHT` — which Kite files under
`exchange='BSE'` while Upstox puts them in a separate index segment. The 2 BFO misses are our own
rows carrying **no `exchange_token` at all**. Both vanish under segment-scoped joining.

### 3. ⚠️ The reverse direction is a REAL HAZARD, and it now has a name

**402 of our 10,096 NSE cash rows have no Upstox counterpart, and every single one is an ETF
indicative-NAV pseudo-instrument** — `AB10BKINAV`, `ABGSECINAV`, `ABSLBAINAV`, `ABSLLQINAV`,
`ABSLNNINAV`, `ABSMSCINAV` and so on. All carry `instrument_type = 'EQ'`; Upstox simply does not
list iNAV instruments.

**An Upstox-authoritative sync with naive tombstoning would deactivate all 402.** This is the
concrete instance of the failure the three-bucket rule in the U-A2 plan exists to prevent, and it
means that rule is **necessary rather than defensive**. A test should assert iNAV-count stability
across a simulated first Upstox-authoritative sync, exactly as one already must for the `-BE` twins.

## What is NOT claimed

- **The BE-suffix RULE is still NSE-only.** BSE join coverage is measured above, but the
  `<symbol>-<instrument_type>` derivation was verified against NSE symbols only, and BSE is where
  that convention is already known not to apply. Do not assume it transfers.
- **One snapshot.** Expiry rolls and renames are precisely the events that could break a join, and
  none occurred during this measurement.
- **Still one snapshot.** Every figure above, including the three resolved caveats, is a single
  reading taken with no expiry roll and no rename in flight.
