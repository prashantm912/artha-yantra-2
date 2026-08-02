# Ledger audit 2026-08-02 — shard C: prose declarations, the T-namespace, and the weekly bug queue

**Scope (owner-authorised, read-only + a docs-only diff).** Shard C of 3. Shards A and B own the §0/§0a/§4b/§9
tables. This shard owns the three places open work is declared *outside* a table:

1. prose bullets inside the dated UPDATE/STATE blocks of `docs/superpowers/plans/2026-07-02-remaining-items.md`;
2. the whole **T-namespace** (T1…T30), which the enumeration recipe itself calls out as having no durable register;
3. the current weekly bug queue, `docs/signal-analysis/2026-07-25-weekly-bug-queue.md`.

**Why it exists.** On 2026-08-02 one enumeration pass dispatched five builders at already-shipped work. Two of the
five (F-OPT, F-SYNC) came from *prose* — both had been promoted to §0 rows G3/G4 on 2026-07-25, both rows read DONE,
and nobody went back to annotate the paragraph that spawned them. Narrative is never re-read once a row exists, so it
reads "open" forever. Those two are now fixed with `→ row Gn, DONE #NNNN` pointers. This shard is every *other* place
that could do the same thing — plus the inverse, which is the more expensive failure: **work that is genuinely open
and that no enumeration can find.**

**Method.** Every verdict was checked against the ledger ROW, the merged PR (`gh pr view`), the code, or a bounded
read-only live query — **never against another prose block**, since prose-to-prose agreement is the failure mode
itself. The single-status rule applies throughout: an item's status is authoritative in its ledger row and nowhere
else; where a sibling doc disagrees, the row wins and the sibling is what gets corrected.

---

## Headline

> **8 genuinely-open items are currently invisible to the enumeration recipe.** Four are measured-real with live
> evidence and want a decision; four are candidates the Architect should rule on. **None is a build I can start —
> three are owner flag/data actions, three are verifications, two are undecidable.**
>
> Alongside them: **14 prose declarations were stale** (promoted, shipped, or superseded, and never annotated), and
> **0 lost T-items** — the T-namespace turned out to be in far better shape than its reputation, with 29 of 30
> carrying a durable row. **1 T-item (T10) has no register anywhere.**

Counts: **8** genuinely-open-and-invisible · **14** stale prose declarations now pointered · **2** sibling-doc drifts
in the newest tune table · **2** chip ids whose content is unrecoverable · **0** items lost outright.

---

## Part 1 — GENUINELY OPEN AND CURRENTLY INVISIBLE (ranked; this is the part that matters)

Ranked by cost-if-missed. "Invisible" = no §0/§0a/G/§4b row; findable only by reading a paragraph.

