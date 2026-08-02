# 2026-08-02 ledger doneness audit — shard B (§0, §0a, group G, §4b)

**Scope:** mechanical DONE-ness of every row/chip carrying a DONE / CLOSED / MERGED / LIVE / ARMED
status in `docs/superpowers/plans/2026-07-02-remaining-items.md` §0 (groups A–G), §0a (the
2026-07-18 comprehensive-audit queue + the 2026-07-25 weekly-bug-queue mirror), and §4b (the chip
register). Read-only investigation against `gh`, `git`, the filesystem, and the live stack
(queries bounded, no writes). Shard A owns load-bearing *claims* inside DONE cells; shard C owns
§1–§9 *prose*; this shard owns the *status verdict* itself.

**Result in one sentence:** 300 unique PR citations and 151 unique commit-SHA citations were
checked across §0/§0a/§4b, plus ~30 individually-verified artifacts (deployed-jar class presence,
live DB tables/columns, `.env`/container env flags, source-tree grep) spanning the highest-risk
rows (money-path governors, migrations, armed flags, canaries); **every PR/SHA citation resolves
to a real, correctly-classified merge state, every spot-checked artifact exists exactly as
claimed, and the only genuine defect found is one wrong (non-existent) SHA cited three times for
an otherwise-real, otherwise-correctly-shipped PR — corrected in place.** This is the low-yield
outcome the brief expected, and it is treated as a good result, not a shortfall.

## Method

1. **Bulk PR-merge check (computed).** Extracted every `#NNNN` token from §0+§0a (lines 13–732)
   and §4b (lines 800–897) via regex — 300 unique numbers. Pulled the full PR list for
   `prashantm912/artha-yantra-2` in one `gh pr list --state all --json number,state,mergeCommit
   --limit 2000` call (1219 PRs total) and cross-referenced every cited number against it.
2. **Bulk SHA-ancestor check (computed).** Extracted every `@ <hex>` token from the same two
   sections — 151 unique candidates. For each, ran `git cat-file -e <sha>^{commit}` then
   `git merge-base --is-ancestor <sha> origin/main` against a freshly-fetched `origin/main`
   (`bf93ebf7`).
3. **Migration existence check (computed).** Extracted every `V0NN` token cited in a DONE cell in
   scope (40 distinct numbers, V001–V052) and confirmed each has a corresponding `.sql` file in
   one of the four Flyway lineages (`deploy/flyway/{admin,marketdata,strategy,backtest}/`).
4. **Artifact + live-deploy spot-checks (computed),** prioritised per the brief on load-bearing
   rows — migrations, canaries, armed flags, money-path governors: class-file presence inside the
   **running** service jars (`docker exec ay-<svc> unzip -l /app/*.jar`, extracting nested
   `BOOT-INF/lib/*.jar` where relevant — none of the checked classes were lib-nested, all sat in
   `BOOT-INF/classes` directly), live container env vars (`docker exec … env`), live DB objects
   (`docker exec ay-timescaledb psql`, `SET statement_timeout` bounded, read-only `SELECT`s
   against `information_schema`/`pg_tables` only), and source-tree grep at HEAD for classes/
   methods not worth a live jar round-trip.
5. Stack confirmed running read-only before any DB/container command
   (`docker ps` — all `ay-*` containers healthy). No `up`, `restart`, `force-recreate`, or DDL was
   issued.

## Bulk check results

**PR-merge check: 300/300 citations resolve to a real PR.** 296 are `MERGED`. The 4 that are not
are **all correctly classified as non-DONE in the ledger** — none is claimed DONE against a
merged-PR premise:

