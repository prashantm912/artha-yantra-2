# Risk governance — daily caps, 5-account ledgers, max-positions wiring, auto-journal

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


Status: ✅ BUILT / DONE (E10 — shipped #77 (daily-loss/max-positions wiring), #300 (auto-journal),
#314 (five-account first-loss-freeze ledger), #315 (ledger stamping); daily caps 10% loss / 1.5% profit /
20% deploy live). Kept as the as-built design ref (the scalper-to-100 roadmap §E10 links it). Original status
below.

Status (original): PLAN (implementation-ready). Owner: single-owner. Target service:
`services/strategy-signal-service` (the account-side paper/risk layer — NOT the per-strategy
signal seam). Date: 2026-06-27.

> Read order for the executor: this plan is self-contained. The load-bearing precedents are
> the A12 paper-risk layer (`RiskService` / `ScalperAccountModel` / `RiskSettingsRepository` /
> `V006__paper_account_risk.sql`) and the CLAUDE.md parity-safe-additive convention. Almost
> everything here is **account-side ([S] safe)** — these rails gate *paper position opening*
> and read closed-trade P&L, NOT signal emission, so the engine goldens never see them. The
> one place a change could touch emission is the `daily-loss-maxpositions-wiring` package, which
> is handled below as a deliberately-bounded **DB-row-only** change (no compiler change, no new
> golden) — see §3.3 and §4.

---

## 1. Goal & the packages this stream closes

This stream automates the **account-side money-management rails** the Siva Global-Risk section
(`strategy-documents/options-scalper-siva/...Consolidated_Strategy.md` §2.1–§2.14) states as
numbers but the platform leaves to the trader's memory. Source of truth: the per-dimension
disposition `docs/strategy-audit/disposition/risk-framework.md` (rows cite `risk-framework.md`
audit evidence with file:line) plus the two single-gap rows in
`docs/strategy-audit/disposition/trend-change.md` (L40) and `risk-framework.md` (r37).

| Package | # gaps | Doc-§ (Siva §2) | Disposition source rows |
|---|---:|---|---|
| **`daily-target-caps`** | 7 | 2.3 r11/r12/r13/r14, 2.6 r24-25, 2.10 r41, 2.14 r62/r66 | `disposition/risk-framework.md` L25, L26, L27, L28, L35, L45, L52 |
| **`five-account-ledgers`** | 2 | 2.4 r15-16, 2.4 r19 | `disposition/risk-framework.md` L29, L31 |
| **`daily-loss-maxpositions-wiring`** | 1 | 3.12 Risk (Global §2) | `disposition/trend-change.md` L40 (single-gap pkg, `GAP-DISPOSITION.md` L167) |
| **`auto-journal`** | 1 | 2.9 r37 | `disposition/risk-framework.md` L41 (single-gap `[S]` pkg, `GAP-DISPOSITION.md` L177) |

**Total: 11 gaps across 4 packages.** All four are `AUTOMATE_PKG` in the disposition. Three are
`[S]` safe by construction (account-side / read-only). The fourth (`daily-loss-maxpositions-wiring`)
is dispositioned PARTIAL→AUTOMATE and is the only one with a parity question — resolved §4 by
**NOT** wiring the dead YAML keys into the compiler (which would be `[P]`) and instead formally
binding the runtime DB limits, keeping it `[S]`.

> **Count reconciliation (audit pass 2).** The `daily-target-caps` "7 gaps" here counts the **7 distinct
> disposition table LINES** (`risk-framework.md` L25/L26/L27/L28/L35/L45/L52, each verified to carry the
> `daily-target-caps` package in its Disposition column). The disposition's own theme rollup
> (`risk-framework.md` L76) labels `daily-target-caps` as **"(6 rows)"** — an undercount in the source
> doc (it folds the r66 reference that appears on both L28 and L52). This is a pre-existing
> source-doc inconsistency, not a defect in this plan; the 7 cited lines each independently check out.
> The stream-total "11" is unaffected either way (the three other packages are 2/1/1).

### Why these belong together
They all read the **same closed-paper-trade ledger** (`paper_positions`) and the **same capital
base** (`paper_account`), and they all gate the **same two ENTRY seams** already in place:
`RiskService.entryAllowed()` (global, `emitEntry` L578) and
`ScalperAccountModel.scalperEntryAllowed()` (scalper-only, `scalperEntry` L454). Adding caps and
per-account ledgers is extending those two existing rails, not building new ones.

---

## 2. Current state (verified file:line, opened 2026-06-27)

### 2.1 The global risk gate — `paper/RiskService.java`
- Three DB-row limit keys: `KILL_SWITCH` (L25), `MAX_OPEN` (L26), `DAILY_LOSS` (L27).
- `entryAllowed()` (L52-70): kill-switch (L53), `max_open_paper_positions` count cap (L56-59),
  `daily_loss_limit` (L60-68). The daily-loss limit supports **`mode: pct` of equity** (L72-78,
  `dailyLossLimitInr` → `account.equity().multiply(value)/100`). **There is NO daily *profit*
  target** and the loss limit is **OFF until the owner sets a row** (no seed).
- `recordTrip` (L80-90) audits once per IST day. `update(key, json)` (L103-109) upserts + audits;
  re-arms the per-day trip dedup on a `DAILY_LOSS` change.

### 2.2 The scalper 5-account discipline — `paper/ScalperAccountModel.java`
- `ACCOUNTS = 5` (L32), `MAX_WINS_PER_DAY = 5` (L35).
- `scalperEntryAllowed()` (L50-62): reads `positions.winLossOn(today)` and pauses on
  `wins >= 5` (banked) **or** `losses >= 5` (all sub-accounts frozen). **Day-granularity loss
  COUNT** — the javadoc (L17-22) is explicit it does **NOT** track which account took which trade,
  so there is no per-account capital split, no per-account 1% target, and no per-account first-loss
  freeze.

### 2.3 The capital base + P&L source — `paper/PaperAccountService.java` / `PaperPositionRepository.java`
- `equity()` (L69) = `starting_capital + Σ realized + Σ MTM unrealized`. `dayPnl()` (L170-173)
  = `realizedOn(today) + unrealizedTotal()`.
- `PaperPositionRepository.realizedOn(istDate)` (L221-227) sums realized P&L for the IST day;
  `winLossOn(istDate)` (L236-244) returns `(wins, losses)` for the day (a `realized_pnl > 0` is a
  win; `<= 0` a loss). `openCount()` (L209-212).
- The single shared close path is `PaperService.doSettle(pos, price, closeReason, exercise)`
  (`PaperService.java` L208-224) — used by manual close, the 15:45 sweep, and expiry settlement.
  It computes `realized` (L218) and calls `positions.close(...)` (L222). **This is the single
  choke point where a trade becomes closed** → the auto-journal hook point.

### 2.4 The wiring seam — `signals/EmissionGuard.java` / `paper/PaperEmissionGuard.java`
- `EmissionGuard` (signals module SPI): `entryAllowed()` (L15), `scalperEntryAllowed()` default
  true (L22-24), `suggestedQty(...)` (L31). The engine depends only on this port (acyclic).
- `PaperEmissionGuard` (paper impl): `entryAllowed()` → `risk.entryAllowed()` (L36-38);
  `scalperEntryAllowed()` → `scalperAccounts.scalperEntryAllowed()` (L41-43).
- `SignalEngine`: `scalperEntryAllowed` consulted at L454 (scalper path, before the confluence
  gate); `entryAllowed` consulted at L578 (`emitEntry`, before persisting any ENTRY). Both gate
  ENTRY only — exits are never gated.

### 2.5 The dead YAML keys — `risk.max_daily_loss_pct` / `max_positions` / `max_positions_per_underlying`
- Present in every scalper YAML (`scalp-two-candle-nifty.yaml` L52-54: `max_positions: 1`,
  `max_positions_per_underlying: 1`, `max_daily_loss_pct: 2.0`) and schema-validated
  (`strategy-schema-v1.json:552`).
- **`StrategyCompiler.compile` reads ONLY `risk.position_sizing` (L66-69) + `risk.session`
  (L82)** — `max_daily_loss_pct` / `max_positions*` are never read anywhere in `strategy-engine`
  or `strategy-signal-service` (grep-confirmed by the audit: `disposition/trend-change.md` L56,
  `trend-change.md` L88/L119). They enforce nothing. The single-position no-averaging behaviour is
  a hardcoded `activeEntry` check (`SignalEngine.java:398-400`), not a read of `max_positions`.

