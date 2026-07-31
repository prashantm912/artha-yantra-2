# Live in-session data-health check — 2026-07-27 (data date)

Analysis date: 2026-07-27, ran 09:43–09:47 IST (scheduled `session-analysis live`).
Analyst: Claude (scheduled live-data-health task). **Read-only run** — no restarts, no writes, no deploys.
Verdict: **GREEN.**

Scope note: this is the mid-session §4.1 data-health watch, the second scheduled read of the day.
[`2026-07-27-open-gate.md`](2026-07-27-open-gate.md) covered the 09:36–09:41 open gate (PASS); the
evening `session-analysis post` run owns `2026-07-27-session-findings.md` and should fold both in.

---

## 0 Verdict

**GREEN.** Both machine canaries healthy, the engine is receiving *and* evaluating bars, and every
data-integrity probe run came back clean. Nothing for the owner to act on.

The one thing worth carrying forward is methodological, not operational: **the eval-outcome counter
read flat for the first two samples and that was the trade window, not a stall** (§2). It is now
written into README §4.1 so the next morning run does not re-derive it.

## 1 Preconditions

| check | result |
|---|---|
| Host-clock guard (B8) | Host `[DateTime]::UtcNow` = **2026-07-27T04:13:16.19Z**, container `now()` = **04:13:27.33Z**. **11 s** apart, under the 60 s threshold — no ⚠ CLOCK-DRIFT line, container time not substituted. Consistent with the open gate's <1 s reading 7 min earlier. |
| Trading day / in-session | Mon 2026-07-27, **09:43 IST**, inside 09:15–15:30. |
| Stack | **11/11** `ay-*` containers healthy. strategy-signal up ~10 h (boot 2026-07-26 23:20 IST), no restart today. |

## 2 Check 1 — engine liveness (counters, never `signal_rejections`)

`docker exec ay-strategy-signal-service sh -c "wget -qO- http://127.0.0.1:8082/actuator/prometheus | grep -E 'ay_signal_eval_outcome|ay_signal_bar_'"`

| sample (IST) | `chart-gate-failed` | `confluence-blocked` | **Σ** | `..._received_age_s` | `..._evaluated_age_s` | slugs emitting |
|---|---|---|---|---|---|---|
| 09:43:45 | 18 | 18 | **36** | 50.016 | 49.840 | 2 |
| 09:44:50 | 18 | 18 | **36** | 26.427 | 26.424 | 2 |
| 09:45:33 | 18 | 18 | **36** | 32.059 | 32.056 | 2 |
| **09:46:54** | **38** | **34** | **72** | 54.375 | 28.673 | **16** |

`ay_signal_eval_failures_total` = **0** at every sample. All other outcomes (`fired`,
`composite-below-threshold`, `unscoreable-indicators-warming`, `discipline-paused`,
`confluence-gate-absent`) = 0 throughout.

**Σ ADVANCING 36 → 72 ⇒ engine alive.** Both gauges stayed fresh and non-negative across all four
samples (no `-1` "no bar this boot", no sub-`-1` clock fault), which is README §4.3's
*received fresh + evaluated fresh* row — neither stall shape present.

### 2.1 The flat segment was the trade window (promoted to README §4.1)

