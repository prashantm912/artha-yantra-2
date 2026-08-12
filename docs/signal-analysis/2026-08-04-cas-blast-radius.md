# CAS blast radius — which consumers read 15:15–15:30, and does any reach a decision

**Date:** 2026-08-04 · **Type:** investigation, no production code · **Scope:** the Closing Auction
Session (CAS) that went live 2026-08-03, and the residual flagged in
[`2026-08-04-session-findings.md` §6.1](2026-08-04-session-findings.md) / ledger G18.

---

## Verdict

**Decision-bearing — but not where §6.1 predicted.** The intraday scalper surface is almost entirely
walled off from the auction window by its own session rails, and the one live-reachable path
(hero-zero, index-weekly-expiry days, 15:15–15:19) is exposed on *scored dots*, not on the traded
strike, which is immune by construction. **The real exposure is the daily plane:** the auction print
now sets the day's HIGH for a quarter of the F&O universe, and that flows straight into `high_52w` /
`recent_high` — hard gate inputs for both swing screens, which persist rows and open paper positions
at 20:00 IST. On the first post-CAS session `close == high` went **0.00% → 25.59%** among F&O-enabled
stocks while the non-F&O control stayed flat.

**The premise as written is confirmed but under-scoped, and one of its framings inverts.** §6.1 names
"VWAP, 3m rollups, last-30-min reads" — VWAP is computed on the future and unaffected, and the 3m
mixing is confined to one terminal bucket the engine never evaluates. It does not name the daily-bar
high, which is the decision-bearing one.

**A separate, uncomfortable answer to the "last N minutes" question:** no liveness or freshness probe
in the platform can detect a frozen price — every one keys on recency or existence, both of which a
frozen-but-ticking stream satisfies. So CAS raised no alarm, correctly; but **a genuine feed freeze
would not have raised one either.** That gap is pre-existing, and CAS is what exposed it.

---

## STEP 0 — premise verification

§6.1's observed shape is **confirmed** (`computed`, re-measured independently).

Distinct 1m closes in 15:16–15:27, `NIFTY 50`/NSE, last seven sessions:

| session | distinct closes 15:16–15:27 | 15:27 | 15:28 | 15:29 |
|---|---|---|---|---|
| 2026-07-27 | 12 | 24004.30 | 24001.65 | 24002.10 |
| 2026-07-28 | 12 | 23989.35 | 23988.25 | 23985.35 |
| 2026-07-29 | 12 | 24239.40 | 24243.30 | 24250.20 |
| 2026-07-30 | 12 | 24330.30 | 24306.60 | 24317.15 |
| 2026-07-31 | 12 | 24375.25 | 24364.15 | 24383.60 |
| **2026-08-03** | **1** | 24573.35 | 24573.35 | **24774.30** |
| **2026-08-04** | **1** | 24463.45 | **24614.90** | 24614.90 |

Twelve distinct closes every pre-CAS session; exactly one on both post-CAS sessions, then a single
late print. `options_chain_snapshots.spot_price` (`underlying='NIFTY 50'`) mirrors it exactly —
pinned 24463.45 from 15:16, both values present at 15:28, entirely 24614.90 from 15:30 (`computed`).

**Extensions the premise did not state**, all `computed`:

- **BSE too.** `SENSEX`/BSE is frozen at 78324.56 from 15:14 and prints 78428.95 at 15:29.
- **All four NSE price indices** show it (`NIFTY 50`, `NIFTY BANK`, `NIFTY FIN SERVICE`,
  `NIFTY MID SELECT`). **`INDIA VIX` does not** (5 distinct closes, no jump) — it is computed from
  options, not auctioned. A clean discriminator.
- **⚠️ The derivatives plane is completely unaffected.** `NIFTY26AUGFUT` trades continuously through
  15:29 with real volume (24566.30 → 24547.40 → 24567.00 → 24564.80; 6,305–30,940 contracts/min).
  `NIFTY-FUT-CONT` is byte-identical to it. CAS is cash-segment only.

That last point **inverts the natural reading of the premise**: the platform did not freeze, it
*split into two regimes*. Every price the live engine gates and fills on is on the plane that kept
trading.

---

## The structural fact that bounds most of the blast radius

