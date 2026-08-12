# Swing exit path: row-based windows stretched by missing daily bars

**Date:** 2026-08-03 · **Type:** investigation only, no production code changed · **Tier:** HOLD-adjacent (money path, exit doctrine)

---

## Verdict

**The defect is real and confirmed, but the named mechanism is wrong: the missing `minBars` guard is
NOT what exposes the exit path — row-based indicator windows are — and as of 23:03 IST tonight NO live
position is exposed, because the data gap self-repaired at ~22:50 IST.** Three Minervini positions
(#13 AUTOIND, #34 KANORICHEM, #35 MENONBE) *were* evaluated on a stretched `sma50` window at tonight's
20:00 batch, ~2h45m before the repair landed; the mis-computed trail was off by ₹0.42–₹2.41 per share,
and **none of the three changed an exit decision** — every close sat 5–26% above both the correct and
the mis-computed trail.

Severity as realized tonight: **LOW** (no wrong exit, no money moved).
Severity of the mechanism if a gap ever coincides with a genuine trail cross: **MEDIUM** — a silent
wrong-level exit on a money path, with no detector.

---

## Premise verification (STEP 0)

| # | Claim (as briefed, `sourced` from another agent) | Verdict |
|---|---|---|
| 1 | Entry path guards on `series.size() < doctrine.minBars()`, `minBars = 60` | **CONFIRMED** |
| 2 | Exit path at `SwingBatchEngine.java:801-830` has NO `minBars` guard, only `isEmpty()` | **CONFIRMED** (with two *other* guards the brief omitted) |
| 3 | An `sma50` trailing stop computes over a gap-stretched window | **CONFIRMED at the rule level, but RE-CHARACTERIZED** — `minBars` is not the mechanism |
| 4 | Selection reads `nse_eod_bhavcopy` (complete), entry/exit read `marketdata.candles` (gapped) | **CONFIRMED** |
| 5 | 65 of 383 funnel passers (17%) mis-evaluated | **RATIO REPRODUCES, ABSOLUTE NUMBERS DO NOT** — measured 48 of 278 (17.3%) |

### 1 — entry guard (`computed`/`sourced`)

`SwingBatchEngine.java:463` (entry candidate loop) and `:618` (`firesEntry` precheck) both guard
`series.size() < doctrine.minBars()`. `minBars` default is 60 — `MinerviniDoctrine.java:49`,
`ManasDoctrine.java:55` (`${artha.<family>.swing.min-bars:60}`).

**Verified as the DEPLOYED value, not just the YAML default** (per the `.env`-vs-YAML trap):
`docker inspect ay-strategy-signal-service` shows no `*_MIN_BARS` or `*_WARMUP_DAYS` override — only
`ARTHA_MINERVINI_SWING_ENABLED`, `ARTHA_MINERVINI_SWING_CRON=0 0 20 * * MON-FRI`,
`ARTHA_MANAS_ARORA_SWING_CRON=0 5 20 * * MON-FRI` and pyramid knobs. So `minBars=60`,
`warmupDays=520` are live. `computed` 2026-08-03 23:03 IST.

### 2 — exit guard (`sourced`)

`SwingBatchEngine.java:802` and `:808` check only `series.isEmpty()`. Confirmed. The brief understates
what *is* there, and both extra guards matter to the fix discussion:

- `:806` — one retry **outside** the per-run cache (audit P0-3), because `MarketDataCandlesClient`
  fail-softs to an empty list on a REST hiccup.
- `:822` — `entryIndex < 0` → skip with `STOP NOT EVALUATED TODAY`, when the entry bar fell outside
  the fetched window.

So the exit path already has a *counted, alerted, never-silent* refusal channel (`skipped++`), and
already accepts refusing in two cases. It is not guard-free; it is missing this *particular* guard.

### 3 — the re-characterization (this is the important correction)

**`sma50` IS a live exit trigger.** The `SwingDoctrine.trailLevel` javadoc
(`SwingDoctrine.java:154-158`) claims Minervini's 50-day-MA is advisory display only and that its real
exit rule is "percent/ATR-based per its own `exit_rules`". **That javadoc is false.** Read from the
live published config (`strategies.published_version_id`, `computed` 2026-08-03 22:5x IST), all four
Minervini strategies at published version 1.0.1 declare exactly:

```json
[{"type":"stop_loss","params":{"basis":"percent","value":8}},
 {"type":"trailing_stop","params":{"alias":"sma50","basis":"indicator"}}]
```

There is **no ATR anywhere in Minervini's exit rules**. The sma50 trail is second in exit precedence,
right behind the 8% stop, and fires on `close <= sma50` (`ExitEvaluator.java:659-671`).

**But `minBars` is not the mechanism, and mirroring it onto the exit would have changed nothing.**
`warmupDays = 520` *calendar* days yields roughly 355 daily bars. A 5-bar gap takes that to ~350.
Neither is anywhere near the 60-bar floor, so the guard would not have fired for any of the three
affected positions. The harm is **window stretch**, which a bar-count floor does not detect:

> A 50-**row** SMA over a series missing 5 sessions still averages 50 rows — it just reaches 5 sessions
> further back in time. `Ta4jIndicators.sma()` wraps ta4j's `SMAIndicator`, addressed purely by integer
> bar index; `EngineSeries.append` enforces only strictly-increasing bucket order and performs no gap
> detection or fill.

**The discriminator is window LENGTH vs gap DISTANCE, not bar count.** Measured
(`computed` 2026-08-03 22:49 IST): from the 2026-08-03 bar, the gap dates sit 30–35 rows back.

| Live rule | Lookback | Reaches the gap? |
|---|---|---|
| Minervini `stop_loss percent 8` | 0 rows (entry price only) | **No** |
| **Minervini `trailing_stop indicator sma50`** | **50 rows** | **YES — rows 30–35 are inside** |
| Manas `stop_loss atr_multiple` (ATR-20 @ entry) | 20 rows from entry bar | **No** |
| Manas `trailing_stop atr_multiple rolling` | ATR-20 per bar from entry | **No** |
| Manas `square_off parabolic_ma 10` | 10 rows | **No** |
| Manas `square_off fast_bars 3` | 3 rows (`close[i-3]`) | **No** |
| peak-since-entry (`favorableExtreme`) | entry→now | **No — every entry postdates the gap** |

Manas verified directly rather than inferred: for KANORICHEM (Manas pos #36, entered 2026-07-21), the
ATR-20 window anchored at the entry bar starts **2026-06-23 in both the gapped and the complete
series** — identical, i.e. the gap falls entirely outside it.

### 4 — the two-plane split (`sourced`)

- **Selection plane:** `ManasScreenService.java:15` — "Pure parameterized SQL over the broad EOD equity
  universe `nse_eod_bhavcopy` (~2.2k EQ/BE…)"; Minervini geometry via
  `screener/minervini/geometry/DailyBarReader.java:11`, also `nse_eod_bhavcopy`.
- **Entry/exit plane:** `SwingBatchEngine` → `MarketDataCandlesClient` →
  `GET /api/v1/market/candles` (`MarketDataCandlesClient.java:88`) → `marketdata.candles`.

Confirmed. A symbol can therefore be selected off a complete plane and then evaluated off a gapped one.

### 5 — the 65-of-383 figure, corrected

Reproduced as *"funnel passers whose historical bars were still absent when tonight's batch ran"* —
i.e. symbols with a pre-2026-08-03 bar carrying `fetched_at > 20:00 IST`:

| Population (screen_date 2026-08-03) | Count | Gapped at 20:00 | Share |
|---|---|---|---|
| Minervini symbols screened | 1,776 | 1,360 | 76.6% |
| **Minervini `passes_all` (funnel passers)** | **278** | **48** | **17.3%** |
| Manas symbols screened | 2,274 | — | — |
| Manas `passes_all` | 124 | — | — |
| Symbols with any historical bar backfilled after 20:00 | 2,249 | — | — |

**The 17% ratio reproduces almost exactly (17.3%). The absolute numbers do not:** today's Minervini
funnel passers are 278, not 383. Minervini + Manas passers = 402, also not 383 — so 383 was probably
measured on a different date or a differently-defined population. `computed` 2026-08-03 22:5x IST.

Note this figure describes the **entry/selection** surface, not held positions. It is the right number
for "how many candidates were scored on stretched windows" and the wrong number for "how many
positions are at risk" — that number is 3, below.

---

## 1. Is any position exposed right now?

**No — as of the final re-measure at 2026-08-03 23:03:05 IST, zero of the 15 held symbols has a
missing daily bar anywhere in 2026-05-01 → 2026-08-03.** `computed`.

The gap repaired itself *during this investigation*, which is worth recording precisely because it
demonstrates the shelf life of a live-state claim:

| Time (IST, 2026-08-03) | Observed state |
|---|---|
| 22:46:41 | first measure — AUTOIND/KANORICHEM/MENONBE missing 06-16, 06-18, 06-19 |
| 22:47:49 | 06-16 filled |
| 22:48:51 | 06-18 filled |
| 22:49:49 | 06-19 filled |
| 22:50:48 | all three symbols show **0 missing** |
| 22:59:51 | universe-wide: all six named gap dates show **0 still missing** |
| 23:03:05 | final: 0 missing for all 15 held symbols |

Repair rows carry `source = BHAVCOPY` and `fetched_at` in the 22:46–22:50 window.

**But exposure at evaluation time was real.** Tonight's batches ran at **20:01:15** (Minervini) and
**20:05:44** (Manas) — `strategy.swing_batch_runs`. Reconstructing the 20:00 state from `fetched_at`,
exactly three held symbols were missing **five** bars each (06-12, 06-15, 06-16, 06-18, 06-19):

| Pos | Book | Symbol | Strategy | Trail | Bars missing at 20:00 | Affected |
|---|---|---|---|---|---|---|
| 13 | minervini | AUTOIND | minervini-cheat-3c | sma50 | 5 | **YES** |
| 34 | minervini | KANORICHEM | minervini-vcp | sma50 | 5 | **YES** |
| 35 | minervini | MENONBE | minervini-vcp | sma50 | 5 | **YES** |
| 36 | manas-arora | KANORICHEM | manas-arora-vcp | ATR-20 rolling | 5 | no (window outside gap) |
| other 14 | — | — | — | — | 0 | no |

Every other held symbol received only its normal 2026-08-03 bar. All 12 Minervini positions carry the
`sma50` trail; zero Manas positions do.

Both batches recorded **0 exits and 0 exit_skipped** tonight.

---

## 2. Magnitude

`sma50` at the 2026-08-03 close, computed two ways from the now-complete series — "as computed"
excludes the five bars that were absent at 20:00; "correct" includes them. `computed` 2026-08-03
22:52 IST.

| Symbol | Pos | Qty | sma50 correct | sma50 as computed | Δ/share | Window start (correct → gapped) | Close | Close vs correct sma50 |
|---|---|---|---|---|---|---|---|---|
| AUTOIND | 13 | 111 | 84.3876 | 84.8028 | **+0.4152** (tighter) | 05-22 → 05-15 | 88.86 | +5.3% |
| KANORICHEM | 34 | 62 | 122.7700 | 121.2344 | **−1.5356** (looser) | 05-22 → 05-15 | 142.97 | +16.4% |
| MENONBE | 35 | 43 | 175.2318 | 172.8216 | **−2.4102** (looser) | 05-22 → 05-15 | 221.09 | +26.2% |

Rupee difference in the governing trail level, per position: **AUTOIND ₹46.09 · KANORICHEM ₹95.21 ·
MENONBE ₹103.64** (Δ × qty). Total ₹244.94 of mis-stated trail across the book.

The window stretch is exactly as predicted: 50 rows reached back to 2026-05-15 instead of 2026-05-22 —
**5 extra sessions**, matching the 5 missing bars.

**No exit decision changed.** The trail fires on `close <= sma50`; all three closes sat far above both
values, so the rule returned `empty` either way. The error was ~0.5%–1.4% of the SMA against a 5–26%
margin. This is the honest reading: the defect was live, measurable, and **benign tonight**.

---

## 3. Which exit rules are affected

Full enumeration of what `ExitEvaluator` dispatches (`ExitEvaluator.java:325-345`), precedence-ordered.
Classified by whether a missing bar changes the value.

| Type | Basis | Class | Why |
|---|---|---|---|
| `stop_loss` / `take_profit` | `percent`, `premium_pct` | **INDEPENDENT** | `entry × value/100` (`:480-483`) |
| | `index_points` | **INDEPENDENT** | distance is the literal value (`:505`) |
| | `atr_multiple` | **DEPENDENT** | Wilder ATR(period) at `entryIndex` (`:484-500`) |
| | `atr_multiple` + `cap_pct` | **DEPENDENT**, cap may mask | `min(mult×ATR, cap%×entry)` (`:493-499`) |
| | `r_multiple` | **INHERITS** first non-`r_multiple` stop | `:501-504` |
| `trailing_stop` | `percent` | ⚠️ **DEAD** — schema-valid, never evaluated | `trailing()` falls through to `Optional.empty()` (`:559-672`) |
| | `premium_pct`, `index_points` | **PARTIALLY DEPENDENT** | offset off `favorableExtreme` — a missing bar can hide a high |
| | `atr_multiple` (entry basis) | **DEPENDENT** + partially | ATR@entry plus peak |
| | `atr_multiple` + `atr_basis: rolling` | **STRONGLY DEPENDENT** | re-reads ATR at every bar `entryIndex..index` and ratchets (`:519-557`) — **live on Manas** |
| | **`indicator`** | **DEPENDENT on the alias's own window** | `bank.valueAt(alias, index)` (`:659-671`, `:682-690`) — **live on Minervini as `sma50`** |
| `scaled_exit` | tiers | **INDEPENDENT** | entry vs close only; also disabled live (`:295-298`) |
| `square_off` | fast / parabolic | **DEPENDENT (both)** | `close[i - fastBars]` raw offset (`:406-407`); `SMA(parabolic_ma)` (`:421-423`) |
| `time_stop` | `max_bars` | **DEPENDENT — counts BARS** | `int held = index - position.entryIndex()` |
| | `max_holding_days` | **DEPENDENT — counts SESSIONS PRESENT** | builds a `Set<LocalDate>` from bars that exist |
| `signal_exit` | expression | **INHERITS** referenced aliases | `:715-733` |

Three points worth separating out:

- **`time_stop` consults no market calendar in either form.** A missing session contributes no bar and
  no `LocalDate`, so both variants make a position look *younger* than it is and fire **late**.
  Mitigating: **no live swing strategy declares `time_stop`**, so this is latent, not live.
- **`favorableExtreme` (`:735-746`) scans only rows that exist**, so a missing session's high is
  invisible and every peak-anchored trail can be *understated* → **looser than doctrine**, a silently
  under-protective stop.
- **`trailing_stop basis: percent` is schema-valid but silently inert.** A config author would get a
  trail that never fires, with no error. No live strategy uses it. Latent config trap.

---

## The fix-shape question

### Why the naive fix is wrong — and it is worse than "risky"

Mirroring `minBars` onto the exit path **would not have prevented tonight's mis-evaluation at all.**
The affected series held ~350 bars against a 60-bar floor. The guard is simply blind to this failure:
it counts rows, and the defect preserves row count while changing what those rows span. Shipping it
would produce a green, plausible-looking PR that closes nothing.

And it would introduce a real hazard. There is a concrete cohort where a 60-bar floor **would** fire:
**44 symbols hold only 44 daily bars each and zero 1-minute bars** — the corporate-action-purge cohort
(`computed` 2026-08-03 22:5x IST; includes **TATASTEEL, WIPRO, TECHM, TRENT, NAUKRI, PIDILITIND,
MPHASIS, NMDC, PETRONET**). Today they are unreachable by the entry pass, which is exactly why none is
held. But a `minBars` guard on the *exit* pass would mean: if such a position ever existed, **the
batch could never exit it** — the precise "stranded position" failure the owner flagged, with the most
liquid names in the universe.

Worth noting what already happens there instead: with fewer than 50 bars, `indicatorLevel` returns
`null` and the indicator branch returns `Optional.empty()` (`ExitEvaluator.java:663-665`). The trail
goes **inert while the 8% hard stop keeps working**. The system already degrades in the
doctrine-correct direction — fail-open on the soft rule, keep the hard floor. A `minBars` guard would
*replace* that graceful degradation with a hard refusal.

### Recommendation: asymmetric — gate the entry, alert the exit

Split the treatment along the doctrine line the platform already states ("entries need fresh truth,
exits need the best available truth"):

**A. Entry pass — refuse on incomplete coverage (safe to refuse).**
Before scoring a candidate, compare the fetched series' session dates against the market calendar over
the deepest indicator lookback the definition declares. Missing sessions inside that window → skip the
candidate with a counted, logged reason. Not entering costs an opportunity; entering on a stretched
window costs money. This is also where the 17.3% figure lives.

**B. Exit pass — evaluate always, never refuse, but detect and alert.**
Run the identical coverage probe, and when it fails: **still evaluate, still let the exit fire**, but
log at ERROR, publish an ops alert, and persist a `degraded` marker on the evaluation. This preserves
"you cannot refuse to leave forever" exactly, and converts a currently-invisible defect into a visible
one.

**C. Fix the ordering, which is the actual root cause.**
The batch fires at 20:00/20:05 while the historical `candles` plane was still being repaired at 22:46.
Both entry and exit read a plane that was not yet complete. Gate the batch on a coverage watermark the
way `SwingBatchCatchUp` already gates on `inputsAsOf()` — the pattern exists and is proven.

**Named failure mode of the recommendation.** (B) is *detective, not preventive*: if a gap ever
coincides with a genuine trail cross, the position still exits at the wrong level and the alert arrives
**after** the fill. Cost when it is wrong: one incorrect exit per coincidence, caught next morning
rather than prevented. I accept that deliberately — the preventive alternative is a refusal, and a
refusal on an exit path is the strictly worse failure. (A) fails by skipping a good entry on a data
artifact; that cost is an opportunity, and it is recoverable the next session. (C) fails by delaying
the batch if the watermark never advances — which must therefore be bounded by a timeout that
**proceeds with a degraded marker** rather than skipping the run, or it recreates the stranding risk.

### What to measure before shipping

1. **Decision flips, not level deltas.** Replay the last ~60 sessions of exit evaluations on gapped vs
   repaired data and count how many times the *decision* changed. Tonight: 3 levels wrong, 0 decisions
   changed. If that holds across 60 sessions, alert-only (B) is correctly sized; if flips exist,
   escalate to a preventive shape.
2. **Coverage-at-batch-time histogram.** How often is the `candles` plane incomplete at 20:00, and by
   how many sessions? Tonight 2,249 symbols had historical bars land after the batch — that needs to be
   a tracked series, not a one-night reading.
3. **An explicit policy for the 44-symbol cohort.** They cannot be entered and their `sma50` trail is
   inert. That should be a stated, tested decision rather than an emergent one.
4. **Red-proof by restoring the literal pre-fix body**, not a stricter equivalent — and state which
   test reddened on which assertion.

### Secondary findings (not fixed here)

- **`SwingDoctrine.java:154-158` javadoc is materially false** — it asserts Minervini's real exit rule
  is "percent/ATR-based" when there is no ATR in its exit rules and the sma50 trail is live. A future
  reader would conclude sma50 cannot exit a position. Docs-only, but actively misleading on a money
  path. Note `MinerviniDoctrine.trailLevel` returns `bank.valueAt("sma50", lastIndex)` — precisely the
  level the exit branch computes — so the javadoc's "need NOT equal the real trigger" is also wrong for
  this family.
- **The repair over-corrected.** The six repaired dates now show **0** missing symbols while untouched
  neighbours (06-11, 06-17) retain the structural **44**. The purge cohort now has bars on six June
  dates but not on the days around them — a new island pattern in the same plane.
- **ATHERENERG had a 2025-05-06 bar backfilled tonight** — an unrelated retro-mutation instance,
  ~300 rows back and immaterial to a 50-row window, recorded only as evidence that the plane keeps
  moving.

---

## Open doubts

1. **The plane moved under me, twice.** The gap repaired between my first and fifth query. Every
   measurement here is stamped; none should be treated as current beyond tonight. In particular
   "no position is exposed" has a shelf life of hours, not days.
2. **I reconstructed the 20:00 state from `fetched_at`, which is an UPSERT timestamp, not first-seen.**
   It bounds when a row landed; it does not pin it. If any of those five bars had been written earlier
   and re-written at 22:46, my reconstruction would overstate the gap. The uniform 5-bars-per-symbol
   pattern across exactly three symbols argues against that, but it is not proof.
3. **I did not verify the sma50 the ENGINE computed** — I recomputed it in SQL as a simple mean of the
   last 50 closes. ta4j's `SMAIndicator` should agree, but I did not capture the engine's own value at
   20:00, and nothing persists it. The Δ figures are therefore `computed` from a *reimplementation*,
   not read from the engine.
4. **`assumed`: that the three symbols' 5-bar gap is representative.** I measured held symbols
   exhaustively but did not check whether other gapped symbols had deeper gaps that would reach shorter
   windows (e.g. a 20-row ATR).
5. **The entry surface is under-investigated.** I established that 17.3% of passers were scored on
   stretched windows but did not measure whether any *entry decision* flipped — that is a larger
   population than the exit surface and I gave it less attention because no money is at risk until a
   position opens.
6. **Backtest/sim parity unexamined.** Whether the deep sims reproduce this row-based stretching is
   unknown; `contracts/fixtures/swing-exit-equivalence.json` is a characterization fixture that already
   documents a live-vs-sim divergence, so it must not be read as proof the two agree.
7. **Screener completeness assumed, not verified.** I confirmed the screener reads `nse_eod_bhavcopy`
   but did not verify that plane is itself gap-free over the relevant window — and CLAUDE.md records
   bhavcopy gaining rows for months-old trade dates.