### 2.6 The journal — `journal/JournalRepository.java` / `V008__journal_entries.sql`
- `journal_entries` (V008 L7-19): nullable links `signal_id` / `paper_position_id` (same-schema
  FKs, validated) + `backtest_run_id` / `backtest_trade_id` (soft refs) + `note` / `tags[]` /
  `discipline_rating` / `emotion_rating`. A **free entry (no link) is first-class.**
- `JournalRepository.insert(EntryInput)` (L68-94) is the create path; `EntryInput` (L38-46) carries
  the nullable links + note + tags. **Nothing auto-populates it** — the React Journal page
  (PR-C9) is manual-only. `paperPositionExists(id)` (L62-65) is the FK validator.

### 2.7 The API + FE surfaces (for the new read endpoints)
- `paper/RiskController.java` (`/api/v1/risk/**`): `GET /settings` (L43-60) returns
  `{items, audit}`; `PUT /settings` (L63-74) upserts one of the three `KEYS` (L27-28). The
  React Paper page (PR-C2) renders these limits. **A new cap key must be added to `KEYS`** to be
  PUT-able, and to `RiskService` to be consulted.
- `paper/PaperController.java` exposes `/api/v1/paper/account` (the `AccountDto` header with
  `dayPnl`). The Cockpit (Y2) + Paper page read it.

---

## 3. Design — per package

> Convention reused throughout: limits live on **`risk_settings` DB rows, never YAML** (the A12
> rule — a flip never mints a strategy version or perturbs a D18 checksum;
> `V006__paper_account_risk.sql` L4-5). New caps follow the exact `daily_loss_limit` shape:
> a typed JSONB row `{enabled, mode, value, ...}` consulted in `RiskService`.

### 3.1 `daily-target-caps` (7 gaps) — `[S]`

Seven gaps collapse into **three new account-side rails** plus **one seed**, all in
`RiskService` + `RiskController` + a new migration. None touch signal emission (they gate
`entryAllowed()`, the existing global rail).

**(a) New limit key `daily_profit_target`** (closes r14 `disposition L28`, r11 `L25` profit half,
r66). Mirror `daily_loss_limit` exactly but trip on the *upside*:

In `RiskService.java`, add the key + a profit-trip in `entryAllowed()`:

```java
public static final String DAILY_PROFIT_TARGET = "daily_profit_target"; // NEW

// inside entryAllowed(), after the daily-loss block (L68):
Optional<Setting> profit = settings.get(DAILY_PROFIT_TARGET);
if (enabled(profit)) {
  BigDecimal target = limitInr(profit.get().value()); // same pct/inr resolver as loss
  if (account.dayPnl().compareTo(target) >= 0) {
    recordTrip(DAILY_PROFIT_TARGET, account.dayPnl(), target); // "target hit — stop for the day"
    return false;
  }
}
```

Refactor `dailyLossLimitInr(node)` (L72-78) into a shared `limitInr(node)` (identical math: `pct`
→ `equity * value / 100`, else the raw INR — `node.path("value").decimalValue()` reads the value as
in the current method) so loss and profit share one resolver. Generalize `recordTrip` (currently
`recordTrip(BigDecimal dayPnl, BigDecimal limit)`, L80-90) to take a leading `String key` so the
audit row names which cap fired.

> **Per-cap trip dedup (audit pass 1 add).** `recordTrip` dedups via the single instance field
> `dailyLossTrippedOn` (L37) and `update(key,…)` re-arms it ONLY on a `DAILY_LOSS` change (L106-108).
> A profit-target trip needs its OWN per-day dedup field (e.g. `dailyProfitTrippedOn`) AND `update()`
> must re-arm it on a `daily_profit_target` change — otherwise a re-armed-loss edit would silently
> un-dedup the profit trip (or vice-versa). Generalize to a `Map<String,LocalDate> trippedOn` keyed by
> the cap key, re-armed for whatever key `update()` writes. The deployment cap (b) does NOT need a
> dedup (it is a live capital-state check, not a one-shot day event) — do not route it through
> `recordTrip`'s dedup; audit it directly each time it blocks, or accept un-deduped audit noise.

**(b) New limit key `max_deployment_pct`** (closes the §2.10 "never entertain the big loss" r41
`L45` and the §2.14 10-12% blowout r62 `L52` — both `daily-target-caps` rows that ask to "seed/clamp
the daily loss cap ≤10-12%/day so no single day blows out"). A per-day capital *deployment* cap (vs
the loss cap which is a P&L cap). This one gates `entryAllowed()` on `capitalUsed()` (already on
`PaperAccountService` L147):