**All 38 enabled scalpers signal off `NFO:NIFTY-FUT-CONT`** — a future — not the index (`computed`,
from the live published configs joined on `published_version_id`). None signals off an index series.
`universe.underlying` (`NSE:NIFTY 50` / `BSE:SENSEX`) is the *option-execution root* used to resolve
legs, not a price series.

**Strike selection is immune, and this is load-bearing.** `ChainSnapshot.basis()` is
`forward − spot` (`MarketOiClient.java:243-245`), and both pickers compute `forward = spot + basis`
(`StrikePicker.java:99`, `HeroZeroStrikeSelector.java:80`) — so `spot + (forward − spot) = forward`
exactly in `BigDecimal`. **The frozen spot cancels.** A +151.45 index jump cannot move the traded
strike. (Caveat in open doubts: this holds while `ForwardCalculator.resolve` picks `PCP_IMPLIED` or
`FUTURES_LTP`; its third fallback `SPOT_CARRY` is `spot × carry` and would not cancel —
`ForwardCalculator.java:77-78`.)

---

## What actually evaluates in the window — measured, not inferred

`strategy.signal_eval_outcomes`, 3m buckets (`computed`):

| bucket (IST) | 08-04 total evals |
|---|---|
| 14:30 … 14:57 | 30 per bucket |
| 15:00 … 15:18 | **2 per bucket** |
| 15:21 onward | **0** |

Evaluation **stops after the 15:18 bucket on both post-CAS sessions**. The 15:28/15:29 auction print
is never evaluated by the live engine.

The two survivors are `scalp-hero-zero-nifty` and `scalp-hero-zero-sensex-niftyoi`, both signalling
on `NFO:NIFTY26AUGFUT`, window 14:30→15:20 (`computed`, from `strategy.signal_rejections`):

| bar (IST) | slug | blocking rail | operand | composite |
|---|---|---|---|---|
| 08-04 15:15 | `scalp-hero-zero-nifty` | `volume-floor` | 36465 | 0.2525 |
| 08-04 15:15 | `scalp-hero-zero-sensex-niftyoi` | `volume-floor` | 36465 | 0.2525 |
| 08-04 15:18 | `scalp-hero-zero-nifty` | `rsi-band` | 57.96 | 0.3283 |
| 08-04 15:18 | `scalp-hero-zero-sensex-niftyoi` | `rsi-band` | 57.96 | 0.3283 |

Both rails are computed on the **future**, so both operands are sound. `degraded=t` on these rows is
**pre-existing, not CAS-caused** — the identical flags `["iv-rank-absent","dow-absent"]` are present
at 14:57, before the window (`computed`; this falsifies the tempting reading that CAS degraded them).

Hero-zero evaluates at 15:15/15:18 on **both** sessions, but `HeroZeroGate.java:157`
(`if (!expiryDay || monthlyExpiryDay)`) blocks entry off-expiry — hence 08-03 (Monday, non-expiry)
scored `composite-below-threshold` while 08-04 (NSE weekly expiry) reached `confluence-blocked`. So
**entry exposure is confined to index-weekly-expiry days, 15:15–15:19.**

**Nothing has fired.** Zero signals, zero paper orders and zero shadow closes in 15:15–16:00 on
either session (`computed`). The intraday exposure is **latent, not realised**.

---

## Consumer table

`D` = reaches a decision (gate / fill / persisted row) · `X` = display or analytics only.

### Intraday — index-derived, live-reachable

