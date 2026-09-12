# Session findings — 2026-09-07 (data date)

Analysis date: 2026-09-07 (scheduled post-market agent, run ~16:20–17:10 IST — before the 18:45+
evening chain, §8). Analyst: Claude (scheduled `session-analysis post`). Data: `signal_rejections`
rows **1,213** (bounds `2026-09-07T09:15:00+05:30`…`15:40`; rows 09:19:07–14:58:00), signals fired
**5 entries + 5 exits** (10 signal rows), paper trades **5 filled / 5 closed, funded net
−₹1,624.34**, shadow champion **27 closes, 17 net wins, +₹7,761.30** (§5.2 — a rare green shadow
day).

Session character: **Monday, NSE-weekly expiry EVE (09-09 NIFTY weekly is Tuesday… front weekly
09-08; no expiry today on either exchange)** · gap-flat open 23,883.15 → all-day slide to a
23,760.30 continuous close (low 23,737.90) · regime **TREND-DOWN (continuous eff 0.808; official
0.684 — trend on BOTH reads, aligned stamps, CAS delta +18.85)** · VIX 10.68–11.35 · signal
contract **`NFO:NIFTY26SEPFUT`** (named by the fired signals' own rows) · **PE-dominant composite
day: 290 PE passes / 18 CE, max PE 0.9043 — and the 60m-bias veto AGREED with the PE side, so the
gate finally opened: first multi-fire funded day since 09-03** · 6th consecutive fully-automatic
Kite login — but the boot window held a NEW shape: Friday's token stayed server-valid overnight,
died at 07:06 IST, and the ticker ran a ~58-min 403 storm until the 08:05 cron re-logged in (§4b).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,213 — **33 of 37** enabled scalpers emitted rows; the missing 4 (`scalp-golden-crossover-nifty`/`-sensex-niftyoi`, `scalp-open-high-low-nifty`/`-sensex-niftyoi` — the CE siblings) evaluated 105×/45× each, every eval `chart-gate-failed`/`composite-below-threshold` (V053 denominator), so coverage is genuinely FULL |
| eval outcomes | chart-gate-failed 2,005 · confluence-blocked 1,213 · composite-below-threshold 197 · **fired 5** · discipline-paused 0 |
| fired reconciliation (§3.36) | **5 fired = 5 emitted = 5 FILLED** — zero suppressions, zero governor refusals; cleanest possible shape on a multi-fire day |
| coverage | **23 of 23** populated 15-min buckets 09:15–14:45 (thinnest: 09:15 at 8, 09:30 at 10); rejections end 14:58 |
| boot health | boot 02:26:25 IST (9th consecutive overnight host downtime — earliest boot yet); market-data's FIRST start attempt crashed (`bhavcopyStartupCatchup` bean, DB-not-ready race — same class as 09-04's strategy-signal crash, other service), docker restart self-healed, `RestartCount=1` both; engine reload 0/37-unresolved transient at 02:26:39 → **37/0/0 at 02:30:57** (~4.3 min — market-data was itself restarting) |
| §3.30 freeze telemetry | **FLAG HIT — 4 of 5 subs frozen before 14:30** (2nd hit, after 09-03): each sub took exactly 1 entry (subs 1/2 at 11:07, sub 3 at 11:43, subs 4/5 at 12:52); first-loss freezes at 11:43 (1), 12:10 (3), 13:04 (4), 13:22 (5); sub 2 stayed available (+₹36.56 win, under the profit lock). Day PnL by sub: 1 −₹452.49 · 2 +₹36.56 · 3 −₹307.84 · 4 −₹64.78 · 5 −₹835.79. `discipline-paused` 0 — no post-13:22 fire reached the check, so the freeze never actually bound |

## 2 Rail findings

- **volume-floor first-block 692/1,213 (57.1%)** — banded and honest (all-fails avg operand 11,831
  vs avg threshold 22,819); `relative-volume-floor` armed **37/37** (§3.14 registry check clean).
