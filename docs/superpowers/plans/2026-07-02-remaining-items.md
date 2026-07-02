# Remaining items — the single forward ledger (2026-07-02)

**Status:** ACTIVE — the one place listing *everything still open* across the whole platform.
Supersedes [`archive/2026-06-30-remaining-build-inventory.md`](archive/2026-06-30-remaining-build-inventory.md)
(kept for provenance; its §4 false-flags, §5 descopes and §6 WON'T-DO lists are carried forward in §7 below
so nothing gets re-flagged). Cross-checked 2026-07-02 against `PHASE_GATES.md`, `docs/DEFERRED_BACKLOG.md`,
the two 2026-07-02 audits (both fix queues fully closed) and the open-PR/issue list (empty).

**Owner rule:** when an item here ships, mark it DONE in place (PR# + SHA) before starting the next one.

---

## 1. Net-new code — ONE item

| id | item | authority | state |
|---|---|---|---|
| `phase5-minervini-trend-template` | **Phase-5 Minervini Track-1 screener** — daily 8-gate Trend Template + RS-rank preset in `ScreenerService` (master-plan §13). VCP/pivot/Cheat/Power-Play stay deferred (owner reads entries manually). | master-plan §13; `DEFERRED_BACKLOG.md` Phase-5 row | **UNBLOCKED** — 200-day equity daily history seeded (#389, live-verified 222 candles/symbol). The lone remaining net-new build on the platform. |

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

## 3. Verification only — next market session (2026-07-03)

- [ ] T2 aligned snapshot buckets row-for-row vs the oipulse barometer (capture crons shifted #441).
- [ ] Capture crons fire on the new boundaries: 09:16 futures / 09:18 options.
- [ ] First pre-open equity scan lands at 09:09:30 (#470) + futures pre-open page renders live (#377).
- [ ] NSE announcement field mapping on live data (#378).
- [ ] Stock-chain warm on a second symbol during market hours (#472 verified on RELIANCE off-hours).
- [ ] Owner hard-reload (Ctrl+Shift+R) — stale cached FE chunks render the old UI.

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

## Net

One build (Minervini screener) · one month of live-paper data then the tuning session · a Nov-2026
calendar refresh · next-session live verifies · the rest is owner toggles/sign-offs or
deferred-until-needed. The platform build is otherwise **complete**.
