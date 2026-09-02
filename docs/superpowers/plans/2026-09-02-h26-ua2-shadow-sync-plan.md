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
| **A2-1** Wire `kite_last_seen_at = now()` into the Kite sync upsert | a sync run advances it for asserted rows and leaves importer placeholders NULL | yes (column unread) |
| **A2-2** Full-master retention: parse equities + F&O into a canonical view | WireMock fixture → expected `(exchange, tradingsymbol, upstox_key)` triples | yes |
| **A2-3** Grammar synthesis + **shadow diff** | `ay_instrument_master_synth_mismatch_total` = 0 over ≥5 sessions incl. one weekly expiry | yes — writes nothing authoritative |
| **A2-4** Surrogate tokens for Kite-tokenless rows + Kite-wire boundary refusal | a high-bit token at any Kite boundary is REFUSED and COUNTED | yes |
| **A2-5** Per-source tombstone scoping | three-bucket table test: Kite-only, Upstox-only, importer | yes |

**A2-1 before everything.** It is two lines and it is the input every later rule reads.

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

## 6. What this plan does NOT do

No cutover, no flag flip, nothing authoritative. Every unit above ships dark, and the point of no
return remains inside **D**, as the parent plan says. Nothing here shortens the soak.
