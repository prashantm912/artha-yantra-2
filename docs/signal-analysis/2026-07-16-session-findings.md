# Session findings — 2026-07-16 (data date)

Analysis date: 2026-07-16 (post-market, ~19:00–22:00 IST). Analyst: Claude (owner-directed).
Data: `signal_rejections` rows **0**, signals fired **0**, paper trades **0** (18 pre-existing OPEN
positions, unmanaged all session).
Session character: **DEAD SESSION — engine inert from boot to close.** Not a market/regime finding:
the strategy engine loaded **0 of 39** published strategies at 08:57 IST and never re-resolved. Data
capture was flawless throughout. This file is an INCIDENT record, not a gate-tuning record — the
usual §1–§3/§5 dimensions have no data to report because nothing was evaluated.

**This is the SECOND occurrence of this class.** The first was 2026-07-15 (filed as ledger row F10,
recovered by a manual restart). It recurred within one day, un-fixed. Fixed same night — see §7.

---

## 1 Funnel numbers (§3.1–3.2)

| metric | value |
|---|---|
| rejections (00:00–24:00 IST) | **0** |
| signals fired | **0** |
| strategies evaluating | **0** (of 39 published+enabled) |
| candle channels subscribed | **0** |

Contrast: 2026-07-10 = 701 rejections, 2026-07-15 = 396. Zero rejections during market hours is
never normal (README §3.1 sanity check) — the ~30-rail AND gate blocking every bar still WRITES a
row. Zero rows = the engine never evaluated a bar.

## 2 Rail findings (§3.3/3.5/3.8)

**None possible.** No bar was evaluated, so no rail fired, passed, or blocked. Any rail-level claim
about 2026-07-16 would be fabricated.

## 3 Composite + dots (§3.4/3.6)

**None possible** (same reason). `GET /api/v1/signal-rejections/dot-health` at 19:02 IST returned
`rowsInspected: 0` with every dot `alive: false, detail: "no rejections yet today"` — including
`breadth` (`required: true`). The DotHealthCanary DID observe the condition; whether it paged is
unverified.

## 4 Data health (§3.7) — CAPTURE WAS PERFECT

The critical contrast, and why this was invisible:

| source | result |
|---|---|
| NFO 1m futures candles | **375/375 bars**, 09:15→15:29 IST, zero gaps, all 3 NIFTY contracts (JUL/AUG/SEP) |
| options chain snapshots | 6 underlyings; NIFTY 50 373 snaps / SENSEX 373 snaps, 09:17→15:30 IST |
| market-data container | healthy all session |
| `subscriber_health_events` | **0 rows** — no canary fired |

**Every downstream health signal was green while the engine did nothing.** Feed, containers,
capture, canaries: all fine. That is the signature of this incident class and the reason it ran for
~6.5 hours unnoticed.

## 5 Shadow-book outcomes

**None.** Shadow positions derive from rejections; there were none. No counterfactual (§4.2) is
possible — not "no signal would have fired", but "the gate never ran". Do not read 2026-07-16 as
evidence about any strategy or rail.

## 6 New data points / anomalies

### 6.1 ROOT CAUSE — a 51-second cold-boot vs Kite-login race (CONFIRMED, thread-level evidence)

Timeline (container logs, UTC→IST):

| IST | event |
|---|---|
| 08:57:04 | stack cold-booted (`StartedAt` 03:27:04Z, `RestartCount=0` — a clean start, not a crash-loop) |
| 08:57:17 | market-data restores **yesterday's** (07-15) Kite session from store — expired |
| 08:57:19–31 | engine `ApplicationReadyEvent` → `reload()`; each strategy's universe needs market-data `/futures/term-structure`, which returns `403 Forbidden` → `DATA_STALE` → `kite-rest circuit` open → `no live Kite session`. **All 39 dropped**: `resolves to an empty universe — not loaded` |
| 08:57:30 | market-data publishes `TOKEN_EXPIRED` |
| **08:57:31.565** | **`signal engine loaded 0 published strategies` / `subscribed 0 candle channels`** — engine inert for the day |
| **08:58:22.943** | owner's Kite login lands → `CONNECTED` → `re-arming feed after session change` → ticker connects |
| 08:58:23 → 15:30 | market-data captures **flawlessly**. Engine never re-resolves. |

**Missed by 51 seconds.**

**Why a cold boot specifically** (this is the part that hid it for months): market-data's
`FuturesTermStructureService.staleFallback` (`:182`) serves an **in-memory** `lastGood`
`ConcurrentHashMap` (`:78`) when the live quote fails, but **throws 503** when that cache is empty.

| market-data state | `lastGood` | same expired-token window | outcome |
|---|---|---|---|
| **warm** (normal day) | populated from yesterday | live quote fails → stale fallback serves cached contracts | resolver OK → 39 load ✅ |
| **cold** (07-15, 07-16) | **empty** | live quote fails → **503** | resolver empty → **0 load** ❌ |

Normal days survive the expired-token window purely on a warm cache. Only a cold start breaks.