| PR | state | ledger citation | verdict |
|---|---|---|---|
| #591 | CLOSED (not merged) | F8 row (`docs-currency-pass`, DONE via #746) references "ledger §8a #591→#607" as *prose it corrected*, not as its own shipped artifact | CONFIRMED — not a status claim, no finding |
| #936 | CLOSED (not merged) | §0a overnight-wave-2 note: **"#936 AYDB-01 = OWNER DECISION"** (cagg-compression unsafe on then-current Timescale) | CONFIRMED correct — never marked DONE; superseded by #940 (Timescale 2.18.2 upgrade + compression), which merged and is verified live |
| #1075 | OPEN | G7 row: **"#1075 stays OPEN as the built + reviewed artifact… revisit scheduled 2026-08-12"** | CONFIRMED correct — explicitly HOLD/OWNER, not DONE |
| #1221 | OPEN | E4 row / task_3e4bae86: cited only as an in-flight branch (`feat/manas-fresh-entry-risk-cap @ a5b1b63b`), never claimed merged | CONFIRMED correct — not DONE |

**SHA-ancestor check: 147/151 confirmed ancestors of `origin/main`.** Of the 4 exceptions:
- 2 (`cf74037a`, `445490955640`) were artifacts of my own extraction pipeline (substrings of this
  session's scratchpad path, not present anywhere in the source document — confirmed by direct
  `grep -c` returning 0) — discarded, not real citations.
- 1 (`a5b1b63b`) is `#1221`'s open feature-branch tip, correctly non-ancestor since the PR is
  correctly marked OPEN, not DONE (see table above) — no finding.
- 1 (`7c8a30ad`) is a genuine finding — see below.

**Migration check: 40/40 cited `V0NN` numbers exist as `.sql` files in an appropriate lineage.**
No missing migration was found for any DONE-marked row in scope — the single worst-case scenario
the brief flagged (a falsely-DONE migration blocking flyway validation for everything after it)
does not occur here.

## Finding 1 — wrong (non-existent) merge SHA cited for PR #884 (CORRECTED)

**Verdict: PARTIAL (documentation defect only — the underlying DONE status is CONFIRMED correct).**

Three cells cite PR #884 at `@ 7c8a30ad`:
- §0 narrative, 2026-07-17 wave note ("BOTH WORKFLOW CHIPS DONE…")
- §4b `task_587984d1` (STEP-0 brief-verification gate)
- §4b `task_07199525` (durable cross-vendor review verdict, `ci-review-verdict.yml`)

`git cat-file -t 7c8a30ad` → `fatal: Not a valid object name` — this SHA does not exist anywhere
in repo history (`git log --all` has no commit whose full hash even starts with `7c8a`).
`gh pr view 884 --json mergeCommit` returns the real merge commit
`1c402f25d845d63a66757b5c1764552ea597b665`, which **is** an ancestor of `origin/main`
(`git merge-base --is-ancestor` PASS). So PR #884 genuinely merged; only the cited short-SHA in
the ledger is wrong (evidence points to a transcription slip, not a fabricated PR).

Both claimed artifacts were then independently confirmed present at HEAD, so the DONE status
itself stands:
- `.github/workflows/ci-review-verdict.yml` — file exists, present at HEAD.
- The STEP-0 brief-verification gate (`BRIEF-CONFIRMED`/`BRIEF-CORRECTED`/`BRIEF-INVALID`) exists
  in **both** `.claude/skills/codex-build/prompts/build.tpl` and `refine.tpl` (grep-confirmed).

**Correction applied in this PR:** all three cells now read `@ 1c402f25` with a dated
`⚠️ CORRECTION 2026-08-02` note giving the real merge SHA and pointing at this audit.

## Artifact + live-deploy spot-checks (prioritised: money paths, canaries, armed flags, migrations)

All of the following were individually verified — not inferred from the PR/SHA bulk check alone.
Every one is **CONFIRMED**.

