> **ARCHIVED 2026-07-02 — DONE #371.** `ScalperManualChecks.CHECKS` 7→16 (the 9 S21–S24 manual-only gaps).

# Follow-up 1 — expand ScalperManualChecks with the missing manual-only rules + wire to the signal card

> Status: PLAN (implementation-ready). Author date: 2026-06-27.
> Service: `services/strategy-signal-service` (the index-option scalper).
> Source of work: `docs/strategy-audit/session-additions-and-manual-coverage.md` (checklist-coverage
> table, lines 61–88 + "Not automated (gaps)" 90–124) and `docs/strategy-audit/gates-strike-sr-fiidii.md`
> (§4.13 rows, lines 35–38). README actionable flags: `docs/strategy-audit/README.md:488` ("At least 8
> S21–S24 manual-only inputs have NO `ScalperManualChecks` item — add them") and `:521` (FII/DII
> participant-wise OI — fetched but dead-wired, no checklist item).

---

## 1. Goal & scope

### Goal
The scalper's owner-facing manual-verification checklist currently ships **exactly 7 generic
discretionary items** (`news_clear`, `level_respected`, `not_parabolic`, `regime_ok`, `vix_normal`,
`global_cues_ok`, `clean_setup`). The Session-21..24 audit found that **at least 8 distinct manual-only
inputs have no on-card reminder** (audit verdict, `session-additions-and-manual-coverage.md:84-88`). This
plan **adds the missing manual-only checks to `ScalperManualChecks.CHECKS`** so the trader gets an on-card
reminder for every documented discretionary rule that the deterministic engine does not (and in several
cases cannot) automate, and **verifies the existing dynamic plumbing carries them end-to-end unchanged**.

### What changes
1. **`ScalperManualChecks.CHECKS`** gains N new `Check(...)` records (the manual-only audit gaps). This is
   the single backend source of truth; everything downstream is already count-agnostic.
2. **`ScalperManualChecksTest`** — the two hardcoded `hasSize(7)` assertions bump to the new count, plus a
   small structural assertion that the newly-added keys are present.
3. **Frontend** — NO production code change is required (render + gate are fully dynamic). The plan adds
   *test* coverage only where a regression risk appears (see §5), and an **optional** layout polish item is
   recorded as an Open Point, not built.
4. **Docs** — flip the two README actionable checkboxes (`README.md:488`, `:521`) and append a one-line
   "covered by manual check" note to the relevant audit rows. (Doc-only, no behaviour.)

### What does NOT change (explicit non-goals)
- **No new automation.** This follow-up adds *manual reminders*, not gates/dots. Wiring India VIX into
  `MarketOiClient.macro()`, consuming the dead-wired `fiiLongPct` into a confluence dot, building a
  pre-open feed, a Sensex-vs-Nifty participation comparator, a positional-OI window, an expiry-IV-crush
  model, the combined-premium-VWAP straddle seam, or a prev-day-VWAP weighting switch are all **separate
  follow-ups** (they need new data wiring / new dots and would touch emitted signals). They are explicitly
  out of scope here and listed as downstream items in §7.
- **No schema / migration / DTO / contract change** (see §6 — none, with reasons).
- **No change to emitted signals.** Adding a `manual_checks` array element is a pure side-channel
  enrichment on the V009 `scalper_detail` JSONB; it never touches the frozen C-2.6 `score_breakdown`, the
  composite, the threshold, entry/stop/target, or the directional side-channel shape. Golden/parity stay
  byte-identical with **no opt-in flag needed** — see §5 for the parity argument.
- **No change to the gate semantics.** The gate stays SOFT (all-ticked OR the single
  "Confirm despite unchecked" override), client-local, never POSTed. Adding more checks simply raises the
  `total` the owner must tick (or override).

---

## 2. Background — current code (verified file:line)

Every line below was opened and confirmed against the working tree on 2026-06-27.

### Definition (the single source of truth)
`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/scalper/ScalperManualChecks.java`
- `record Check(String key, String label, String docRef, String assist)` — **line 21**.
- `public static final List<Check> CHECKS = List.of(...)` — **lines 24–60**, exactly 7 items:
  `news_clear` (docRef `2.13`), `level_respected` (`4.11`), `not_parabolic` (`3.1`), `regime_ok` (`3.10`),
  `vix_normal` (`4.5`), `global_cues_ok` (`4.7`), `clean_setup` (`3.1`).
- `appendTo(ObjectNode root)` — **lines 65–74**: `root.putArray("manual_checks")`, then per `Check` puts
  `key`/`label`/`doc_ref`/`assist`. Iterates `CHECKS` — **auto-serializes any item added to the list**.
- Class javadoc (lines 7–17) states: strings are ASCII-only (PS5.1/checkstyle trap), the FE prefixes `§`
  onto `doc_ref`, doc refs point at
  `strategy-documents/options-scalper-siva/Options_Scalper_Siva_Consolidated_Strategy.md`.
  **CORRECTION (audit pass 1): the javadoc's "`§` prefix" claim is STALE.** The live FE renders
  `{c.doc_ref}` raw with NO `§` prepended (`ManualVerifyChecklist.tsx:111-115` — confirmed; the FE spec
  fixture bakes the glyph into the string literally, e.g. `'CHEAT_SHEET §3'`). So a new `docRef` like
  `"4.17.4"` displays bare as `4.17.4`, exactly like the existing `2.13`/`4.11`. This is cosmetic only
  (no build/parity impact) but the plan must not rely on a `§` being added — see the §4 and OP-5
  corrections below.

### Stamp (engine attaches the checklist to a scalper signal)
`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/signals/SignalEngine.java`
- Import — **line 23** (`import ...scalper.ScalperManualChecks;`).
- `scalperDetailJson(ScalperConfluenceGate.Decision d, ScalperConfig cfg, String tradeableExchange)` —
  **lines 667–706**: builds the `scalper_detail` ObjectNode (side/underlying/expiry/tradeable/strike/
  option_ltp/iv/delta/confluence_aggregate/`dots[]`; `legs[]` only when `d.neutral()`), then calls
  `ScalperManualChecks.appendTo(root)` — **line 704** — and returns `root.toString()`.
- Stamp call site — **lines 618–622**, inside `if (decision != null)` (i.e. scalper signals only):
  `signals.stampScalperDetail(id, exchange, decision.pick().candidate().tradingsymbol(),
  scalperDetailJson(decision, strategy.scalper(), exchange))`.

### Persist (V009 side-channel write/read)
`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/signals/SignalRepository.java`
- `stampScalperDetail(long id, String tradeableExchange, String tradeableTradingsymbol, String detailJson)`
  — **lines 184–190**: `UPDATE signals SET tradeable_exchange=?, tradeable_tradingsymbol=?,
  scalper_detail=?::jsonb WHERE id=?`. The whole detail (incl. `manual_checks`) is **one JSONB blob** — no
  per-check column.
- Read-back — `row(...)` maps `scalper_detail` via `nullableTree(rs.getString("scalper_detail"))` — **line
  172** (`nullableTree` at lines 200–203, tolerant of NULL for non-scalper signals).

