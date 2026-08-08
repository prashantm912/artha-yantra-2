# T21 — premium exit bands on the scalpers: PREMISE FALSIFIED, DO NOT BUILD

**Date:** 2026-08-08 · **Item:** T21 (HOLD tier, "owner-approved to BUILD") · **Verdict of Step 0:
the work this brief asks for shipped 14 days ago.** This document is the required plan deliverable;
its plan is: build nothing, correct the stale ledger row that re-queued the item, and (optionally)
pin the "how would we know it works" observable that is already being tracked by the daily audit.

---

## 1. Step 0 — premise verification (FAILED, with the full evidence chain)

The brief's premise: *"30 scalper strategies have no premium exit bands — they exit on indicator
only and ride the 15:15/15:30 square-off."* Every clause of that is a correct description of
**2026-07-25 before ~23:00 IST**, and false since.

| # | Claim checked | Result | Evidence (all read this session) |
|---|---|---|---|
| 1 | 30 scalpers lack premium bands **today** | **FALSE** | `grep -L premium_pct` over all 63 YAMLs in `services/strategy-signal-service/src/main/resources/scalper-strategies/` returns **zero files**. Sample (`scalp-morning-trade-nifty.yaml:51-56`): `take_profit premium_pct 35` + `stop_loss premium_pct 25`, each commented `# T21 (owner 2026-07-25)` |
| 2 | The build was decided but never done | **FALSE** | Commit `64f9caaa` — `feat(strategy-signal): T21 — premium bands (TP +35% / SL −25%) on all 42 bracket-less scalper YAMLs (#990)` — is in `main` history. Ledger row **D1** (`docs/superpowers/plans/2026-07-02-remaining-items.md` ~line 857): DONE #990, 3 review rounds; sibling money defect fixed in **#993** (`entryExposureIsShort`) |
| 3 | Config could be a silent no-op (never republished) | **FALSE — republish done and verified** | Same ledger, deploy-state block: *"28 enabled scalpers republished through the gateway ... 38/38 enabled scalpers now publish a config carrying `premium_pct` AND keeping the `relative-volume-floor` tag; 0 strategies left with an unpublished newer version"* (2026-07-25 ~23:00 IST). Decision-pack doc `docs/signal-analysis/2026-07-25-weekly-bug-queue.md:40-48` records the same, plus "ALL SHIPPED ... NOTHING FROM THIS PACK IS OPEN as of 2026-07-27 pre-open" |
| 4 | Any straggler running an old published config | **NONE remain** | The one known residual — `scalp-trend-change-sensex-sensexoi-pe` published at 1.0.1 (pre-#990), found 2026-07-27 findings §9.2 — was chipped as `task_76d8f2a4` and **CLOSED 2026-07-30**: now published **1.0.4**, verified against both DB and repo YAML. Freshest sweep: 2026-08-07 findings §6.4, `tools/published-config-drift.py` → **69 published / 69 matched, 0 DB-only, 0 YAML-only**; the only 2 STALE-PUBLISH rows are minervini name/description drafts, not scalpers |
| 5 | The "30" figure itself | **TRUE as history** | B11a (bug-queue row, measured 07-23): 30 of 38 **live** scalpers had no premium exit. #990 then banded all **42** bracket-less YAMLs of 63 (the delta: disabled variants). Both numbers are correct for their dates; neither is true today |

**Where the false premise came from.** The ledger carries two T21 rows that disagree:
row **D1** (DONE #990, deployed, republished) and the **"ACTION TRIAGE (written 2026-08-07
18:20 IST)"** table (~line 1048), whose T21 row still reads *"Awaiting the call ever since; it is
not blocked on anything."* The triage row was derived from the B11 "pack DELIVERED" row instead of
D1 — exactly the enumeration-trap class the ledger itself warns about, and the same failure mode as
the three already-built items in the recent batch. Note `task_587984d1` (ledger): *the builder lane
has no gate on the BRIEF — only on the code* — this document is that gate doing its job.

**What "owner has now chosen Build it" most plausibly means:** the owner approved the stale triage
row. If the owner in fact wants something *beyond* #990 (different band numbers, bands on the 8
strategies that already had brackets, a trailing premium band), that is a **new item needing a new
owner statement with numbers** — see Open Questions.

---

## 2. The plan (all units are docs/process; zero production code, zero migrations)