**Why nothing recovered it** — all three reload paths are structurally blind:
- **08:40 IST cron** (`morningReload`) — already passed; the stack booted 08:57.
- **20s `reconcilePublishedStrategies`** — compares the registry's published set against the
  **last-reload snapshot** (`SignalEngine.java:1477`), deliberately NOT the loaded subset (comment
  cites #579: comparing against loaded reloads forever, since swing/non-rollable/empty skips are a
  legitimate steady state). registry=39, snapshot=39 → **no drift** → a TOTAL failure is
  indistinguishable from steady state.
- **no `kite.status` listener existed** in strategy-signal at all (grep: zero references), even
  though market-data publishes session deltas on that channel and `FeedPipeline.restartFeed()`
  (`:107`) already self-heals the ticker off the same signal. The asymmetry WAS the bug.

### 6.2 Blast radius — 18 paper positions unmanaged

`PaperStaleTickAlerter` emitted **28,441 WARNs** (≈68/min ≈ 17 positions × 4/min × ~7h). With 0
candle channels subscribed, no ticks reached the paper book, so no SL/TP could evaluate all session.
This explains and supersedes the 2026-07-15 §6.2 finding (16/17 positions tickless): **same cause,
louder**. It is not a separate WS defect.

### 6.3 The engine's degraded state is PARTIAL, not total (found during the fix drill)

A cold boot does **not** produce a clean 0-of-39. Reproduced live at 21:50 IST: booting the engine
with market-data down yielded `loaded 32 published strategies (7 dropped on an unresolved
universe)` — the drop is **per strategy**, and the `kite-rest` breaker re-opening partway through a
sequential reload leaves a **partial** set loaded. This matters for detection: any predicate of the
form "loaded is non-empty ⇒ healthy" reads a degraded session as a success. Promote to a standing
check: **the honest health signal is `unresolved == 0`, never `loaded > 0`.**

### 6.4 Method note — the IST clock trap bit again

`TZ='Asia/Kolkata' date` returns **GMT** in this git-bash; 12:59 GMT was nearly read as IST (a 5.5h
error that would have mis-scoped every query). Authoritative IST clock:
`docker exec ay-timescaledb psql -U artha -d artha -t -c "SELECT now() AT TIME ZONE 'Asia/Kolkata';"`
(README §2 already warns to bound queries by explicit `+05:30`; this adds the *clock-reading* trap).

## 7 Tuning candidates

No gate/rail tunes — no data. The actions are structural.

| item | current | action | evidence | status |
|---|---|---|---|---|
| F10 Part A — engine cold-start self-heal | engine loads once at boot; no session-status listener | `kite.status` listener + **bounded** (3× ~35s) retry converging on `unresolved == 0`; `start()` also reads the level-triggered `kite:session:status` key | §6.1 | **SHIPPED [#874](https://github.com/prashantm912/artha-yantra-2/pull/874) @ d9f30a8f — deployed + drill-proven live** |
| F10 Part B — detection | exhausted retry only LOGS `DEGRADED`; nothing pages | distinct probe on `inSession && login-done && market-data GREEN && (loaded==0 \|\| unresolved>0)`; needs the failure-vs-empty split first | §6.1, §6.3 | **OPEN** (ledger F10 Part B) |
| retry bound width | 3 attempts × 35s ≈ 70s | widen — the live drill recovered on **attempt 3 of 3** (~73s for market-data to serve); ~10s more and all three would have burned | drill 21:50–21:52 IST | **OPEN** (chip) |
| `resolveUniverse` failure-vs-empty | returns `List.of()` for BOTH a resolver failure and a legitimately empty screen | split them; blocks Part B and forces the `futures_screener` guard hack | §6.1 | **OPEN** (chip) |

### 7.1 Drill evidence (the only proof that counts)

Deployed #874, then reproduced the incident at 21:49–21:52 IST **without touching credentials** — by
starting strategy-signal while market-data was down (same "resolution fails" state):

```
16:20:45  loaded 32  (7 dropped)    ← cold boot: PARTIAL load (§6.3)
16:20:59  loaded 0   (39 dropped)
16:20:59  boot: kite session already CONNECTED and universes unresolved — reloading   ← key-read path
16:20:59  attempt 1 left 39 strategies unresolved — retrying in 35000 ms
16:21:37  attempt 2 left 39 strategies unresolved — retrying in 35000 ms
16:21:55  kite.status CONNECTED received — reload retry already in flight             ← dedupe guard
16:22:49  loaded 39  (0 dropped)
16:22:49  attempt 3 resolved every universe — retry complete                          ← SELF-HEALED
```

Load-bearing observations: attempts **1 and 2 both failed** — a one-shot reload would have failed
outright; the chain armed from the **level-triggered key alone** (nothing published on the channel —
market-data was down), which is the lost-edge fix; and the CAS guard refused a duplicate chain when
market-data's own `CONNECTED` landed at 16:21:55.

**Not yet exercised live:** the channel-listener path (engine boots with the key reading
`TOKEN_EXPIRED`, then the login publishes a `CONNECTED` edge). Tomorrow's real startup is its first
live run.

## 8 Honesty caveats

- 2026-07-16 carries **no strategy/rail/regime information**. Any rollup must treat it as a NULL
  session, not a zero-signal session.
- The drill reproduced the *state* (resolution failing at boot) via market-data being down, not via
  an expired token. Same code path (`resolveUniverse → List.of()` → drops), different trigger.
- Whether `DotHealthCanary` actually paged the owner on `breadth alive=false` is **unverified**.