### Migration (side-channel columns — already applied, frozen)
`deploy/flyway/strategy/V009__scalper_signal_detail.sql`
- **Lines 8–10**: `ALTER TABLE signals ADD COLUMN tradeable_exchange TEXT;` / `tradeable_tradingsymbol
  TEXT;` / `scalper_detail JSONB;`. `manual_checks` is a JSON **array inside** the `scalper_detail` JSONB —
  **not its own column**. The header comment (lines 1–7) is explicit that scalper keys must **not** go into
  the frozen C-2.6 `score_breakdown` (parity FAIL, same rule as `suggested_qty`).

### Wire (controller → DTO, untyped)
`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/signals/SignalsController.java`
- `dto(SignalRow row)` builds a `Map<String,Object>` (LinkedHashMap) — **lines 106–128**;
  `dto.put("scalperDetail", row.scalperDetail())` — **line 126**. The whole JSON (incl. `manual_checks`)
  flows to the FE untyped through Jackson. Because the return is a generic `Map`, **adding a check does not
  drift the springdoc `/v3/api-docs` snapshot** (CLAUDE.md: `Map<String,Object>` returns are not
  enumerated).

### Frontend types (generic — no change needed)
`frontend-react/src/api/signals.ts`
- `scalperDetail?: ScalperDetail | null` on the signal — **line 37**.
- `interface ManualCheck { key; label; doc_ref; assist }` — **lines 48–53**; the comment at **line 47**
  reads "rendered dynamically — do NOT hardcode the set".
- `interface ScalperDetail { ...; manual_checks: ManualCheck[] }` — **lines 56–68** (`manual_checks` at
  line 67).

### Frontend render + gate computation
`frontend-react/src/components/ManualVerifyChecklist.tsx`
- `const checks = detail.manual_checks` — **line 22**.
- `total = checks.length` — **line 40**; `tickedCount` — line 41; `allTicked = total>0 &&
  tickedCount===total` — **line 42**; `confirmed = allTicked || overridden` — **line 43**; pushed up via
  `onConfirmedChange` in a `useEffect` — **lines 45–47**.
- Dynamic check rows: `checks.map((c) => <li key={c.key}>...checkbox + label + assist + doc_ref...)` —
  **lines 99–119**.
- Soft-warning `role="alert"`: `{total - tickedCount} of {total} checks unconfirmed` — **lines 123–130**.
- Override checkbox "Confirm despite unchecked" — **lines 133–141**.
- Re-arm (clears ticks + override) keyed on `detail.tradeable` — **lines 31–38**.
- Ticks are **local React state**, never POSTed.

### Frontend gate mount points (two)
`frontend-react/src/pages/scalper/SignalTakeTicket.tsx` (the /scalper cockpit ticket)
- `const [confirmed, setConfirmed] = useState(false)` / `onConfirmedChange` cb — **lines 50–51**;
  `takeBlocked = scalperDetail != null && !confirmed` — **line 52**; `<ManualVerifyChecklist>` mount —
  **line 134**; Place button `disabled={place.isPending || !draft.qty || takeBlocked}` — **line 141**.

`frontend-react/src/pages/signals/SignalsPage.tsx` (the /signals detail aside)
- `const [confirmed, setConfirmed] = useState(false)` / cb — **lines 66–67**; `takeBlocked = scalperDetail
  != null && !confirmed` — **line 69**; `<ManualVerifyChecklist>` mount — **line 189**; Take button
  `disabled={take.isPending || takeBlocked}` — **line 196**.

### Tests
`services/strategy-signal-service/src/test/java/in/arthayantra/strategysignal/scalper/ScalperManualChecksTest.java`
- `canonicalListIsWellFormed`: `assertThat(checks).hasSize(7)` — **line 16**; unique keys (line 17); all
  four fields non-blank (lines 18–25).
- `appendToStampsAManualChecksArray`: `assertThat(arr).hasSize(7)` — **line 37**; first key `news_clear` —
  **line 43**.
- **These two `hasSize(7)` literals are the ONLY count-coupled assertions in the whole pipeline** — they
  must be bumped to the new count.

`frontend-react/src/components/ManualVerifyChecklist.spec.tsx` — Vitest/RTL, uses a **2-check fixture**
(count-agnostic); asserts dynamic render, the "N of N unconfirmed" warning, confirm-only-when-all-ticked,
the override path, and re-arm on `tradeable` change. **Not count-coupled.**

`frontend-react/e2e/scalper-checklist.spec.ts` — Playwright + axe; drives /scalper, walks the live feed to
a signal whose checklist renders, asserts the soft-gate (`role=alert` "unconfirmed" + Place disabled →
tick the single "Confirm despite unchecked" override → alert clears + Place enabled), plus a 480px mobile
render check. **Count-robust by design** (uses the single override checkbox).

---

## 3. The work items (from the audit)

The audit's "8 distinct manual rules with no checklist item" (verdict
`session-additions-and-manual-coverage.md:85-88`) plus the two §4.13 FII sub-reads from
`gates-strike-sr-fiidii.md`. Each item below becomes **one** `Check` record (key chosen to be stable +
snake_case; `docRef` is the bare consolidated-doc section string the FE displays verbatim — **no `§`
prefix** is added by the FE; see the §2/§4 audit-pass-1 corrections).

| # | Item (new check) | Proposed key | docRef | Audit doc-section ref |
|---|---|---|---|---|
| 1 | **FII futures Long/Short-ratio** (≈87–94% short = sell every level; ~50% crossover = short-covering trigger; importance FII>Pro>DII>Client; DII-buy-alone may not lift) | `fii_ls_ratio` | `4.17.4` | `session-additions-and-manual-coverage.md` table row line 55 + verdict 76,86-88; `gates-strike-sr-fiidii.md` §4.13 rows 35–38; `README.md:521` |
| 2 | **Index-constituent contribution** (top movers; sector sync; BankNifty top-3 ≈60%; crude→BankNifty adverse) | `constituent_contribution` | `4.14.4` | `session-additions-and-manual-coverage.md` table row 23; coverage line 75 (NO) |
| 3 | **Pre-open (9:00–9:07) positioning + advances/declines** for the morning bias | `pre_open_bias` | `4.14.5` | rows 26,46; coverage line 77 (NO) |
| 4 | **Sensex participation / volume** (skip thin Sensex, prefer Nifty; pick by nearer expiry / richer premium) | `sensex_participation` | `4.17.2` | row 51; coverage line 78 (NO) |
| 5 | **Intraday-vs-positional OI agreement + PCR progression ladder** (>50% call-vs-put on BOTH windows; PCR 1.2→1.5→2; ~5cr call vs 10–12cr put) | `oi_intraday_positional` | `4.17.3` | row 54; coverage line 79 (NO) |
| 6 | **Expiry-day / post-event IV crush awareness** (call IVs fall 2nd-half of expiry day; IV crashes post-event) | `iv_crush_awareness` | `4.17.5` | row 56; coverage line 80 (NO) |
| 7 | **Straddle combined-premium VWAP-break entry + one-leg management** (long when combined Call+Put closes above its own VWAP with volume; SL = straddle VWAP +10–15pt) | `straddle_vwap_entry` | `4.15.2` | rows 31,35; coverage line 81 (NO — "documented in YAML comments, not in the checklist") |
| 8 | **Time-of-day data weighting** (prev-day data + prev-day VWAP until 11 AM; intraday + current VWAP after) | `time_of_day_vwap` | `4.14.5` | row 25; coverage line 82 (NO) |
| 9 | **India VIX absolute regime bands + VIX-vs-price grid** (10–11 lower-bullish / 12–14 medium / 15–16 seller-favoured / 17+ higher; 4-cell VIX/price grid; vs prev-day close) — **beyond** the existing `vix_normal` spike item | `vix_regime_bands` | `4.14.1` | coverage line 69 ("vix_normal only covers abnormal spike, NOT the regime bands or the VIX/price grid"); `gates-strike-sr-fiidii.md` §4.12/§4.5 row 34 |