> **Cite correction (audit pass 1):** the ≤10-20%/trade + ≤20%/day rule (r6, `risk-framework.md`
> L19) is dispositioned to a DIFFERENT package — `probability-graded-sizing` ("cap premium_budget at
> a % of equity") — NOT `daily-target-caps`. It is out of scope for this stream. `max_deployment_pct`
> here closes only the two `daily-target-caps` deployment rows r41 (L45) and r62 (L52), which speak of
> the ≤10-12%/day blowout cap. The 20.0% default in §3.1(d) is therefore the OUTER deployment guard,
> not the doc's r6 10-20% per-trade figure (see Open Point 1 + 9).

```java
public static final String MAX_DEPLOYMENT_PCT = "max_deployment_pct"; // NEW
// {enabled, value} — value is a % of equity; total open deployment may not exceed it.

Optional<Setting> deploy = settings.get(MAX_DEPLOYMENT_PCT);
if (enabled(deploy)) {
  // value() = node.path("value").decimalValue(); deployment is ALWAYS a % of equity (no mode
  // field), so do NOT route it through limitInr() — limitInr defaults an absent mode to "inr"
  // (RiskService L74 asText("inr")) and would treat 20.0 as ₹20, not 20% of equity.
  BigDecimal value = deploy.get().value().path("value").decimalValue();
  BigDecimal cap = account.equity().multiply(value).divide(BigDecimal.valueOf(100));
  if (account.capitalUsed().compareTo(cap) >= 0) {
    // capitalUsed() is a live capital-state read, not a one-shot day event — see the
    // per-cap-dedup note under (a); audit directly, do not use recordTrip's per-day dedup.
    settings.audit(MAX_DEPLOYMENT_PCT, "TRIP",
        "open deployment " + account.capitalUsed().toPlainString() + " ≥ cap " + cap.toPlainString());
    return false;
  }
}
```

> Note: the *per-trade* ≤10-20% half is best enforced as a non-blocking **buying-power-style
> warning** at order time (`PaperAccountService.buyingPowerWarning` L157-167 is the precedent —
> paper stays paper). The *per-day total* half is the hard `entryAllowed` block above. See Open
> Point 1 on whether the per-trade half should hard-block.

**(c) Over-trade afternoon taper** (closes r24-25 `L35` "don't over-trade / size-creep"). A
soft, count-based **rail already half-exists** (the 5-loss / 5-win freeze in `ScalperAccountModel`).
The incremental ask is a *time-of-day* taper: after a configurable IST cutoff, suppress fresh
scalper entries once N trades are already done today. Implement as a new optional field on the
seeded `daily_loss_limit`-sibling row or — simpler — a constant in `ScalperAccountModel`:

```java
// ScalperAccountModel: after the 5-loss / 5-win checks, an afternoon over-trade taper.
static final int AFTERNOON_TRADE_CAP = 3;          // fresh entries allowed after the cutoff
static final LocalTime TAPER_FROM = LocalTime.of(14, 0);
// if now >= TAPER_FROM and (wins+losses) >= AFTERNOON_TRADE_CAP -> false
```

This is scalper-path-only (consulted at `SignalEngine.scalperEntry` L454), so it never affects a
non-scalper or the engine goldens.

**(d) The seeds** (closes r12 `L26` the 0.5% rule + r13 `L27` the 2-3% cap). Add a **new
migration** that INSERTs the documented defaults as `risk_settings` rows so the headline caps are
**on by default** instead of disabled:

```sql
-- V011__seed_risk_defaults.sql  (additive; risk_settings created in V006)
INSERT INTO risk_settings (key, value) VALUES
  ('daily_loss_limit',   '{"enabled":true,"mode":"pct","value":2.0}'),   -- §2.3 r13 (L27) 2-3%/day
  ('daily_profit_target','{"enabled":true,"mode":"pct","value":1.5}'),   -- §2.3 r14 (L28) 1-2%/day
  ('max_deployment_pct', '{"enabled":true,"value":20.0}')                -- §2.14 r62 (L52) ≤10-12% blowout guard; NOTE: no "mode" key (always % of equity)
ON CONFLICT (key) DO NOTHING;
```

> The doc's "0.5% rule" (r12) is the *tighter, all-accounts* stop; 2-3% (r13) is the *outer* day
> cap. We seed the outer 2% as the default `daily_loss_limit` and document the 0.5% as a
> conservative owner-set value (Open Point 2). `ON CONFLICT DO NOTHING` keeps it idempotent and
> never clobbers an owner edit on re-migrate.

**Data flow:** YAML never involved. `RiskController.KEYS` (L27-28) gains the two new keys so the
React Paper/Settings panel can PUT them; `GET /settings` already returns all rows. The trip writes
a `risk_audit` row (existing append-only log) named by the cap that fired.

> **FE binding is NOT free (audit pass 1 — missing step).** `frontend-react/src/pages/paper/PaperPage.tsx`
> renders the risk limits as **hardcoded per-key blocks** (`kill_switch` ~L149,
> `max_open_paper_positions` ~L161-185, `daily_loss_limit` ~L192-216 — each a bespoke toggle/input),
> NOT a generic loop over the `{items}` envelope. So `daily_profit_target` + `max_deployment_pct` will
> **not appear in the UI** until two new hardcoded blocks (toggle + value/mode input + a PUT via the
> existing `updateRisk.mutate({key, value})` seam) are added there, plus the `riskEnabled(...)`
> helper usage. This FE work is required for the §5.5 e2e assertion ("render + editable") to pass and
> must be a line item in PR-1 (it currently is not). The `frontend-react/src/api/paper.ts` PUT body
> shape (`{key, value}`) already supports any key, so only the render blocks are new.

### 3.2 `five-account-ledgers` (2 gaps) — `[S]`

Closes r15-16 (`L29` per-account capital split + per-account 1% target) and r19 (`L31` per-account
first-loss freeze). Today `ScalperAccountModel` is **day-granularity loss COUNT** with no per-account
identity. The faithful model needs **per-account ledgers**: which logical sub-account took each
trade, its own capital slice, its own 1%-target / first-loss state.

**Schema (new migration):**

```sql
-- V012__scalper_account_ledger.sql
-- 5 logical sub-accounts splitting the paper capital base (single-user). Trades are
-- round-robin assigned; each account banks at +1% and freezes on its first loss for the day.
CREATE TABLE scalper_subaccount (
  idx              SMALLINT PRIMARY KEY CHECK (idx BETWEEN 1 AND 5),
  capital_fraction NUMERIC(6,4) NOT NULL DEFAULT 0.2000   -- equal split by default
);
INSERT INTO scalper_subaccount (idx) VALUES (1),(2),(3),(4),(5);

-- which sub-account a closed paper trade was charged to (the per-account ledger)
ALTER TABLE paper_positions ADD COLUMN subaccount_idx SMALLINT
  REFERENCES scalper_subaccount(idx);
CREATE INDEX idx_paper_subaccount ON paper_positions (subaccount_idx)
  WHERE subaccount_idx IS NOT NULL;

GRANT SELECT ON scalper_subaccount TO ay_strategy;
```

**Assignment + gate logic** (in `ScalperAccountModel`, promoted from count to ledger):
- On a scalper entry, assign the next **non-frozen** sub-account (round-robin over `idx`), stamp
  `subaccount_idx` on the opened `paper_positions` row.
- A sub-account is **frozen for the IST day** once a trade charged to it closes at a loss (its
  *first-loss freeze* — r19) OR it has banked its **per-account 1% target** (r15-16): the day's
  realized P&L for trades with that `subaccount_idx` ≥ `capital_fraction × equity × 1%`.
- `scalperEntryAllowed()` returns false only when **all 5 are frozen** (preserves today's
  aggregate-freeze semantics as the degenerate case), so existing `ScalperRiskIntegrationTest`
  behaviour (5 losses → frozen) still holds — but now each loss freezes a *specific* account.

> **Backward-compat trap (audit pass 1 — load-bearing).** The existing `ScalperRiskIntegrationTest`
> helpers insert closed trades with **NO `subaccount_idx`** (NULL) — `fiveLossesFreezeAllSubAccounts`
> (L37-43), `fiveWinsBankTheDay` (L45-51), `winsAndLossesBelowBothCapsStillAllow` (L54-57),
> `aFlatTradeCountsAgainstTheAccounts` (L60-65) all rely on the day-granularity COUNT over ALL closed
> trades. If the rewritten model counts **only** `subaccount_idx IS NOT NULL` rows ("Non-scalper
> trades leave `subaccount_idx` NULL and are invisible" — below), then 5 NULL-idx losses freeze ZERO
> accounts and **these four existing tests FAIL** (`scalperEntryAllowed()` stays true). This is a real
> behaviour regression, not just a test edit. **Resolution (pick one, recommend (a)):**
> (a) keep a NULL-idx **aggregate fallback**: when no trade carries an idx, fall back to today's
>     win/loss COUNT (the legacy path), so old rows + old tests behave identically; per-account freeze
>     only engages once trades carry an idx. The existing four tests then pass UNCHANGED.
> (b) update the four existing tests to stamp `subaccount_idx` — but then the "must still pass
>     unchanged" claim in §4 + §5.2 is false (the tests changed), and any real NULL-idx history (every
>     paper trade taken before V012) silently stops counting toward the scalper freeze.
> Choose (a) so neither the goldens NOR the legacy ledger semantics shift. This MUST be stated in the
> PR and is added as Open Point 10.

**Stamping the sub-account at open** needs the entry path to know the assignment. The cleanest
seam: `PaperSignalListener.onSignalTaken` (L27-39) → `PaperService.openOrder` already opens the
position; add an optional `subaccountIdx` to `OrderRequest` (the record at `PaperService.java`
L39-47 — extend it by one field) set from `ScalperAccountModel.nextFreeAccount()` when the
originating signal is a scalper. (Non-scalper trades leave `subaccount_idx` NULL and are invisible to
this model — see the backward-compat trap above for the NULL-idx fallback.)

> **Two missing wires (audit pass 1):**
> 1. **"Is this signal a scalper?"** Neither `SignalTaken` (`signals/SignalTaken.java`:
>    `record SignalTaken(long signalId, Integer qty, BigDecimal fillPrice)`) nor `OrderRequest`
>    carries a scalper flag, so `onSignalTaken` cannot tell a scalper entry from a non-scalper one. The
>    plan MUST add a resolution step: look up the signal's strategy and test whether it is a scalper
>    (the engine already distinguishes via `strategy.scalper() != null`, `SignalEngine.java`:430). The
>    simplest seam is a new repository read (does the signal's `strategy_version` carry a `scalper`
>    block?) consulted in `onSignalTaken` (or a `scalper` boolean added to `SignalTaken`, which is a
>    `signals`-module change — additive, the record is in `signals`). Without this, EVERY paper open
>    (including non-scalper) would get an idx, polluting the ledger.
> 2. **The actual DB write.** `OrderRequest.subaccountIdx` must flow through
>    `PaperService.upsertPosition` (L159-181) → `PaperPositionRepository.insertOpen` (L85-109), which
>    today has **no `subaccount_idx` parameter or column in its INSERT** (L96-99). Add the column to
>    that INSERT (and stamp only on the `insertOpen` branch, not the averaging `updateOpen` branch —
>    averaging keeps the original account). This repository change is currently unstated in the plan.

**Data flow:** entirely account-side. The 5-account state is derived from `paper_positions` rows
(survives restart, no in-memory state — preserving the existing design note `ScalperAccountModel`
L25). No signal-emission change, no golden.

### 3.3 `daily-loss-maxpositions-wiring` (1 gap) — `[S]`

The disposition (`disposition/trend-change.md` L40) offers two options for the dead
`max_daily_loss_pct` / `max_positions*` YAML keys: **(i) wire them into the compiler/engine**, or
**(ii) formally rely on the paper-runtime `daily_loss_limit`**. **This plan takes (ii).** Wiring
the YAML keys into `StrategyCompiler` + the engine would be **[P] parity-sensitive** — it would
make `max_daily_loss_pct` / `max_positions` alter which signals fire per strategy, requiring an
FU2-style default-OFF tag + a new golden, for a rail the account-side `RiskService` already
enforces globally. That is duplicative and risky. Instead:

**Resolution (option ii, [S]):**
1. **Bind the documented per-strategy intent to the global DB rail.** The §3.1(d) seed makes
   `daily_loss_limit pct:2.0` the default — exactly the `max_daily_loss_pct: 2.0` every YAML
   declares. So the *behaviour* the dead key intended is now live, account-wide.
2. **Mark the YAML keys as advisory/dead.** JSON has no comment syntax, so do **not** try to add a
   `//` comment near `strategy-schema-v1.json:550-552` (it would break `flyway`-unrelated JSON-Schema
   parsing). Two valid options: (a) add a `"description"` string to each of the three property
   schemas (`max_positions`/`max_positions_per_underlying`/`max_daily_loss_pct`) reading e.g.
   `"advisory/descriptive only — NOT read by the compiler; enforcement is the paper-runtime
   risk_settings rows"` — `description` is a first-class JSON-Schema keyword and changing it does NOT
   change validation behaviour, but **CHECK whether it drifts the springdoc contract**
   (`ContractCaptureTest`): the schema is a resource, not a `@*Mapping`, so it should not, but re-run
   ci-contracts to confirm; or (b) leave the schema untouched and put the advisory note ONLY in the
   scalper YAML headers + the schema README. Do **not** delete the keys (CLAUDE.md: leave pre-existing
   dead code; the schema validates them and removing a schema entry IS a contract change). Recommend
   (a)-with-contract-recheck, falling back to (b) if any drift appears.
3. **`max_positions` / `max_positions_per_underlying`** are already enforced structurally
   (`SignalEngine.java:398-400` single-active-entry per `(version, symbol)`). Document that the
   YAML value is descriptive; the structural cap is the enforcer. No code change.

> Net: this "package" is closed by a **documentation correction + the §3.1 seed**, with NO
> compiler change and NO new golden — keeping it `[S]`. The alternative (i) is recorded as Open
> Point 3 with the strong recommendation to NOT do it.

### 3.4 `auto-journal` (1 gap) — `[S]`

Closes r37 (`disposition/risk-framework.md` L41): a Journal page exists but nothing auto-populates
it from the paper ledger. Add an event-driven **auto-journal stub** on every closed paper trade,
so each trade lands a linked journal entry the trader then annotates (discipline/emotion ratings).

**Hook point:** `PaperService.doSettle` (L208-224) is the single close choke point. After
`positions.close(pos.id(), realized, closeReason)` (L222), publish a `PaperPositionClosed` event
(`positionId`, `realized`, `closeReason`) — mirroring the existing `SignalTaken` event pattern that
`PaperSignalListener` consumes.

> **Three wiring facts (audit pass 1):**
> 1. **Inject the publisher.** `PaperService` does NOT currently hold an `ApplicationEventPublisher`
>    (its constructor L95-110 wires only the ledger collaborators). Add it as a new constructor arg —
>    additive. (`SignalEngine`/`SignalsController` already inject one; the bean exists.)
> 2. **Event field name.** The listener below uses `e.positionId()` — name the record field
>    `positionId` (not `id`) so the accessor matches. Record:
>    `record PaperPositionClosed(long positionId, BigDecimal realized, String closeReason)`.
> 3. **Transaction + rollback.** `doSettle` runs inside the caller's `@Transactional`
>    (`closePosition` L184, `settle`/`markToCloseIntraday` L274/284). A **synchronous** `@EventListener`
>    runs in that SAME transaction, so a journal-insert failure would **roll back the trade close** —
>    unacceptable (the close must win even if journaling fails). Either (a) make the listener
>    `@TransactionalEventListener(phase = AFTER_COMMIT)` so it fires only after the close commits and a
>    failure cannot roll the close back (matches "a paper-open failure is logged, never propagated"
>    philosophy in `PaperSignalListener`), or (b) wrap the insert in try/catch + log. Recommend (a)
>    AFTER_COMMIT + try/catch. Note this means the auto-journal IT must commit (not rely on a
>    rolled-back test tx) to observe the row.

**New listener** `journal/AutoJournalListener.java` (journal module, same schema as `JournalRepository`):

```java
@Component
public class AutoJournalListener {
  private final JournalRepository journal;
  // AFTER_COMMIT so a journal failure can never roll back the trade close (see §3.4 fact 3);
  // wrap the body in try/catch + log (mirrors PaperSignalListener's "logged, never propagated").
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPaperPositionClosed(PaperPositionClosed e) {
    // one auto-stub per closed trade, linked to the position; tags mark it machine-created
    journal.insert(new JournalRepository.EntryInput(
        null, e.positionId(), null, null,
        "Auto: " + e.closeReason() + " realized=" + e.realized().toPlainString(),
        List.of("auto", e.realized().signum() >= 0 ? "win" : "loss"),
        null, null)); // ratings left null for the trader to fill in
  }
}
```

**Idempotency / opt-out:** gate the listener behind a config flag
`artha.paper.auto-journal-enabled` (default true) so it can be disabled, and the
`tags: [auto]` marks machine entries so the React Journal can filter them. No schema change needed
— `journal_entries` already accepts a `paper_position_id`-linked entry with null ratings (V008
L9-16, and the `discipline_rating`/`emotion_rating` CHECKs at V008 L15-16 permit NULL).

> **Modulith placement (audit pass 1).** This service runs full `ApplicationModules…verify()`
> (`ModularityTest.java`). The `journal` module today does **not** import `paper`
> (verified: no `import …strategysignal.paper` in `journal/`). Placing `AutoJournalListener` in
> `journal` and the `PaperPositionClosed` event in `paper` makes `journal` reference a `paper` type —
> a NEW module edge. Spring Modulith treats event-listening as decoupled IFF the event is an exposed
> module API, but the listener still needs the type on its classpath. Mirror the working
> `SignalTaken` precedent (event lives in the PUBLISHER's module, `signals`, consumed by `paper` →
> `paper`-depends-on-`signals`, allowed): put `PaperPositionClosed` in the `paper` module and accept a
> `journal → paper` dependency, **then re-run `ModularityTest`** to confirm it still verifies (it is
> not in the plan's §5 test list — added below). If `verify()` rejects the new edge, fall back to
> putting the event in a neutral module both can see (as `SignalTaken` sits in `signals`).
> **`paperPositionExists` is NOT auto-called by `insert()`** (it is a controller-layer validator); the
> FK still holds because the listener links a just-closed real position, but do not claim `insert`
> validates it.

**Data flow:** closed paper trade → `PaperPositionClosed` event → `AutoJournalListener` →
`journal_entries` row → existing Journal API (`JournalController`) → React Journal page. No
signal-emission path, no golden, no contract change (the journal list/create endpoints already
exist; payload shape unchanged).

---

## 4. PARITY classification

Per CLAUDE.md parity-safe-additive + the FU2 default-OFF-tag convention. `[P]` = alters emitted
signals (must hide behind a NEW default-OFF strategy tag + a NEW golden variant; existing goldens
stay byte-identical). `[S]` = read-only / account-side / new path with no existing golden.

| Change | P/S | Why | Tag + golden plan |
|---|:--:|---|---|
| `daily-target-caps` (a) `daily_profit_target` | **[S]** | Gates `RiskService.entryAllowed()` — the existing account-side global rail (`emitEntry` L578). It can only SUPPRESS an entry the same way the existing `daily_loss_limit` does; it never changes *which* signal a strategy computes. The engine goldens never instantiate `RiskService` (`EmissionGuard` is `Optional`, absent in the harness). | None. No tag, no golden. |
| `daily-target-caps` (b) `max_deployment_pct` | **[S]** | Same: a global `entryAllowed()` suppressor reading `capitalUsed()`. Account-side. | None. |
| `daily-target-caps` (c) afternoon taper | **[S]** | Scalper-path-only (`ScalperAccountModel`, consulted at `scalperEntry` L454). The scalper confluence gate is LIVE-only and never on the golden/parity harness (`ScalperConfluenceGate` is `Optional<>`, the FEATURES YAMLs carry no scalper). Suppresses an entry; never alters a computed signal. | None. |
| `daily-target-caps` (d) seed migration | **[S]** | DB rows only. Per A12 (`V006` L4-5) a `risk_settings` change never mints a version or perturbs a checksum. **Behavioural note:** seeding the loss cap ON-by-default WILL change live paper behaviour (good — it's the documented intent) but NOT any golden (no harness reads it). | None — but flag the behaviour change in the PR description (Open Point 2). |
| `five-account-ledgers` schema + ledger | **[S]** | New table + nullable `paper_positions` column + scalper-path assignment. Account-side; the gate still only SUPPRESSES scalper entries. No emission change. (GAP-DISPOSITION L147 also labels it `[S]`.) **But** the count→ledger rewrite must keep the NULL-idx aggregate fallback (§3.2 backward-compat trap) or it silently changes `scalperEntryAllowed()` for legacy/no-idx rows — that is an account-side behaviour shift, still NOT a golden change, but it WOULD break 4 existing ITs if mishandled. | None — but keep the NULL-idx fallback (Open Point 10). |
| `daily-loss-maxpositions-wiring` (option ii) | **[S]** | Resolved as doc-correction + the §3.1 seed — NO compiler read added. **Note:** the source `GAP-DISPOSITION.md` lists this package under the **Parity-sensitive `[P]`** heading (L153/L167) precisely because option (i) is `[P]`. This plan's `[S]` is a deliberate *reclassification* earned by taking option (ii) (no compiler read); the `[P]` label only ever applied to option (i). (Option i, wiring the YAML keys into `StrategyCompiler`, WOULD be `[P]` and is explicitly rejected — Open Point 3.) | None (because we do NOT take option i). |
| `auto-journal` | **[S]** | A post-close `@EventListener` writing a `journal_entries` row. Off the signal path entirely; closing a trade is not emission. | None. |

**Net: every change in this stream is `[S]`.** No new strategy tag, no new golden variant, no
regeneration of the 5 engine goldens. The deliberate design choice that keeps the one parity-risky
package (`daily-loss-maxpositions-wiring`) safe is **not** wiring the dead YAML keys into the
compiler (§3.3). The two golden tripwires (`GoldenDeterminismTest`, `BacktestParityTest`) must be
re-run as a regression check and stay byte-identical — they will, because none of these changes is
on the `TickwiseGoldenRunner` / `ReplayEngine` path.

---

## 5. Tests

### 5.1 Unit / IT — `daily-target-caps` (extend `PaperAccountRiskIntegrationTest.java`)
Mirror the existing `dailyLossTripPausesEntryAndWritesAnAuditRow` (L84-92) pattern (mock profile,
`engine-enabled=false`, per-method reset L57-67):
- `dailyProfitTargetPausesEntryWhenHit` — insert a closed WIN that exceeds the target row; assert
  `risk.entryAllowed()` is false + a `TRIP` audit row named `daily_profit_target`.
- `profitTargetPctModeSizesAgainstEquity` — `mode:pct value:1.5`; assert the INR threshold = 1.5%
  of equity.
- `maxDeploymentPctBlocksWhenOpenDeploymentExceedsCap` — open positions whose `capitalUsed()`
  exceeds the cap; assert `entryAllowed()` false.
- `seededDefaultsArePresentAfterMigration` — a fresh-stack IT asserting the three V011 rows exist
  and are `enabled:true` (proves the seed; the reset L64 deletes them so this needs its own class
  or a no-reset method that re-runs the seed — see Open Point 4 on test-DB seed interaction).
- `RiskController` MockMvc: `PUT /settings` accepts `daily_profit_target` + `max_deployment_pct`
  (extend `KEYS`); a non-key still 400s.

### 5.2 Unit / IT — `five-account-ledgers` (extend `ScalperRiskIntegrationTest.java`)
Reuse the `insertClosed(realized)` helper (L76-84), adding a `subaccount_idx`:
- `firstLossFreezesOnlyThatSubAccount` — a loss charged to account 1 freezes 1 but 2-5 still
  allow → `scalperEntryAllowed()` true.
- `allFiveFrozenStopsEntries` — a loss to each of 1-5 → false (the degenerate case matching
  today's `fiveLossesFreezeAllSubAccounts` L37-43, which must still pass unchanged).
- `perAccountOnePercentTargetBanksThatAccount` — a win charged to account 1 that exceeds its 1%
  slice freezes account 1 (banked) but others remain.
- `nextFreeAccountRoundRobinsOverNonFrozen` — assignment skips frozen accounts.
- A migration IT proving `scalper_subaccount` has 5 rows + the `paper_positions.subaccount_idx`
  column + FK exist.

### 5.3 Unit / IT — `auto-journal` (new `AutoJournalIntegrationTest.java`, beside `JournalIntegrationTest`)
- `closingAPaperPositionWritesAnAutoJournalEntry` — open + close a paper position via
  `PaperService`; assert one `journal_entries` row linked to that `paper_position_id` with the
  `auto` tag and null ratings.
- `autoJournalDisabledWritesNothing` — flag off (`artha.paper.auto-journal-enabled=false`); assert
  no row.
- `autoJournalWinLossTagMatchesRealizedSign` — a winning close tags `win`, a losing close tags
  `loss`.
- `JournalIntegrationTest` (existing) must stay green — manual entries unaffected.

### 5.4 Golden / parity / boundary tripwires (MUST stay green — regression only)
- `GoldenDeterminismTest` (`libs/strategy-engine`) — re-run; assert byte-identical. **Do NOT
  regenerate.** (Verified: the test harness instantiates neither `RiskService` nor
  `ScalperAccountModel` nor `EmissionGuard` — `EmissionGuard`/`ScalperConfluenceGate` are
  `Optional<>` in `SignalEngine` L101/L104 and absent on the replay path.)
- `BacktestParityTest` (`services/backtest-service`) — re-run; the three byte-match asserts
  (two-replay determinism L68, trades-equality L72, replay==golden L77) stay green.
- `ModularityTest` (`services/strategy-signal-service`) — re-run after the auto-journal +
  five-account-ledger module wiring. The new `journal → paper` (event) edge and the
  `paper`-internal `PaperPositionClosed` event MUST still pass `ApplicationModules…verify()`.
- **ci-contracts** — if §3.3 step 2(a) adds `description` strings to the schema, re-run
  `ContractCaptureTest` / ci-contracts to confirm no spec drift (resource schema, not a `@*Mapping` —
  expected no drift, but verify).

### 5.5 e2e (`e2e/tests/`)
- `paper.spec.ts` (existing Paper-page flow): assert the new `daily_profit_target` /
  `max_deployment_pct` limit rows render + are editable in the Settings/Risk panel (the
  `RiskController` GET already returns them once seeded).
- `journal.spec.ts` (existing): after taking + closing a paper trade, assert an `auto`-tagged
  journal entry appears in the list. (If no scalper trade can be driven deterministically in e2e,
  assert via a manual `POST /paper/orders` + close instead — see the existing paper e2e setup.)
- a11y (axe) on any new Settings rows — no new component types, so likely a no-op.

---

## 6. Dependencies & sequencing

```
V006 paper_account_risk  ──┬─► daily-target-caps  (§3.1: new keys + V011 seed)  [no upstream dep]
(exists)                   │
                           ├─► five-account-ledgers (§3.2: V012 schema + ledger) [no upstream dep]
                           │      └─ needs the PaperSignalListener→openOrder stamp seam (exists)
                           │
                           └─► daily-loss-maxpositions-wiring (§3.3) ── DEPENDS ON the §3.1(d) seed
                                  (it is "closed by" the seed + doc; sequence AFTER daily-target-caps)

V008 journal_entries (exists) ─► auto-journal (§3.4: PaperPositionClosed event + listener) [independent]
```

- **No external-feed dependency.** Unlike the OI/VIX/breadth packages, every rail here reads the
  existing `paper_positions` / `paper_account` tables — no market-data wiring blocks them.
- **`daily-loss-maxpositions-wiring` must follow `daily-target-caps`** because its resolution
  *is* the §3.1(d) seed plus the doc note; there is nothing to do until the seed lands.
- **`five-account-ledgers` and `auto-journal` are independent** and can land in parallel /
  any order relative to the caps.
- **SPAN / equity-universe gating is N/A** — these packages are long-premium-paper account rails,
  not sell legs or per-stock packages, so neither the SPAN appliance (#47) nor the equity universe
  (`equity-fno-universe-screener`) gates them.
- **Migration ordering:** V011 (seed) and V012 (ledger) are the next two strategy-lineage
  versions after V010. They are independent; either order is fine as long as both are
  suffix-versioned new files (CLAUDE.md: applied migrations are checksum-locked — never edit V006).

---

## 7. Effort + suggested PR breakdown

| Package | Effort | PR |
|---|:--:|---|
| `daily-target-caps` | **S→M** | **PR-1** `feat(strategy-signal): daily profit/loss/deployment caps + seed (account-side)` — `RiskService` (2 new keys + shared `limitInr` + generalized per-cap-keyed `recordTrip` + per-cap dedup field), `RiskController.KEYS`, `V011__seed_risk_defaults.sql`, the afternoon taper in `ScalperAccountModel` (needs a `LocalTime` import + the existing `clock`), **the two new FE risk blocks in `PaperPage.tsx`**, IT + e2e. (Effort bumped S→M: the FE blocks + per-cap dedup were under-counted.) |
| `daily-loss-maxpositions-wiring` | **S** | **folded into PR-1** — the advisory note on the dead keys (a `description` string in `strategy-schema-v1.json` per §3.3 step 2(a), re-running ci-contracts, OR a scalper-YAML-header-only note per 2(b); the PR-1 seed is the enforcer). Doc/string change, no compiler code. |
| `five-account-ledgers` | **L** | **PR-2** `feat(strategy-signal): per-account scalper ledgers (5-account split + 1% target + first-loss freeze)` — `V012__scalper_account_ledger.sql`, `ScalperAccountModel` rewrite (count→ledger), the `openOrder` sub-account stamp seam, IT. The largest item (schema + assignment + freeze logic + the round-robin). |
| `auto-journal` | **S** | **PR-3** `feat(strategy-signal): auto-journal closed paper trades` — `PaperPositionClosed` event, `AutoJournalListener`, flag, IT + e2e. Independent, smallest. |

Each PR: short-lived `feat/` branch, Conventional Commit scoped `strategy-signal`, squash-merge,
build with the full reactor + `-am` (`-pl services/strategy-signal-service -am verify`, JaCoCo
≥ 60%). PR-2 is the only `L` — consider splitting the schema + the freeze logic if review
granularity is wanted. Total stream effort: **M-L** (one L, three S).

---

## 8. Open Points

1. **Per-trade deployment cap: hard-block or warn?** §3.1(b) `max_deployment_pct` hard-blocks on
   the *per-day total*; the doc's ≤10-20%-*per-trade* (r6) half could (a) be a non-blocking
   buying-power-style warning at order time (matches `buyingPowerWarning`, paper-stays-paper), or
   (b) a hard pre-open block on the single order's projected usage. **Recommended default: (a)
   warn** — a paper hard-block on a single order is heavier than the existing paper philosophy and
   the per-day total already hard-caps deployment. Revisit if the owner wants a strict per-trade
   gate.

2. **Seed the 0.5% rule or the 2% cap as the default `daily_loss_limit`?** The doc states both: a
   tight 0.5%-of-capital *all-accounts* stop (r12) and an outer 2-3%/day cap (r13). §3.1(d) seeds
   2.0% as the default. **Options:** (a) seed 2.0% (outer cap, recommended — fires rarely, lets
   the owner tighten to 0.5% in the panel); (b) seed 0.5% (conservative, may halt paper testing
   early on a normal red day). **Recommended default: (a) 2.0%.** Also: seeding ANY enabled cap is
   a live behaviour change vs today's all-off default — call it out explicitly in the PR
   description so it is an informed choice, not a silent flip.

3. **`daily-loss-maxpositions-wiring`: option (i) wire the YAML keys, or (ii) rely on the runtime
   DB limit?** §3.3 takes **(ii)** (doc + seed, `[S]`). **Option (i)** — making `StrategyCompiler`
   read `max_daily_loss_pct` / `max_positions` and enforce them per-strategy — is **[P]
   parity-sensitive** (it changes which signals fire per strategy → needs a default-OFF tag + a new
   golden), duplicates the global rail, and contradicts "limits live on DB rows, never YAML"
   (A12). **Recommended default: (ii)** — strongly. Record (i) as rejected-unless-owner-insists; if
   ever pursued it is its own FU2-style tag-gated plan, not part of this stream.

4. **Seed-vs-IT-reset interaction.** `PaperAccountRiskIntegrationTest.reset()` (L57-67)
   `DELETE FROM risk_settings` per method — which would wipe the V011 seed mid-suite. **Options:**
   (a) the seed-assertion test lives in a separate class with no `risk_settings` delete (recommended);
   (b) the reset re-applies the seed after deleting. **Recommended default: (a)** — keep the
   existing reset semantics for the trip tests (they need a clean slate) and prove the seed in its
   own minimal class.

5. **Round-robin vs lowest-free sub-account assignment** (§3.2). **Options:** (a) round-robin over
   `idx` (recommended — even rotation, matches the doc's "rotate accounts" r16); (b) always the
   lowest free `idx` (simpler, but account 1 over-trades). **Recommended default: (a) round-robin.**

6. **Should the per-account 1% target also gate the GLOBAL profit target (§3.1a)?** Two profit
   stops could interact (an account banks at +1% while the day target is +1.5%). **Options:**
   (a) independent (per-account freezes that account; the global target stops ALL — recommended,
   matches the doc's two distinct rules r15/r14); (b) unify into one. **Recommended default: (a)
   independent.**

7. **Auto-journal: one entry per trade, or per-day digest?** §3.4 writes one stub per closed
   trade. A high-frequency scalp day could create many auto rows. **Options:** (a) one per trade
   (recommended — each trade is annotatable; the `auto` tag + the React filter manage volume);
   (b) a single end-of-day digest entry. **Recommended default: (a) per-trade**, with the `auto`
   tag so the FE can collapse/filter them.

8. **Does `auto-journal` backfill historical closed trades?** The listener only fires on NEW
   closes. **Options:** (a) forward-only (recommended — simplest, no backfill job); (b) a one-shot
   admin backfill of existing closed `paper_positions`. **Recommended default: (a) forward-only.**

9. **(audit pass 1) Where does the per-trade ≤10-20% (r6) cap live, if at all?** `max_deployment_pct`
   here is the *per-day* deployment guard (r41/r62), and r6 is dispositioned to the SEPARATE
   `probability-graded-sizing` package — out of THIS stream. **Options:** (a) leave r6 entirely to
   `probability-graded-sizing` (recommended — keeps package boundaries clean; this stream does not
   touch sizing); (b) add a non-blocking per-order deployment warning here as a convenience (overlaps
   Open Point 1's "warn" recommendation). **Recommended default: (a)** — do NOT pull r6 in; if a
   per-order warn is wanted, it is the (a)-warn option of Open Point 1, not a new cap.

10. **(audit pass 1, load-bearing) NULL-`subaccount_idx` fallback in the ledger rewrite.** The 4
    existing `ScalperRiskIntegrationTest` methods insert closed trades with no `subaccount_idx`. If the
    rewritten `ScalperAccountModel` counts only idx-stamped rows, those tests FAIL and any pre-V012
    paper history stops counting toward the freeze. **Options:** (a) keep a NULL-idx aggregate fallback
    (when no row carries an idx, use today's day-granularity win/loss COUNT) so legacy rows + the 4
    existing ITs behave identically and per-account freeze engages only once idx-stamped trades exist
    (recommended); (b) update the 4 tests to stamp idx (drops the "unchanged" guarantee + the legacy
    semantics). **Recommended default: (a).** This is the single biggest correctness risk in the
    stream — call it out in PR-2.

11. **(audit pass 1) How does `onSignalTaken` know the signal is a scalper (to stamp an idx)?**
    `SignalTaken` + `OrderRequest` carry no scalper marker. **Options:** (a) add a `boolean scalper`
    to the `SignalTaken` record (a `signals`-module change, additive — the engine already knows via
    `strategy.scalper() != null`) and thread it to `OrderRequest` (recommended — single source of
    truth, no extra DB read); (b) a new `PaperSignalListener` repository read resolving the signal's
    strategy-version `scalper` block at open time. **Recommended default: (a).** Without this, every
    paper open (incl. non-scalper) gets an idx and pollutes the ledger.

---

## Audit pass 1 findings

Audited 2026-06-27 by opening every cited file. **Verdict: SOUND WITH OPEN POINTS** — the architecture
is correct and genuinely parity-safe (all four packages are account-side; the golden/parity harness
provably cannot see them), but four real implementation gaps and one cite misattribution had to be
fixed before this is developer-ready. Corrections applied in place above; summary:

**Citations — all spot-checked, mostly accurate.** Verified and CONFIRMED: `RiskService`
KILL_SWITCH/MAX_OPEN/DAILY_LOSS L25-27, `entryAllowed` L52-70, `dailyLossLimitInr` L72-78, `recordTrip`
L80-90, `update` L103-109; `ScalperAccountModel` ACCOUNTS=5 L32 / MAX_WINS=5 L35 / `scalperEntryAllowed`
L50-62 / day-count javadoc L17-22 / restart-state note L25; `PaperAccountService` equity L69 / dayPnl
L170-173 / capitalUsed L147 / buyingPowerWarning L157-167; `PaperPositionRepository` realizedOn
L221-227 / winLossOn L236-244 / openCount L209-212; `PaperService.doSettle` L208-224 (realized L218,
`positions.close` L222); `EmissionGuard` L15/L22-24/L31; `PaperEmissionGuard` L36-38/L41-43;
`SignalEngine` activeEntry L398-400 / scalperEntry-gate L454 / emitEntry-gate L578 — **and confirmed
`emissionGuard`+`scalperGate` are `Optional<>` (L101/L104), which is the linchpin of the parity claim**;
`JournalRepository` EntryInput 8-arg record L38-46 (the auto-journal `new EntryInput(...)` call has the
right 8 args in the right order — compiles) / insert L68-94 / paperPositionExists L62-65; `RiskController`
KEYS L27-28 / GET L43-60 / PUT L63-74 (non-key 400s); dead YAML keys `scalp-two-candle-nifty.yaml`
L52-54; schema `max_daily_loss_pct` at **`strategy-schema-v1.json:552`** (and L550/L551 siblings);
`StrategyCompiler` reads only `position_sizing` L66-69 + `session` L82; V006 risk_settings L4-5 A12 note;
V008 journal_entries L7-19 / nullable links L9-16; disposition `risk-framework.md` L25/26/27/28/35/41/45/52,
`trend-change.md` L40 (two-option text) + theme-rollup L56, audit `trend-change.md` L88/L118-119,
`GAP-DISPOSITION.md` L147 (five-account `[S]`/L), L167 (`daily-loss-maxpositions-wiring`), L177
(`auto-journal` `[S]`). Strategy lineage's latest applied migration is V010 → V011/V012 are correctly
the next two versions. All five cited test files exist; `PaperAccountRiskIntegrationTest` trip test
L84-92 + reset L57-67, `ScalperRiskIntegrationTest` insertClosed L76-84 + fiveLosses L37-43,
golden/parity byte-match asserts — all confirmed.

**One cite misattribution (FIXED):** §3.1(b)/§3.1(d) claimed `max_deployment_pct` "closes r6
≤10-20%/trade". r6 (`risk-framework.md` L19) is dispositioned to **`probability-graded-sizing`**, a
DIFFERENT package out of this stream; this package's deployment rows are r41 (L45) + r62 (L52). The
seed comment and the (b) prose were corrected, and Open Point 9 added.

**Soundness (FIXED in place):**
- §3.1(a) profit-trip needs its OWN per-day dedup field + an `update()` re-arm for `daily_profit_target`
  (the single `dailyLossTrippedOn` field + the `DAILY_LOSS`-only re-arm at L106-108 would cross-wire the
  two trips). Generalize to a key-keyed map. Noted inline + the deployment cap must NOT use that dedup.
- §3.1(b) deployment code must read `value` directly, NOT route through the mode-aware `limitInr`
  (absent `mode` defaults to `inr` at L74 → 20.0 read as ₹20 not 20%). Corrected the snippet.
- §3.1(c) afternoon taper needs a `LocalTime` import (not currently imported in `ScalperAccountModel`);
  the `Clock` is already present. Flagged in PR-1.

**Parity (CONFIRMED + tightened):** every change is genuinely `[S]` — the engine/replay harness
instantiates none of `RiskService`/`ScalperAccountModel`/`EmissionGuard` (all `Optional`, absent on the
golden path), so `GoldenDeterminismTest` + `BacktestParityTest` stay byte-identical. **Tightened two
points:** (1) GAP-DISPOSITION lists `daily-loss-maxpositions-wiring` under the `[P]` heading (L153/167) —
the plan's `[S]` is a justified *reclassification* (only option-i is `[P]`); §4 now says so. (2) the
five-account count→ledger rewrite is `[S]` to goldens but WILL shift account-side behaviour for legacy
NULL-idx rows unless the fallback is kept (next bullet).

**Completeness (gaps ADDED):**
- **NULL-idx backward-compat trap (§3.2):** the 4 existing scalper ITs insert idx-less trades; the
  ledger rewrite must keep a NULL-idx aggregate fallback or it breaks them and silently drops pre-V012
  history from the freeze. Added a resolution subsection + Open Point 10 (biggest correctness risk).
- **Two missing five-account wires (§3.2):** (1) nothing tells `onSignalTaken` the signal is a scalper
  (added Open Point 11 + a resolution step); (2) `PaperPositionRepository.insertOpen` (L85-109) has no
  `subaccount_idx` column/param — the actual DB write was unstated. Both added.
- **Auto-journal wiring (§3.4):** `PaperService` has no `ApplicationEventPublisher` (new ctor arg);
  the listener runs inside the close `@Transactional` so it MUST be `@TransactionalEventListener
  AFTER_COMMIT` (+ try/catch) or a journal failure rolls back the trade close; the `journal→paper`
  module edge + `PaperPositionClosed` placement must re-pass `ModularityTest`. All three added; the
  event field renamed `positionId` to match the listener accessor.
- **FE binding missing (§3.1 / §7):** `PaperPage.tsx` renders risk limits as **hardcoded per-key
  blocks**, not a generic `{items}` loop — the two new caps need two new render blocks or they never
  appear in the UI (breaking the §5.5 e2e). Added to §3.1 data-flow + PR-1; effort bumped S→M.
- **Schema "comment" is invalid JSON (§3.3 step 2):** JSON has no `//` comments — replaced with a
  `description`-string option (with a ci-contracts recheck) or a YAML-header-only note.
- **Test list (§5.4):** added `ModularityTest` + a ci-contracts recheck as regression tripwires (both
  required by CLAUDE.md but absent from the plan).

**Open points:** the plan's 8 were genuine and well-reasoned; added 9 (r6 scope), 10 (NULL-idx
fallback — load-bearing), 11 (scalper-ness resolution at open). Open Point 4 (seed-vs-reset) is real and
correctly captured — note the reset at L61 re-arms only `daily_loss_limit`'s dedup, so once a profit
dedup exists the reset must clear it too (covered by the §3.1(a) note).

**Net:** safe to hand to a developer AFTER the five completeness gaps are read as part of the design
(they are now inline). No change is mis-marked `[P]`-as-`[S]`; nothing in the stream perturbs a golden.

---

## Audit pass 2 findings

Independent second pass, 2026-06-27. I re-opened every load-bearing source file myself (not trusting
pass 1's transcription) and re-derived the parity argument from the engine source. **Verdict: SOUND
WITH OPEN POINTS — readiness CONFIRMED.** The architecture holds, pass 1's five completeness gaps and
its one cite correction are all genuine and correctly resolved, and I found NO new soundness defect, NO
mis-marked `[P]`-as-`[S]`, and NO broken data-flow step. One source-doc counting inconsistency and two
low-severity observations are added below; none blocks the work.

**Citations re-verified independently (sampled ~30, all CONFIRMED).** Re-read and matched to byte:
`RiskService` keys L25-27, `entryAllowed` L52-70, `dailyLossLimitInr` L72-78 (and the `mode` default
`asText("inr")` at L74 — the linchpin of pass-1's deployment-snippet fix), `recordTrip` L80-90 (single
`dailyLossTrippedOn` field L37), `update` re-arm `DAILY_LOSS`-only L106-108; `ScalperAccountModel`
ACCOUNTS=5 L32/MAX_WINS=5 L35/`scalperEntryAllowed` L50-62/day-count javadoc L17-22/restart-state L25;
`PaperAccountService` equity L69/dayPnl L170-173/capitalUsed L147/buyingPowerWarning L157-167;
`PaperPositionRepository` insertOpen L85-109 (NO `subaccount_idx` — confirmed)/updateOpen L112-118
(averaging branch)/realizedOn L221-227/winLossOn L236-244/openCount L209-212; `PaperService` ctor
L95-110 (NO `ApplicationEventPublisher` — confirmed)/OrderRequest 8-field L39-47/doSettle L208-224
(realized L218, positions.close L222); `PaperSignalListener.onSignalTaken` L27-39 (synchronous, no
scalper marker); `SignalTaken` L10 = `(long signalId, Integer qty, BigDecimal fillPrice)` (confirmed no
scalper flag); `EmissionGuard` SPI in `signals`, `scalperEntryAllowed` default-true L22-24;
`JournalRepository.EntryInput` 8-arg L38-46 (the auto-journal `new EntryInput(null, e.positionId(),
null, null, note, tags, null, null)` call has the right 8 args/order — compiles) / insert L68-94 does
NOT call `paperPositionExists` (confirmed); `RiskController.KEYS` L27-28 + non-key 400 L65-66 + GET
items-envelope L43-60; dead YAML keys L52-54 in `scalp-two-candle-nifty.yaml`; schema
`max_daily_loss_pct` at **L552** (siblings L550/L551); `StrategyCompiler` reads only `position_sizing`
L66-69 + `session` L82; V005 `paper_positions` GRANT L51, V006 risk_settings L4-5 A12 note + L40 grant,
V008 journal_entries L7-19 (CHECK 1-5, NULL-permitting, L15-16). Migrations: latest applied is **V010**
→ V011/V012 correct. Disposition rows re-read in full: `risk-framework.md` L25/26/27/28/35/45/52 all
carry `daily-target-caps`, L29/L31 `five-account-ledgers`, L41 `auto-journal`; `trend-change.md` L40 is
the two-option `daily-loss-maxpositions-wiring` row (the "(or formally rely on the paper-runtime
`daily_loss_limit`)" clause is verbatim — option (ii) is a faithful read); `GAP-DISPOSITION.md`
**L147** `five-account-ledgers [S]`, **L153/L167** `daily-loss-maxpositions-wiring` under the `[P]`
heading, **L177** `auto-journal` under `[S]`. All four cited test files exist; `ScalperRiskIntegrationTest`
and `PaperAccountRiskIntegrationTest` internals re-read (next bullet).

**The parity claim re-derived from source (CONFIRMED, strongest evidence).** I read `SignalEngine`
directly: `emissionGuard` is `Optional<EmissionGuard>` (L101) and `scalperGate` is
`Optional<ScalperConfluenceGate>` (L104); the global gate fires at `emitEntry` **L578**
(`emissionGuard.isPresent() && !emissionGuard.get().entryAllowed()`) and the scalper gate at
`scalperEntry` **L454** — both **suppress** an entry, neither alters a computed signal, and the comment
at L576-577 confirms exits (`emit()`) are deliberately NOT gated. The structural single-active-entry
check (`activeEntry` L398-400, scalper-distinguished by `strategy.scalper() != null` L405/L430) is the
real `max_positions` enforcer, exactly as §3.3 claims. **Grep proof of the central safety claim:**
`max_daily_loss_pct` / `max_positions*` are read in ZERO Java files across `libs/strategy-engine` AND
`services/strategy-signal-service` — the only Java hit is a *comment* in `scalper/ScalperRisk.java:13`,
not a read. So option (i) genuinely is the only `[P]` path and the plan's option (ii) avoids it. Every
`[S]` mark in the §4 table is correct.

**Pass-1's corrections re-checked — all RIGHT, none introduced a new error:**
- The `max_deployment_pct` mode-default trap is real: `dailyLossLimitInr` (L74) defaults an absent
  `mode` to `"inr"`, so routing the no-`mode` deployment row through `limitInr` would read `20.0` as
  ₹20. The corrected snippet reads `value` directly and does `equity*value/100` — correct. (`20.0`
  parses as a Jackson `DoubleNode`; `.decimalValue()` → `BigDecimal 20.0`, the same proven path the
  existing loss cap uses for `value:2.0`. No new issue.)
- The cite re-attribution (r6 → `probability-graded-sizing`, NOT this stream) matches
  `risk-framework.md` L19 verbatim; this package's rows are r41 (L45) + r62 (L52). Correct.
- The NULL-`subaccount_idx` backward-compat trap is the single biggest risk and is **fully
  confirmed**: `ScalperRiskIntegrationTest.insertClosed` (L76-84) inserts closed rows with NO
  `subaccount_idx`, and `fiveLossesFreezeAllSubAccounts`/`fiveWinsBankTheDay`/
  `winsAndLossesBelowBothCapsStillAllow`/`aFlatTradeCountsAgainstTheAccounts` rely on the
  day-granularity COUNT over ALL closed rows. The recommended NULL-idx aggregate fallback (Open
  Point 10) is the correct fix. (Minor: a couple of pass-1's method line-refs are off by 1-2 lines —
  annotation vs signature line — immaterial.)
- The auto-journal AFTER_COMMIT requirement is correct: `doSettle` runs inside the caller's
  `@Transactional` (`closePosition` L184, `markToCloseIntraday` L284), so a synchronous same-tx
  listener failure would roll back the close. `@TransactionalEventListener(AFTER_COMMIT)` + try/catch
  is the right shape.

**New observations (audit pass 2) — all LOW severity, none blocking:**
1. **Count reconciliation (FIXED inline §1):** the plan's `daily-target-caps` "7 gaps" counts 7
   disposition table LINES; the disposition's own rollup (`risk-framework.md` L76) says "(6 rows)". A
   pre-existing inconsistency in the SOURCE doc, not a plan defect — the 7 cited lines each check out
   and the stream total (11) is unaffected. Added a reconciliation note to §1.
2. **AFTER_COMMIT batches journal writes on the 15:45 sweep (note, not a defect).** Because
   `markToCloseIntraday` (`PaperService` L284) is a single `@Transactional` that settles EVERY
   intraday position in one tx, an `AFTER_COMMIT` auto-journal listener fires its inserts *after the
   whole sweep commits*, not per-position — N closes → N journal rows, all written post-commit in one
   burst. Functionally correct (each closed position still gets exactly one linked stub) and the
   `AutoJournalIntegrationTest` should still observe them, but the IT must let the sweep tx COMMIT (the
   §3.4 fact-3 note already says "must commit, not a rolled-back test tx"). No change needed; flagging
   so the test author expects post-commit, possibly-batched, inserts.
3. **Pre-existing latent bug in `PaperPositionRepository.intradayOpen()` (OUT OF SCOPE — do NOT fix
   here).** That query (L160-174) hand-lists columns and omits `stop_loss`/`take_profit`, yet maps with
   `PaperPositionRepository::map`, which reads `rs.getBigDecimal("stop_loss")`/`take_profit` by name —
   on a ResultSet that didn't SELECT them this throws `SQLException`. This predates this plan and the
   plan never touches `intradayOpen`, so per CLAUDE.md (leave pre-existing dead/buggy code, mention it)
   it is noted, not fixed. Relevant only to confirm V012's `subaccount_idx` add does NOT need to touch
   `intradayOpen` (it doesn't — the column is nullable, the mapper is name-based and reads neither the
   new column nor relies on it). Added as Open Point 12 so it is not silently inherited.

**Schema / grants re-checked (no gap).** V012 adds `subaccount_idx` to `paper_positions`; PG table-level
GRANTs (V005 L51: SELECT/INSERT/UPDATE/DELETE to `ay_strategy`) auto-extend to new columns, and the
service writes as `artha` (single writer) anyway — no re-grant needed. V012 grants only `SELECT` on the
new `scalper_subaccount` table, consistent (the model reads capital fractions; the only write is the
`paper_positions.subaccount_idx` stamp). V011 seed runs as the migration user, unaffected by the
`ay_strategy` read-only convention. Migration ordering (V011/V012 after V010, independent) holds.

**Dependency order re-checked (correct).** `daily-loss-maxpositions-wiring` AFTER `daily-target-caps`
(its resolution IS the §3.1(d) seed) is right; `five-account-ledgers` and `auto-journal` independent
is right. The PR-1-folds-the-wiring-doc-note packaging is sound (the wiring "package" is a
description-string + the seed, no code).

**Open points:** pass 1's 11 are well-reasoned and I concur with every recommended default. Added Open
Point 12 (the pre-existing `intradayOpen` column-list bug — explicitly OUT of scope, flagged so it is
not mistaken for a regression introduced by V012).

**Readiness verdict (pass 2):** READY to hand to a developer. Every signal-affecting surface is
account-side and provably invisible to the golden/parity harness (`emissionGuard`/`scalperGate` are
`Optional`, absent on the replay path; the dead YAML keys are read by zero Java). The only behaviour
changes are intentional and account-side (the seed turns caps on; the ledger rewrite must keep the
NULL-idx fallback). Nothing perturbs a golden, no contract drift beyond the optional schema-`description`
re-check (§3.3/§5.4), and the five pass-1 completeness gaps plus the three pass-2 notes are now inline.

> **Open Point 12 (audit pass 2) — pre-existing `intradayOpen` column-list bug (OUT OF SCOPE).**
> `PaperPositionRepository.intradayOpen()` (L160-174) SELECTs an explicit column list that omits
> `stop_loss`/`take_profit` but maps via `PaperPositionRepository::map` (which reads them by name) —
> a latent `SQLException` on the 15:45-MTM path that predates this stream. Do NOT fix it in this plan
> (CLAUDE.md: leave pre-existing code; surgical changes only). Recorded so V012's column add is not
> blamed for it and so a future cleanup can pick it up. **Recommended: leave untouched; file separately.**