Σ was flat at 36 across the first three samples — ~2 min spanning a 3m bar boundary — with only
**2** slugs emitting (`scalp-morning-trade-nifty`, `scalp-morning-trade-sensex-niftyoi`). That is
**not** a stall: most scalper YAMLs open after 09:45 under the cross-strategy rule, and the
`morning-trade` family is the deliberate exception (`window: { from: "09:16", to: "15:00" }`, per its
own YAML header comment — "the general 'after 09:45' cross-strategy rule does NOT apply —
owner-confirmed"). At 09:45 the rest came in-window and Σ stepped +36 with slugs 2 → 16.

Two operational consequences, both now in README §4.1:
1. A counter delta taken wholly before 09:45 is close to meaningless — space the two reads across a
   bar boundary **after** 09:45.
2. The gauges, not the counter, are what settled liveness here. The counter is an attribution
   primitive; treating its opening-half-hour flatness as evidence is exactly how the 2026-07-17 false
   escalation was manufactured.

Corroborator — `strategy.signal_eval_outcomes`, latest bucket only (never a session-wide `sum()`):
09:42 → 4, 09:39 → 4, 09:36 → 4, 09:33 → 4. Fresh, non-zero, landing every 3 min. The flat **4** per
bucket is the same window effect: 2 slugs × 2 sides.

Context (not the liveness signal): `strategy.signal_rejections` since 09:15 went **18 rows / 2 slugs
(max 09:43:00)** → **34 rows / 16 slugs (max 09:46:26)**.

## 3 Check 2 — shadow book + variants

**Zero shadow positions opened today, and that is correct** — not a shadow-book fault. No rejection
passed composite:

| slug | side | rows | composite min–max | threshold | passed |
|---|---|---|---|---|---|
| `scalp-morning-trade-nifty` | CE | 3 | 0.399–0.426 | 0.600 | 0 |
| `scalp-morning-trade-sensex-niftyoi` | CE | 3 | 0.399–0.426 | 0.600 | 0 |

(The remaining 12 of the first 18 rows are PE/context-less rows blocked before scoring, carrying no
composite.) The shadow book opens only on composite-passing rejections, so 0 rows is the expected
consequence of 0 passes — no F8 lot-size failure to chase.

League (`GET /api/v1/signal-rejections/shadow-summary`, all-time, unchanged today):

| variant | closed | wins | pnl points | **pnl NET ₹** | unpriced |
|---|---|---|---|---|---|
| `champion` | 178 | 73 | −752.85 | **−44,160.71** | 0 |
| `vol-off` | 36 | 10 | −471.10 | **−19,331.28** | 0 |
| `vol-12k5` | 28 | 8 | −353.10 | **−13,390.05** | 0 |
| `composite-055` | 11 | 3 | −10.20 | **−1,542.15** | 0 |

`unpriced = 0` across all four books ⇒ no NFO lot-size lookup failures. No close carries a null
`pnl_net`.

## 4 Machine canaries (STEP 0)

| canary | result |
|---|---|
| `GET /api/v1/market/health/data` | `{"status":"GREEN","marketOpen":true,"asOf":"2026-07-27T04:13:39Z","tickedTokens":69,"problems":[]}` — data plane machine-verified, `tickedTokens > 0`. |
| `GET /api/v1/signal-rejections/dot-health` | 18 rows scanned / 6 context-bearing. **alive:** `breadth`\*, `futures_oi`\*, `underlying_oi`\*, `vix`, `oi_spurt_price`. **dead:** `iv_rank`, `dow`, `fii` — all `required: false`. (\* = required) |

Both GREEN ⇒ per the task contract, only checks 1, 2 and 5 were run in depth.

**The dead set is exactly the carried one** — 07-24 ledger has `ivRank` NULL 100% and `fiiLongPct`
NULL 100% (dead-data since 07-02) and `dowUp` NULL by design (un-armed). Nothing newly dead, nothing
newly alive. This corrects the open-gate file's §6 reading that 21 min in was "early for those inputs
to populate": they are not pending, they are the standing state, and the correction is now in README
§4.1 so the EOD re-check in that file's carry-forward list is judged against the ledger rather than
against the clock.

## 5 Check 5 — EXT-02 Upstox rate budget (first live session on this code)

`docker logs ay-market-data-service --since 2026-07-27T03:40:00Z` → **123 lines, zero matches** for
`rate` / `budget` / `acquire` / `429` / `throttl` / `unpriced`. Log stream verified live (cagg-refresh
lines through 04:16Z), so the empty match set is a real absence, not a dead pipe.

No rate-starvation of the armed F9 margin path, and no `unpriced` reason attributable to budget
exhaustion. The open−30min batch-lane pause appears to be doing its job on its first live session.

## 6 Data-integrity probes

| probe | result |
|---|---|
| §3.15 misaligned (phantom) 1m candles since 09:15 | **0 rows**. No feed outage, so no phantom-bar inflation of the `volume-floor` operand. |
| §3.12 OI quadrant liveness | **No NEUTRAL on any context-bearing row** — `SHORT_COVERING`/`SHORT_BUILDUP` ×4, `LONG_UNWINDING`/`LONG_BUILDUP` ×2. The 07-20 748/748-NEUTRAL defect is not present. |
| Capture freshness | `NIFTY26JULFUT` / `26AUG` / `26SEP` each **30** 1m bars since 09:15, max bucket **09:44 IST** at wall-clock 09:45:33 (= now−1m). Full coverage, no interior gap. |
| §3.10 coverage denominator | Boot line `signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)`. `strategy.strategies` published+enabled = **44** = 38 scalpers + 6 swing (batch engine, not live-loaded). `unresolved == 0` — the honest health signal — satisfied. |

## 7 Carry into the evening `post` run

1. Re-check `iv_rank` / `dow` / `fii` at EOD **against the dead-dot ledger**, not against the clock
   (§4) — dead at 15:30 is a ledger confirmation, not a new finding, unless the set itself changed.
2. Re-run the §3.15 phantom-candle probe at EOD — zero at 09:47 only proves no outage yet.
3. Re-run §3.12 at EOD — quadrants were live all morning; confirm they stayed live, since the 07-20
   failure was session-long rather than intermittent.
4. Confirm the post-09:45 eval ramp held all session (§2.1), and that 0 fires is regime rather than
   coverage.
5. Re-check the host-clock guard at EOD — 11 s this run, <1 s at the open gate. B8 stays a
   free-running-CMOS watch item, not a closed one.