That is **9 new checks → CHECKS grows 7 → 16**.

### Scope decision recorded as Open Point (see §8, OP-1)
The verdict names **8** rules; this plan proposes **9** (it splits the audit's "VIX bands beyond the spike
item" out as its own check, item 9, because `vix_normal` is explicitly noted as covering only the spike).
Whether to ship all 9 — or trim items the owner deems redundant with an existing generic check (e.g. item 9
vs `vix_normal`, item 5 vs the automated OI dots) — is an owner judgement call. **Default: ship all 9** (a
reminder is cheap; the audit explicitly flags each as uncovered). The plan is structured so any item can be
dropped by deleting its `Check` and adjusting the count — no other change.

---

## 4. Per-item design

### Shared mechanics (applies to every item)
- **Where:** add the `Check` to `ScalperManualChecks.CHECKS` (`ScalperManualChecks.java:24-60`), appended
  **after** the existing 7 so the first-element invariant (`appendTo` first key `news_clear`, test line 43)
  is preserved and the existing items keep their order.
- **What auto-happens:** `appendTo()` (lines 65–74) iterates `CHECKS`, so each new item is serialized into
  the `manual_checks` JSON array with no further backend code.
- **Data flow (identical for every item):**
  `ScalperManualChecks.CHECKS` → `appendTo(root)` (`SignalEngine.scalperDetailJson`, line 704) →
  `root.toString()` → `SignalRepository.stampScalperDetail(... scalper_detail=?::jsonb ...)` (line 188) →
  read-back `nullableTree` (line 172) → `SignalsController.dto.put("scalperDetail", ...)` (line 126,
  untyped Map) → Jackson → FE `signals.ts` `ScalperDetail.manual_checks` (line 67) →
  `ManualVerifyChecklist` `checks.map` (lines 99–119) → counts into `total`/`confirmed` (lines 40–43) →
  `onConfirmedChange` → `takeBlocked` on /scalper (`SignalTakeTicket.tsx:52`) and /signals
  (`SignalsPage.tsx:69`) → Place/Take button `disabled`.
- **ASCII + checkstyle:** keep all four strings ASCII-only (no `≈`, `→`, `%`-glyph issues are fine but
  prefer plain words). Use `>=`, `vs`, `pt`, plain hyphens. This matches the existing file (the javadoc at
  line 15 calls out the PS5.1 UTF-8/checkstyle trap; the existing items already write "S/R", "2-3", etc.).
- **docRef format:** bare section number string (e.g. `"4.17.4"`), matching the existing items
  (`"2.13"`, `"4.11"`). **CORRECTION (audit pass 1): the FE does NOT prepend `§`** — `ManualVerifyChecklist.tsx:111-115`
  renders `{c.doc_ref}` verbatim, so the displayed text is `4.17.4` (NOT `§4.17.4`). The `signals.ts:47`
  comment only says "rendered dynamically — do NOT hardcode the set"; it does not mention a `§` prefix.
  This matches the existing 7 items (which also show bare numbers). No action needed — just do not write
  a leading `§` into the `docRef` string (that would double up if the javadoc claim were ever made true).
- **Label/assist length:** keep `label` one sentence (the owner-facing statement of the rule), `assist` one
  short actionable hint, mirroring the 7 existing items. Lines should pass the project checkstyle line
  length the same way the existing multi-line `new Check(...)` blocks do (each string on its own line).

The exact block to append to `CHECKS` (insert immediately before the closing `)` of `List.of(...)`, i.e.
after the `clean_setup` item at line 60, replacing the final `));` appropriately):

```java
          new Check(
              "fii_ls_ratio",
              "FII futures Long/Short ratio is not against the trade (heavy short = sell every"
                  + " level; a move toward 50 percent is a short-covering trigger).",
              "4.17.4",
              "Read the NSE FII index-futures Long/Short ratio; FII outweighs Pro, DII, Client."),
          new Check(
              "constituent_contribution",
              "Index direction is confirmed by its heaviest constituents and sector sync, not one"
                  + " stray stock.",
              "4.14.4",
              "Check the top movers and sector breadth (e.g. Bank top-3 dominate BankNifty)."),
          new Check(
              "pre_open_bias",
              "Pre-open positioning and advances/declines agree with the intended morning bias.",
              "4.14.5",
              "Read the 9:00-9:07 pre-open A/D and positioning before the first trade."),
          new Check(
              "sensex_participation",
              "Sensex has real participation; if thin, prefer Nifty (or pick the chain with nearer"
                  + " expiry / richer premium).",
              "4.17.2",
              "Compare Sensex vs Nifty option volume/OI; skip a thin Sensex day."),
          new Check(
              "oi_intraday_positional",
              "Intraday and positional OI agree (over 50 percent call-vs-put gap on BOTH), with the"
                  + " PCR progressing the right way.",
              "4.17.3",
              "Cross-check today-vs-(yesterday+today) OI and the PCR ladder (1.2 -> 1.5 -> 2)."),
          new Check(
              "iv_crush_awareness",
              "Aware of IV crush risk: IV falls in the second half of expiry day and right after a"
                  + " scheduled event.",
              "4.17.5",
              "On an expiry afternoon or post-event, expect call IVs to drop - size accordingly."),
          new Check(
              "straddle_vwap_entry",
              "For a straddle, entry is a combined Call+Put premium close above its own VWAP with"
                  + " volume; manage one leg, SL near straddle VWAP +10-15pt.",
              "4.15.2",
              "Time the entry off the combined-premium VWAP break, not the index chart."),
          new Check(
              "time_of_day_vwap",
              "Reference window matches the clock: prev-day data and prev-day VWAP until 11 AM,"
                  + " intraday and current VWAP after.",
              "4.14.5",
              "Before 11 AM weight prior-session levels; after 11 AM use the current-session VWAP."),
          new Check(
              "vix_regime_bands",
              "India VIX absolute band and the VIX-vs-price grid suit the trade (10-11 lower-bullish"
                  + " / 12-14 medium / 15-16 seller-favoured / 17+ higher).",
              "4.14.1",
              "Map India VIX to its band and compare to the prev-day close; ignore erratic intraday VIX.")
```

(Implementation note: the existing list ends `...skip it."));` at line 60 — change the final item's closing
`)` to `),` and append the nine blocks, ending the last one with `));`.)

---

#### Item 1 — `fii_ls_ratio` (§4.17.4)
- **File/target:** `ScalperManualChecks.CHECKS`. New `Check("fii_ls_ratio", ...)`.
- **Why a manual check and not a gate:** `fiiLongPct` *is* fetched into `Macro`
  (`MarketOiClient.java:375-383`, `:625-641`) but is **consumed by no dot/gate** (audit row 55; confirmed
  dead-wired in `gates-strike-sr-fiidii.md:35`). Turning it into a dot is a behaviour change to emitted
  signals (new confluence dot → different `confluence_aggregate`/dots[] → golden churn) and is therefore a
  **separate automation follow-up** (§7), not this manual-reminder follow-up.
- **Data flow:** shared mechanics above. No consumer of the dead-wired value is added here.
- **README:** `README.md:521` is the FII/DII **participant-wise OI** actionable (§4.13/§4.17.4).
  **CORRECTION (audit pass 1): do NOT fully check it off as "covered".** `fii_ls_ratio` covers the
  §4.17.4 FII index-futures **Long/Short ratio** (the one value actually fetched into `Macro`), but it
  does NOT cover the richer §4.13 4-participant change-in-OI matrix (the LB/SC/LU/SB classifier across
  FII/Pro/DII/Client × index/stock futures+calls+puts — `gates-strike-sr-fiidii.md:35-38`). Append
  "(L/S ratio now covered by `fii_ls_ratio` manual check; the §4.13 participant change-in-OI matrix
  remains a separate automation follow-up)" and leave the box reflecting partial coverage (or split it
  into the L/S-ratio sub-line vs the participant-matrix sub-line). Owner to decide the exact checkbox
  treatment — see OP-4.