| Consumer | file:line | reads window? | D/X | note |
|---|---|---|---|---|
| Bar-driven engine eval (all entries + exits) | `SignalEngine.java:1643`, `:3066-3076` | **no** for 36/38 | D (unreached) | `withinSessionWindow` `continue`s the whole block |
| hero-zero × 2, 15:15–15:19 | `HeroZeroGate.java:157`; `ScalperGates.java:28` | **yes** | **D** | expiry days only; measured above |
| `futuresBasis` dot — **sign flips** | `ScalperGates.java:851-859`; `FuturesTermStructureService.java:124,158` | **yes** | **D** | see below |
| `derivePremiumSkew` ATM anchor | `MarketOiClient.java:847-874` | **yes** | **D** | mis-anchors 3 strikes |
| `deriveIvPair` ATM anchor | `MarketOiClient.java:882-932` | **yes** | **D** | mis-anchors 3 strikes |
| Traded strike (`StrikePicker`, `HeroZeroStrikeSelector`) | `StrikePicker.java:99`; `HeroZeroStrikeSelector.java:80` | yes | **D — immune** | spot cancels exactly |
| `max-oi-sr-gate` hard gate | `ScalperConfluenceGate.java:1012-1016` | **no** | D (unreached) | `s24-trade-window` caps 14:30 |
| confluence-flip exit oracle | `SignalEngine.java:3114` | **no** | D (unreached) | same 14:30 rail |
| `iv_slope` dot | `ActiveStrikeService.java:191-199` | n/a | D — **spot-free** | reads stored `ceIv`/`peIv` only |
| `activeStrikeSideIvSeries` Black-76 re-solve | `ActiveStrikeService.java:220-235` | yes | **X** | javadoc `:205-210` "DISPLAY PATH ONLY" |
| `ConnectingDotsService.atmIvFromSeries` | `ConnectingDotsService.java:433-449` | yes | **X** | callers are the controller + backtest analytics |
| `OiTrendingService`, `ExpiryCompareService`, `OiPremiumService`, `BigOiLogService`, CSV export | `OiTrendingService.java:65-104`; `ExpiryCompareService.java:179`; `OiPremiumService.java:53-54`; `BigOiLogService.java:70-71` | yes | **X** | consumers read OI, not spot |
| `basis-gate` hard rail | `ScalperConfluenceGate.java:1046` | would | **D — dormant** | armed on **0 of 44** published |
| All liveness/freshness probes | see below | yes | **D — blind** | none can detect a frozen price |
| `GapDetector` 10-min trailing tail | `GapDetector.java:32,70` | **yes** | **D** | gates re-fetch; overwrites the window |

**The sharpest intraday defect — `futuresBasis` reads backwards, and it reaches a persisted row.**
`FuturesTermStructureService.java:151` computes `basisAbsolute = ltp.subtract(spot)` with `spot` the
live **index** quote (`:124`); `ScalperGates.java:851-859` turns its sign into a side:
*"future > spot (premium) is bullish → CE; future < spot (discount) bearish → PE."*

Across the auction print the future moved **+4.80** while the basis swung **146.65 points and changed
sign** (`computed`):

| IST | future | index spot | basis | dot reads |
|---|---|---|---|---|
| 15:26 | 24567.00 | 24463.45 | **+103.55** | supports CE |
| 15:27 | 24560.00 | 24463.45 | **+96.55** | supports CE |
| **15:28** | 24564.80 | **24614.90** | **−50.10** | **supports PE** |

A negative NIFTY basis is economically impossible intraday — it is a pure artifact. The chain
frozen spot → `futuresBasis` (`MarketOiClient.java:467`) → `basis` dot
(`ConnectTheDotsScorer.java:389`, weight 1.0) → composite → persisted row is **confirmed in live
data**: the `scalp-hero-zero-nifty` rejection at bar 15:18, evaluated 15:19:18 inside the frozen
window, carries `{"dot":"basis","absent":false,"weight":1.0,"supports":false,"reason":"futures
basis"}` in its `score_breakdown` (`computed`).