### Unit 1 — correct the stale ledger triage row (ships alone, do first)
- **Files:** `docs/superpowers/plans/2026-07-02-remaining-items.md` (ACTION TRIAGE table T21 row →
  point at D1/#990 and mark RESOLVED-ALREADY-BUILT with a pointer to this doc). The bug-queue doc
  needs no edit — it already records the ship correctly.
- **Red→green check:** re-run the enumeration the triage table prescribes; T21 must no longer
  surface as dispatchable. `tools/ledger-consistency-check.py` stays at its standing 10 known
  false-positive REVIEW lines (08-07 baseline), no new ones.
- **Parity/golden exposure:** none (docs only). **Migration:** none. **Independently shippable:** yes.

### Unit 2 — pin "what would working look like" (optional; carry, don't build)
The brief is right that nobody has seen these bands fire: `take_profit premium_pct` sits in the
never-fired set at **36 occurrences** and `signal_exit` at **38** (08-07 findings §6.3). But the
standing, evidence-backed read is **entry starvation, not bracket distance** — the scalper paper
book takes ~1 funded entry per session and recent sessions were zero-fire; shadow TP waves on
08-04/08-05 prove `take_profit` is mechanically reachable. The observable that closes the loop,
already tracked daily by the §3.29 unexercised-path audit:

> First funded scalper entry → `strategy.paper_positions` row carries non-NULL premium TP/SL levels
> (option-leg basis, index-side levels NULL per the #990 round-2 fix) → a later close with reason
> `TAKE_PROFIT`/`STOP_LOSS` moves `take_profit premium_pct` out of the never-fired set.

- **Files:** none — the audit exists. If anything, add one sentence to the triage row (Unit 1)
  naming this as T21's acceptance evidence.
- **Red→green check:** the never-fired count for `take_profit premium_pct` drops below 36 in a
  future session-findings §3.29 table. Not schedulable; it is a market-event gate.
- **Independently shippable:** yes (it is a sentence inside Unit 1's edit).
- Adjacent open chip, deliberately NOT folded in: `task_1052482e` (exit reason not persisted on
  `strategy.signals`) — separate scope, separate owner call.

### Explicitly rejected unit — re-running the requested build
Re-adding bands would be a no-op at best; at worst it reintroduces the two defects the #990 review
cycle paid for: (a) the round-2 **live Critical** — `premium_pct` resolved against the INDEX entry
price = one-bar force-exit for every held-PE (`levelFromRules` was deleted; index-side levels now
persist NULL for premium_pct-only strategies); (b) the #993 class — stop side keyed on
`definition.direction()` instead of the held option side. Any fresh copy of the level math would
also violate the #1095 single-definition rule (`PremiumLevels.paiseRounded`,
`libs/strategy-engine/src/main/java/in/arthayantra/strategyengine/eval/PremiumLevels.java`).

---

## 3. Constraint accounting (as the brief demands, stated even though nothing is built)

1. **Fixture:** `contracts/fixtures/exit-equivalence.json` already pins premium semantics
   (9 `premium_pct` occurrences). #990 changed *which strategies configure* bands, not semantics,
   so the fixture was correctly untouched then and stays untouched now. **Scenarios added: none.**
2. **Level formula:** stays solely `PremiumLevels.paiseRounded` (#1095). No new call sites. The
   non-unified `PremiumExitEvaluator.rawLevel` (backtest-only trailing arm) is out of scope.
3. **Parity:** zero exposure — no `SignalEvent`/`Trade` field changes, `GoldenSignalsJson.write()`
   untouched, no run of `GoldenDeterminismTest`/`BacktestParityTest` required beyond CI's normal
   gates. Byte-identity holds because no serialized surface is touched at all.
4. **Republish:** none needed — the 07-25 republish already landed, was executed with the #1016
   rule, and the 08-07 drift sweep shows 0 scalper divergence.
5. **Migration:** none. For the record, the strategy lineage is currently at **V059**
   (`deploy/flyway/strategy/V059__paper_order_settles_position.sql`); the V054/V055 collision the
   brief warns about is on the **marketdata** lineage and is irrelevant here.

## 4. What could go wrong on the live book

- **The danger is building, not the bands.** The bands are live exit doctrine on 63 configs. A
  duplicate build risks: double band rows in YAMLs; a blind republish that reverts tag/exit deltas
  (the #1016 GAINS-vs-LOSES trap); reintroduction of the index-basis force-exit. Every one of those
  is a live-money defect on the one funded scalper entry a session takes.
- **Band-number tuning is NOT licensed by this item.** The standing prior (bug-queue doc, footer):
  *every measured loosening of the scalper entry gate has LOST money* (T1, T7, G13, G10 — four
  tests, three knobs). Treat −25/+35 the same way: any retune must arrive as an owner-stated number
  with a measured counterfactual, judged over forward sessions, not as a side effect of "T21".
- **A too-tight TP converts winners to scratches** — the reason #990's numbers came from the owner
  (E9-style) and the reason the never-fired status must be read as starvation until a funded entry
  says otherwise.

## 5. Open questions (could not be settled from the code)

1. **What did the owner actually approve on 2026-08-08?** If it was the stale triage row, T21 is
   closed by Unit 1. If the owner wants *new* band work (different numbers / the 8 already-bracketed
   strategies / trailing premium), that needs a fresh owner statement with numbers — nothing in the
   repo records such a request.
2. **Today-fresh DB confirmation:** the live DB was unreachable from this session (postgres MCP
   auth failed). Freshest live evidence is 2026-08-07's drift sweep (69/69 matched) plus the
   07-25/07-30 DB-verified republishes. A `live-verify` probe of `strategy.strategy_versions` for
   the published `premium_pct` count would make claim 3 today-fresh; nothing found suggests it
   changed overnight.
3. **"Leave the PR open"** cannot be honored: no branch, worktree, or open PR for T21 exists
   (checked `git branch -a`, `git worktree list`). #990 merged 2026-07-25. There is nothing to
   leave open.

## Critical files for implementation
- C:\Trading\ArthaYantra\artha-yantra-2\docs\superpowers\plans\2026-07-02-remaining-items.md
- C:\Trading\ArthaYantra\artha-yantra-2\docs\signal-analysis\2026-07-25-weekly-bug-queue.md
- C:\Trading\ArthaYantra\artha-yantra-2\docs\signal-analysis\2026-08-07-session-findings.md
- C:\Trading\ArthaYantra\artha-yantra-2\services\strategy-signal-service\src\main\resources\scalper-strategies\ (63 YAMLs, all banded)
- C:\Trading\ArthaYantra\artha-yantra-2\libs\strategy-engine\src\main\java\in\arthayantra\strategyengine\eval\PremiumLevels.java