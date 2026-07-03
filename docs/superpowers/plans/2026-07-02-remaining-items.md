# Remaining items — the single forward ledger (2026-07-02)

**Status:** ACTIVE — the one place listing *everything still open* across the whole platform.
Supersedes [`archive/2026-06-30-remaining-build-inventory.md`](archive/2026-06-30-remaining-build-inventory.md)
(kept for provenance; its §4 false-flags, §5 descopes and §6 WON'T-DO lists are carried forward in §7 below
so nothing gets re-flagged). Cross-checked 2026-07-02 against `PHASE_GATES.md`, `docs/DEFERRED_BACKLOG.md`,
the two 2026-07-02 audits (both fix queues fully closed) and the open-PR/issue list (empty).

**Owner rule:** when an item here ships, mark it DONE in place (PR# + SHA) before starting the next one.

---

## 1. Net-new code

| id | item | authority | state |
|---|---|---|---|
| `phase5-minervini-trend-template` | **Phase-5 Minervini Track-1 screener** — daily 8-gate Trend Template + RS-rank preset in `ScreenerService` (master-plan §13). VCP/pivot/Cheat/Power-Play stay deferred (owner reads entries manually). | master-plan §13; `DEFERRED_BACKLOG.md` Phase-5 row | UNBLOCKED but **owner-DEFERRED (2026-07-03) until the 10x-roadmap phases finish**. |
| `10x-roadmap-p2-p5` | **The 10x-value roadmap's remaining phases** — F2 proposals pass (self-triggers at ≥5 rollup sessions), F3.2–.5 dot fixes (variant-evidence-gated), F6 telegram bot, F5 always-on host, F7 graduation framework, F9 risk layer. P1+F8+F4v2 all SHIPPED 2026-07-03 (#483–#491). | [`2026-07-03-10x-value-roadmap.md`](2026-07-03-10x-value-roadmap.md) | ACTIVE — each phase's gate (data / owner input) is listed in the roadmap; nothing buildable without a gate opening. |

## 2. Owner-gated — needs owner input/time, not code

| id | what's built | what's needed |
|---|---|---|
| `live-forward-paper-analysis` | Auto-paper ON (#367); every gate block persisted to `signal_rejections` (#404); analysis procedure = [`2026-06-30-live-signal-analysis-runbook.md`](2026-06-30-live-signal-analysis-runbook.md) | **~1 month of live-paper scalper trades**, then run the runbook: E9 target/trail band number + per-scalper keep/cut/tune via counterfactual replay on real captured premium. The biggest open track. |
| `span-real-spn-broker-parity` | `.spn` loader + parity harness, CI-green on synthetic fixture (#144) | a real `nsccl.<date>.s.spn` + one known broker margin number + owner sign-off |
| `telegram-scalp-alert-optin` | notifier path built (#152) | owner sets the bot token |
| `per-strategy-notifications` | ntfy verified end-to-end (direct + service path, 2026-07-02) | only `scalp-connect-the-dots-nifty` has notifications ON — owner toggles the other 11 published strategies per taste |
| `sensex-pe-publish` | 18 SENSEX-PE drafts seeded (#382) | owner publish decision (9 NIFTY-PE already live) |
| `value-verify-ratify` | data-foundation value-verify **PASSED** live-vs-live 2026-07-01 (captured OI == oipulse exact share) | owner ratifies the close; residual low nits in §5 |
| `soft-dot-arming` | FU2 dots + drasticFloor default built inert | arm only if live forward-paper data proves them (else stays a §7 WON'T) |

## 3. Verification only — next market session

Ran live 2026-07-03 (findings addendum A2 in `docs/signal-analysis/2026-07-03-session-findings.md`):

- [x] T2 aligned snapshot buckets row-for-row vs the oipulse barometer — **PASS** (labels identical;
  Call OI exact 3/3; put-row residuals = oipulse polling lag on BSE OI dissemination, our
  end-of-window values are the fresher ones).
- [x] Capture crons fire on the new boundaries: 09:16 futures / 09:18 options — **PASS**.
- [x] First pre-open equity scan lands at 09:09:30 (#470) + futures pre-open page renders live
  (#377) — **PASS**.
- [ ] NSE announcement field mapping on live data (#378) — not exercised 2026-07-03; check next
  session with fresh announcements.
- [ ] **sentimentPct formula reconcile (master-plan §18.6, found in the 2026-07-03 doc sweep):**
  `ActiveStrikeService.sentimentPct` is ΔOI-based (`100·(ΣpeΔOI−ΣceΔOI)/Σ(ceOi+peOi)`); oipulse's
  dashboard figure is LEVEL-based (`(ΣPut−ΣCall)/ΣPut·100`), and the Siva cheat-sheet thresholds
  were read off oipulse. One live-session compare of our number vs theirs; if they diverge
  materially, add a level-based method beside the existing one (never silently change it) and
  decide which the sentiment gate should read.
- [ ] Stock-chain warm on a SECOND symbol during market hours (#472 verified on RELIANCE
  off-hours) — not exercised 2026-07-03.
- [ ] Owner hard-reload (Ctrl+Shift+R) after each FE redeploy — standing owner action.

**New verifies for 2026-07-06** (P1/F8 live acceptance, roadmap `2026-07-03-10x-value-roadmap.md`):
canary tile green through the session + zero false alerts; NIFTY26JULFUT TICK_AGG bars past 12:40
(#482 fix) + watch for "dropping future-stamped tick" WARNs; variant books (vol-off / vol-12k5 /
composite-070) populate with net-₹ labels; breadth dot scores intraday (advances/declines non-zero
in rejections' context). The 09:42 + 15:47 agents cover all of it.

### 3b. Audit-register fix queue (reconciled 2026-07-03, code-verified)

Reconciliation DONE — full verdict table lives as the dated addendum at the bottom of
[`archive/2026-07-02-full-codebase-audit-findings-register.md`](archive/2026-07-02-full-codebase-audit-findings-register.md).
Net: 4 FIXED since the freeze · 4 accepted-risk (single-owner posture, closed) · **22 real
survivors** forming the standing quality fix queue. Work them in waves (S-effort live-path batch
first: resubscribe gap, emit transactionality, ticker-status truth, pipeline decoupling, eval
counter, interval strictness; then ops/FE S-items; the five M-items last — note #3
replay-fallback is PARITY-SENSITIVE and needs a golden re-verify). Mark rows fixed in the
addendum table as they land.

## 4. Scheduled maintenance

| when | item |
|---|---|
| **before ~2026-11-16** | **CD-2 yearly calendar CSV refresh** — add 2027 NSE/BSE holiday CSVs to `libs/market-calendar`. `CalendarHorizonCanaryTest` goes red ~45 days before year-end; unrefreshed, the monthly-expiry look-ahead cliff starts 2026-12-29 and OI capture silently halts 2027-01-01. |

## 5. Small optional nits (low value — batch when touching the area)

- Value-verify residuals: F3 heatmap UTC label edge case; F5 strike-series ΔOI basis; per-leg IV=greeks
  display class on derived history.
- FE revamp leftovers: options-chain Radix-Select, "Columns" rename, table density toggle.

## 6. Deferred-by-design — build only when a consumer appears

Provenance rows live in [`docs/DEFERRED_BACKLOG.md`](../../DEFERRED_BACKLOG.md) (cross-cutting + parked tables):

- `candles_1h` IST re-anchor (before any 1h chart/overlay consumer; drop+recreate the cagg).
- Upstox `/pcr` live-freshness test → dormant `source.pcr` flag (display-only today, no urgency).
- optimizer `requirements.txt` hash-pinning (`pip-compile --generate-hashes`, CI hardening).
- Recorded real Kite binary-frame capture + B-9 frame-guard production wiring (needs a first-party WS client).
- Options-fidelity SNAPSHOT/SYNTHETIC_B76 live walk; walk-forward fold + MedianPruner live walk (multi-month data).
- Second-order greeks (speed/zomma/color); §6.3 BSM-on-spot seam (stock options); 20-level depth flattening.
- Native 3-min option-snapshot capture (config-only; wanted for 3-min Table-2 volume fidelity).
- Data-Ops parked: `backfill_jobs` audit table, per-expiry bulk export, STOMP status, contract-type selector.
- Per-check server audit on signal Take; full-auto execution flag (semi-auto "Take" is the v1 safety boundary).
- AdvanceChart TV-binary extras (drawing tools, study templates, OI-bar, trade-history, audio alerts).

## 7. Decided WON'T DO — never re-flag as pending

Owner NOs, consolidated (full rationale in the archived inventory §6):

E8 ATR-stop arming · FU2 soft-dots as hard gates · E3 Dow dot arming · **W-U4 Upstox cutover (stay Kite,
split-by-capability end-state)** · SPAN short/sell premium legs (long-only) · live-broker order arming
(keep paper/read-only) · E12 ideal-window + OH-freshness + economic-event lockout/event anchors ·
Event Days page (static Budget slideshow, no API) · OiPulse ≥90% AI badge (proprietary; faithful
Table-1/2 HIGH tier is the equivalent) · E1 equity-screener OOM path (replaced by on-demand Upstox +
captured bank-radar) · SENSEX point-scale constant (dead — signals ride NIFTY-FUT-CONT).

---

## Net (refreshed 2026-07-03 evening)

Everything currently buildable is BUILT (roadmap P1+F8+F4v2 + treadmill/provenance fixes,
#483–#491, all deployed). What remains: **the data month** (rollup accrues per session, proposals
pass self-triggers at ≥5 sessions → then the tuning session with the exit-band runbook) · the
**roadmap's gated phases** (§1 row — telegram token, always-on-host hardware, graduation numbers,
real `.spn`) · **Minervini** (owner-deferred behind the roadmap) · a **Nov-2026 calendar refresh**
(§4) · §2 owner toggles/sign-offs · §3 next-session verifies. Machines watch the machines now:
two in-code canaries + two scheduled agents cover live health and per-session forensics.