**Two things keep this contained, and both are configuration, not code.** The hard `basis-gate`
(`ScalperConfluenceGate.java:1046`) sits behind `cfg.has("basis-gate")` and is armed on **0 of 44**
published enabled strategies; and hero-zero's window ends 15:20, so **no evaluation reaches the 15:28
sign flip** — the inversion above is measured on the data, not on a bar anyone scored. Within the
window the dot is stale rather than flipped. A counterfactual on the 15:18 row moves the aggregate
roughly 0.31 → 0.36 against a 0.60 threshold — **not outcome-changing on that bar** (`computed`,
indicative only; see doubt #5).

### Money paths that run after the auction print lands

| Consumer | file:line | reads window? | D/X | note |
|---|---|---|---|---|
| `bracketEvaluation` SL/TP, every 15s to 15:59:45 | `PaperScheduler.java:32` | **yes** | **D / money** | resolves against the **option's own** tick — healthy plane |
| `expirySettlement` 15:35 | `PaperScheduler.java:50`; `PaperExpiryService.java:231-251` | **yes — the auction print** | **D / money** | see below |
| `intradayMarkToClose` 15:45 (the "15:45 sweep") | `PaperScheduler.java:59` | last real tick, any age | **D / money** | `style='intraday'` only |
| `t1RolloverPrompt` 15:30 | `PaperScheduler.java:41` | yes | D (notification) | |
| `StraddleExitMonitor` to 15:30 | `StraddleExitMonitor.java:59,110` | **yes** | **D / money** | |
| `ShadowExitMonitor` (armed) | `ShadowExitMonitor.java:82,101` | polls to 15:30 | D (shadow ledger) | square-off 15:12 |
| `EodBackfillJob` 15:45 | `EodBackfillJob.java:48-49` | **yes** | **D — persists** | re-fetches 1m from open + 1d, authoritative |

**Expiry settlement is a case where CAS made the code *more* correct by accident.**
`resolveSettlementSpot` (`PaperExpiryService.java:231-251`) takes the last **index** tick at any age
and `intrinsic()` turns it into the settlement price. At 15:35 that tick is the auction print — which
*is* what NSE settles index options against. The javadoc calls `intrinsic` "a documented
approximation of the official settlement price"; under CAS the approximation improved. But the code
arrives there via last-tick-at-any-age, **not by design** — nothing models an auction phase.

### The daily plane — where the real exposure is

| Consumer | file:line | reads window? | D/X | note |
|---|---|---|---|---|
| `nse_eod_bhavcopy` → adjusted daily plane | `AdjustedEquityDailySql.java:75,97` | yes (via close/high/low) | **D** | the shared screen base CTE |
| Minervini `high_52w` / gate 6 | `TrendTemplateService.java:114,122`; `MinerviniGates.java:20,36,44` | **yes** | **D** | `close ≥ high52w·(1−within%)` |
| Manas `high_52w`, `recent_high`, gates 5 + 6 | `ManasScreenService.java:123`; `ManasGates.java:69,89-90` | **yes** | **D** | `max(high) OVER w252`, `max(high) OVER wnh` |
| Swing batch 20:00/20:05 IST | `strategy.swing_batch_runs` | **yes** | **D — persists + enters** | ran 08-03, 1 minervini entry |
| Index 1d bar | `marketdata.candles`@1d | yes | **D** | RS benchmark; **correct** — official close belongs here |

---

## The measurement that makes this decision-bearing

The auction print does not merely change the close — **it can set the day's HIGH**, and daily `high`
is a direct gate input via `max(high) OVER w252 AS high_52w`.

For the index on 08-03 this is directly measurable (`computed`): continuous-session high
**24609.45**, daily bar high **24774.30** — the auction print sits **164.85 points above anything
continuous trading touched**, and became both the day high and the close.

For equities we have no intraday capture, so the mechanism was tested on daily-bar *shape*, using
CAS eligibility as a natural control. CAS applies to F&O-enabled (Category I) stocks only; the 216
F&O underlyings from `marketdata.instruments` are the treated set, every other `EQ` name is the
control (`computed`):

| trade date | group | n | `close == high` | `close == last` |
|---|---|---|---|---|
| 2026-07-29 | non-F&O | 2196 | 1.23% | 6.56% |
| 2026-07-29 | **F&O** | 211 | **0.00%** | 1.90% |
| 2026-07-31 | non-F&O | 2198 | 1.41% | 6.28% |
| 2026-07-31 | **F&O** | 211 | **0.00%** | 1.42% |
| **2026-08-03** | non-F&O | 2204 | 1.63% | 6.31% |
| **2026-08-03** | **F&O** | 211 | **25.59%** | **98.58%** |

The control group barely moves (1.23 → 1.41 → 1.63%). The treated group goes **0.00% → 25.59%** on
`close == high` and **~1.7% → 98.58%** on `close == last`. The 98.58% is the CAS mechanism stated
plainly: the official close now *is* the last (auction) trade.

**The obvious alternative cause — 08-03 being a strong up day (+1.60%) — is falsified.** Across the
whole pre-CAS window the universe-wide rate is flat at 0.80–1.30% for NIFTY returns from −0.79% to
+1.10%, i.e. uncorrelated with day strength; the two strongest pre-CAS up days (07-27 +0.96% at
1.25%, 07-29 +1.10% at 1.12%) sit mid-band. And the non-F&O control experienced *the same* +1.60%
market on 08-03 and did not move. Same day, same market, same pipeline — only CAS eligibility
differs.

Consequence: on 08-03, **a quarter of the F&O universe closed exactly at its day high**, so for
those names `high_52w` and `recent_high` are anchored on a price no intraday order could have
traded. Both swing screens then gate on exactly that. The swing batch ran at 20:00/20:05 IST on
08-03 and made 1 minervini entry; candidates rose from 106/111 (07-30/07-31) to **139**, and
manas-arora from 71/75 to **93** (`computed`). That correlation is **suggestive only** — one
post-CAS session, and a +1.60% day raises candidate counts on its own.

---

## Session-tail aggregates named in §6.1

- **3m rollup — the onset aligns, only the terminal bucket mixes.** 15:15 IST is exactly a 3m
  boundary (915 min from midnight, 915/3 = 305; also clean on the epoch — 09:45 UTC = 35,100 s,
  35,100/180 = 195), and likewise for 5m (915/5 = 183) and 15m (915/15 = 61). So **no bucket
  straddles the moment continuous trading stops.** Buckets 15:15/15:18/15:21/15:24 are entirely
  frozen (delta 0.00). The mixing is confined to the **terminal 15:27–15:30 bucket**, measured as
  `O=24463.45 H=24614.90 L=24463.45 C=24614.90`, delta **+151.45** (`computed`) — it opens on the
  frozen price and closes on the auction print. That bucket closes at 15:30, no 15:30 bar exists, and
  the engine stopped evaluating after 15:18, so **it never reaches a live decision**. Real in stored
  data (replay, charts), inert live. `CandleRepository.java:309-332`.
- **`candles_1h` (IST-reanchored) straddles 15:00–16:00** (`V029__candles_1h_ist_reanchor.sql:20`) —
  it blends ~15 min of continuous trading, the frozen stretch and the auction print into one bar.
  Display-side today.
- **⚠️ The `candles_1d` cagg and native `candles`@1d disagree under CAS.** The cagg lags and carried
  **24463.45** (the frozen continuous price) while the native daily bar carried **24614.90** (the
  official close) (`computed`). CLAUDE.md already warns these two diverge for 1d; CAS makes the
  divergence *semantic* rather than merely temporal — one is now the auction close and the other is
  not. The swing path reads the native/bhavcopy plane, so it gets the correct value; anything reading
  the cagg for a daily close gets the pre-auction price.
- **VWAP / VWMA / relative-volume floor: unaffected.** All are computed on the future
  (`SessionIndicators.java:20-47`; volume-floor `ScalperConfluenceGate.java:574,1509-1511`), which
  never freezes. VWMA(20) is used by all 63 scalpers for entry and `signal_exit`.
- **Index 1d close.** Correct as stored. The official close belongs on the daily bar and on the RS
  benchmark; §6.1's retraction of the T31 repair stands.

### Freshness probes: the answer is neither false alarm nor suppression — it is blindness

**No probe in the tree can detect a frozen price** (`sourced`). `CandleBuilder.java:100-118` emits
bars only from arriving ticks, so the flat bars *prove ticks kept flowing* with unchanged values.
Every liveness probe — `DataHealthCanary` (240 s), `FeedWatchdog` (180 s), `SubscriberHealthCanary`,
`SessionLivenessHeartbeat`, the paper `DATA_STALE` 15 s rail — keys on **existence/recency**, all of
which the frozen stream satisfies. `PartialBucketCanary` (`:405`) is volume-based and index volume is
0 on both sides of the transition, so it is inert either way. The one value-change probe,
`DotHealthCanary`'s G12 `frozen` check, watches macro/OI operands and **never price** — and its
`MIN_FROZEN_BARS = 8` (~24 min on a 3m primary) exceeds the 15-minute window regardless.

This is the *good* answer to the question the brief asked (no alarm is being suppressed, because no
alarm was ever wired to this signal) and simultaneously the uncomfortable one: **if CAS had been a
genuine feed failure rather than a market-structure change, nothing would have caught it either.**
That is a pre-existing gap CAS merely exposed, not a CAS defect.

---

## Is post-CAS backtest parity answerable today?

**No — and the blocker is data, not the auction.** `marketdata.expired_contracts` covers expiries
only through **2026-07-30**; there are zero rows for any expiry ≥ 2026-08-03 (`computed`). Option
legs resolve through that table, so **option-replay does not cover 08-03 onward at all**. The 08-04
NIFTY weekly expired today and has not been ingested. The question cannot be asked yet.

**When it can be asked, it will be meaningful — more so than expected.** The replay spine
`NIFTY-FUT-CONT` is byte-identical to the dated front future and carries **no CAS artifact**
(`computed`, 1m through 15:29 with real volume). Since all 38 scalpers signal off that spine, a
parity run over post-CAS dates exercises a clean series. Two caveats: (1) any strategy whose
`signal_underlying` were an index would replay ~13 frozen bars plus a jump — none exists today, so
this is a guard for future configs, not a present defect; (2) `BacktestRunner.strikeReferenceInstrument`
(`:859-865`) anchors ATM on the signal series absent an override, so the backtest inherits the same
immunity as live.

**Recommended gate before running one:** confirm `expired_contracts` has the 08-04 expiry, then
verify the 3m 15:27 bucket is present in replay — that is the one bucket where the two regimes mix.

---

## Claim ledger

`computed` — the seven-session distinct-close table; the snapshot `spot_price` flip; SENSEX/BSE and
the four-index + VIX discriminator; futures/`NIFTY-FUT-CONT` continuity; all 38 scalpers signalling
off `NIFTY-FUT-CONT`; the eval-outcome decay 30 → 2 → 0 and the stop after 15:18; the four
CAS-window rejection rows and their rails; `degraded=t` being pre-existing at 14:57; zero signals /
paper orders / shadow closes in the window; the basis sign flip (+91.85 vs −59.60); the 3-strike ATM
displacement (step 50; 24450 vs 24600); the index continuous-vs-daily high gap of 164.85; the
F&O-vs-control bhavcopy table; swing batch run times, candidate counts and the 08-03 entry;
`expired_contracts` coverage ending 2026-07-30; the 3m bucket arithmetic.

`computed` (cont.) — the `basis` dot's persisted `score_breakdown` entry on the 15:18 row; the
basis swing across the print (+103.55 / +96.55 / −50.10); `basis-gate` armed on 0/44 published; the
terminal 15:27 bucket OHLC; the `candles_1d` cagg vs native daily divergence; the absence of any
intraday equity capture.

`sourced` — every `file:line` in the consumer table; `HeroZeroGate.java:157`;
`PaperScheduler.java:32,41,50,59`; `PaperExpiryService.java:231-251`; `EodBackfillJob.java:48-49`;
`AdjustedEquityDailySql.java:75,97`; `ManasScreenService.java:123`; `TrendTemplateService.java:114,122`;
`MinerviniGates.java:20,36,44`; `ManasGates.java:69,89-90`; `BacktestRunner.java:859-865`;
`FuturesTermStructureService.java:124,151`; `ConnectTheDotsScorer.java:389`;
`CandleRepository.java:309-332`; `CandleBuilder.java:100-118`; `PartialBucketCanary.java:405`;
`GapDetector.java:32,70`; `SessionIndicators.java:20-47`; `V029__candles_1h_ist_reanchor.sql:20`.

`recalled` — that CAS applies to F&O-enabled Category I stocks, and the 15:15/15:30 phase times.
From §6.1, which sourced them from public reporting. **Load-bearing** for reading the
F&O-vs-control table as *causal* rather than merely *correlated with F&O membership*, so it is
repeated in open doubts.

`assumed` — that the 15:28/15:29 print is the official auction close rather than an unrelated feed
artifact. The expiring-chain convergence in §6.1 is strong evidence and the F&O-confined bhavcopy
signature is stronger, but no exchange circular was read in this investigation.

---

## Open doubts

1. **Containment of the intraday exposure rests on two config values, not on code.** Hero-zero's
   `to: "15:20"` and the unarmed `basis-gate` tag are the only things keeping the auction print away
   from a hard rail. Neither is a session guard; a window extension or a tag arming — both ordinary,
   low-ceremony changes — would silently make the 15:28 sign flip reachable. Nothing would fail a
   test.
2. **Two sessions, one of them atypical.** 08-03 and 08-04 are the entire post-CAS record, and 08-04
   was an NSE weekly expiry — the only day hero-zero's entry gate opens. The bhavcopy measurement has
   **n = 1** (08-04 not yet ingested at analysis time). The 25.59% figure should be treated as "the
   effect is real and large" and **not** as a calibrated magnitude.
3. **The F&O-vs-control inference leans on `recalled` eligibility.** The control is clean *if* the
   216 `NFO` FUT underlyings are the CAS Category I set. If eligibility is narrower or broader, the
   grouping is mis-specified — though the 98.58% vs 6.31% split is so stark that the treated group is
   plainly experiencing a different close mechanism, whatever it is called.
4. **No equity intraday data exists**, so for stocks it is proven that the auction print *equals* the
   high 25.59% of the time, **not** that it *raised* the high above the continuous session. Only the
   index (164.85 points on 08-03) demonstrates the raise directly. The equity magnitude is unmeasured
   and unmeasurable from what we store.
5. **No outcome changed, and the counterfactual arithmetic is shaky.** The intraday exposure is
   demonstrated as *reached*, never as *having altered a verdict*. The one counterfactual computed
   (0.31 → 0.36 vs a 0.60 threshold) came from a reconstructed weight denominator that does not
   reproduce the recorded composite (0.3125 reconstructed vs 0.3283 recorded), so treat the magnitude
   as indicative only. **"This would have caused a bad trade" is not supported.**
5a. **`oi_confluence_gate: enabled=false` does not suppress the OI dots** — every dot including
   `basis` scored `absent: false` for hero-zero. What that flag actually controls was not traced, and
   it matters for reasoning about which strategies are exposed.
6. **`ForwardCalculator` fallback frequency is unmeasured.** The strike-immunity argument holds while
   `PCP_IMPLIED` or `FUTURES_LTP` resolves. If thin auction-window option quotes push it to
   `SPOT_CARRY` (`:77-78`), the cancellation breaks and strike selection becomes exposed. Untested.
7. **`MarketCalendar.SESSION_CLOSE = 15:30`** and ~10 duplicated `LocalTime.of(15,30)` literals now
   mean "end of auction" rather than "end of continuous trading". Nothing in the codebase models an
   auction phase. Whether that is wrong is per-consumer and was not audited one by one.
8. **08-04's actuator counters are untrustworthy** — two mid-session container recreations (14:44,
   15:24 IST, §6.2) zeroed them. Every measurement here comes from DB rows, which survived, not from
   counters.