| # | item | where declared | verdict | evidence | rank rationale |
|---|---|---|---|---|---|
| **1** | **`SessionLivenessHeartbeat` (#941) is still UNARMED** — the only detector for a stack that dies mid-session | ledger §0 prose ×3 (2026-07-17 F10 block, 2026-07-19→20 STATE, "OWNER-GATED / carried forward") | **STILL OPEN** | `computed` — `docker exec ay-strategy-signal-service` → `ARTHA_HEARTBEAT_SESSION_URL` **EMPTY**, so the `@ConditionalOnProperty` bean is never created. Dormant **14 days** since the 2026-07-19 merge. ⚠️ `ARTHA_HEARTBEAT_URL` (#640, the 20:15 swing-batch switch) **IS** set (len 56) — a reader checking "is a heartbeat armed?" finds the wrong one | Highest. The ledger's own prose says a stack dead 09:15–15:30 that recovers by 20:15 "pings happily while the whole session is lost". That hole is open *today* and only a paragraph knows |
| **2** | **PE forward-paper verdict** — do the `-pe` scalpers actually fire after #959's composite inversion? | ledger §0 prose, 2026-07-20 STATE "OWNER-GATED / carried forward" | **STILL OPEN — and now answerable** | `computed` — bounded live reads: **27** `-pe` strategies exist (matching #959), **all 27 published**, of which **18 are live** (`enabled AND published_version_id IS NOT NULL`) split **9 NIFTY + 9 SENSEX**; the other 9 are SENSEX explicitly `enabled=false`, so there is **no publish gap** confounding this. Those 18 fired **0** signals since 2026-07-21, against **69** non-PE over the identical window (`strategy.signals ⋈ strategy_versions ⋈ strategies`, `generated_at >= 2026-07-21T00:00+05:30`) | High. 11 sessions of a live money-path fix sitting unvalidated, and the measurement is one query. #959's own PR note says "NECESSARY, NOT SUFFICIENT — judge on forward paper". Both indices are represented among the live 18, so the zero is not a one-index artifact |
| **3** | **2026-07-02 bhavcopy still partial — and its stated blocker is DISCHARGED** | ledger §8b "Data action" + §8d "07-02 bhavcopy re-fetch — DEFERRED (needs a trigger)" | **STILL OPEN, no longer blocked** | `computed` — `marketdata.nse_eod_bhavcopy`: 07-01 = **3,268** · **07-02 = 266** · 07-03 = **3,283**. `sourced` — the "needs a targeted re-fetch endpoint (small build)" blocker is gone: `POST /api/v1/market/eod-backfill/refetch?date=` exists at `EodBackfillController:43` (shipped #744), and its javadoc names *this exact case* ("the correction path for a partially-captured bhavcopy the self-healing catch-up cannot reach") | High. A one-call fix that reads as blocked — the cheapest open item on the platform, poisoning Equity-Returns r1d + delivery for that date. ⚠️ It is a **mutating live write**, so it stays an owner/Architect call |
| **4** | **T10 — 17 stale OPEN swing paper positions** | weekly bug queue B11c + "OWNER — chronic" in every session tune table | **STILL OPEN** | `computed` — `strategy.paper_positions WHERE status='OPEN'`: **11 minervini** (oldest 2026-07-07) + **6 manas-arora** (oldest 2026-07-10) = **17**, 0 scalper. Exactly the 2026-07-31 findings count, unchanged 8 days. `sourced` — #992 (owner rec (b)) downgraded the **alert**; the positions were never squared off | High. **The one T-item in the entire namespace with no §0/G row.** Real money sitting in a book whose exits are starved |
| **5** | **§3 "Verification only — next market session" — 3 boxes unchecked since 2026-07-03** | ledger §3 (`- [ ]` items) | **STILL OPEN ×3** | `sourced` — all three surfaces still exist: `AnnouncementController`/`AnnouncementService` (#378 NSE announcement field mapping); `ActiveStrikeService.sentimentLevelPct:88` + `OptionsAnalyticsController:151,454` (#512 — the owner still owes one live compare of ΔOI-flow `sentimentPct` vs level-based `sentimentLevelPct`, then a decision on which the **gate** reads); the chain-warm path (#472, second symbol never exercised) | Medium-high. A whole section, 30 days stale, that the six-location recipe **does not name**. #512's is a live *gate* input |
| **6** | **`source.optionanalytics` Upstox PCR flip — the one live freshness check never ran** | ledger §6 (deferred-by-design), "Only a LIVE market-hours freshness check remains before flipping it" | **STILL OPEN** | `sourced` — `market-data-service/src/main/resources/application.yml:87`: `optionanalytics: ${ARTHA_MD_SOURCE_OPTIONANALYTICS:native}`. Still `native`; the Upstox route has never run in production | Medium. Built, tested, one check from usable, invisible for a month |
| **7** | **Mid-session subscription-drop DRILL on #886's receive-gap path** | ledger §0 prose, 2026-07-17 F10 block: "HIGHEST-VALUE NEXT ACTION IS A DRILL, NOT A BUILD" | **CANDIDATE — genuinely open** | `sourced` — row **F10** now reads `Part A DONE #874 · Part B DONE — G2 ARMED 2026-08-01` and states "this row no longer carries independent work", so the drill lost its home; G2 covers the **load**-coverage class, not the mid-session-drop class. `computed` — no drill record in `docs/signal-analysis/`; the two logged drills (07-16, 07-17) are both COLD-START | Medium. The prose warns a negative result "would overturn 'Part B is already shipped'" — i.e. it could reopen a row currently marked DONE |
| **8** | **task_f624fca7** — should an empty `contracts` array on an INDEX ladder be a FAULT? (#877 residue) | ledger §0 prose "Open chips:" list, 2026-07-17 | **CANDIDATE — genuinely open, no §4b row** | `sourced` — cited as still live and un-owned at `docs/superpowers/plans/2026-07-26-t9-strategy-coverage-watchdog-design.md` §3.7: "(Whether an empty `contracts` array on an INDEX ladder should be a FAULT is chip `task_f624fca7`'s question, owned by the resolver, untouched here.)" | Medium. A design doc from 6 days ago explicitly routes around it, so it is live — but it lives in one sentence of a July narrative |

### Undecidable — recommend explicit retirement, not carrying

| item | where | verdict | evidence |
|---|---|---|---|
| **task_019321d3**, **task_2938fa28** | ledger §0 prose "Open chips:" list only | **CONTENT UNRECOVERABLE** | `computed` — `git log -S<id> --all -- docs/` returns exactly ONE introducing commit each: `eedace50` (#893, 2026-07-17) — the very sentence that lists them. Described *nowhere*, in any doc, ever. No §4b row. They cannot be actioned and must not be counted as queue depth. **Recommendation: retire the ids explicitly** unless the owner recalls what they were |

### Explicitly NOT manufactured

Two things I checked and declined to promote: the "Owner hard-reload (Ctrl+Shift+R) after each FE redeploy" checkbox
(a standing habit, not work), and the option-(a) `portfolioFifoNet` re-run — that one is already carried *inside* row
F3's own status cell, so it has a home and does not need a new one.

---

## Part 2 — Stale prose declarations, now pointered (all applied in this PR)

Each row: the paragraph declared work; the work is promoted/shipped/superseded; the paragraph never said so. All
verdicts checked against the row, the PR, or the code.

| # | declaration | file:where | verdict | evidence | pointer applied |
|---|---|---|---|---|---|
| 1 | "3 HOLD PRs — **OPEN for owner** (built+reviewed+APPROVED, never merged): #935, #938, #930" + "#936 AYDB-01 = OWNER DECISION" | ledger §0a, overnight-wave-2 block | **SHIPPED / CLOSED** | `computed` `gh pr view`: **#935 MERGED · #938 MERGED · #930 MERGED · #936 CLOSED** | `→ ALL RESOLVED` block + a note that memory topic `comprehensive-audit-2026-07-18` still carries the stale "3 HOLD PRs OPEN" line |
| 2 | "**STILL OPEN:** `feat/dead-anchor-orphan-detection` … needs its final cross-vendor round" | ledger §0, 2026-07-17 FINAL STATE | **SHIPPED** | `computed` `gh pr view 894` = MERGED (@ a716d851). The *same block's* header already records it — the paragraph is the pre-merge "original note" | stale-heading warning inline, before the words "STILL OPEN" can be read as live |
| 3 | "**OPEN (owner 'add to pending, take it later')** — FIFO vs RS-priority slot admission" | ledger §Manas section | **PROMOTED → row F3, DONE #751** | `sourced` row F3 `fifo-slot-probe` = "DONE — #751 @ 9c8df0b6, MERGED+DEPLOYED 2026-07-12" (V034 probe columns). `computed` #751 MERGED | `→ row F3, DONE #751`, noting option (b) shipped and option (a) is F3's own follow-up |
| 4–6 | "serial/N+1 backtest reads (`ManasAroraBacktestService.readSeries`)" declared open in **three** separate places | ledger §Manas "one open perf follow-up", §8a LOW bullet, §8d "HOLD/next" | **PROMOTED → row F2, DONE #750** | `computed` `git log -S readSeriesBatched` → `4639a844` "perf(market-data): batch the N+1 candle reads … (F2) (#750)". `sourced` production now calls `readClosesBatched`/`readSeriesBatched` (`MinerviniBacktestService:353,508`); `readSeries` survives only as the reference path the batch-equality ITs compare against | `→ row F2, DONE #750` on all three, with the code evidence on the first |
| 7 | "**PENDING:** one batched `frontend-react` rebuild+redeploy for #897+#899" | ledger §0, 2026-07-17 weekend batch | **SHIPPED** | `computed` live `arthayantra/frontend-react:dev` image built **2026-07-31T22:25Z**; the DataTable waves #943–#948 deployed in between | `→ DISCHARGED — do not enumerate` |
| 8 | "**PARKED — HOLD-tier, money-path, NOT armed** (owner decision, chip task_e263cfb0):" | ledger §0, 2026-07-20 STATE — a *heading* over bullets that contradict it | **SHIPPED + ARMED** | `sourced` its own two bullets: detector #1044 ARMED 2026-07-26, auto-replay #1036 ARMED 2026-07-27 ~22:45 IST, both since verified on real sweeps. `computed` `task_e263cfb0` appears in the repo **only** in that line — no §4b row | heading-superseded warning; the chip discharged with it |
| 9 | "**OPEN CHIPS (not yet built):**" | ledger §0, 2026-07-20 STATE | **EMPTY** | `sourced` its sole bullet is struck through and CLOSED 2026-07-25 | note that the heading outlived its content — and that `OPEN CHIPS` is literally one of the recipe's location-#4 greps, so it *matches* and reads live |
| 10 | "**OWNER before Monday:** B8 host clock resync" | ledger §0, 2026-07-25 deploy-state | **SHIPPED** | `sourced` row B8 = DONE 2026-07-27, drift +1 s, `W32Time` Running/Automatic, stratum 4 | `→ row B8, DONE 2026-07-27` |
| 11 | "Open chips: task_37ee83e0 … task_1b85c64f" (10 ids) | ledger §0, 2026-07-17 FINAL STATE | **8 of 10 discharged; 6 of 10 have NO §4b row** | see the per-chip table below | full per-chip verdict list appended to the sentence |
| 12 | §8a's HIGH/MED audit-finding bullets (H6, H8, M1…M40, batch #128) | ledger §8a | **ALL PROMOTED** | `sourced` **H6** → row B4; **H8** + batch #128 + **M36–M40** → row E4 (re-mapped against HEAD 2026-08-02); M31 → §8g #655; M12/M35/M39 → #607; M20 → #649; M1/M16/M17/M18/M28 → #737/#739/#741. `computed` all six PRs MERGED | "read the ROW, never these bullets" header with the full mapping |
| 13 | bug queue: "Item 7's blocked tunes **remain blocked** by design (ledger G1 — earliest Tue 2026-07-28)" | `2026-07-25-weekly-bug-queue.md`, owner-decision pack | **SUPERSEDED** | `sourced` row **G1 CLOSED 2026-07-29** — quota met, all five resolved, **none by being applied** | `→ NO LONGER BLOCKED` + per-tune verdicts |
| 14 | bug queue: "**Deferred by construction** (not bugs — tunes blocked on data): T1, T7, T3, T5, T2" | `2026-07-25-weekly-bug-queue.md`, scheduler-binding sweep | **SUPERSEDED — and two are REJECTED BY MEASUREMENT** | `sourced` G1: T1 **REJECTED** (would-have-fired set 2W/9L, −121.95 pts; all six rails' sets lose, 5W/36L, −538.50 pts) · T7 **REJECTED** (`composite-055` worst of four books) · T3 → G13 · T5 → G12 · T2 → E8 | `→ SUPERSEDED` + the standing prior spelled out: **every measured loosening of the scalper entry gate has lost money** |

### The 10-chip prose list, resolved

| chip | §4b row? | verdict | evidence |
|---|---|---|---|
| task_37ee83e0 | **no** | **SHIPPED** | observability half = #895 (`ay_signal_eval_outcome_total`); the red-on-main test race it also named = #901 |
| task_79092520 (+ twin task_71a017e6) | **no** | **PARTLY SHIPPED, remainder PROMOTED** | config half = #959 (27 `-pe` YAMLs inverted, republished 2026-07-20); the FEATURE + bearish-numbers half is owner-parked with successor **§0a row AUD-PF02** (`2026-07-18-pf02-typed-scoring-bias-design.md:85` draws the boundary explicitly) |
| task_f624fca7 | **no** | **STILL OPEN** | see Part 1 #8 |
| task_ade97df8 | no | **SHIPPED** | #900 (also recorded in the 2026-07-17 weekend block) |
| task_8f139394 | **no** | **SHIPPED** | it is the `universe.bucket` live/sim divergence (identified inside the `task_2560273c` row) → #889 |
| task_019321d3 | **no** | **UNRECOVERABLE** | see Part 1 |
| task_2938fa28 | **no** | **UNRECOVERABLE** | see Part 1 |
| task_f10a03 | yes | PARTIAL (row is current) | — |
| task_a6c12601 | yes | CLOSED 2026-07-26 by the retirement task_3a928626 | — |
| task_1b85c64f | yes | DONE #1033 | — |

---

## Part 3 — The T-namespace, enumerated end to end

The recipe warns that T1…T23 "exist ONLY in the newest session-findings tune table unless someone promoted them to
§0 group G". **The good news is that somebody did.** 30 T-items exist (T1–T30, no gaps — T4 is real, it is the
`basis` dot). **29 carry a durable row.** Exactly one does not.

| T | subject | verdict | durable register |
|---|---|---|---|
| T1 | `relativeVolumeMultiplier` k 1.5→1.2 | **REJECTED BY MEASUREMENT** | G1 (CLOSED) — 6th consecutive no-pay; made final by the G11 decision |
| T2 | `iv_rank` dot NULL 100% | carried, not open | row **E8** (DONE — "re-open only if Sept IV-history data") |
| T3 | `iv_pair` gap 0.02→0.005 | knob **REJECTED**; operand re-scoped | G1 → successor row **G13** (DROP vs REDEFINE, design call open) |
| T4 | `basis` dot 0/359 support | **CLOSED — no action** | resolved as REGIME on 07-20 (505/748 = 67.5%); alive 23–90% since |
| T5 | `iv_abs_band` 10–12→10–13 | **SUPERSEDED** | G12 (the band was never the question) |
| T6 | `vwap` dot ≥15 bps | SHIPPED | bug-queue D2+D3 → #991 |
| T7 | composite threshold 0.600 | **REJECTED BY MEASUREMENT** | G1 (CLOSED) |
| T8 / T26 | entry-path emit latency | SHIPPED | row **G8** — #1176 |
| T9 | strategy-coverage watchdog | SHIPPED + **ARMED LIVE 2026-08-01** | row **G2** |
| **T10** | **17 stale OPEN paper positions** | **STILL OPEN** | ⚠️ **NONE — the only T with no row** (see Part 1 #4) |
| T11 | SENSEX volume-floor tag | SHIPPED | closed by B1 #980 |
| T12 | quote limiter / futures-OI cadence | SHIPPED | row **G5** — #1031 |
| T13 + T17 | dot-health canary false all-dead | SHIPPED | B4 #983 |
| T14 | sign-aware margin invariant | SHIPPED | row **G17** — #1171 (2026-08-01) |
| T15 | engine boot-line durability | SHIPPED + verified 07-31 | B7 #987 |
| T16 | relative-volume-floor tag | SHIPPED | B1 #980 |
| T18 | `breadth` threshold >32 | **CLOSED as regime** | 07-23; successor question is T30 |
| T19 | gap-backfill phantom candles | SHIPPED | B3 #982 |
| T20 | FINNIFTY thin-tape canary | SHIPPED | B6 #986 |
| T21 | premium exits on bracket-less YAMLs | SHIPPED | D1 #990 |
| T22 | `oi_spurt` floors (50,8)→(15,3) | SHIPPED | D2+D3 #991 |
| T23 | partial-bucket tolerance | CLOSED | row **G9** — #1180 |
| T24 | `volume` dot dead | SHIPPED | row **G6** — #1082 |
| T25 | scalper paper routing | SHIPPED | row **G7** — #1067 |
| T27 | relative-floor opening surge | CLOSED — **DO-NOT-ARM accepted** | row **G10** |
| T28 | frozen `atmIv` | DONE | row **G12** |
| T29 | scalper `time_stop` | DONE — owner: **KEEP the stop** | row **G11** |
| T30 | `breadth` live but never crossing | DONE | row **G16** — #1169 |

### Rejected tunes must not read as available work

**T1, T7, plus G13's and G10's own counterfactuals — four measured tests across three knobs, and every measured
loosening of the scalper entry gate LOST money.** T1's decisive numbers: the volume-floor would-have-fired set is
2W/9L, −121.95 pts, and the union of all six rails' would-have-fired sets is 5W/36L, −538.50 pts. T7's:
`composite-055` was the worst of four books at −₹321/close. Both are `sourced` from row G1's closing evidence. The
bug-queue bullet that still framed them as "deferred by construction … blocked on data" now carries that verdict.

### Two sibling-doc drifts corrected in the newest tune table

Both in `docs/signal-analysis/2026-07-31-session-findings.md` — the file location #5 of the recipe sends you to, so a
`PROPOSED` row there is read as open work:

- **T2** read `PROPOSED (carried)` while row **E8** is DONE. Row wins; pointer added.
- **T14** read `PROPOSED (carried)`; **G17 / #1171 shipped the next day (2026-08-01)**. Honest when written — but it
  is the newest table, so a fresh enumeration reads it as open. Pointer added.

---

## Part 4 — Weekly bug queue (`2026-07-25-weekly-bug-queue.md`)

Every B/S/D row's status was compared against its ledger mirror. **The table itself is clean** — B1–B11, S1–S3, D1–D8
all agree with their rows (B8's and S1/S2's earlier drift was already fixed by E2E-01 T4 on 2026-07-31). The drift is
entirely in the **closing bullets** under the owner-decision pack, which is exactly the shape this shard was looking
for:

- "Item 7's blocked tunes remain blocked by design" → G1 CLOSED (Part 2 #13). **Corrected.**
- "Deferred by construction … T1, T7, T3, T5, T2" → all five resolved (Part 2 #14). **Corrected.**
- **B11c / T10** — the pack's owner recommendation (b) was taken and #992 downgraded the *alert*; the **positions**
  were never addressed and there is no ledger mirror. **Annotated with the 2026-08-02 live count (still 17).**
- "NOTHING FROM THIS PACK IS OPEN as of 2026-07-27 pre-open" — **accurate and correctly dated**; left alone.
- The doc-hygiene note about stale `non-Var pathkey` boilerplate in the 07-21/22/23 midday gates — self-resolving,
  explicitly "no action beyond this note". Left alone.

---

## Applied changes (docs-only)

`docs/superpowers/plans/2026-07-02-remaining-items.md` — 14 pointers/corrections, each dated and attributed to this
shard: §0 prose (FE-redeploy PENDING, dead-anchor STILL-OPEN, the 10-chip list, the PARKED heading, the OPEN-CHIPS
heading, the heartbeat bullet, the PE-verdict bullet, the drill paragraph), §0a (the 4 HOLD PRs), §3 (the section
header), §6 (`optionanalytics`), §8a (the audit-findings mapping), §8b + §8d (bhavcopy ×2), §Manas + §8a + §8d (N+1
×3), §Manas (FIFO), §0 (B8 owner line).

`docs/signal-analysis/2026-07-25-weekly-bug-queue.md` — 3 (blocked-tunes bullet, deferred-by-construction bullet,
B11c/T10 row).

`docs/signal-analysis/2026-07-31-session-findings.md` — 2 (T2, T14 status pointers).

**No ledger rows were opened.** Per the brief, items I could not confirm are real work are listed as candidates for
the Architect, not promoted unilaterally. Four of the eight (#1, #2, #3, #4) are measured-real and I would promote
them; #5–#8 want a judgement call.

---

## Claims ledger

**computed** (measured this session): all four live DB reads (17 open paper positions by book/age; 0 PE vs 69 non-PE
signals since 07-21; 18 enabled+published `-pe`; nse_eod_bhavcopy 3,268/266/3,283) · both container env probes
(`ARTHA_HEARTBEAT_SESSION_URL` empty, `ARTHA_HEARTBEAT_URL` len 56) · the FE image build timestamp · all `gh pr view`
states (#935/#938/#930/#936/#894/#1036/#737/#739/#741/#655/#649/#607/#750/#751/#900/#889/#959/#1171) · the
`git log -S` provenance of task_019321d3 / task_2938fa28 / task_8f139394 / readSeriesBatched · the T1…T30 enumeration
(swept every `docs/signal-analysis/*.md` tune table, not just the newest).

**sourced** (read from code or a row, cited inline): `application.yml:87`, `EodBackfillController:38-46`,
`ActiveStrikeService.sentimentLevelPct:88`, `MinerviniBacktestService:353,508`, the batch-equality ITs, T9 design
§3.7, PF-02 design §"Distinction from the owner-parked task", rows B4/E4/E8/F2/F3/F10/G1–G17.

**recalled** — none load-bearing.

**assumed** — the ranking in Part 1 is my judgement of cost-if-missed, not a measurement.

---

## Open doubts

1. **T10's remedy is not mine to pick.** I measured 17 positions and proved there is no row. Whether the fix is
   "subscribe the holdings", "square them off", or "accept EOD-only and stop counting them as debt" is an owner call
   I deliberately did not make. The risk of promoting it as written is that a builder reads "17 stale positions" as
   a cleanup task and closes live money positions.
2. ~~**The PE count discrepancy is unexplained.**~~ **RESOLVED in-session — it was worth the one query, and the
   answer was not the obvious one.** The 27-vs-18 gap is **not** a publish gap (the failure mode that hid the 24
   SENSEX CE drafts for weeks): all 27 `-pe` are published, and the 9 that are not live are explicitly
   `enabled = false`. The split is **9 NIFTY live + 9 SENSEX live + 9 SENSEX disabled** — so the zero-fires reading
   stands and is *not* a single-index artifact. ⚠️ Residual, smaller: **why 9 SENSEX `-pe` are disabled while their
   9 twins run** is not explained by anything I read, and nobody appears to be tracking it. Not promoted — it may
   well be a deliberate owner A/B — but it is the kind of asymmetry that turns out to be an accident.
3. **The drill (Part 1 #7) may be genuinely unnecessary rather than merely undone.** I proved no drill is recorded
   and that F10 disclaims it; I did **not** re-derive whether G2's `StrategyCoverageWatchdog` incidentally covers the
   mid-session-drop class. If it does, the right action is a WON'T-DO, not a row.
4. **"No §0 row exists" is proven by absence.** For each Part 1 item I grepped the ledger for the item's distinctive
   tokens and read the plausible rows. That is strong but not exhaustive — a row phrased in vocabulary I did not
   guess would have been missed. The direction of that error is a false "invisible" claim, never a false "covered".
5. **§8a's M1/M16/M17/M18/M28 → #737/#739/#741 mapping is the ledger's own wave record**, and I verified those PRs
   are MERGED but did **not** verify that each M-number's substance is actually discharged by the PR the ledger
   attributes it to. The pointer says "shipped in the 2026-07-12 waves", which is as strong as the evidence.
6. **I did not touch §9 or the §0/§0a/§4b tables** — shards A and B own them. Where I read a row it was strictly as a
   cross-reference. If shard A or B changes a status I cited (G13 and G15 both have status cells that do not lead
   with a verdict and may move), the pointers I wrote would need re-checking.
7. **The two unrecoverable chip ids might be recoverable from the owner's session history**, which I cannot read. I
   recommend retirement; that recommendation is cheap to reverse and expensive to defer.
