# Engine-liveness detector (unified) — design

**Status:** designed 2026-07-16 with the owner, ready to build. Supersedes the F10-Part-B sketch in
the ledger row (`p1-phase4-platform-planes` neighbour `F10`) and absorbs chip `task_7f6642c4`.

**Owner decisions taken 2026-07-16 (all four forks):** build BOTH the new cross-service detector AND
the canary-gate fixes · bar-anchored K-consecutive-bars predicate · in strategy-signal, JDBC-only ·
alert + bounded auto-heal via `forceResubscribe`.

---

## 1. Verdict — why this exists

**The signal-eval stall is not three incidents. It is nearly every session.** Measured 2026-07-16
against the live DB (`artha`), last 9 trading days:

| IST day | live-engine rejections | verdict |
|---|---|---|
| 2026-07-06 Mon | 643, 09:19→15:19 | **stalled 80.7min** (13:43:30→15:04:11) + 3 more holes (20.8 / 14.7 / 11.7 min) |
| 2026-07-07 Tue | 638, 09:19→**14:22** | died mid-session |
| 2026-07-08 Wed | 0 | dead (stack outage) |
| 2026-07-09 Thu | 0 | dead (stack outage) |
| 2026-07-10 Fri | 701, 09:19→**14:52** | died mid-session + 4 holes >10min |
| 2026-07-13 Mon | 0 | **dead — not previously recorded anywhere** |
| 2026-07-14 Tue | 0 | dead (eval loop parked on unbounded fetch — fixed #866) |
| 2026-07-15 Wed | 396, **09:50**→15:21 | late start + mid-session stall (146.8min max hole) |
| 2026-07-16 Thu | 0 | dead (F10 cold-start — Part A fixed #874) |

**Zero clean sessions in nine.** Every one of these passed every canary.

**07-06 is the proof the stall is real and not a quiet market.** During the 80-minute hole,
**82 one-minute bars arrived** on all three NIFTY futures (`NIFTY26JULFUT`/`AUGFUT`/`SEPFUT`), and
all nine active strategies stopped writing inside a **10-second window**:

```
scalp-morning-trade-nifty      09:22:15 → 13:43:28
scalp-straddle-nifty           09:46:40 → 13:43:28
scalp-connect-the-dots-nifty   09:46:45 → 13:43:30
scalp-trend-change-nifty       09:46:39 → 13:43:26
scalp-market-movers-nifty      09:46:27 → 13:43:20
...                                       ...
(nothing until scalp-hero-zero-nifty at 15:04:11)
```

Nine strategies with *different* windows (one starts 09:22, the rest 09:46) do not all legitimately
end at 13:43:2x. Bars fed, engine silent. That is a stall, and it self-recovered 80 minutes later.

**Trap for anyone re-deriving this: do NOT use `strategy.signals` as a liveness ledger.** It mixes
the live tick engine with the **swing BATCH** engine. Every signal on the zero-rejection days
(07-08 ×10, 07-13 ×1, 07-14 ×3, 07-16 ×1) is `style: swing`, stamped `00:00:00` — batch rows that
fake liveness on a stone-dead live engine. `strategy.signal_rejections` is live-engine-only and is
the correct ledger.

---

## 2. Why every existing detector is blind (all three, structurally)

The common root: **every current detector asks the engine about itself, using state the fault
corrupts.**

`SubscriberHealthCanary.sweep():114-195` is **one method, not two canaries** — #679's eval branch
(`:135-154`) and #634's receive branch (`:156-191`) are two `if`s behind the SAME four early-returns.
Anything silencing one silences both. That alone explains the total silence across all occurrences.

| # | Blind spot | Mechanism | Evidence |
|---|---|---|---|
| 1 | **Freeze-together** (kills #679) | `lastBarReceivedAtMs` written only at `SignalEngine:612` (`onCandleMessage`); `lastBarEvaluatedAtMs` only at `:659` (`drain()`); and `drain()` is scheduled ONLY from `:635-637` *inside* `onCandleMessage`. So `drain()` cannot run unless `onCandleMessage` ran → on a subscriber drop both freeze together → `evalLag = received − evaluated` (`:127`) settles at ≈0 forever → `evalLag >= barGapMs` (`:135`) is **structurally incapable** of firing. | Locked in as intended by `SubscriberHealthCanaryTest:180-191` (`quietMarketBothFrozen_noEvalAlarm`) |
| 2 | **Gate 3 — vacuous-truth disarm** (kills #634 AND #868) | `:120` `!engine.hasOneMinuteSubscriptions()` → `SignalEngine:559-568` iterates `loaded` and returns false when nothing is loaded. **The watchdog is disarmed by the most catastrophic failure it can face.** Same gate disarms `SignalStarvationCanary:77-80`. | 07-16: engine logged `loaded 0 published strategies`; `subscriber_health_events` = 0 rows |
| 3 | **Gate 4 — self-blinding discriminator** | `:167-169` returns silently when `feedAge > feedFreshMs`; `feedAgeMs():231-241` reads `ticks:last-at` over **the same Redis whose subscription just dropped**, and returns `Long.MAX_VALUE` on any exception. Converts *"I can't tell"* into *"stay silent"* — conservative in the wrong direction. | Locked by tests `:80`, `:92` |

Gate 3 is the **same conflation class as `task_f10a04`**: it fuses "nothing loaded because we're
intentionally off" with "nothing loaded because we're broken".

---

## 3. The design

### 3.1 Core idea — cross-service divergence

> **market-data** writes candles. **strategy-signal** writes rejections. Two services, two tables,
> one database. During a session: **bars present + rejections absent = the engine is dead.**

Survives every blind spot **by construction, not by tuning**:

| Blind spot | Why it cannot bite |
|---|---|
| Freeze-together | never reads `received`/`evaluated` — reads two DB tables |
| Gate 3 | never asks what is `loaded`; F10 cold-start = 0 rejections + bars flowing → **fires** |
| Gate 4 | never touches Redis — JDBC only |

Safe to run in-process: on 07-15 `PartialBucketCanary` kept firing throughout the stall, proving the
JVM + scheduler survive this fault. `SubscriberHealthTelemetry:34-38` already holds a `JdbcTemplate`.
And in-process is where the heal lever (`forceResubscribe`) lives — no cross-service RPC.

### 3.2 The predicate

```
alarm IFF
      calendar.isOpen(now)                                  // pure computation, unblindable
  AND now within [session-start + boot-grace, session-end]  // conservative clock window
  AND barsInLastK  >= minBars                               // market-data IS producing
  AND rejectionsInLastK == 0                                // strategy-signal is NOT consuming
```

- **NOT gated on `loaded`** (that is gate 3 — the bug).
- **No Redis** (that is gate 4).
- **No engine in-memory heartbeat** (that is freeze-together).
- `loaded.size()` and `lastReloadEmptyUniverseDrops` (`SignalEngine:154`) go in the **alert body as
  evidence — NEVER as an arming gate.**

### 3.3 K — derived, not guessed

Inter-rejection gap distribution, 09:50–15:15 IST, four independent days (live DB, 2026-07-16):

| Day | p95 | p99 | max | gaps >10min |
|---|---|---|---|---|
| 07-06 | 172s | 217s | 80.7min | — |
| 07-07 | 172s | 177s | 74.4min | 2 |
| 07-10 | 171s | 179s | 41.5min | 4 |
| 07-15 | 174s | 185s | 146.8min | 2 |

**p95 = 171–174s on every day** — the 3m bar interval (180s). The engine answers every bar. Then the
distribution **stops**, and the next thing that exists is a 10–147 minute hole. The 4–10 minute band
is essentially empty. **Bimodal with a canyon.**

**K = 7 (1-minute bars) ≈ 7 minutes** sits in the canyon: ~2× healthy p99, ~0.7× the smallest observed
stall. Would have caught all four 07-06 holes and every dead day.

**Caveats — both load-bearing:**
1. **Every session in the sample is contaminated** (there is no clean day to baseline against), so K
   is derived from a distribution that includes the fault. The healthy mode is nonetheless stable to
   ±3s across four days, which is why K is defensible anyway.
2. **`lag()` only pairs rows that exist** — a session that dies and never resumes (07-07 @ 14:22)
   produces NO trailing gap and is invisible to the percentile query. Therefore the detector MUST
   anchor on **wall-clock-since-last-rejection**, not on gaps between existing rows. The real damage
   is worse than the maxima above.

### 3.4 Response — alert + bounded auto-heal

`SignalEngine.forceResubscribe():597-607` rebuilds the Redis listener container. It is **NOT
`reload()`** → it does **NOT** touch `bankCache`, so the thrash objection that killed F10's
level-triggered design (chip `task_f10a03`) **does not apply here**.

- Alert via the established two-tier path (service → `NotifierClient` fail-soft; scheduler → event-bus
  alert, since a thrown service cannot alert for itself).
- Heal via `forceResubscribe`, **bounded** attempts + cooldown so a misdiagnosis cannot thrash.
- The asymmetry this closes: `FeedWatchdog:84` → `FeedPipeline.restartFeed():103` already self-heals
  the *ticker* off a 60s clock check. strategy-signal has never self-healed its *subscriber*. #874
  added a `kite.status` listener (`SignalEngine:519-525` → `:1550`) but that recovers **strategy
  loading only**, not the candle subscription.

### 3.5 Second workstream — fix the two gates

The new detector is the **instrument**; the gate fixes are the **actuator** (they re-arm the
existing-but-dead-in-practice `forceResubscribe` self-heal).

- **Gate 3:** distinguish "nothing loaded because intentionally off" from "nothing loaded because
  broken". Discriminator is one JDBC query, measured live 2026-07-16: **45 strategies are
  enabled + published** (of 73 total). If 45 should be loaded and 0 are → broken, not idle.
  (Keep the legitimate-idle path quiet: mock stack / engine-disabled contexts / genuinely zero
  published strategies must NOT alarm.)
- **Gate 4:** a Redis failure must never silence the Redis-failure detector. Treat "cannot determine
  feed age" as **suspicious**, not as **healthy**.
- Both gates are pinned by passing tests (`:116-123`, `:180-191`, `:80`, `:92`) — those tests encode
  the buggy behaviour as intended and must be revised deliberately, with the reasoning recorded.

---

## 4. Hard constraints for the builder

- **The knobs must actually work.** `artha.canary.*` and `artha.signals.subscriber-watchdog.*` have
  **NO `application.yml` entry and NO compose passthrough** (verified) — so `enabled`,
  `bar-gap-ms:180000`, `feed-fresh-ms:90000` are pinned to `@Value` defaults and **cannot be tuned or
  disabled without an image rebuild**. Setting them in `.env` does NOTHING. This is the #653 trap
  (`application.yml:64` warns about it). **Any new knob ships with its yml key + compose passthrough
  in the same PR, name-matched EXACTLY, or it is dead on arrival.**
- **Bounded queries only.** `candles` is a compressed hypertable — copy the bounded idiom at
  `DataHealthCanary:163-167` (`SELECT max(ts) ... WHERE ts > now()-1h`), never an unbounded scan.
- **IST/UTC:** in-container `now()`/`::date` is **UTC**. Filter by explicit `+05:30` ISO bounds, never
  `::date = CURRENT_DATE` (off-by-one across IST midnight).
- **Modulith:** the detector reads `signals`-module state. `notifier → signals`, so `signals` must
  never import `notifier` — alert via an in-process event record + `@EventListener` in notifier
  (`DotInputAlert`/`DotAlertListener` is the template). `signals` also cannot import `paper`.
- **Parity firewall:** `libs/strategy-engine` is untouchable; the live `SignalEngine` must never build
  a `SignalEvent`.
- **Sequencing:** `task_f10a04` touches `SignalEngine.resolveUniverse`/`reload`/the drop counter.
  This work touches `SignalEngine` too. **f10a04 lands FIRST; this rebases on top.**
- **Do NOT reuse the universe resolver** to decide "are bars flowing" — that is circular (f10a04 is
  rewriting it, and it is Kite-dependent, i.e. exactly what breaks). Ask the DB whether market-data is
  producing 1m bars; that needs no universe resolution.

## 5. Drill + proof obligations

- **No drill hook exists in strategy-signal.** Repo-wide grep for `drill|force-open` under
  `services/strategy-signal-service/src/main` returns **zero**. Only `DataHealthCanary` has one. The
  pattern to port: `DataHealthState:24/:34-38/:49-55` (fault injection via
  `@Value("${artha.canary.drill-suppress-key:}")`, silently skips recording one instrument's bar) +
  `artha.canary.force-open` (`DataHealthCanary:81/:255-258`, makes an off-hours drill possible).
- **Prove the test can fail.** Every new test must be observed RED against unfixed code before it is
  kept. On 2026-07-16 five defects surfaced in this service and **not one** was caught by a green
  suite; the previous fix's own first draft passed 785 tests while still able to silently reissue the
  outage.
- **Drill it live.** The only step that produced real evidence on 07-16 was the live cold-boot drill —
  it immediately exposed a ~10s margin nobody had reasoned about.

## 6. Open questions (deliberately unresolved)

- **The reference bar set.** "Bars are flowing" needs a symbol scope that requires no universe
  resolution and no Redis. Candidate: a bounded count over `marketdata.candles` for `interval='1m'`
  within the window, optionally scoped to a configured reference symbol. Builder picks; constraints in
  §4. Must not become a second place where universe resolution can fail.
- **Boot grace.** The engine legitimately has nothing to say before its first bar. `task_a6c12601`
  already carries the matching arm-fix for `SignalStarvationCanary` (separate `sessionAnchorMs` from
  `lastGateOutputAtMs`, add `boot-grace-ms` ~30min, predicate `alarm iff now−anchor>grace AND
  now−reference>window`) — reuse that shape rather than inventing a second one.
- **Relationship to #868 `SignalStarvationCanary`** (shipped DORMANT, arm-gated on `task_a6c12601`).
  It early-returns on `!hasOneMinuteSubscriptions()` (`:77`) and `outputAtMs==0` (`:87`) — i.e. it is
  gate-3-blind by construction. Decide whether this detector **supersedes** it (and #868 is retired
  before ever being armed) or complements it. Leaning supersede — two half-detectors is how we got here.
- **Dating conflict, unresolved.** `2026-07-16-session-findings.md:11-12/:87` files 07-15 as an F10
  cold-start (0 loaded → gate 3), but `:25` records 396 rejections that day and
  `2026-07-15-session-findings.md:152-155` documents a mid-session 11:49→14:10 self-recovered stall.
  Both cannot be true of the same session. **Gate 3 explains 07-16 with certainty; 07-15/07-10/07-07
  are UNDETERMINED from static code** — gate 4 is the leading candidate. The live drill is what
  settles it.
