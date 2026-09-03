# H26 U-A2 — Upstox master sync in shadow, build plan

**Status: PLAN, not started.** Written 2026-09-02 on the owner's ruling to plan now and build after
V060 is deployed and probed live (16:35 IST today). Companion to
`2026-08-26-h26-upstox-primary-plan.md`, which this refines; §1.2 and §2 there remain the authority
on *why*, this covers *how*.

**Tier: HOLD.** It writes to `marketdata.instruments`, the identity table every join goes through.
Ships dark. Earns a rationed Codex slot pre-merge.

---

## 0. STEP 0 — what the parent plan assumes, checked against the tree

Two of its premises are stale in the direction of *less* work, and one in the direction of *more*.

**(a) `UpstoxInstrumentDumpGateway` largely EXISTS.** The parent plan reads as if the fetch and parse
are greenfield. `UpstoxFnoMasterClient` already does
`GET https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz`, gunzips it, parses
it, caches with a refresh window and a retry backoff, and has a `warm()` entry point. **Reuse the
plumbing; do not rewrite it.**

**(b) But it retains the WRONG SHAPE, and this is the real work.** It keeps
`Map<FnoKey, FnoLeg>` — F&O only (`segmentFor` returns `null` for anything but `NFO`/`BFO`), holding
`(instrumentKey, lotSize)` keyed by tuple. U-A2 needs the **opposite direction** (Upstox row →
canonical Kite-grammar symbol) and needs **equities**, which are ISIN-addressed (`NSE_EQ|<ISIN>`) and
currently discarded entirely. So the unit is a second consumer of the same download, not a second
downloader.

**(c) `kite_last_seen_at` is currently FROZEN.** V060 backfilled it once and nothing maintains it.
U-A2 must wire the write into the Kite sync upsert **before** any tombstone rule reads it, or the
scoping reads a value that stopped advancing on 2026-09-02.

**(d) The importer placeholders are a third provenance class, not noise.** V060's census found the
metadata-empty importer rows are the majority of the table, `is_active = false`, and carry **no**
Kite provenance. The parent plan's tombstone rule is stated as "Kite rows vs Upstox rows"; there is a
third bucket that must be tombstoned by **neither**. Getting this wrong reactivates or deactivates
~half the table.

---

## 1. Units