#### Item 2 — `constituent_contribution` (§4.14.4)
- No data source exists (audit row 23 — the only hit is a YAML comment). Purely manual; this is the only
  way to surface it. Shared mechanics.

#### Item 3 — `pre_open_bias` (§4.14.5)
- No pre-open snapshot feed (audit rows 26,46; opening-tick path is 09:15–09:30 via
  `ScalperConfig.OPENING_FROM/TO`, not 9:00–9:07). Manual reminder only.

#### Item 4 — `sensex_participation` (§4.17.2)
- No runtime Sensex-vs-Nifty comparator (audit row 51; the niftyoi/sensexoi split is a static A/B in
  `ScalperStrategySeeder.java:38-73`). Manual reminder only.

#### Item 5 — `oi_intraday_positional` (§4.17.3)
- Only a single intraday imbalance read exists (`callPutDeltaFilter`/`imbalancePct`); no positional
  two-window agreement, no PCR ladder (audit row 54). Manual reminder; the positional series is derivable
  but unbuilt (§7).

#### Item 6 — `iv_crush_awareness` (§4.17.5)
- No time-of-day/expiry IV-decay logic (audit row 56). Manual reminder. README itself marks the *awareness*
  as not-automatable (the expiry/IV signal is derivable but unbuilt).

#### Item 7 — `straddle_vwap_entry` (§4.15.2)
- Explicitly LIVE-deferred — the combined-premium-vs-VWAP entry is "LIVE market-data the deterministic seam
  cannot recompute" (`ScalperConfluenceGate.java:128-131`, `scalp-straddle-nifty.yaml:24-31,86`); v1 emits a
  two-leg BUY draft only (audit row 35). Documented today only in YAML comments — the checklist makes it an
  on-card reminder. Short side is SPAN-deferred (#47).

#### Item 8 — `time_of_day_vwap` (§4.14.5)
- The 11:00–13:00 midday block is encoded (`ScalperGates.java:23-24,37`) but there is no "prev-day vs
  current VWAP by 11 AM" weighting switch; a single current-bar VWAP is always used
  (`ConnectTheDotsScorer.java:71-74`) (audit row 25). Manual reminder; prev-day VWAP series is computable
  (§7).

#### Item 9 — `vix_regime_bands` (§4.14.1)
- `MarketOiClient.macro()` hard-codes `null` VIX level + direction (`MarketOiClient.java:394-397`); the
  existing `vix_normal` item covers only the abnormal spike (coverage line 69), **not** the absolute bands
  or the VIX/price grid. India VIX candles exist (`ConnectingDotsService.vixByBucket`), so wiring the
  *direction dot* is a future automation (§7) — but the absolute-band judgement + "ignore erratic intraday
  VIX" remain a manual read, so a manual check is warranted regardless. Shared mechanics.
- **Coexistence with `vix_normal`:** both ship. `vix_normal` (docRef `4.5`, "abnormal spike") and
  `vix_regime_bands` (docRef `4.14.1`, "absolute band + grid") are distinct reads per the audit; keeping
  both is intentional (see OP-1 for the trim alternative).

---

## 5. Tests

### 5.1 Backend unit (REQUIRED edit)
`services/strategy-signal-service/src/test/java/in/arthayantra/strategysignal/scalper/ScalperManualChecksTest.java`
- **`canonicalListIsWellFormed`** — bump `assertThat(checks).hasSize(7)` (line 16) to `hasSize(16)`. The
  unique-keys + non-blank-fields assertions (lines 17–25) already cover the new items (they iterate the
  whole list) — no edit there.
- **`appendToStampsAManualChecksArray`** — bump `assertThat(arr).hasSize(7)` (line 37) to `hasSize(16)`.
  Keep the first-key `news_clear` assertion (line 43) — the new items are appended, so element 0 is
  unchanged.
- **Add one new test** `newManualOnlyChecksArePresent` asserting the nine new keys exist and are unique
  alongside the originals (guards against an accidental key collision / typo):
  ```java
  @Test
  void newManualOnlyChecksArePresent() {
    var keys = ScalperManualChecks.CHECKS.stream().map(ScalperManualChecks.Check::key).toList();
    assertThat(keys)
        .contains(
            "fii_ls_ratio", "constituent_contribution", "pre_open_bias", "sensex_participation",
            "oi_intraday_positional", "iv_crush_awareness", "straddle_vwap_entry",
            "time_of_day_vwap", "vix_regime_bands");
  }
  ```
- (Optional, cheap) add an assertion that every `docRef` is a dotted-numeric string so a future typo like
  `"4..17"` is caught; not strictly required.

### 5.2 Frontend unit (NO edit required — verify, don't change)
`frontend-react/src/components/ManualVerifyChecklist.spec.tsx` uses a 2-check fixture and is count-agnostic.
Adding real backend checks does **not** touch it. **Do not** change the fixture to 16 — the spec's purpose
is to prove the component renders *whatever* set it is handed; keeping the small fixture is correct.
Re-run it (`npm run test:ci`) to confirm green; no source change.

### 5.3 E2E (NO edit required — verify)
`frontend-react/e2e/scalper-checklist.spec.ts` is count-robust by design (asserts the single
"Confirm despite unchecked" override path, not "tick all N"). Adding checks does not change its assertions.
Re-run against a running mock stack to confirm the override still clears the soft-gate with 16 checks
present. No source change.

### 5.4 Parity / golden strategy — why NO opt-in flag or new golden variant is needed
CLAUDE.md's parity-safe-additive convention requires that any change that *can alter emitted signals* be
gated behind a default-off flag with a new golden variant. **This change cannot alter emitted signals**, so
that machinery does not apply. The argument, checked against the code:
1. The golden writer compares **signal bytes** (`GoldenSignalsJson.write()`, frozen) and **trade records**.
   The `manual_checks` array is written onto the V009 `scalper_detail` JSONB by
   `SignalEngine.scalperDetailJson()` (line 704) — which is the **live emit path** side-channel, **not** the
   `score_breakdown` the golden serializes. The migration header (`V009...sql:1-7`) and `suggested_qty`
   precedent confirm scalper keys live outside the frozen breakdown.
2. The deterministic replay used by `GoldenDeterminismTest` / `BacktestParityTest` does not stamp the V009
   `scalper_detail` (the stamp is on the live `emit` path, guarded by `decision != null`; replay computes
   the same `score_breakdown` bytes regardless). `manual_checks` is a **static constant list** — it does
   not depend on any per-run value, so even where it *is* stamped it is identical across both deterministic
   replays.
3. Therefore the composite, threshold, entry/stop/target, and the directional `scalper_detail` shape are
   all byte-identical before/after. The existing `scalperDetailJson` already proved this pattern: it stamps
   the `legs[]` array (lines 689–703) and `manual_checks` (line 704) without a golden variant.

**Verification step (do this, don't assume):** after the change, run
`mvnw -pl services/strategy-signal-service -am verify` and confirm `GoldenDeterminismTest` +
`BacktestParityTest` pass with **no `-Dgolden.regen`** and **no golden-file diff**. If any golden file
changes, STOP — that means `manual_checks` leaked into a serialized path and the assumption is wrong; treat
it as a parity FAIL and re-evaluate (it should not, per the above).

### 5.5 Verify trio (CLAUDE.md)
- Backend: `mvnw -pl services/strategy-signal-service -am verify` (full reactor + `-am`, never a bare
  `-pl` on the leaf — CLAUDE.md build rule). JaCoCo ≥60% line is unaffected (we add list entries + one
  test).
- Frontend (PowerShell `Push-Location frontend-react`): `npm run lint` + `npm run test:ci` + `npm run
  build`. No FE source changed, so this is a regression check.
- E2E: `cd e2e && E2E_OWNER_PASSWORD=<owner pw> npx playwright test scalper-checklist` against a running
  mock stack.

---

## 6. Migrations / schema / contract impact

**None. Explicitly, with reasons:**

- **Flyway / schema:** NONE. `manual_checks` is a JSON array **inside** the existing `scalper_detail` JSONB
  column added by V009 (`V009...sql:8-10`). Adding an array element writes one more object into the same
  blob — no new column, no `ALTER TABLE`, no new migration. (And per CLAUDE.md, applied migrations are
  checksum-locked — editing V009 would fail `flyway validate`; we do not touch it.)
- **DTO / TS types:** NONE. The controller surfaces `scalperDetail` via an untyped `Map.put`
  (`SignalsController.java:126`); the FE `ManualCheck` interface (`signals.ts:48-53`) is generic
  (`key`/`label`/`doc_ref`/`assist`) and already typed as `ManualCheck[]`. A new array element fits the
  existing type.
- **springdoc contract (`ContractCaptureTest`):** NO re-capture. Per CLAUDE.md, generic `Map<String,Object>`
  returns are **not enumerated** in `/v3/api-docs`; adding response *keys* (or, here, array *elements*
  inside an already-untyped node) does not drift the snapshot. No `-Dcontracts.capture=true`, no
  `openapi-typescript` regen, no `contracts/gen/*.d.ts` change. (ci-contracts only fails on breaking spec
  diffs / new query params / new `@*Mapping` paths — none here.)
- **Redis / WS / notifier:** NONE. The live `SignalEmitted.ScalpDetail` side-channel
  (`SignalEngine.java:644-657`) carries underlying/side/strike/symbol/ltp/aggregate — **not**
  `manual_checks`. The checklist is read by the FE from the GET `/signals/{id}` DTO, not the WS frame. No
  change to the event payload.

---

## 7. Rollout, risk, backout

### 7.1 PR breakdown
Single small PR — `feat(strategy-signal): add 9 manual-only checks to ScalperManualChecks`. Scope:
1. `ScalperManualChecks.java` — append 9 `Check` records (CHECKS 7 → 16).
2. `ScalperManualChecksTest.java` — `hasSize(7)` → `hasSize(16)` (×2) + new `newManualOnlyChecksArePresent`
   test.
3. `docs/strategy-audit/README.md` — `:488` ("At least 8 ... manual-only inputs have NO checklist item")
   can be checked off (this PR adds all 8+). `:521` (FII participant-wise OI) is only PARTIALLY satisfied
   — see the §4 item-1 correction: the L/S ratio is now covered, the §4.13 participant change-in-OI matrix
   is not. Note that nuance rather than blanket-checking `:521`. Append a one-line "covered by manual
   check" note to the relevant audit rows in `session-additions-and-manual-coverage.md` (doc-only).

Branch: `feat/expand-scalper-manual-checks` (trunk-based, Conventional Commits, squash-merge, scope =
`strategy-signal`). One commit. No `main` push.

**Downstream automation follow-ups (NOT in this PR — recorded so they are not lost):** wire India VIX into
`MarketOiClient.macro()` + a VIX-direction dot (item 9 automation); consume the dead-wired `fiiLongPct` into
an FII dot + the §4.13 LB/SC/LU/SB change-in-OI classifier (item 1 automation); pre-open snapshot feed
(item 3); Sensex-vs-Nifty participation comparator (item 4); positional-OI two-window + PCR ladder (item 5);
expiry-IV-crush model (item 6); combined-premium-VWAP straddle seam on a live (non-replay) path (item 7);
prev-day-VWAP weighting switch (item 8). **Each of these touches emitted signals and MUST follow the
parity-safe-additive convention (default-off tag + new golden variant).** The sibling plan
`docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md` already implements the
tag-gated soft-dot→hard-gate promotion pattern (and the VIX-dot wiring it depends on); the item-1/item-9
automations above should land there or in a follow-up modelled on it, not in this manual-reminder PR.

### 7.2 Risk register
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Forget to bump `hasSize(7)` → red build | Med | Low (caught by CI) | §5.1 calls out both lines (16 & 37) explicitly; the build fails fast. |
| Non-ASCII glyph in a new string → checkstyle/PS5.1 encoding break | Med | Low | All proposed strings are ASCII (no `≈`/`→`/`§`); matches existing items. Verified in the §4 block. |
| A new key collides with an existing key | Low | Med (dedupe assert fails) | `canonicalListIsWellFormed` already asserts `doesNotHaveDuplicates`; new keys are distinct from the 7. |
| `manual_checks` accidentally leaks into a golden-serialized path | Very Low | High (parity FAIL) | §5.4 verification gate: run goldens with no regen; any diff = STOP. The pattern is already proven by the existing `legs[]`/`manual_checks` stamp. |
| Checklist grows long → owner friction / mobile scroll | Med | Low (UX) | The component already has a mobile accordion (`ManualVerifyChecklist.tsx:54-69`) and the override. OP-2 records an optional "group by automatable/judgement" polish, not built here. |
| Line too long → checkstyle | Low | Low | Strings split across `+` concatenation as in the §4 block, matching existing multi-line items. |

### 7.3 Backout
Trivial and isolated. Revert the single squash commit (or just delete the 9 appended `Check` records and
restore `hasSize(7)`). No data migration to undo — historical signals simply carry a shorter `manual_checks`
array in their stored `scalper_detail` JSONB; the FE renders whatever count is present (count-agnostic), so
old and new rows coexist with zero migration either way. No deploy coupling beyond rebuilding
strategy-signal-service (`docker compose ... build strategy-signal-service && up -d strategy-signal-service`,
LIVE `ARTHA_DB_NAME=artha`/`ARTHA_REDIS_DB=0`).

---

## Open Points

- **OP-1 — exact item set: 8 vs 9, and trim candidates.** The audit verdict names 8 manual-only gaps; this
  plan proposes 9 by splitting the VIX absolute-band read (`vix_regime_bands`) out from the existing
  `vix_normal` spike item, per coverage line 69. Two sub-decisions the code cannot settle:
  (a) whether to ship `vix_regime_bands` separately or fold the band wording into `vix_normal`'s assist;
  (b) whether `oi_intraday_positional` (item 5) is redundant with the automated OI dots the owner already
  sees on-card. **Options:** ship all 9 / ship 8 (fold VIX) / ship fewer (drop 5). **Recommended default:**
  ship all 9 — a reminder is low-cost, every item is audit-flagged as uncovered, and any can be dropped by
  deleting one `Check` + adjusting the count. *Owner to confirm the final set + the count for the test.*

- **OP-2 — checklist length / grouping UX.** 16 items is a long manual list on the ~480px mobile target.
  The component already collapses on mobile (`ManualVerifyChecklist.tsx:54-72`) and offers the override, so
  it is *functional*, but the owner may want the checks grouped (e.g. "always-applicable" vs
  "context-specific: straddle / Sensex / expiry-day") or some marked optional. That is a FE enhancement
  (new `category` field on `Check` + grouped render) — **out of scope here** (it would add a DTO field and
  FE render logic). **Recommended default:** ship flat; open a follow-up if the owner finds it noisy.

- **OP-3 — context-conditional checks.** Several new items only apply in specific contexts:
  `straddle_vwap_entry` only for the straddle strategy; `sensex_participation` only for SENSEX-root
  variants; `iv_crush_awareness` mainly on expiry day. The current pipeline stamps the **same fixed list on
  every scalper signal** (`appendTo` has no strategy/context input). Making checks conditional would require
  threading the strategy slug / underlying / expiry-day flag into `appendTo` and is a behaviour change.
  **Options:** ship all checks on every signal (owner ignores the irrelevant ones) / make `appendTo`
  context-aware. **Recommended default:** ship the fixed list (simplest, matches today's design); record
  context-conditional rendering as a future enhancement. *Owner to confirm they are OK seeing e.g. the
  straddle reminder on a non-straddle signal.*

- **OP-4 — exact `label`/`assist` wording.** The strings in §4 are drafted from the audit's rule
  summaries; the owner (who wrote the Siva method) may prefer different phrasing or a different `docRef`
  granularity (e.g. `4.13` vs `4.17.4` for the FII item — the audit cites both). **Recommended default:**
  use the §4 wording and the `docRef`s in the §3 table (which match the consolidated-doc sections the FE
  links). *Owner to red-pen the copy before merge.*

- **OP-5 — docRef target sections exist in the consolidated doc.** The new `docRef`s (`4.14.1`, `4.14.4`,
  `4.14.5`, `4.15.2`, `4.17.2`, `4.17.3`, `4.17.4`, `4.17.5`) are taken from the audit's section column.
  **RESOLVED (audit pass 1): all eight section headers were re-opened and confirmed present in
  `Options_Scalper_Siva_Consolidated_Strategy.md`** (4.14.1 line 1505, 4.14.4 :1517, 4.14.5 :1522,
  4.15.2 :1555, 4.17.2 :1613, 4.17.3 :1618, 4.17.4 :1622, 4.17.5 :1625). Note the FE shows the bare
  string with **no `§` prefix** (see the §2/§4 corrections), so the rendered text will read e.g.
  `4.17.4`. Two checks (item 3 `pre_open_bias` and item 8 `time_of_day_vwap`) deliberately share `4.14.5`
  (the doc section "Pre-open data & time-of-day data weighting" covers both) — that is faithful, but the
  owner sees the same `4.14.5` twice; flag for OP-4 wording review. *Low risk — the FE just shows the
  string; a wrong section number is cosmetic, not a build/parity failure.*

---

## Audit pass 1 findings

Independent pass-1 audit (2026-06-27). Every `file:line` / method / yaml key / test cited in the plan was
opened against the working tree; soundness, parity, completeness, and open points were checked. **Verdict:
sound-with-open-points — implementation-ready once the corrections below are folded in (all already applied
in place above).** Nothing blocks the build or breaks parity.

### Citations verified CORRECT (opened and confirmed)
- **Core backend:** `ScalperManualChecks.java` — record `Check` line 21, `CHECKS` list-of-7 lines 24–60
  (keys + docRefs exactly as listed), `appendTo` lines 65–74 (iterates `CHECKS`, auto-serializes). ✓
- **Stamp:** `SignalEngine.java` — import line 23; `scalperDetailJson` lines 667–706 with
  `ScalperManualChecks.appendTo(root)` at line 704; stamp guarded by `if (decision != null)` at line 618,
  call at 619–621 (plan said 618–622 — accurate enough). ✓
- **Persist:** `SignalRepository.java` — `stampScalperDetail` 184–190 (`scalper_detail=?::jsonb`),
  read-back `nullableTree` at 172 + def 200–203. ✓
- **Migration:** `V009__scalper_signal_detail.sql` lines 8–10 add `scalper_detail JSONB`; header 1–7 says
  scalper keys must stay OUT of the frozen `score_breakdown`. ✓
- **Wire:** `SignalsController.java` `dto(...)` 106–128, `dto.put("scalperDetail", ...)` line 126 (untyped
  `Map` → no springdoc drift). ✓
- **FE types:** `signals.ts` — `scalperDetail?` line 37, comment line 47, `ManualCheck` 48–53,
  `ScalperDetail.manual_checks` line 67. ✓
- **FE render/gate:** `ManualVerifyChecklist.tsx` — `checks` line 22, `total`/`tickedCount`/`allTicked`/
  `confirmed` 40–43, `useEffect`→`onConfirmedChange` 45–47, dynamic rows 99–119, `role="alert"` 123–130,
  override 133–141. ✓
- **FE mount points:** `SignalTakeTicket.tsx` 50–52 / mount 134 / Place disabled 141; `SignalsPage.tsx`
  66–69 / mount 189 / Take disabled 196. ✓
- **Tests:** `ScalperManualChecksTest.java` — `hasSize(7)` at lines 16 AND 37, first-key `news_clear`
  at 43. FE `ManualVerifyChecklist.spec.tsx` is a count-agnostic 2-check fixture. e2e
  `scalper-checklist.spec.ts` is count-robust (uses the single "Confirm despite unchecked" override, line 61). ✓
- **"Why manual not gate" backend cites:** `MarketOiClient.java` `fiiLongPct` fetched 375–383 +
  `latestFiiLongPct` 625–641, `macro()` returns `null,null` VIX at 394–397; `ScalperConfig.OPENING_FROM/TO`
  72–73; `ScalperConfluenceGate.java` 128–131 (combined-premium VWAP "deterministic seam cannot
  recompute"); `ScalperGates.java` 23–24,37 (11:00–13:00 block); `ConnectTheDotsScorer.java` 71–74
  (single VWAP); `ScalperStrategySeeder.java` 38–73 (static niftyoi/sensexoi A/B, 36 strategies);
  `ConnectingDotsService.vixByBucket` exists; `scalp-straddle-nifty.yaml` 24–31 LIVE-deferred. ✓
- **Audit-source cites:** `session-additions-and-manual-coverage.md` table rows (FII 55, constituent 23,
  pre-open 26, sensex 51, OI 54, IV-crush 56, straddle 35, time-of-day 25) + coverage lines 69/75–82 +
  verdict 84–88; `gates-strike-sr-fiidii.md` §4.13 rows 35–38; `README.md:488` + `:521` are the two
  unchecked `[ ]` actionables. ✓
- **docRef anchors:** all 9 consolidated-doc sections exist (see OP-5 resolution). ✓

### PARITY — SOUND (the critical check)
`GoldenSignalsJson.write()` (`libs/strategy-engine/.../golden/GoldenSignalsJson.java`, opened in full)
serializes ONLY `timestamp`/`exchange`/`tradingsymbol`/`direction`/`composite`/`breakdown[]` — it never
touches `scalper_detail` or `manual_checks`. The `manual_checks` array is written by
`SignalEngine.scalperDetailJson()` onto the V009 side-channel (the LIVE emit path, guarded by
`decision != null`), which the deterministic replay behind `GoldenDeterminismTest`/`BacktestParityTest`
does not reach. Moreover `ScalperManualChecks.CHECKS` is a **static constant** — even if it were ever
serialized into a compared path, the value is identical across both replays. **Adding the 9 checks cannot
alter emitted signal bytes; no opt-in flag or new golden variant is required.** The plan's §5.4 argument is
correct and now independently confirmed against `GoldenSignalsJson`. The §5.4 "run goldens, any diff = STOP"
backstop is retained as a belt-and-braces step (appropriate).

### SOUNDNESS — the change compiles and works
- The 9 `new Check(...)` records match the `record Check(String, String, String, String)` arity; all
  strings are ASCII (no `≈`/`→`/`§`/`%`-glyph); multi-line `+` concatenation matches the existing style →
  no checkstyle line-length or encoding break.
- `appendTo` auto-serializes them (no other backend edit). The two `hasSize(7)→hasSize(16)` bumps and the
  new `newManualOnlyChecksArePresent` test are the only count-coupled edits; `Check::key` is a valid record
  accessor reference so the new test compiles. JaCoCo ≥60% is unaffected (added lines are covered).
- No DTO/contract/schema/migration/WS change — all four "none" claims in §6 verified.

### Issues found and CORRECTED in place
1. **WRONG CITE (cosmetic, repeated 3×): "the FE prepends `§` onto `doc_ref`".** FALSE. The live FE
   renders `{c.doc_ref}` raw (`ManualVerifyChecklist.tsx:111-115`); the FE spec fixture bakes `§` into the
   literal string when it wants one (`'CHEAT_SHEET §3'`). The class javadoc claims a `§` prefix but is
   stale vs. the code. Corrected in §2, §4 (shared mechanics), and OP-5. Practical effect: new docRefs
   display bare (`4.17.4`), exactly like the existing 7. No build/parity impact; do NOT write a leading
   `§` into the new `docRef` strings.
2. **COMPLETENESS / over-claim on `README.md:521`.** That checkbox is FII/DII **participant-wise OI**
   (§4.13 4×2 LB/SC/LU/SB matrix + §4.17.4 L/S ratio). `fii_ls_ratio` covers only the §4.17.4 L/S ratio
   (the single value actually plumbed into `Macro`); the §4.13 participant change-in-OI classifier remains
   unbuilt (`gates-strike-sr-fiidii.md:35-38`). Corrected §4 item-1 + §7 PR-step-3 to flag PARTIAL coverage
   rather than a blanket check-off. `README.md:488` (the "8 manual-only inputs" actionable) IS fully
   satisfied by this PR.
3. **Minor — duplicate docRef.** Items 3 (`pre_open_bias`) and 8 (`time_of_day_vwap`) both legitimately map
   to §4.14.5; the owner will see `4.14.5` on two cards. Faithful, but flagged in OP-5 for OP-4 wording review.
4. **Minor — missing cross-reference.** The §7 downstream automations (VIX dot, FII dot) are the subject of
   the sibling plan `2026-06-27-followup2-soft-dots-to-hard-gates.md`; added a pointer so the parity-safe
   automation home is explicit and the two plans don't silently overlap.

### Open points added
- **OP-6 (new) — stale class javadoc.** `ScalperManualChecks.java:15-16` asserts the FE prefixes `§`, which
  the FE does not do. Out of scope to change the javadoc in this PR (it's pre-existing text, not introduced
  by this change — per CLAUDE.md "leave pre-existing dead/wrong code, mention it"), but recorded here so a
  future reader does not trust it. If the owner wants the `§` to actually appear, that is a one-line FE edit
  in `ManualVerifyChecklist.tsx` (prepend `'§'` before `{c.doc_ref}`) — a deliberate UI change, not part of
  this manual-reminder follow-up.

### Residual open points (genuine unknowns, owner-gated — unchanged)
OP-1 (8-vs-9 item set + trim candidates), OP-2 (16-item mobile length / grouping), OP-3
(context-conditional checks), OP-4 (label/assist/docRef wording) all remain valid owner decisions; none
blocks implementation. The `total` test count must match whatever final set the owner approves (OP-1).

---

## Audit pass 2 findings

Independent second pass (2026-06-27, fresh skeptical review). I re-opened the working tree from scratch —
the core chain, the parity authority, the audit sources, every "why manual not gate" backend cite, the doc
anchors, and (newly) the two satellite test files the plan did not enumerate. **Verdict: sound — ready to
implement once the owner settles OP-1 (the 8-vs-9 item set, which drives the test counts).** The single
residual stale `§`-prefix claim that pass 1 missed is now corrected in place. No parity, build, contract,
or data-flow defect remains.

### Re-verified independently against the working tree (sampled, all CORRECT)
- **Core chain** — `ScalperManualChecks.java`: `record Check` line 21, `CHECKS` 7 items lines 24–60 (keys +
  docRefs exact), `appendTo` iterates `CHECKS` lines 65–74. `SignalEngine.scalperDetailJson` 667–706,
  `appendTo(root)` at 704, stamp guarded by `if (decision != null)` at 618. `SignalRepository.stampScalperDetail`
  184–190 (`scalper_detail=?::jsonb`), `nullableTree` read-back 172/200–203. `SignalsController.dto` builds a
  `Map<String,Object>`, `put("scalperDetail", …)` at 126. V009 cols 8–10, header 1–7 (keys OUT of frozen
  `score_breakdown`). FE `signals.ts` `ManualCheck` 48–53 / `ScalperDetail.manual_checks` 67. ✓
- **PARITY (the load-bearing check), re-confirmed at the source** — I read `GoldenSignalsJson.write()` in full
  (`libs/strategy-engine/.../golden/GoldenSignalsJson.java:41-70`). It serializes ONLY
  `fixtureFormat`/`strategy`/`candles`/`signals[]`, where each signal carries
  `timestamp`/`exchange`/`tradingsymbol`/`direction`/`composite`/`breakdown[]`. It never references
  `scalper_detail`, `manual_checks`, or `ScalperManualChecks`. Combined with (a) the stamp living on the
  `decision != null` LIVE emit path the deterministic replay does not reach, and (b) `CHECKS` being a static
  constant (identical across both replays even if it WERE serialized), the plan's §5.4 argument is fully
  sound. Adding 9 checks cannot move a single golden byte. No opt-in flag / no new golden variant. ✓
- **Doc anchors** — grepped the eight section headers in `Options_Scalper_Siva_Consolidated_Strategy.md`: all
  present at exactly the lines OP-5 cites (4.14.1→1505, 4.14.4→1517, 4.14.5→1522, 4.15.2→1555, 4.17.2→1613,
  4.17.3→1618, 4.17.4→1622, 4.17.5→1625). Header 4.14.5 is literally "Pre-open data **and** time-of-day data
  weighting", so items 3 + 8 legitimately share 4.14.5; header 4.17.4 is "FII Long/Short-ratio gate (extends
  §4.13)", which directly substantiates pass-1's PARTIAL nuance for README:521. ✓
- **"Why manual not gate" cites** — `MarketOiClient.macro()` returns `null,null` VIX (line 396-397) and
  fetches `fiiLongPct` (375-383); `ScalperConfluenceGate.java:128-131` ("LIVE market-data series the
  deterministic seam cannot recompute"); `ScalperConfig.OPENING_FROM/TO` 72-73 (09:15-09:30, so pre-open
  9:00-9:07 is genuinely unconsumed); `ConnectTheDotsScorer.java:71-74` (single current-bar VWAP dot);
  `ScalperStrategySeeder.java:38-73` (static niftyoi/sensexoi A/B string list, 36 strategies). ✓
- **README actionables** — `:488` ("At least 8 … manual-only inputs have NO `ScalperManualChecks` item —
  add them") and `:521` (FII/DII participant-wise OI, fiiLongPct dead-wired) are both live unchecked
  `[ ]` actionables tagged **[NEW — actionable]**. ✓
- **FE gate / e2e count-robustness** — `SignalTakeTicket.tsx:49-52` + `SignalsPage.tsx:65-69`
  (`takeBlocked = scalperDetail != null && !confirmed`); `ManualVerifyChecklist.tsx:113` renders
  `{c.doc_ref}` raw (no `§`); the spec fixture bakes `§` into a literal (`'CHEAT_SHEET §3'`, line 22/37) —
  confirming pass-1's §-correction; `scalper-checklist.spec.ts:60-63` exercises the SINGLE override checkbox
  ("robust regardless of how many checks exist"). ✓
- **§4.13 participant-matrix nuance (validates pass-1 correction #2)** — `gates-strike-sr-fiidii.md:35-38`
  states `/fii-dii/long-short` yields ONLY the FII index-futures L/S ratio, NOT the 4-participant
  LB/SC/LU/SB change-in-OI matrix (which is "entirely un-automated"). So `fii_ls_ratio` covering only
  §4.17.4 while README:521 spans §4.13 is correct → PARTIAL coverage is the right call. ✓

### Confirmed pass-1 corrections are RIGHT and introduced no new error
- **§-prefix correction** (pass-1 #1) — independently verified at `ManualVerifyChecklist.tsx:113` (raw render)
  and `.spec.tsx:22/37` (the `§` is in the fixture literal, not prepended by code). The class javadoc
  (`ScalperManualChecks.java:15-16`) is indeed the stale source. Correct.
- **README:521 PARTIAL** (pass-1 #2) — independently verified against `gates-strike-sr-fiidii.md:35-38` (see
  above). Correct, and the §7 PR-step-3 wording reflects it.
- **Duplicate docRef 4.14.5** (pass-1 #3) and **sibling-plan cross-ref** (pass-1 #4) — both factual and
  non-blocking. Correct.

### Issue found that BOTH the author and pass 1 missed — CORRECTED in place
- **Residual stale `§`-prefix claim in §3.** Pass 1 corrected the `§`-prefix wording in §2, §4, and OP-5 but
  **left it intact in the §3 table preamble** (line 167: "`docRef` is the consolidated-doc section the FE
  will display with a `§` prefix"). That directly contradicts pass-1's own correction. Fixed in place to
  "bare … section string the FE displays verbatim — no `§` prefix". Cosmetic-doc only; no build/parity
  effect, but it was an internal inconsistency a reader would trip on.

### Things both passes under-stated (verified, no defect — strengthens the plan)
- **Two satellite test files reference `manual_checks` and are NOT count-coupled** (the plan asserts the two
  `hasSize(7)` literals are "the ONLY count-coupled assertions" but never enumerates these to prove it):
  (a) `SignalsControllerTest.java:35` hand-builds `"manual_checks":[{"key":"news_clear"}]` (a 1-element stub
  through the untyped controller — never reads `CHECKS`), and (b) `SignalTakeTicket.spec.tsx:92-94` uses its
  own 1-element `manual_checks` fixture (`key:'trend'`). Both are count-agnostic; a global `hasSize(7)` grep
  finds only the two `ScalperManualChecksTest` lines. So the plan's "only two count-coupled assertions"
  claim is TRUE — now positively confirmed, not just asserted.

### One completeness nit for the implementer (non-blocking)
- **The new `newManualOnlyChecksArePresent` test (§5.1) is coupled to OP-1, not just the two `hasSize`
  literals.** It hardcodes all 9 new keys. If the owner trims the set under OP-1 (e.g. folds `vix_regime_bands`
  into `vix_normal` → 8 checks → CHECKS=15), the implementer must drop the trimmed key from THIS test's
  `.contains(...)` list AND set both `hasSize` literals to 15 — three edits, not two. The plan's "delete one
  `Check` + adjust the count" note (§3 / OP-1) should be read as "+ adjust the new-keys test too". Worth a
  one-line note at implementation time; does not change the design.

### Over-claim check (automatable vs manual-only) — clears
Several new checks are tagged "**Automatable**" by the audit (constituent contribution, FII L/S, pre-open,
Sensex participation, OI intraday-vs-positional, time-of-day VWAP, VIX bands). The plan does NOT claim these
are intrinsically un-automatable — it claims they are *not automated today* (each confirmed unbuilt above),
so an on-card reminder is warranted now, with the actual automation routed to §7 / the sibling
`followup2-soft-dots-to-hard-gates.md` plan under the parity-safe-additive convention. That framing is
honest and matches the audit's own "Manual reminder; … derivable but unbuilt" language. No over-claim.

### Final readiness verdict
**SOUND.** Every load-bearing citation re-verified against the working tree; the parity argument holds at the
`GoldenSignalsJson` source; no schema/DTO/contract/WS impact; the two count-coupled test literals are the
only ones (now positively proven). The only code-touching edits are 9 list entries + two `hasSize` bumps +
one new test — all mechanical. Implementation is gated solely on the owner settling OP-1 (final item set →
final test counts) and red-penning the OP-4 copy; neither is a soundness risk.