9. **SENSEX/BSE traced only at the index-series level.** `scalp-hero-zero-sensex-niftyoi` executes
   BSE options on a Thursday expiry cadence; its CAS-window behaviour was not separately measured.
10. **Worktrees excluded.** Only the main checkout was read; `.claude/worktrees/*` copies were
    deliberately skipped.
11. **Whether the auction bar always lands at 15:28/15:29 is unestablished.** Both observed sessions
    printed before 15:30. **If it ever lands *at* 15:30 it closes the 15:27 3m bucket and makes that
    bucket evaluable** — which would move the mixed-regime bar from inert to live. Two samples cannot
    settle this.
12. **Not swept:** the Connecting-Dots `vwap` factor (`ConnectingDotsIndicators.java:56-70`, a fourth
    VWAP implementation with no session reset that crosses into backtest-service), and whether live
    ATM-strike selection in options analytics reads the index spot.
13. **Pre-existing trap, currently unreachable:** `SessionIndicators.java:36-40` returns HLC/3 rather
    than null when session volume is 0, so VWAP on any *index* series silently tracks price instead
    of degrading honestly. Index volume is 0 by construction. Harmless today because no index is a
    primary series — a live defect the moment one becomes one.

---

## Suggested follow-ups (not actioned — investigation only)

| # | Item | Why |
|---|---|---|
| 1 | Decide the doctrine for daily `high` under CAS | 25.59% of F&O names; feeds `high_52w` in both swing screens — the largest decision-bearing surface |
| 1a | Consider a value-change (not just recency) liveness probe | nothing in the tree can see a frozen price; a real feed freeze would also go undetected |
| 2 | Reconcile `candles_1d` cagg vs native daily under CAS | they now disagree *semantically*: 24463.45 vs 24614.90 |
| 3 | Re-measure the bhavcopy table once ~10 post-CAS sessions exist | doubt #2 — magnitude is uncalibrated |
| 4 | Fix or scope `futuresBasis` during 15:15–15:30 | a scored dot reading confidently backwards |
| 5 | Re-anchor `derivePremiumSkew` / `deriveIvPair` on the forward, not raw spot | 3-strike displacement; the pickers already show the cancellation idiom |
| 6 | Revisit parity once `expired_contracts` covers 08-04+ | currently unanswerable |
| 7 | Consider modelling an explicit auction phase | `SESSION_CLOSE` now means two different things |