| Unit | Verify check (red→green) | Ships dark? |
|---|---|---|
| ✅ **A2-1** Wire `kite_last_seen_at = now()` into the Kite sync upsert — **MERGED 2026-09-03 [#1572](https://github.com/prashantm912/artha-yantra-2/pull/1572) @ `1eafd378`**, NOT YET DEPLOYED | a sync run advances it for asserted rows and leaves importer placeholders NULL | yes (column unread) |
| **A2-2** Full-master retention: parse equities + F&O into a canonical view | WireMock fixture → expected `(exchange, tradingsymbol, upstox_key)` triples | yes |
| **A2-3** Grammar synthesis + **shadow diff** | `ay_instrument_master_synth_mismatch_total` = 0 over ≥5 sessions incl. one weekly expiry | yes — writes nothing authoritative |
| **A2-4** Surrogate tokens for Kite-tokenless rows + Kite-wire boundary refusal | a high-bit token at any Kite boundary is REFUSED and COUNTED | yes |
| **A2-5** Per-source tombstone scoping | three-bucket table test: Kite-only, Upstox-only, importer | yes |

**A2-1 before everything.** It is two lines and it is the input every later rule reads.

✅ **A2-1 DONE — [#1572](https://github.com/prashantm912/artha-yantra-2/pull/1572) @ `1eafd378`, merged 2026-09-03.** It really was two lines, and both matter:

- Set on **BOTH** branches of `InstrumentRepository.upsertFromStaging`. INSERT-only is the plausible wrong version and it looks finished — every row that already existed keeps its old value forever however many times Kite re-asserts it, so the column reads *"Kite has not seen this lately"* for precisely the rows Kite publishes most reliably. Red-proved.
- `upsertSyntheticCont` left untouched, and pinned by its own test because it is a different production method: `SYN-CONT` rows are tokenless by design and are **ours, not Kite's**, which is why V060's backfill excluded them.

⚠️ **NOT YET LIVE, and its verification is NOT the deploy.** The column is only written by the instrument sync, which runs **08:30 IST**, so the first real proof is the morning after the deploy — not the deploy itself. Check then: `kite_last_seen_at` advanced for Kite-asserted rows, still NULL for the 6 `SYN-CONT` rows and for importer placeholders.

⚠️ **What review verified so a later unit need not re-derive it** (`timescale-domain-reviewer`, 2026-09-03): `now()` is `transaction_timestamp()`, so one dump yields ONE identical stamp across all its rows — which is what "this dump asserted these rows" should mean, and is why `clock_timestamp()` would be wrong. Leaving `tombstoneVanished` untouched is also correct rather than an omission: *"Kite last asserted this at T"* stays a true statement about the past after a row vanishes, and clearing it would destroy exactly the input **A2-5**'s per-source tombstone scoping needs. The `instruments_staging` V002-snapshot hazard is **inert** here — the upsert writes a literal `now()` and never selects the column (and V060 added it to staging regardless).

---

## 1a. ⚠️ SUPERSEDED SAME DAY — the grammar-synthesis premise does not hold

Open question 2 below was measured before any code was written, and the answer changes this plan's shape. Full receipt: `docs/signal-analysis/2026-09-02-h26-ua2-identity-join-measurement.md`.

**`exchange_token` joins 100.00% on BOTH halves** — 9,694/9,694 NSE_EQ and 31,836/31,836 NSE_FO. **Nothing needs to synthesise a Kite tradingsymbol.**

For equities the symbol difference is entirely Kite's series-suffix convention (`-SG`, `-ST`, `-BE`), and the rule is exact — bare for EQ, else `<symbol>-<instrument_type>` — at **100.00%, zero unmatched**. That also generalises [[H29]]/[[H36]]: the `-BE` twin is one instance of a convention spanning SG, N0, SM, BE, GS and ST.

`trading_symbol` and `isin` are **already in `UpstoxInstrumentMaster` and parsed on every load**, so A2-2 is a retention change, not a new parser.

**What this does to the units below:** A2-3 stops being "validate a risky synthesis" and becomes "confirm a measured join stays at 100% across sessions and an expiry roll". Smaller and much safer — but **not deleted**, because a join that is perfect today is exactly what an expiry roll or a rename could degrade, and that is what a soak is for. Everything in §2 about counters, durability and the weekly expiry still applies to the new form.

⚠️ **Do NOT read this as "the risk is gone".** Three things are unmeasured and each could restore it: BSE entirely (and BSE is where the BE rule already does not apply), the reverse direction (rows of ours with no Upstox counterpart, which is what tombstone scoping turns on), and `exchange_token` UNIQUENESS — 30 active NSE tokens map to more than one row, so the join key needs a tie-break rule before it can be trusted as identity.

## 2. The shadow diff — the crux, and what would make it worthless

The parent plan's verify is `mismatch_total = 0 across ≥5 sessions`. That is necessary and **not
sufficient**, and the failure modes are ones this repo has already been bitten by:

- ⚠️ **A counter at zero is indistinguishable from a diff that never ran.** The mismatch counter must
  be paired with a **`compared_total`**, and the soak judged on both. "Zero mismatches over five
  sessions" with `compared_total = 0` is the exact success-shaped-nothing this platform has recorded
  repeatedly.
- ⚠️ **Both counters are process-lifetime.** A restart mid-soak resets them, and the box restarts
  routinely. The soak must read a **durable** per-session row, not a Micrometer counter — the lesson
  V061 was just built on, and the one that made the H44 weekly arming report unable to do its job.
- ⚠️ **The soak must include a weekly expiry ON PURPOSE.** Compressed weekly-option symbols are the
  grammar most likely to be synthesised wrong, and they only exist near expiry. Five quiet sessions
  that skip one prove the easy case.
- ⚠️ **A wrong symbol creates a DUPLICATE ROW, not an error** — so the diff must compare against the
  Kite master's actual symbol set, not merely check that synthesis produced *something* well-formed.

**Also detect one-key-to-many-CANONICAL mappings here.** This is where the U-A1 review routed the
check that a UNIQUE index could not safely carry: an exchange rename legitimately leaves two rows
sharing one Upstox key (ISIN-addressed, `TATAMOTORS→TMPV` keeps its ISIN), so the constraint would
reject correct data ~59 times a year. A diff CAN tell a rename lineage from a synthesis bug, because
it sees both rows and their `first_seen_at`. **A one-key-to-many finding is a WARNING to inspect, not
an automatic failure.**

---

## 3. Per-source tombstoning — the three-bucket rule

Today `tombstoneVanished()` deactivates any active row in a covered exchange that is absent from
staging. With two writers that is unsafe in both directions.

| bucket | identified by | Kite sync may tombstone | Upstox sync may tombstone |
|---|---|---|---|
| Kite-asserted | `kite_last_seen_at IS NOT NULL` | **yes** | no |
| Upstox-asserted | `upstox_last_seen_at IS NOT NULL` | no | **yes** | 
| importer placeholder | both NULL, metadata-empty | **no** | **no** |
| `SYN-CONT` synthetic | `segment = 'SYN-CONT'` | no (already exempt) | no |

⚠️ **A row can be BOTH** once Upstox is authoritative. The rule is per-source, so a row asserted by
both is deactivated only when **both** stop asserting it — which is the correct behaviour and is not
what a naive `AND`/`OR` on two columns gives you. Write the truth table into the test.

⚠️ **The `-BE` twins are the canary.** If tombstone scoping is wrong, the first Upstox sync
deactivates every Kite-only row, and H29/H36 reignite at scale. A test must assert twin-count
stability across a simulated first Upstox-authoritative sync.

---

## 4. Surrogate tokens

Deterministic hash of `(exchange, tradingsymbol)` with the high bit set, minted **only** for rows with
no Kite token, collision-checked at sync time.

⚠️ **The boundary guard is the load-bearing half, not the minting.** Every Kite wire boundary must
REFUSE and COUNT a high-bit token (`ay_kite_surrogate_token_refused_total`). A surrogate silently
reaching the Kite wire is exactly the H29-class fail-soft this is meant to make loud.

⚠️ **Type check first:** `InstrumentRecord.instrumentToken` is a primitive `long`, and
`InstrumentRepository` binds it with `setLong`. Confirm the surrogate path cannot flow into that
record before assuming a null-check suffices anywhere.

---

## 5. Open questions the Architect must settle BEFORE briefing a builder

1. **Where does the shadow diff's durable row live?** A new table, or a new `ingest_runs` source?
   `ingest_runs` is a job ledger and `rows_written` would have to be overloaded to carry
   `compared`/`mismatched`, which reads wrong later. Leaning: a small dedicated table, mirroring V061.
2. **Does the equity half need synthesis at all?** Upstox equity keys are ISIN-addressed and the Kite
   symbol is not derivable from an ISIN without a mapping. If the join is `ISIN → existing row`, there
   is no grammar risk on equities and the shadow soak is really an F&O soak. **This materially
   changes the size of A2-3 and should be settled first.**
3. **What kills this unit?** The parent plan's kill criterion covers the rate projection, not
   synthesis. State up front what mismatch rate at the end of the soak means "stop", so the decision
   is not taken while looking at the number.

---

## 5a. The open questions, SETTLED — 2026-09-02

The plan required these be settled by the Architect before any builder is briefed. All three are
answered; Q2 was answered by measurement (§1a) and changed the unit's shape.

### Q1 — where the soak's durable row lives: **a small dedicated table (V062), not `ingest_runs`**

`ingest_runs` is a JOB ledger: it answers "did this run, and did it throw". The soak needs to record
an OBSERVATION — compared, matched, mismatched, per segment — and the only field available to carry
it is `rows_written`, a single integer. Overloading that would mean a row saying `rows_written = 3`
that means "3 mismatches", which reads as "wrote 3 rows" to every existing consumer, including the
ingest-health board and `IngestCoverageCanary`.

⚠️ **The deciding argument is not tidiness, it is that the two questions fail differently.** A soak
pass that RAN but compared nothing is a success in the job ledger and a total failure as evidence —
which is exactly the `compared_total` trap in §2. Keeping them in one row makes that failure
invisible; keeping them separate makes it a column you must look at.

**Still register a `SOURCE_INSTRUMENT_SHADOW_DIFF` in the ledger too**, for the run/failure
lifecycle only. `IngestCoverageCanary.EXPECTED` is an explicit list, so adding a source does NOT
auto-enrol it in the daily REQUIRE matrix and cannot false-RED the 08:45 canary — verified while
building NEW-13. Do not add it to `EXPECTED` until the soak has passed once, mirroring the
deliberate deferral `MarketContextEodJob` already documents.

Shape mirrors V061: one row per session per segment, with `compared`, `matched`, `mismatched`, and
the failing examples capped. **Never a Micrometer counter as the record** — same reasoning as V061
and the H44 weekly report.

### Q3 — what kills this unit, stated as a number BEFORE the soak

Written now, on purpose, so the decision is not taken while looking at the result.

**Baseline, `computed` 2026-09-02:** segment-scoped `(segment, exchange_token)` join coverage is
**100.00%** on `NSE_EQ` (9,694/9,694) and `NSE_FO` (31,836/31,836).

| observation | verdict |
|---|---|
| coverage stays 100.00% on NSE_EQ and NSE_FO for ≥5 sessions incl. one weekly expiry | **PROCEED to A2-4/A2-5** |
| any miss whose cause is identified as a segmentation difference or a tokenless row of ours | **not a failure** — both are already-characterised classes (§1a, and the BSE/BFO measurement) |
| any UNEXPLAINED miss, even one | **STOP.** Do not proceed, do not widen the tolerance. One unexplained identity miss is the whole risk of this item |
| `compared` = 0 on any session | **the soak did not run that session** — it does not count toward the five, and it is not a pass |
| the 402 iNAV rows change count without a listing event | **STOP** — the tombstone rule is wrong |

⚠️ **"Explained" means identified BEFORE the verdict, against a named class.** Explaining a miss
after seeing it, by inventing a class that fits it, is how a kill criterion becomes decorative. If a
new class is genuinely discovered, that is a finding to record and re-baseline from — not a reason to
call the current soak passed.

⚠️ **The soak cannot be shortened by the 100% baseline.** A join that is perfect today is precisely
what an expiry roll or a rename would degrade, and neither occurred during the measurement. The five
sessions and the weekly expiry are load-bearing for that reason alone.

## 6. What this plan does NOT do

No cutover, no flag flip, nothing authoritative. Every unit above ships dark, and the point of no
return remains inside **D**, as the parent plan says. Nothing here shortens the soak.
