# Session findings — 2026-08-28 (data date)

Analysis date: 2026-08-28 EOD (scheduled post-market agent, run ~15:55–16:40 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **1,105** (bounds
`2026-08-28T09:15:00+05:30`…`15:40`; rows 09:19–15:19), signals fired **9 evals = 7 emitted + 2
risk-suppressed**, paper trades **4 entries / 1 close (+₹3,604.56 realized) — and 2 positions
STUCK OPEN past square-off (§6.1, the session's headline)**, shadow closes **52** across 4
variants (champion 40).

Session character: **Friday, post-BSE-monthly-expiry (fresh SENSEX Sep-03 front weekly)** ·
chop day (official o 24,122.60 → c 24,175.65 = +0.22% on 0.46% range, eff 0.476 = mixed;
continuous freeze 24,142.45 = +0.08% on a 0.46% continuous range [24,077.00–24,188.30],
continuous eff **0.178 = CHOP** — EIGHTH straddle, doctrine keeps continuous; CAS delta
**+33.20**, positive after 2 negatives) · signal contract **`NFO:NIFTY26SEPFUT`** (1,121 log
mentions) · overnight HOST downtime again (3rd consecutive): all containers started
**08:39:35 IST**, `RestartCount=0` · **FIRST DAY OF ARMED KITE AUTO-LOGIN — and its first live
boot-window attempt FAILED (§4b)**.

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,105 — **38 of 38 scalpers, FULL coverage** (2nd full-coverage session after 08-25; `premium_skew` n=28, `iv_*` n=123–861) |
| eval outcomes | chart-gate-failed 2,154 · confluence-blocked 1,105 · composite-below-threshold 262 · **fired 9** · discipline-paused 0 |
| fired reconciliation (§3.36) | **9 fired = 7 emitted + 2 suppressed** — `daily_profit_target` tripped **12:25:20 IST** (`risk_audit` 91: `day P&L 3604.5600 breached limit 2061.06015` — limit = 1.5% × CURRENT equity ₹137,404, the NEW-10 equity-base mechanic now measured on the PROFIT side). Suppressed: both golden-crossover PE re-fires at 12:25 |
| coverage | **25 of 25** 15-min buckets 09:15–15:15 populated — no interior holes; `subscriber_health_events` 0 rows |
| boot health | boot 08:39:35 IST (post host-downtime), 0/38-unresolved transient → **38/0/0 at 08:46:49 (~7 min — LONGER than the in-band ~100 s of 08-25/08-27; the §3.38 boot variant stretched by the boot-window kite-rest breaker being OPEN 08:39:58–08:46:12, §4b)** |
| paper (funded) | 4 entries (3 positions; pos 98 averaged 65→130 by the 11:36 convergence pair), 1 close: pos 98 `TIME_STOP` **+₹3,604.56** — the book's largest realized win to date. **Positions 99/100 UNCLOSABLE — see §6.1** |

## 2 Rail findings

- **volume-floor first-block 617/1,105 (55.8%)** — banded and honest; `relative-volume-floor`
  armed on 38/38 (drift script clean on enabled set).
- **`strike-pick` post-BSE-expiry Friday: 65 all-fails, 17 SENSEX-rooted slugs, 0 NIFTY** —
  neither saturated (550/374/350) nor clean (14 on 08-14). Series is now 3-of-5 saturated /
  2-of-5 clean-ish. Consistent with the §3.27 chain-pricing read: the fresh Sep-03 SENSEX weekly
  priced its delta-band strikes mostly INSIDE the static 300–800 band (the funded picks filled
  at ₹779.55/₹773.60 — just under the 800 top), leaving only a morning tail outside it.
- First-block tail: time-window 198 · rsi-band 74 · time-of-day-preference 42 ·
  divergence-vol-gate 26 · volume-pump 24 · two-candle 24 · pct-price-move 24 ·
  oi-cross-required 22 · confluence-composite 14 · directional-change-gate 10 · hero-zero 10 ·
  supertrend-15m 8 · morning-opening-formation 4 · option-side-constraint 4 · max-oi-sr-gate 2 ·
  psar-durability 2 (17 distinct rails).
- **confluence-composite all-fails split (§3.39): 434 `60m bias opposes the side`
  (composite 0.1961–0.8511) + 186 score-shortfall aggregates.** Sole-blocker veto set: §5.3.

## 3 Composite + dots

- **OI bloc fully LIVE** — quadrants NEUTRAL **0/861** (SHORT_BUILDUP 392 · LONG_BUILDUP 319 ·
  SHORT_COVERING 106 · LONG_UNWINDING 44), spurt NULL 0, basis LIVE 861/861. futures_oi capture
  **372 of ~375 minutes** — yesterday's ~20-missing-minute watch item CLEARS.
- **Composite passes 189 of 861 scored (22.0%) — 70 CE / 119 PE** (two-sided chop tape, vs
  08-27's 20/302 down-day skew); max 0.8511.
- Live-dot support (complete session, n=861 unless noted): `iv_abs_band` 0% (n=123, **7th
  day** — atmIv stamp 0.097687, below the 10–12 band) · `iv_rank` 0% (withheld, standing) ·
  `iv_pair` 0% (**29th** — T3, owner) · `breadth` **2.7%** (see T30 below) · oi_spurt 8.5% ·
  volume 28.3% · trending_cross 38.3% · vwap 39.1% · rsi 40.2% · iv_slope 44.7% (n=123) ·
  sentiment_slope 45.8% · basis 50.4% · futures_oi 50.4% · underlying_oi 52.4% · sentiment
  56.9% · vix 57.8% · psar 63.6% · premium_skew 64.3% (n=28) · vwma 77.0% · drastic_oi 89.1% ·
  supertrend 98.4%.
- **§3.28 breadth (T30) — first real threshold CROSSINGS since 08-24:** PE declines ran 24–34,
  crossing the `>32` rule intra-session for 23 supports (2.7%); CE dead (advances 23–30). Not a
  boundary-pin day (no extremum exactly at 32); still the step-function/side-aware shape.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,105/1,105 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,105/1,105 | by design (un-armed) |
| `fiiLongPct` | live on all 861 contextful rows | healthy |
| `atmIv` | 1 distinct (0.097687) | frozen daily stamp — correct (G12/T28, 25th) |
| vix / ceIvAvg6 / skew / basis | 15 / 46 / 91 / 87 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 9th consecutive |
| §3.17 canary | **2 WARNs + 1 straddle** — ±715 (11 NIFTY lots) on buckets 11:18/11:21, WARNs 11:22:18/11:28:18 IST, BOTH UNPAIRED: the day's-first-non-benign-event lot-cache-miss class (08-25 §6.1 mechanism; boot 08:39, first non-benign event 11:22; trailing half released at its documented deadline). One straddle correctly suppressed at 11:40 once warm | benign — explained |
| signal-future capture | **375/375 min** aligned 1m on `NIFTY26SEPFUT` (KITE 359 + TICK_AGG 16, 0 BACKFILL) | ✓ |
| futures_oi capture | 25,668 snaps / **372 of ~375 min** | ✓ — 08-27 watch item cleared |
| morning ingest | TWO catch-up passes, all SUCCESS: 02:31 IST (post-#1515 deploy recreate) and 08:39:50 (host-downtime boot); INSTRUMENT_SYNC 09:05; OPTIONS_SNAPSHOT_CAPTURE 09:18 | ✓ |

## 4b BOOT WINDOW (§3.41 — first day of ARMED auto-login)

**Not clean — the armed auto-login's first live boot attempt FAILED and a manual login rescued
the morning.** Timeline (all IST):

- **08:39:35** — containers boot (host powered off overnight, 3rd consecutive day; boot is
  AFTER the 08:05 cron slot, so the `catchUpOnBoot` path is the operative one, as designed).
- **08:39:46** — `kite session restored from store (encrypted_at=2026-08-27T03:10:58Z)` — a
  **~29½-hour-old DEAD token resumed**, the exact #1520 mechanism.
- **08:39:50–58** — every consumer 403s on the dead token; **`kite-rest` breaker CLOSED→OPEN at
  08:39:58** — and per #1512 the OPEN line now names the cause: 10× `Forbidden: 403 …
  TokenException "Incorrect api_key or access_token"`. **First live proof of the #1512
  instrumentation doing its job.**
- **08:39:51** — auto-login boot catch-up armed: "inside the window — will attempt in 20s if
  the session is still not connected".
- **08:40:12** — **`kite auto-login FAILED, terminal for today: refused at AUTHORIZE:
  UNEXPECTED_RESPONSE (redirect carried no request_token)`** — the IDENTICAL failure to the
  first real run on 08-27 21:25, **with #1515 deployed**: the image was built 02:31 IST (2 min
  after the #1515 merge) and the deployed jar fingerprints `https://kite.zerodha.com` +
  `/connect/login` with no `kite.trade` — the login-host fix is aboard and did NOT resolve it.
  **The authorize-step root cause is therefore NOT the origin** (CLAUDE.md's open alternatives —
  an intermediate 302 carrying no token, or cookie scope — remain, now with the origin
  hypothesis eliminated by measurement).
- **~08:46–08:47** — owner logged in MANUALLY; breaker OPEN→HALF_OPEN→CLOSED 08:46:12–13; new
  token stored `encrypted_at 08:47:25 IST`; engine reload healed 0/38→38/0/0 at 08:46:49.
- **Capture minutes lost that overlap the window: 0 in-session** (session valid ~28 min before
  the 09:15 open; futures_oi 372/375, options capture from 09:18). The 08:40–08:47 gap-fetch
  WARNs ("no live Kite session for historical fetch") are pre-open warm-up degradation only.
- The 08:15 watchdog cron slot predates boot (box off) and its boot catch-up path is the same
  window as the login attempt — no separate page observed; the terminal-day gate correctly
  prevented retries after the AUTHORIZE verdict.

**Verdict: the boot-window machinery (#1510/#1511/#1512/#1520 diagnostics + terminal-day gate +
fail-safe manual path) all behaved as designed; the AUTHORIZE step itself remains broken with
the origin fix falsified.** Proposal in §7 (NEW-11).

## 5 Funded book + shadow outcomes + counterfactuals

### 5.1 Funded fires

| pos | signals | leg | entry | exit | reason | net |
|---|---|---|---|---|---|---|
| 98 | #267 (connect-the-dots-nifty-pe, 11:30 bar) + #269 averaged (golden-crossover-nifty-pe, 11:36) | NIFTY2690124350PE 65→130 | 202.03 avg | 12:01:11 | TIME_STOP | **+₹3,604.56** |
| 99 | #268 (golden-crossover-sensex-niftyoi-pe, 11:36) | SENSEX2690377900PE ×20 | 779.55 (11:37:21) | — | **STUCK OPEN (§6.1)** | unbooked ≈ −₹3,803 |
| 100 | #273 (connect-the-dots-sensex-niftyoi-pe, 11:57) | SENSEX2690377800PE ×20 | 773.60 (11:58:12) | — | **STUCK OPEN (§6.1)** | unbooked ≈ −₹4,948 |

- Signals #270–272 (connect-the-dots-sensex re-fires 11:36–11:51 on the same leg) were refused
  by the `sub_account_allocation` governor — "projected 15,591 would cross sub-account 2
  allocation 30,000" — sub-account 2's allocation being OCCUPIED by unclosable pos 99. Each was
  correctly released `TAKEN->EXPIRED`.
- **§3.34 heat-gate:** grep 0; `margin_snapshot` 0.00 on all entries (long-option zero-SPAN
  class, N23-A standing).
- **§3.36 risk gate:** `daily_profit_target` tripped 12:25:20 on dayPnl +₹3,604.56 vs limit
  ₹2,061.06 (1.5% × current equity ₹137,404) — **2nd lifetime profit trip, and it tripped on a
  dayPnl BLIND to positions 99/100**: their MTM is uncomputable by the book (no tick), so
  unrealized contributed ~0 while the chain marked them ≈ −₹8,751 at the close. **True day P&L
  ≈ −₹5,147 at chain marks; the book state says +₹3,604 and "in profit, entries paused".**
- Emit latency on real emissions: entries 12.7–21.8 s, exits 10.5–18.3 s (consistent with
  08-27's 17.7–21.3 s and G8's 16.7–17.6 s).
- §3.30 freeze telemetry: sub 1 profit-locked after the 12:01 close (banked +₹3,604 > 1% of
  allocation); subs 2/3 not frozen but **allocation-dead** behind the stuck positions; subs 4/5
  never reached. Risk-gate pause from 12:25 made the distinction moot for the afternoon.

### 5.2 Shadow book

**Champion: 40 closes, 4 net wins, −2,617.20 pts, −₹83,062.45 — the book's WORST net day on
record** (prior worst 07-30 −₹58,233.05), on a chop tape that stopped out both directions: 14
deduped `(bar, leg, entry)` clusters on ~9 bar times, EVERY morning cluster negative — worst
10:15 `SENSEX2690377000CE` −₹24,109.94 (8 slugs), 11:30 `SENSEX2690378000PE` −₹17,956.05,
09:45 `NIFTY2690124050CE` −₹15,089.30, 11:30 `NIFTY2690124350PE` −₹12,865.02. Only green:
the tiny 14:51 CE pair (+₹2,159). All-time champion **−₹300,477.39** (745 closes, 263 net
wins) — the −₹300k line crossed. Shadow entry latency p50 80 s / p95 86 s (n=52, structural
class, unchanged).

**The funded-vs-shadow contrast is the day's strategy lesson:** the funded book (risk-capped,
5-sub governors, profit-target) finished +₹3,604 realized on the same tape where the uncapped
shadow book lost ₹83k — the capital-governor stack, not signal quality, made the day.

**Challenger-only class: 4 observations, 0 wins / 4 losses, −₹11,272.28** (composite-055 3 rows
−₹7,472.11; vol-off 1 row −₹3,800.17). **Loosening ledger moves 30/25/5 → 34 measured / 29
losses / 5 wins.** All-time: composite-055 **−₹23,433.78** · vol-12k5 −₹46,846.39 · vol-off
−₹71,008.44.

**Per-rail counterfactual P&L (owner directive 08-20), all-time champion NET:** volume-floor
401 / **−₹139,311.40** · rsi-band 94 / −₹53,690.14 · two-candle 35 / −₹20,177.67 ·
divergence-vol-gate 34 / −₹17,734.48 · volume-pump 34 / −₹14,488.61 · **`confluence-composite`
18 / +₹274.80 — its +₹8,085 cushion COLLAPSED today (day contribution ≈ −₹7,810)**; only
supertrend-15m (+₹390.05) joins it above zero. **Root split flipped AGAIN (4th flip in 5
measured days): SENSEX back NEGATIVE −₹121.04/trade (330) vs NIFTY −₹627.79 (415)** —
confirmed not stable enough to act on.

### 5.3 §4.2 counterfactuals — the 60m-bias-vetoed set (day 3, NEW-9)

Sole-blocker veto set: 5 rows → **4 deduped legs, all CE, 09:45–10:18** — and every one has a
DIRECT champion-shadow corroboration (the same rejections composite-passed and opened shadow
positions):

| bar | leg | entry | champion cluster outcome |
|---|---|---|---|
| 09:45 | NIFTY2690124050CE | 211.20 | cluster −₹15,089.30 (6 rows, stops + square-off) — LOSS |
| 10:15 | NIFTY2690124100CE | 195.20 | cluster −₹6,940.06 — LOSS |
| 10:15 | SENSEX2690377000CE | 765.00 | cluster −₹24,109.94 — LOSS |
| 10:18 | NIFTY24100CE / SENSEX77000CE | 195.80 / 782.45 | same legs 3 min later — LOSS |

**Veto ledger after 3 days: day 1 refused 8/8 losers; day 2 model-split (engine-loss /
30-min-hold-win); day 3 refused 4/4 CE losers on a chop day, engine-corroborated in the
champion book itself.** Running: the veto has refused losers under engine exits on all 3 days
(12/12); only the day-2 pure-hold model dissents. Keep accumulating (n=3 sessions).

## 6 New data points / anomalies

### 6.1 UNCLOSABLE PAPER POSITIONS — a funded leg outside the tick-subscription band fills but can never settle

**The composition defect, first live occurrence.** Positions 99/100 opened on
`BFO:SENSEX2690377900PE` / `...77800PE` and CANNOT be closed: every exit pass — TIME_STOP
settles 12:13:18/12:28:10, `signal-exit` closes, and the 15:44:58 `INTRADAY_MTM` square-off —
refused with `settlement reference … has never ticked — left OPEN, NOT settled at a fabricated
price`. **1,973** `paper bracket starved` WARN lines accrued (one per position per 15 s sweep).

Mechanism, each half individually correct and documented:

1. **Entry fills without a tick.** `PaperService` (≈`:813-845`): explicit price wins; else
   fresh tick (>15 s stale ⇒ 422 DATA_STALE); else — documented — "with no tick at all a
   signal take still fills at its own entry price" (`refSource=SIGNAL_ENTRY`, the gate-captured
   chain premium).
2. **Exit refuses without a tick.** The #694 doctrine's never-seen branch: settles use the last
   REAL tick at any age and refuse only when NO tick was ever seen — never fabricate.
3. **The tick WS subscribes a STATIC ATM band.** Today's BFO band: 11 strikes 76,700–77,700
   (all with 375 bars from 09:15 — fixed all session, centred on the 09:15 spot 77,116). The
   `StrikePicker`'s static premium band (SENSEX 300–800) picked **77,900/77,800 PE — ~800 pts
   ITM, outside the band**. The NIFTY pick (24,350, ~100 pts ITM, inside its band) settled
   normally (+₹3,604.56).

So: chain capture (REST, 2-min) priced the legs, the entry's no-tick fallback filled them, and
the exit's no-tick refusal stranded them. **Consequences measured today:** sub-accounts 2/3
allocation-dead for the session (governor refusals on signals 270–272); the risk gate's dayPnl
blind to ≈ −₹8,751 of chain-marked losses (profit-target tripped "in profit" on a truly
negative day, §5.1); no automatic resolution path before the Sep-03 expiry
(`PaperExpiryService` / the 08:52 past-expiry recon is the eventual backstop).

**Proposals (owner):** NEW-12 in §7. This run changed nothing (read-only); the positions
remain OPEN.

### 6.2 First armed auto-login morning — AUTHORIZE failure survives the #1515 origin fix

§4b in full. Net: the origin hypothesis for `UNEXPECTED_RESPONSE (redirect carried no
request_token)` is falsified by a fingerprinted deploy; the failing step needs its redirect
chain captured (NEW-11).

### 6.3 H31 day-context — 2nd consecutive ZERO-failure session

`insight trust read day-context FAILED` grep: **0** (trajectory 89% → 18% → 0% → 0%). Same
denominator caveat as 08-27 (successes do not log; ~28 expected sweep fires).

### 6.4 §3.29 audit — funded vocabulary delta: TIME_STOP 17 → 18

Fired vocabulary since 07-01: TRAILING_STOP 22 · STRUCTURAL_STOP 20 · **TIME_STOP 18 (+1, pos
98)** · STOP_LOSS 8 · MANUAL 2. Armed-path table unchanged (10 rows + tag `oi-confluence-exit`
8). Never-fired unchanged: `take_profit premium_pct` (36, zero funded TP closes in 2 months) ·
`signal_exit` (38) · `square_off` (2) · tag `oi-confluence-exit` (8). INDETERMINATE standing
pair: `trailing_stop atr_multiple` (2), `stop_loss atr_multiple` (2). ⚠️ Note: two MORE
TIME_STOP exits were EMITTED today (signals #275/#276) but their positions could not settle
(§6.1) — `close_reason` counts only settled exits, so the vocabulary under-counts the engine's
exit decisions on a stuck-position day.

### 6.5 NEW-8 trail-should-have-fired watch — 5th clean measurement

Boot catch-up swing pass ran 08:40; `pyramid_risk_cap` trips for MENONBE/BHEL (normal 6%
open-risk governor, `risk_audit` 89/90). No trail/stop breach anomalies.

### 6.6 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **12 REVIEW lines — the identical standing set of
  08-26/08-27** (7×[A] chip open/closed keyword pairs, 5×[B] pickup-vs-DONE keyword class).
  No edits made; ledger consistent modulo the standing set.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Unchanged; nothing
  republished by this run.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| **NEW-12 (08-28)** | **funded legs outside the tick-subscription band are fillable but unclosable** | **NEW — OWNER + ledger row needed.** Options, not mutually exclusive: (a) entry-side guard — refuse (or re-pick inward) a funded leg whose settlement reference has no tick history (the shadow book can keep trading them; only FUNDED fills need closability); (b) subscribe the picked leg's token on fill; (c) an operational settle path for the two stuck positions (manual close at chain LTP is an owner call — this run touched nothing). Positions 99/100 remain OPEN | §6.1 |
| **NEW-11 (08-28)** | auto-login AUTHORIZE failure — origin hypothesis falsified | **NEW — OWNER.** #1515 deployed (jar-fingerprinted) and the identical `UNEXPECTED_RESPONSE (redirect carried no request_token)` recurred at 08:40:12. Next diagnostic: capture the authorize redirect chain (status + Location host, token-free) on failure — the two open hypotheses (intermediate 302 / cookie scope) are indistinguishable from the current verdict string | §4b |
| NEW-9 (08-26) | 60m-bias veto (inside confluence-composite) | **OPEN — day 3: refused 4/4 CE losers (chop day), champion-corroborated; engine-model tally 12/12 losers refused across 3 days; only day-2's pure-hold model dissents** | §5.3 |
| NEW-10 (08-27) | risk-limit base = current equity | **OBSERVATION (owner) — now measured on BOTH sides:** loss limit 08-27 (₹4,014 = 3% × ₹133.8k), profit target today (₹2,061 = 1.5% × ₹137.4k) | §1 |
| watch | `strike-pick` chain-proximity | **WATCH** — post-BSE-expiry Friday came in at 65 fails (neither saturated nor clean); series 3-of-5 saturated. Next: Mon/Tue NSE-weekly cluster | §2 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | no mid-session deploy today (the 02:31 IST #1515 deploy was off-hours) |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — 2nd lifetime trip**; tripped blind to unmarkable positions (§5.1 — feeds NEW-12) | §1 |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | grep 0; coverage still N23-A |
| T30 | `breadth` dot `>32` | **OPEN — first crossings since 08-24** (PE declines 24–34 straddled the rule, 2.7%; CE dead) | §3 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 55.8%, banded; loosening ledger now **34/29/5** — today added 4 losses / 0 wins |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct = 0.097687 (25th); `iv_abs_band` 0% 7th day |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (29th session) |
| T23 | partial-bucket tolerance | **OPEN** | 2 WARNs + 1 straddle — ±715 first-event pair, benign-by-shape |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5: no challenger-only rows today |
| T7 | composite threshold | **REJECTED — carried** | composite-055 challenger-only: 0W/3L −₹7,472.11; all-time −₹23,433.78 |
| NEW-8 (08-24) | trail-should-have-fired watch | **STANDING — 5th clean measurement** | §6.5 |
| T8/T26 | latency | OPEN (data) — entries 12.7–21.8 s, exits 10.5–18.3 s; shadow p50 80 s / p95 86 s | §5.1 |
| T2 | `iv_rank` | carried, not open | NULL 1,105/1,105 |

## 8 Honesty caveats

- **This run executed ~15:55–16:40 IST** — before the 18:4x evening batch and 18:52/18:53
  swing settles (EXITS-only; 0/0 is the normal correct outcome, H27); tonight's ingest
  outcomes are tomorrow's verifications. No session tomorrow (Saturday) — the next post run is
  Monday 08-31.
- Regime stamped from the CONTINUOUS session (§3.33a): eff 0.178 = **chop**; official 0.476 =
  mixed — 8th straddle. **G11 chop count 7 → 8, again stop-favouring** (the day's one funded
  winner was a TIME_STOP close; decision already made 07-31: KEEP).
- The ≈ −₹8,751 unbooked MTM on positions 99/100 is a CHAIN-snapshot mark (2-min granularity,
  no spread/slippage) — the paper book itself cannot mark them, which is the finding.
- The champion book's −₹83,062.45 is a fan-out figure (40 closes → 14 deduped clusters on ~9
  bar times); the effective independent sample is ~9. It is the worst day on record under
  dedup too (every morning cluster negative).
- §5.3's day-3 corroboration uses the champion book's own engine-exit fills; no pure 30-min
  hold model was run today — the day-2 model divergence caveat stands.
- Read-only run: SELECTs, log greps, in-container reads, one jar fingerprint. No restarts,
  deploys, writes, config changes, republishes — and NO action on the stuck positions.
- Docs-only PR: this file + rollup rows + README §3.40.