- **`strike-pick`: 44 all-fails, ALL NIFTY-rooted (8 slugs), ZERO SENSEX** — mild NSE-cluster
  Monday (§3.27's Mon–Tue NIFTY shape), far below the 235–604 saturation days. SENSEX picks
  resolved and funded (~₹750–800 legs).
- **confluence-composite all-fails 592 = 82 `60m bias opposes the side` (composite 0.2941–0.6649)
  + 510 score-shortfall aggregates (39 distinct values 0.1961–0.5851)** (§3.39 split). First-block
  share just 9. The veto count collapsed from 09-04's 746 because the bias pointed DOWN and the
  composite passes were PE — **the veto and the tape agreed, and the gate fired** (§5.3).
- First-block tail: time-window 269 · rsi-band 83 · time-of-day-preference 48 · pct-price-move 20 ·
  two-candle 20 · divergence-vol-gate 20 · volume-pump 20 · option-side-constraint 12 ·
  oi-cross-required 12 · confluence-composite 9 · max-oi-sr-gate 6 · call-put-delta-filter 2
  (13 distinct rails).

## 3 Composite + dots

- **OI bloc fully LIVE**: quadrants NEUTRAL **0/882** (SHORT_BUILDUP 510 — the bearish tape read
  correctly — · LONG_BUILDUP 297 · SHORT_COVERING 65 · LONG_UNWINDING 10), spurt NULL 0. futures_oi
  capture 25,599 snaps / **371 of 375 minutes** (missing: 4 scattered singles 09:15/09:47/13:56/15:17).
- **Composite passes 308 of 882 scored (34.9%) — CE 18 (max 0.6649) / PE 290 (max 0.9043).** Fired
  evals scored 1.0000 ×2 (11:06), 0.9191 (11:42), 0.9277 ×2 (12:51).
- Dot support (n=882 unless noted): `iv_rank` 0% (withheld, standing) · `iv_pair` 0% (**35th**
  session — T3, owner) · **`iv_abs_band` 0% (n=123) — flipped back OUT of the band: fresh atmIv
  stamp 0.099551 (09-04's 16:00 write) sits just under 0.10; T28's coin flip, 3rd face-change in
  4 sessions** · oi_spurt 6.7% · basis 9.3% (down-tape: basis dot one-sided) · vwap 15.3% ·
  volume 21.5% · trending_cross 32.5% · iv_slope 46.3% (n=123) · futures_oi 59.2% ·
  sentiment_slope 60.1% · underlying_oi 62.9% · premium_skew 66.7% (n=6) · rsi 73.0% · psar 73.8% ·
  sentiment 79.5% · drastic_oi 87.5% · vix 90.7% · breadth 90.7% · vwma 91.2% · supertrend 99.5%.
- **§3.28 breadth (T30) — side-split saturated the 08-11 way**: PE declines 33–42 vs `>32` (nearly
  always crossing), CE advances 13–16 (never) — the with-trend side gets the free +1.0, the
  counter-trend side dead weight. Aggregate 90.7% is two saturated sides superimposed.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 882/882 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 882/882 | by design (un-armed) |
| `fiiLongPct` | live on all 882 contextful rows | healthy |
| `atmIv` | 1 distinct (**0.099551 — FRESH, 09-04's EOD write**) | frozen daily stamp, correct mechanism (G12/T28) |
| vix | 14 distinct, 10.68–11.35 | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 15th consecutive |
| §3.17 canary | **0 WARNs + 0 straddles** (from a `--tail 4000` snapshot — knowable) | fully clean; **NEW-6: 2nd consecutive clean opening bucket** |
| signal-future capture | **375/375 1m minutes** (KITE-replaced tail as normal); options chain 1m+ snaps to 15:33 | healthy, full session — incl. across the 14:26 ticker blip (§6.2) |
| per-contract tick/bar divergence | **8 `data canary RED` events, 6 contracts** (BFO SENSEX 77300CE/PE, 77100CE; NFO NIFTY 24300CE/PE, 23250PE ×3 — one gap 879 s) | **H49 recurrence day 3** — in-session, non-expiry, clean network; DB heals underneath via KITE tail re-fetch; §6.3 |
| dot-health | consistent with row-level reads; no newly-dead dot | clean |

## 4b BOOT WINDOW (§3.41) — a NEW shape: the weekend token died mid-morning, ~58-min pre-open ticker 403 storm, healed by the 08:05 cron

All IST, from the md log snapshot (`--tail`). **Circuit-breaker transitions: ZERO all day. Capture
minutes lost overlapping the window: ZERO** (everything below ends 70 min before the open).

- **02:26:23** — containers start (9th consecutive overnight host downtime; earliest boot yet).
  ⚠️ **market-data's FIRST start attempt CRASHED**: `UnsatisfiedDependencyException` creating
  `bhavcopyStartupCatchup` — the DB-not-ready boot race, this time on market-data (09-04 it was
  strategy-signal's `paperStrategyScopeGuard`). Docker restart relaunched clean at 02:26:33;
  `RestartCount=1` on both services.
- **02:26:37** — `kite session restore: persisted token from 2026-09-04 … expired at
  2026-09-05T06:00+05:30 — NOT resumed` (#1520's 6th refusal). Boot catch-up at 02:26:44:
  *outside the 08:00–15:30 window — not attempting* (correct).
- **02:30:21** — `kite session status -> TOKEN_EXPIRED` then `-> CONNECTED` 2 ms apart, ticker
  connected 02:31:48 — **Friday's token was still SERVER-valid**: the restore's computed expiry
  (Sat 06:00) was conservative; Kite had not yet invalidated a token unused over a weekend
  (`computed` from the log sequence; the exact validation trigger at 02:30 is `assumed` — likely
  the periodic session-status check finding the stored token still accepted).
- **07:06:21–07:06:24** — contract canary ran (4 drift entries — the standing daily count), then
  `kite ticker disconnected`: Kite invalidated the stale token pre-open. **07:06:27 → 08:04:59:
  ~57 ticker reconnect attempts, every one `handshake … HTTP/1.1 403 Forbidden`, ~62 s apart** —
  benign by construction (pre-open, nothing to capture) but the longest 403 storm since 08-27.
- **08:04:59–08:05:00** — the 08:05 cron slot fired: authorize hop 1 → 302 same-origin,
  `request_token` at hop 2 → **CONNECTED 08:04:59, ticker connected 08:05:00** (6th consecutive
  fully-automatic login; watchdog at 08:15 saw a valid session; `ay_kite_session_valid` 1.0 at
  analysis time).
- **02:26:39 → 02:30:57** — engine reload transient 0/37-unresolved heals to **37/0/0** (~4.3 min;
  longer than the ~60 s norm because market-data was itself crash-restarting; `unresolved==0`
  reached 6.7 h before the open).

Verdict: **benign, but reportable** — the "restore refuses / server still accepts / dies at ~07:06"
sequence is what an armed pre-open trading job would have tripped over; today only the pre-open
ticker paid. Watch whether Monday-after-weekend boots repeat it (§7 NEW-16).

## 5 Shadow outcomes + counterfactuals

### 5.1 Funded book — 5 fills, 1W/4L, net −₹1,624.34

| pos | leg | slug | entry (IST) | exit | net |
|---|---|---|---|---|---|
| 108 | NIFTY2690823950PE ×130 @165.70 | golden-crossover-nifty-pe | 11:07 | TIME_STOP 11:43 | −₹452.49 |
| 109 | SENSEX2691076900PE ×20 @754.65 | connect-the-dots-sensex-niftyoi-pe | 11:07 | TIME_STOP 11:37 | +₹36.56 |
| 110 | SENSEX2691076900PE ×20 @752.05 | connect-the-dots-sensex-niftyoi-pe | 11:43 | **TRAILING_STOP** 12:10 | −₹307.84 |
| 111 | NIFTY2690823950PE ×130 @172.90 | golden-crossover-nifty-pe | 12:52 | STRUCTURAL_STOP 13:04 | −₹64.78 |
| 112 | SENSEX2691076900PE ×20 @797.55 | connect-the-dots-sensex-niftyoi-pe | 12:52 | TIME_STOP 13:22 | −₹835.79 |

The PE entries were RIGHT on direction (index fell all afternoon) and still lost — every exit fired
before the 14:00+ leg down; the 11:07–13:22 window was the day's sideways middle. T29's exit-track
question again, from the permissive side. §3.34 heat: grep **0** on 5 funded entries = every margin
call succeeded; `margin_snapshot`/`margin_pct` 0.00 on all 5 (long-option no-SPAN shape — coverage
question N23-A stands). §3.40 settle: **0 refused** — all 5 legs had live tick references
(strikes inside the pinned band). Emit latency on the 5 entries 16.0–19.5 s (vs G8's 16.7–17.6 s
band — 19.5 s is a new high-water; watch under T8/T26). §3.29 delta: TIME_STOP +3, TRAILING_STOP
+1, STRUCTURAL_STOP +1; **never-fired set unchanged** (take_profit premium_pct 35 armed / 0 closes
since 07-01 · signal_exit 37 / 0 · square_off 2 / 0 · tag oi-confluence-exit→CONFLUENCE_FLIP 8 /
0). INDETERMINATE standing: the two `atr_multiple` rows; `stop_loss` premium_pct/percent bases.

### 5.2 Shadow book — 27 closes, 17 net wins, +₹7,761.30 (rare green day; concentrated)

**Champion: 27 closes → 11 deduped `(bar, leg, entry)` clusters.** The PE square-off family
carried it: 09:27 pair +₹2,180.09 · **09:48 pair ×5 fan-out +₹13,876.80 (79% of the gross win
side)** · 10:12 pair +₹1,833.19 · 10:15/10:18 +₹705.39 — every PE cluster held to square-off on a
falling tape. Against them the 13:54 CE-bounce chase: `NIFTY2690823700CE` ×5 **−₹6,857.40** +
`SENSEX2691075700CE` ×5 **−₹3,976.77**. ⚠️ Concentration per §3.24: ex the 09:48 pair the day is
**−₹6,115.50** — one bar carried the sign. All-time champion **−₹412,421.12** (944 closes, 316 net
wins). Entry latency p50 **1:18.9** / p95 1:21.1 (n=37) — **back inside the 1:18–1:20 structural
band** after two sessions above (T8/T26 watch de-escalates).

**Challenger-only class: 2 observations, 2 WINS, +₹973.10** (57th–58th measured loosenings —
first winning challenger-only day since 09-03): vol-off `SENSEX2691076900PE` 10:36 +₹546.57 ·
vol-12k5 10:48 +₹426.53. **Loosening ledger 56/45/11 → 58 measured / 45 losses / 13 wins.**
All-time: composite-055 **−₹25,741.29** (2 closes today, −₹2,218.91) · vol-12k5 **−₹52,306.03** ·
vol-off **−₹83,938.24** — REJECTED statuses stand (trend-down days flatter loosenings; the books
remain deep negative).

**Per-rail counterfactual P&L (owner directive 08-20), all-time champion NET:** volume-floor 524 /
**−₹261,189.78** (13 new closes today were net POSITIVE ~+₹16k — the PE square-off winners were
volume-floor-refused) · rsi-band 113 / −₹75,849.09 · two-candle 42 / −₹13,675.41 ·
divergence-vol-gate 41 / −₹11,232.22 · call-put-delta-filter 7 / −₹11,149.59 ·
morning-opening-formation 4 / −₹9,629.13 · volume-pump 41 / −₹7,986.35 · max-oi-sr-gate 9 /
−₹6,998.85 · **`confluence-composite` 25 / +₹2,045.45 — the cushion gave back another −₹2,218.91
(2 new closes, the 13:54 CE legs)** · oi-cross-required 29 / +₹1,155.04. **Root split: today BOTH
positive (NIFTY +₹231.75/trade ×14, SENSEX +₹347.44 ×13); all-time SENSEX −₹190.89 (435) vs NIFTY
−₹649.75 (509)** — 7th flip; still not actionable.

### 5.3 §4.2 counterfactuals — NEW-9 day 9: the veto's BOTH faces in one session

- **Permissive face (first clean observation):** the 60m bias read DOWN, the composite passes were
  PE, the veto let them through — 5 funded fires. Result −₹1,624.34 (1W/4L): direction right,
  exits ate it. The veto working as a momentum filter does not make the entries winners.
- **Blocking face:** sole-blocker set **2 rows → 2 deduped CE legs** (13:54
  `NIFTY2690823700CE` / `SENSEX2691075700CE`, composites 0.6127) — both corroborated LOSERS via
  the books' own rows on the same legs (champion clusters −₹6,857.40 / −₹3,976.77 ×5 each;
  challenger singles −₹1,420.16 / −₹798.75). The veto refused the same afternoon CE-bounce chase
  it refused on 09-04.
- **Tally: 27 losers refused (d1–3, d5, d6, d8, d9) vs ~6 winners + 2 washes (d4).** Still no
  proposal — the 08-31 adverse day (~₹48.5k) keeps the sign regime-dependent.

## 6 New data points / anomalies

### 6.1 Log barrier further eased — `--tail` now reads back to 08-29; `--since` still dead

Bounded `--tail ≤~4000` reads the newest strategy-signal segment cleanly (today fully covered; a
`--tail 10000` redirect still collapses to the corrupt 09-01 segment, so keep N small).
`--since` on strategy-signal still returns ZERO lines for any modern date — the silent-artifact
trap stands. All log-derived checks today (canary, heat, suppressions, boot lines) came from a
`--tail 4000` snapshot in the scratchpad. Recreate proposal stands at low urgency.

### 6.2 In-session ticker blip 14:26:47–52 IST — zero measurable impact

Two `Failed to connect to 'ws.kite.trade:443'` errors then `kite ticker disconnected` at
14:26:52; **no reconnect line, yet capture never gapped** (signal future 375/375 1m minutes;
futures_oi's 4 missing minutes don't touch 14:26; options snapshots continuous; rejections ran to
14:58). Reads as a sub-second WS re-establish the SDK didn't log, after a transient DNS/connect
failure — the NEW-13 outbound-blip family, mildest instance yet. Watch, no action.

### 6.3 H49 day 3 — 8 canary REDs / 6 contracts, one 879 s bar-closing gap

Same tick-agg-not-closing-bars shape on pinned strikes: BFO `SENSEX2691077300CE/PE` (10:10),
`77100CE` (12:40, 635 s), NFO `NIFTY2690824300CE/PE` (12:10), `23250PE` (14:00/14:20/14:40 —
879 s, the longest measured). Non-expiry day, clean network (0 breaker transitions, in-session
ticker blip is 14:26 ≠ any RED window). DB heals underneath via the KITE tail re-fetch; no funded
exposure (the funded legs — 23950PE/76900PE — were NOT among the affected contracts). Evidence
accrues to ledger H49; mechanism question unchanged.

### 6.4 Swing books PARTIALLY UNBOUND — first manas entry after 5 capacity-bound sessions

The 08:35 catch-up for session 09-04: **manas-arora 141 candidates → 1 entry (RUBYMILLS ×35
@428.51, book manas-arora, OPEN)**, with **8 pyramid-cap refusals** logged (BALAMINES, DEEPINDS,
ARROWGREEN, PREMIERPOL, INOXINDIA, KAMDHENU, PFOCUS, ACMESOLAR — 6.0% portfolio open-risk cap);
minervini 162 candidates → 0 entries. NEW-8 11th measurement: the cap is binding at the margin,
not absolute. One oddity: the catch-up summary line says `0 refusal(s)` while 8 pyramid-cap WARNs
precede it — the summary's refusal counter evidently counts a different class (`recalled`-free
observation; not escalated). Also: `instrument meta lookup failed for NSE:ARROWGREEN — unresolved
proxy (lot 1): 404` — strategy-signal's own meta path does not ride the H29 `-BE`-twin fallback
that market-data's resolver applied to the same symbol seconds earlier (H29/H36 twin-fallback
lines fired normally in market-data). Cosmetic today (the symbol was cap-refused anyway); noted.

### 6.5 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **12 REVIEW lines — the identical standing set** of
  08-26…09-04 (7×[A] chip open/closed keyword pairs, 5×[B] pickup-vs-DONE keyword class). No
  edits; ledger consistent modulo the standing set.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Nothing republished.

### 6.6 H31 day-context — 7th consecutive clean day

Refresh durations 217 ms–2.4 s across the session, no FAILED movement, phase margin intact.

### 6.7 09-04 evening chain (inherited watches) — ALL CLEAN

EVENING_CHAIN DONE 18:58:58 · bhavcopy 09-04 **3,506 rows** · MARKET_CONTEXT_DAY 09-04 row present
· MINERVINI_PLANE_DIVERGENCE DONE 18:47 · NOTIFIER_HEALTH 09-07 DONE (08:29:59, SCHEDULED) ·
INGEST_COVERAGE 09-07 DONE 08:45.

## 7 Tuning candidates

Ledger §0 group G/H is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-16 (09-07) | weekend-stale Kite token: restore refuses it, the server still accepts it, it dies mid-morning (~07:06) → ~58-min pre-open ticker 403 storm until the 08:05 cron | **OBSERVATION — new**; benign today (pre-open), but the sequence brackets the 08:05 slot's assumptions; watch next Monday | §4b |
| H49 (ledger) | per-contract tick-agg bar-closing sparse | **OPEN — day 3: 8 REDs / 6 contracts, longest gap 879 s, in-session non-expiry** | §6.3 |
| NEW-9 (08-26) | 60m-bias veto | **OPEN — day 9: both faces measured — let 5 PE fires through (−₹1,624 on exits, not direction) AND refused 2 corroborated CE losers; tally 27 losers vs ~6 winners** | §5.3 |
| NEW-6 (08-19) | unpaired opening-bucket canary WARN | **CLEAN — 2nd consecutive clean opening; 0 WARNs + 0 straddles all session** | §4 |
| NEW-13 (09-01) | recurring host outbound-network death | **OBSERVATION (owner/ops) — carried**; mildest instance yet: 14:26 WS blip, zero impact | §6.2 |
| NEW (09-04) | DB-not-ready boot-race crash | **OBSERVATION — carried, now BOTH services have shown it** (09-04 strategy-signal, today market-data); docker restart self-heals; `RestartCount=1` after host downtime is expected noise | §4b |
| log barrier (09-01) | strategy-signal docker log | **eased further** — `--tail` reads to 08-29; `--since` still dead; recreate at low urgency | §6.1 |
| NEW-10 (08-27) | risk-limit base = current equity | **OBSERVATION (owner) — carried**; no trip (day −₹1,624 ≪ limit) | — |
| watch | `strike-pick` chain-proximity | mild NIFTY Monday cluster: 44 all-fails / 8 NIFTY slugs / 0 SENSEX | §2 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried**; no deploy today | — |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried**; heat grep 0 on 5 funded entries (calls succeeded; N23-A coverage question stands) | §5.1 |
| T30 | `breadth` dot `>32` | **OPEN** — side-saturated again (PE decl 33–42 crossing, CE adv 13–16 never); the 08-11 shape | §3 |
| T29 | exit model dominates entry gate | **OPEN (exit-band track)** — new face: funded PE entries RIGHT on direction lost −₹1,624 to mid-window exits while the no-time-stop shadow PE holds made +₹13.9k on one bar | §5.1/5.2 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 57.1%; loosening ledger **58/45/13** — today's 2 challenger-only WINS are the trend-day face |
| T28 | `atmIv` frozen daily stamp | **OPEN** | fresh stamp 0.099551 just OUTSIDE the band → `iv_abs_band` 0%; 3rd coin-flip in 4 sessions |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (35th session) |
| T23 | partial-bucket tolerance | **OPEN** | 0 WARNs + 0 straddles — cleanest measurable session |
| T1 / T7 | `relativeVolumeMultiplier` / composite threshold | **REJECTED — carried** | all challenger books deeper negative |
| NEW-8 (08-24) | swing governor watch | **STANDING — 11th measurement: PARTIALLY UNBOUND (manas 1 entry, 8 pyramid-cap refusals; minervini 0)** | §6.4 |
| T8/T26 | latency | **OPEN (data) — shadow p50 1:18.9 BACK IN BAND (n=37); funded emit latency 16.0–19.5 s, 19.5 s a new high-water** | §5.1/5.2 |
| T2 | `iv_rank` | carried, not open | NULL 882/882 |

## 8 Honesty caveats

- **Regime: TREND-DOWN, from the CONTINUOUS session** (o 23,883.15 → continuous freeze 23,760.30
  pinned 15:15–15:28; range 23,737.90–23,890.00; net −0.51% on 0.64%, **eff 0.808**). Official CAS
  print 15:29 **+18.85** → close 23,779.15 (official read −0.44%/0.64%, eff 0.684 — trend on both
  reads, aligned stamps). **G11 chop count stays 10.**
- **This run executed ~16:20–17:10 IST, before the evening chain**: today's bhavcopy EOD, screens,
  settles, market-context write and canaries had not run — tomorrow's run inherits them.
  (Friday 09-04's chain is verified clean in §6.7.)
- Log-derived numbers come from `--tail` snapshots (§6.1) — `--since` on strategy-signal still
  silently returns zero lines and must never be read as a quiet session.
- The §4b 02:30 CONNECTED mechanism is labeled: the log sequence is `sourced`; the
  weekend-token-longevity explanation is `computed`; the exact 02:30 trigger is `assumed`.
- Shadow figures are fan-out counts (§3.24); deduped clusters and the 09:48 concentration are in
  §5.2. Veto counterfactuals are corroborated by shadow rows on the same legs, not hand-priced.
- Read-only run: SELECTs, log/tail reads, `docker inspect`, in-container health GETs. No restarts,
  deploys, writes, config changes, republishes. Docs-only PR: this file + rollup rows.
