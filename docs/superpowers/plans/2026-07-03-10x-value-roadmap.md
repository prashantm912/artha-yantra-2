# 10x-value roadmap (2026-07-03)

**Status:** ACTIVE — strategic forward plan, complements (does not replace) the
[remaining-items ledger](2026-07-02-remaining-items.md). The ledger tracks *finishing* the
platform; this document answers a different question: **what would make the platform 10x more
valuable?**

**P1 SHIPPED 2026-07-03 (same day):** F4 DataHealthCanary ([#484](https://github.com/prashantm912/artha-yantra-2/pull/484)
`f6ced3a1`) · F1 champion/challenger shadow variants ([#485](https://github.com/prashantm912/artha-yantra-2/pull/485)
`ec7cd9bb`, first experiments `vol-off` / `vol-12k5` / `composite-070` live) · F3.1 live constituent
breadth ([#486](https://github.com/prashantm912/artha-yantra-2/pull/486) `05678806`). All three
deployed + endpoint-verified same evening; live acceptance criteria (checkboxes below) accrue from
the 2026-07-06 session onward. Build lesson: the Map-return ratchet caught BOTH new endpoints —
typed records from the start next time.

**Same-evening follow-through (#488–#491):** F8 cost model shipped (see the F8 status block —
scope corrected: paper was already net; the gap was the shadow book). F2's ACCRUAL MECHANISM
shipped (#489: `docs/signal-analysis/rollup.md` + the 15:47 agent's append step — the proposals
pass self-triggers at ≥5 sessions, ~2026-07-09/10). F4 v2 shipped (#491 `DotHealthCanary`:
per-dot gate-input liveness + `/signal-rejections/dot-health`, required-dots ntfy — the F3
"canary asserts dots can score" acceptance line is now real). Plus the treadmill/provenance fix
(#490, findings ledger #8/#9). F10 Minervini is owner-DEFERRED until the roadmap phases finish.

**The honest framing:** the build is complete — data capture, 12 live-validated scalpers, a
strict confluence gate, rejection forensics, the shadow book, backtest + optimizer, the OI
suite, and a default-disabled semi-auto order path (`execution/RestOpenAlgoOrderGateway`,
owner "Take" only). None of that is worth 10x more with another page or another indicator.
The platform's value function is:

> value = (proven, tuned edge) × (capital safely deployed against it) × (uptime/trust of the machine)

Today the first factor is unproven (~0 signals fire; the gate blocks everything; day-1 shadow
evidence says the gate blocked 20/20 losers — good, but zero fired trades = zero realized
edge), the second is zero by design (paper only, owner WON'T-DO on auto-arming stands), and
the third is fragile (the 12:40 CandleBuilder stall ran undetected for 3 hours; capture dies
when the laptop lid closes). 10x comes from attacking those three factors, in that order.

**Guardrails carried forward (never violated by this roadmap):**
- Live-broker order *arming* stays a WON'T-DO (ledger §7) — semi-auto "Take" is the v1 safety
  boundary. This roadmap builds the *evidence bar and safety spine* so the owner can revisit
  that decision deliberately, not a backdoor around it.
- Long-only premium (no short/sell legs).
- Parity firewall: nothing here touches golden vectors or the deterministic replay path.

---

## Theme 1 — Close the learning loop (edge discovery)

The month of live-paper data (ledger §2, the biggest open track) is accruing at ~1 session/day.
Every feature here multiplies what that month teaches.

### F1. Champion/challenger shadow variants
**What:** generalize the shadow book (#479) from "trade the rejections" to "trade N parameter
variants virtually, side by side, on the same live ticks." A challenger = the champion YAML +
a config diff (e.g. relative volume floor `0.5×` rolling median instead of fixed 125k, or
`ivPairMinGap 0.015`). Each variant gets its own virtual book in `strategy.shadow_positions`
(new `variant` column), evaluated by the same live gate code path with overridden params —
never a separate reimplementation.
**Why 10x:** today a knob change needs a multi-session wait *per change*. Variants test 3–5
hypotheses per session concurrently on identical market data — the tuning month compresses ~5×,
and every proposal in the forensics docs gets a real-PnL label instead of a counterfactual guess.
**Build notes:** the gate already computes the full all-eval + StrikePicker pick for every bar
(that's how rejections carry `wouldBeLeg`); a variant re-scores the same evaluated rails with
different thresholds — cheap, no extra market-data calls. Cap: 5 variants, same 50-position cap
each, same 15:12 square-off.
**Acceptance criteria:**
- [ ] ≥2 challenger variants defined as config diffs in a `shadow_variants` table (or YAML block), visible on the signal-rejections page with per-variant open/closed counts.
- [ ] Same session, same underlying: champion and challengers produce *different* entry sets, each with independently computed exits and PnL — verified against 1m candles for one session by hand.
- [ ] Per-variant session PnL table lands automatically in the post-market findings doc (the 15:47 agent picks it up via the README §6 SQL toolkit — one new query, method doc updated).
- [ ] Zero coupling: `paper_positions`, notifications, and the live gate's *firing* decision are byte-identical with variants on vs off (assert via one session's `signals` + `signal_rejections` row counts).
- [ ] Kill switch: `ARTHA_SCALPER_SHADOW_VARIANTS_ENABLED` default OFF in code, ON in compose (same pattern as the shadow book).

### F2. Automated multi-session rollup + tune-proposal generator
**What:** the /session-analysis skill's rollup mode, made concrete: after every session the
post-market agent appends to a running `docs/signal-analysis/rollup.md` — per-rail block rates,
per-dot support rates, shadow PnL by blocking rail, variant league table (from F1) — and once
≥5 sessions accrue, emits *ranked tune proposals as literal config diffs* with the evidence
row that justifies each (e.g. "volume_floor: 125000 → `0.5×median(20)`; shadow says this rail
vetoed −513 pts on 07-03 but variant B shows it also vetoed +9 pts on trend days; net +X").
**Why 10x:** turns a pile of dated findings files into a decision queue the owner can approve
in five minutes. The owner's time is the scarcest resource in a solo shop.
**Acceptance criteria:**
- [ ] `rollup.md` exists and is appended by the 15:47 agent every session without manual prompting (verify 3 consecutive sessions).
- [ ] After session 5: a "Proposals" section with ≥3 entries, each = exact YAML/env diff + evidence citation (session dates + shadow/variant PnL numbers) + risk note.
- [ ] Every proposal is traceable: a reader can reproduce its evidence from the cited findings files alone.
- [ ] No auto-apply: proposals are docs only; applying one remains an owner-approved PR.

### F3. Dead-dot revival — raise the composite ceiling
**What:** the 2026-07-02 forensics proved the composite is capped at 0.765 because 5 of 19.6
weight-units can never score live: `volume` (floor unpassable), `iv_rank` (null live),
`iv_pair` (0.10-fraction gap unreachable), `breadth` (EOD bhavcopy → 0/0 intraday), `oi_spurt`
(price%-floor 50 vs observed max 22.2). Fix each *for real*, not by loosening blindly:
1. **Live breadth producer** — the 09:09:30 pre-open scan (172/38) and the live
   index-contribution endpoint (42/8) already compute advance/decline; publish one of them into
   the dot's input instead of the EOD table.
2. **`iv_rank` live semantics** — capture-era IV history now spans ~3 weeks; compute rank over
   the captured window with an explicit `min_history_days` guard, NEUTRAL below it.
3. **`iv_pair` gap unit fix** — the 0.10 threshold is a fraction where the data is in
   IV points; decide the unit, fix threshold to 0.01–0.02 equivalent (forensics proposal).
4. **`oi_spurt` price floor** — 50 → 5–10 (observed p95).
5. **Volume floor** — replace fixed 125k with `k × rolling median(20 bars)` (F1 variant proves
   `k` first).
**Why 10x:** a gate that *can't* reach strong confluence never fires; every fired trade the
platform will ever take flows through this ceiling.
**Acceptance criteria:**
- [ ] Post-fix live session: max observed composite ≥ 0.85 AND every one of the 5 dots scores non-neutral at least once (query `signal_rejections.score_breakdown`).
- [ ] Each threshold change lands only after its F1 variant or rollup proposal supports it (no blind loosening; the 07-03 lesson — that day the strict gate was *right*).
- [ ] Breadth dot: intraday value within ±5% of the live index-contribution page for 3 spot checks.
- [ ] Golden + parity suites byte-identical (all changes are live-gate/config side, behind the parity firewall).
- [ ] DataHealthCanary (F4) gains a per-dot "capable of scoring" assertion so a dot going dead again alerts instead of silently re-capping the composite.

---

## Theme 2 — Trust the machine (operational autonomy)

### F4. DataHealthCanary — the in-code live watcher
**What:** promote the method doc's §7.8 end-state from "agent runs SQL at 09:42" to a component
inside strategy-signal/market-data: per-token 1m-bar liveness (bars advancing while
`ticks:last` seq advances — exactly the 12:40 stall signature), per-dot input freshness,
capture-cron heartbeats (09:16/09:18 boundaries), publisher staleness. Alerts via the existing
ntfy/telegram notifier within 60s, with a per-check cooldown so one stall ≠ 400 pings.
**Why 10x:** the 12:40 CandleBuilder stall starved the engine for 3 hours and was found
post-market by an agent. The same class of fault during a *fired* trade is a money bug. The
09:42 agent stays as the deep-dive layer; the canary is the tripwire.
**Acceptance criteria:**
- [ ] Fault drill: manually suppress one token's bar publishing (config or test hook) on the mock stack → ntfy alert within 60s naming token + check.
- [ ] The 2026-07-03 stall replayed (rogue future-stamped tick before the #482 guard) is detected by the bars-vs-ticks liveness check in ≤2 minutes.
- [ ] 5 consecutive live sessions with zero false-positive alerts (cooldown + market-hours gating + holiday calendar respected).
- [ ] Canary state exposed at a `/api/v1/*/health/data` endpoint + a small status strip on the dashboard (green/amber/red per check).
- [ ] The 09:42 live-health agent reads the canary endpoint first and only deep-dives on amber/red — agent runtime drops accordingly.

### F5. Always-on host — unattended market days
**Status 2026-07-03 — DECISION BRIEF ready:** measured footprint + 3-option comparison + migration
runbook in [`2026-07-03-always-on-host-brief.md`](2026-07-03-always-on-host-brief.md); recommendation
= Windows mini-PC on LAN (~Rs 35-50k one-time, zero architecture change, Claude agents move onto the
box). Awaiting the owner's option pick.
**What (original):** move the stack (or at minimum market-data capture + strategy-signal + DB) off the
laptop to an always-on box — mini-PC on the LAN or a small VPS. Loopback-only gateway stance
is preserved (LAN/VPN access only, no public exposure). Includes: compose bring-up on boot,
`ay backup` to off-box storage nightly, and the scheduled agents' host story (they run in the
Claude desktop app — either the box runs it, or agents stay on the laptop and read the box).
**Why 10x:** capture history has holes wherever the laptop slept; the scalping-goal memory has
said "needs always-on host" since day one of forward capture. Every other feature's data
quality inherits this.
**Acceptance criteria:**
- [ ] 5 consecutive market days fully unattended: zero capture gaps (options-chain snapshot cadence unbroken 09:15–15:30), zero missed cron boundaries.
- [ ] Laptop fully off during one of those days; owner can still open the UI from the laptop when it wakes (LAN/VPN).
- [ ] Nightly backup lands off-box; one restore drill from it passes (`ay restore` round-trip).
- [ ] Documented runbook: boot, upgrade (build on laptop → deploy to box), rollback.
- [ ] Kite daily login flow works from the box (redirect handling documented; token lands in the box's stack).

### F6. Two-way Telegram command surface
**Status 2026-07-03 — BUILT + deployed dormant (#493):** `telegram` module in strategy-signal —
/status /pnl /positions + confirm-guarded /pause /resume /flatten (existing kill-switch +
mark-to-close levers only), triple fail-closed (flag + shared bot token + chat-id allowlist),
silent to unknown chats, pre-boot updates drained, audits to `bot_commands` (V019). **Owner
go-live (~5 min, any time):** BotFather token → `.env` `ARTHA_NOTIFIER_TELEGRAM_BOT_TOKEN`
(escape `$` as `$$`), chat id → `ARTHA_NOTIFIER_TELEGRAM_CHAT_ID`,
`ARTHA_TELEGRAM_COMMANDS_ENABLED=true`, `ay up`, send /status. Acceptance drills (flatten on
mock, second-account silence) run after go-live.
**What (original):** upgrade the existing one-way notifier to a command bot: `/status` (feed, canary,
open positions), `/pnl` (paper + shadow today), `/flatten` (paper square-off now),
`/pause` `/resume` (per-strategy paper entry gate), `/last` (most recent signals/rejections).
Auth = chat-id allowlist (owner only) + a confirm step on mutating commands.
**Why 10x:** the owner is AFK most of the market day by design. Today the only lever away
from the desk is nothing. This is the difference between "autonomous with a leash" and
"unattended and hoping."
**Acceptance criteria:**
- [ ] All read commands answer in <5s with live data; verified against the UI during a session.
- [ ] `/flatten` closes all open paper positions (same code path as 15:12 square-off) after an explicit "yes" confirm; drill executed once on mock, once live-paper.
- [ ] Unknown chat-id gets silence (not an error) — verified with a second account.
- [ ] Mutating commands audit-logged (who/what/when) in the DB.
- [ ] Bot down ≠ trading down: strategy-signal runs unaffected with the bot container stopped.

---

## Theme 3 — Graduation to real money (the value cliff)

Order arming stays owner-decided. These features build the evidence bar and the safety spine so
that decision, when it comes, is boring.

### F7. Graduation framework — promotion criteria + progress dashboard
**What:** codify per-strategy promotion stages: **paper → Take-eligible (micro, 1 lot) →
scaled**, each with written, measurable criteria (e.g. ≥20 fired paper trades, profit factor
≥1.3 cost-adjusted, max drawdown ≤X, slippage-adjusted expectancy > 0, zero canary reds during
its trades). A dashboard page shows every published strategy's progress against the bar.
Promotion itself = owner action; the framework only measures.
**Acceptance criteria:**
- [ ] Criteria document reviewed + numbers set by owner (this is the E9-band conversation's natural home).
- [ ] Dashboard: per-strategy stage, trades-so-far vs required, each criterion green/red, computed nightly from `paper_positions` + shadow + execution-quality data (F8).
- [ ] A strategy meeting the bar triggers a one-time ntfy "graduation candidate" note — never an order.

### F8. Execution-quality analytics — does the edge survive costs?
**Status 2026-07-03 — v1 SHIPPED, scope corrected on inspection:** the paper ledger was ALREADY
fully cost-adjusted (Phase 43: `realized_pnl` = net cash through the shared engine `FillSimulator`
with the pinned statutory schedule + per-order slippage; `paper_orders` even records bid/ask when
known) — the roadmap's premise was wrong for paper. The real gap was the SHADOW book (raw premium
points only). v1 adds `shadow_positions.cost`/`pnl_net` (V018, 1-lot INR through the SAME engine
fill model, zero added slippage — the recorded LTPs are the fills), the league surfaces net
first, and the latency SQL (README §6). Spread capture on shadow entries stays open (needs
bid/ask in the chain source). Zerodha-calculator cross-check fixture = the ShadowCostModelTest
numbers (buy 100 → sell 140, lot 75 ⇒ cost ₹65.40) — owner verifies once against the calculator.
**What (original):** every paper/shadow fill today books at LTP with zero cost model. Add: spread capture
at entry/exit (bid/ask from the depth already in Kite quote mode), a slippage estimate
(cross-the-spread + 1 tick), brokerage/STT/stamp model for NFO/BFO options, and
signal-timestamp→entry-LTP latency. Surface cost-adjusted PnL everywhere raw PnL shows.
**Why 10x:** scalps live or die on costs; a 0.05% edge with 0.08% round-trip cost is a losing
strategy the current numbers call a winner. Graduation criteria (F7) are meaningless without it.
**Acceptance criteria:**
- [ ] Every new paper + shadow position row carries entry/exit spread, modeled cost, and cost-adjusted PnL columns (additive migration, old rows null).
- [ ] Cost model validated once against a hand-computed Zerodha brokerage-calculator number for a sample NIFTY option round trip (within ₹1).
- [ ] Findings docs + rollup switch their PnL tables to cost-adjusted (raw kept alongside).
- [ ] One session's report shows per-trade latency (signal `generated_at` → entry snapshot ts) p50/p95; p95 > 5s raises a canary amber.

### F9. Risk & capital layer — SPAN sizing via the Upstox margin API
**What:** wire SPAN+exposure margin into paper sizing: lots = f(capital, SPAN+exposure margin,
per-trade risk %); portfolio heat cap (max concurrent margin at risk); a daily loss governor
that pauses new paper entries past a threshold (flatten stays manual/F6). All advisory-annotated
first, enforcing behind a flag.
**SPAN source (decided 2026-07-04, VERIFIED LIVE):** route through Upstox `POST /v2/charges/margin`
— server-side SPAN on the login-free analytics token we already hold (a 1-lot short returned real
`span_margin`/`exposure_margin`/`total_margin`/`required_margin`/`final_margin`), NO `.spn` file
and NO broker-number hunt. Capital = `GET /v2/user/get-funds-and-margin` (live; 423 during the
00:00–05:30 IST funds-maintenance window). The marginism appliance (#126) demotes to the
offline/backtest fallback (`SpanEngine` gets a second, Upstox-backed impl for live). **Gotcha:**
`quantity` must be a multiple of the contract lot size (UDAPI1104 otherwise — read it from the
Upstox instrument master); ≤20 legs/basket.
**Status 2026-07-04 — SPAN SOURCE SHIPPED ([#510](https://github.com/prashantm912/artha-yantra-2/pull/510)):**
the margin capability (the `.spn`-blocked piece) is BUILT + deployed: `UpstoxMarginClient` +
`POST /api/v1/market/margin` (typed record, fail-soft, gated on the analytics token) compute
broker-real SPAN server-side. Remaining = the F9 *application* layer (below), which needs owner
risk numbers + the advisory week.
**Acceptance criteria:**
- [x] **SPAN source: broker-real margins available, no `.spn` file** — `POST /api/v1/market/margin`
  resolves the leg → Upstox key → `/v2/charges/margin`; live-verified. (#510)
- [ ] Paper entries carry `advised_lots` + margin snapshot from the margin endpoint — needs the owner's
  per-trade-risk %; the returned SPAN is broker-real by construction (Upstox IS a broker), so no
  separate parity step, just `advised_lots × margin ≤ available funds` (`GET /v2/user/get-funds-and-margin`).
- [ ] Governor drill on mock: inject losses past the daily cap → new entries blocked, ntfy fired,
  existing positions untouched — needs the owner's daily-loss + heat-cap numbers.
- [ ] Flag default OFF; ON in compose only after one clean advisory-mode week.

---

## Theme 4 — Widen the funnel (new alpha, already sanctioned)

### F10. Minervini Track-1 screener (ledger §1 — the one net-new build)
**What:** as ledgered: daily 8-gate Trend Template + RS-rank preset in `ScreenerService`,
over the seeded 200-day equity history. Positional/equity alpha, decorrelated from the
intraday scalp book.
**Acceptance criteria:**
- [ ] All 8 Trend Template gates implemented per master-plan §13, unit-tested against hand-computed fixtures for 3 symbols (pass/fail/boundary).
- [ ] RS rank = percentile vs full NSE universe on the 200-day window; top-decile list stable across a restart (deterministic).
- [ ] Screener page preset "Minervini TT" returns in <3s; refreshes automatically post-bhavcopy ingest.
- [ ] Backfill horizon guard: symbols with <200 sessions of history excluded with a visible count, not silently passed.

---

## Sequencing & effort

| Phase | Features | Effort (rough) | Why this order |
|---|---|---|---|
| **P1 — now, during the data month** | F4 canary · F1 variants · F3.1 breadth producer | ~4–6 sessions of build | Everything downstream consumes this month's data; canary protects it, variants multiply it. |
| **P2 — as rollup data lands** | F2 rollup generator · F3.2–.5 dot fixes (evidence-gated) · F8 cost model | ~4–5 sessions | Proposals need ≥5 sessions anyway; cost model must exist before any keep/cut verdict. |
| **P3 — autonomy** | F6 telegram bot · F5 always-on host | ~3–4 sessions + hardware decision | Makes the unattended operation real; F5 has an owner purchase/choice gate. |
| **P4 — graduation stack** | F7 framework · F9 risk layer | ~3–4 sessions | Only meaningful once F8 numbers exist and the tuning month concludes. |
| **P5 — parallel/filler** | F10 Minervini | ~2–3 sessions | Independent of everything; can slot into any quiet phase. |

**Owner decision points:** F5 hardware (mini-PC vs VPS vs stay-laptop), F7 criteria numbers
(the E9-band conversation), F9 prerequisite (`.spn` file, ledger §2), and — after the whole
stack proves itself — whether to revisit the order-arming WON'T-DO. Everything else is
buildable autonomously under the existing per-PR discipline.

**What this roadmap deliberately does NOT contain:** more UI pages, more indicators, more
scalper variants, broker cutovers, or anything on the ledger §7 WON'T-DO list. The thesis is
narrow: prove the edge faster (T1), keep the machine trustworthy while unattended (T2), make
the paper→real decision evidence-based and safe (T3), and add the one sanctioned new alpha
surface (T4).