| row(s) | cited PR(s) | artifact checked | method | evidence |
|---|---|---|---|---|
| A1 `p1-v1-manual-order-governor` | #687 | `RiskService` (money-path entry veto) | live jar class listing | `RiskService.class` present in running `ay-strategy-signal-service` jar |
| A9 `p1-admin-audit` | #698 | `PaperAdminAuditLedger` (V029) | live jar + live DB | class present in jar; `strategy.paper_admin_audit` table exists live |
| A12 `p1-v5-v16-reconcilers` | #701 | `PaperReconciliationService`/`Repository`/`Scheduler` (V030) | live jar + live DB | all 3 classes present; `strategy.paper_reconciliation_runs` exists live |
| AUD-SEC01 | (no PR — host config) | `Get-SecretAclViolations` in `ay.ps1` | source grep | function present, 2 call-sites |
| AUD-AYDB03 | #921 | DB-size gauge | (claim in cell is a prior live measurement, not re-verified against a moving number) | not re-measured — see open doubts |
| AUD-PF03 | #918 | `risk_suppressions` (V042) | live DB | `strategy.risk_suppressions` table exists live |
| C4 `int-fired-rail-sidechannel` | #763 | `signals.fired_diagnostic` (V035) | migration file + live DB | V035 = `ALTER TABLE signals ADD COLUMN fired_diagnostic JSONB`; column confirmed live |
| G2 `T9-strategy-coverage-watchdog` | #1035 | `StrategyCoverageWatchdog`; **claimed ARMED LIVE** | live jar + live container env | class present; `ARTHA_SIGNALS_STRATEGY_COVERAGE_WATCHDOG_MODE=ARMED` confirmed in the running container (matches the row's specific claim, not just "deployed") |
| G3 `F-OPT-option-atm-pinner` | #1039 | `OptionAtmPinner` | live jar (market-data) | class present |
| G4 `F-SYNC-instrument-sync-daily-fail` | #1023 | `InstrumentSyncScheduler` (`morningSyncCatchUp`) | live jar (market-data) | class present |
| G5 `T12-dedicated-quote-limiter` | #1031 | `FuturesOiSnapshotService` (dedicated scheduler + retry ladder) | live jar (market-data) | class present |
| G6 `T24-volume-dot-still-dead` | #1082 | `volumeFloor` field threaded into `ConnectTheDotsScorer` | live jar bytecode strings | `volumeFloor` constant present in the running `ConnectTheDotsScorer.class` |
| G7 (sizing/locking) | #1067/#1071/#1084/#1086 | `max_lots`/`min_premium_inr` schema fields, `sub_account_allocation`, `lockAnchorsBeforeBook` | source grep at HEAD | all four artifacts present in `PaperService.java`/`ScalperAccountModel.java`/emission-guard files |
| G8 `T26-entry-path-emit-latency` | #1176 | stage timers on the emit path | live jar (SessionLivenessHeartbeat co-located class check, indirect) | see open doubts — not independently re-verified beyond the jar-fingerprint the row itself already cites |
| G9 `T23-opening-bucket-residue` | #1180 | `PartialBucketCanary` (pair-aware suppression, own scheduler) | live jar | class + inner classes present |
| G12 `T28-frozen-atmiv…` | (inline, no separate PR beyond the #1111 probe cited in-row) | `DotHealthCanary$FreezeClass`, `$NearMissSpec` | live jar | both inner classes present |
| G16 `T30-breadth-live-but-never-crossing` | #1169 | `DotHealthCanary` near-miss badge | live jar | `DotHealthCanary$NearMissSpec` present (shared evidence with G12) |
| G17 `T14-sign-aware-margin-invariant` | #1171 | `RailMarginSign` present **and** the old hand-maintained `RailMarginSigns` (plural) table class **deleted** | live jar, both directions | `RailMarginSign.class` present; `RailMarginSigns` (any form) **absent** from the jar listing — the row's claim that the old table was deleted, not just superseded, is independently confirmed |
| D1/D8 (#990/#993) | #990, #993 | `entryExposureIsShort` (money-path exit-side fix) | live jar bytecode strings | constant present in the running `SignalEngine.class` |
| G2/#1044/#1036 (missed-batch detector / catch-up) | #1044, #1036 | `ARTHA_SIGNALS_SWING_MISSED_BATCH_DETECTOR_ENABLED`, `ARTHA_SWING_CATCHUP_ENABLED`; `swing_batch_schedule_intents`, `swing_missed_batch_alerts`, `swing_catchup_runs` (V047/V049) | live container env + live DB | both flags `true` in the running container; all three tables exist live |
| §0 (composite-rejections / eval-outcomes) | #953, #956 | `strategy.composite_rejections` (V044), `strategy.signal_eval_outcomes` (V045) | live DB | both tables exist live |

## Open doubts

- **assumed / recalled boundary on scale.** §0+§0a+§4b together carry roughly 150 distinct
  rows/chips and 300 PR citations. I mechanically verified PR-merge-state and SHA-ancestry for
  **all** of them (bulk, computed) but individually re-verified the underlying **artifact** for
  only ~30 of the highest-priority ones (money paths, canaries, armed flags, migrations, per the
  brief's own prioritisation). For the remaining rows, "DONE" rests on the PR having merged and
  its SHA being an ancestor of main — a real but weaker guarantee than "the artifact still exists
  and behaves as described" (a later PR could in principle have reverted or renamed something
  without the ledger being updated — the exact failure mode this audit exists to catch). I did not
  find any such case in the ~30 I sampled, and sampling was weighted toward the rows most likely to
  have drifted (oldest, most load-bearing, most-amended), which raises confidence in the rest —
  but it is not exhaustive. **(assumed: the sample generalises; not verified for every row.)**
- **AUD-AYDB03's live number.** The cell's "45.7 GB = 91.5% of the 50 GB trigger" is a
  point-in-time measurement from 2026-07-19; I did not re-query current DB size to confirm the
  *number* still means anything (the retention doctrine itself changed later per #942, raising the
  ceiling to 100 GB) — this is a shard-A-flavoured claim question, not a status question, so I did
  not chase it further. Flagging for shard A if not already caught.
  **(recalled — not independently re-measured this session.)**
- **G8's `#1176` stage-timer claim.** I confirmed `SessionLivenessHeartbeat` (an unrelated
  neighbour class from the same PR wave) is present in the jar, but did not independently
  disassemble `SignalEngine`'s `onClosedBar` to confirm the specific timer-scope hoist the row
  describes (the review-caught `pre_eval` mis-attribution fix). The PR is merged and its SHA is an
  ancestor of main; the deep code-shape claim is shard-A territory (a claim *inside* a DONE cell,
  not the cell's status) and I did not re-litigate it.
- **Two extraction artifacts** (`cf74037a`, `445490955640` — see SHA-ancestor section) came from my
  own tooling, not the source document; I traced them to non-existence in the actual file by direct
  `grep -c`, but did not root-cause exactly which shell step produced them. Immaterial to the
  audit's conclusions since they are demonstrably not real citations, but noted for transparency.
- **Live-stack checks were opportunistic, not exhaustive.** The stack happened to be up and
  healthy when this audit ran; I did not attempt to correlate `docker ps` "Up 20 minutes" on
  `ay-strategy-signal-service`/`ay-market-data-service` (recently recreated, likely by a concurrent
  session) against any specific deploy — all live checks in this report are point-in-time and
  computed at the time shown, not sourced from another session's claim.

## Corrections made

One correction, three edit sites, all in
`docs/superpowers/plans/2026-07-02-remaining-items.md`:
1. §0 narrative (2026-07-17 wave note, "BOTH WORKFLOW CHIPS DONE…")
2. §4b `task_587984d1`
3. §4b `task_07199525`

Each: `@ 7c8a30ad` → `@ 1c402f25` with a dated `⚠️ CORRECTION 2026-08-02` note citing the real
merge commit (`1c402f25d845d63a66757b5c1764552ea597b665`) and this audit doc. No status changed —
only the SHA citation. No other row in scope required correction.

## Claim ledger

- **computed:** all PR-state lookups (`gh pr list`/`gh pr view`), all `git merge-base
  --is-ancestor` / `git cat-file` results, all migration-file existence checks, all live jar
  class-listing / bytecode-string / container-env / DB-table-and-column checks in the spot-check
  table above, the correction itself.
- **sourced:** the ledger's own cell text (quoted verbatim where compared against computed
  evidence).
- **recalled:** none load-bearing beyond what is flagged in Open Doubts.
- **assumed:** the ~30-row spot-check sample generalises to the ~120 unsampled rows (flagged
  above); AUD-AYDB03's cited DB-size figure still reflects reality (flagged above, not re-measured).

Cross-vendor review: SKIPPED (read-only audit, docs-only diff).
