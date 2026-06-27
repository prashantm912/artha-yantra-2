# Risk governance — daily caps, 5-account ledgers, max-positions wiring, auto-journal

Status: PLAN (implementation-ready). Owner: single-owner. Target service:
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
→ `equity * value / 100`, else the raw INR) so loss and profit share one resolver. Generalize
`recordTrip` to take the `key` so the audit row names which cap fired.

**(b) New limit key `max_deployment_pct`** (closes r6 ≤10-20%/trade + ≤20%/day; also the §2.10
"survive the day" r41 `L45` and the §2.14 10-12% blowout `L52`). A per-trade + per-day capital
*deployment* cap (vs the loss cap which is a P&L cap). This one gates `entryAllowed()` on
`capitalUsed()` (already on `PaperAccountService` L147):

```java
public static final String MAX_DEPLOYMENT_PCT = "max_deployment_pct"; // NEW
// {enabled, value} — value is a % of equity; total open deployment may not exceed it.

Optional<Setting> deploy = settings.get(MAX_DEPLOYMENT_PCT);
if (enabled(deploy)) {
  BigDecimal cap = account.equity().multiply(value(deploy)).divide(HUNDRED);
  if (account.capitalUsed().compareTo(cap) >= 0) {
    recordTrip(MAX_DEPLOYMENT_PCT, account.capitalUsed(), cap);
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
  ('daily_loss_limit',   '{"enabled":true,"mode":"pct","value":2.0}'),   -- §2.3 r13 2-3%/day
  ('daily_profit_target','{"enabled":true,"mode":"pct","value":1.5}'),   -- §2.3 r14 1-2%/day
  ('max_deployment_pct', '{"enabled":true,"value":20.0}')                -- §2.2 r6 ≤20%/day
ON CONFLICT (key) DO NOTHING;
```

> The doc's "0.5% rule" (r12) is the *tighter, all-accounts* stop; 2-3% (r13) is the *outer* day
> cap. We seed the outer 2% as the default `daily_loss_limit` and document the 0.5% as a
> conservative owner-set value (Open Point 2). `ON CONFLICT DO NOTHING` keeps it idempotent and
> never clobbers an owner edit on re-migrate.

**Data flow:** YAML never involved. `RiskController.KEYS` (L27-28) gains the two new keys so the
React Paper/Settings panel can PUT them; `GET /settings` already returns all rows. The trip writes
a `risk_audit` row (existing append-only log) named by the cap that fired.

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

**Stamping the sub-account at open** needs the entry path to know the assignment. The cleanest
seam: `PaperSignalListener.onSignalTaken` (L27-39) → `PaperService.openOrder` already opens the
position; add an optional `subaccountIdx` to `OrderRequest` set from
`ScalperAccountModel.nextFreeAccount()` when the originating signal is a scalper. (Non-scalper
trades leave `subaccount_idx` NULL and are invisible to this model.)

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
2. **Mark the YAML keys as advisory/dead in the schema doc** (a comment in
   `strategy-schema-v1.json` near L552 and the scalper YAML headers) so a future reader does not
   re-add a compiler read expecting enforcement. Do **not** delete them (CLAUDE.md: leave
   pre-existing dead code; the schema validates them and removing the schema entry is a contract
   change).
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
(id, realized, closeReason) — mirroring the existing `SignalTaken` event pattern that
`PaperSignalListener` consumes.

**New listener** `journal/AutoJournalListener.java` (journal module, same schema as `JournalRepository`):

```java
@Component
public class AutoJournalListener {
  private final JournalRepository journal;
  @EventListener
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
L9-16). Same-schema FK is validated by `paperPositionExists` already.

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
| `five-account-ledgers` schema + ledger | **[S]** | New table + nullable `paper_positions` column + scalper-path assignment. Account-side; the gate still only SUPPRESSES scalper entries. No emission change. | None. |
| `daily-loss-maxpositions-wiring` (option ii) | **[S]** | Resolved as doc-correction + the §3.1 seed — NO compiler read added. (Option i, wiring the YAML keys into `StrategyCompiler`, WOULD be `[P]` and is explicitly rejected — Open Point 3.) | None (because we do NOT take option i). |
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

### 5.4 Golden / parity tripwires (MUST stay byte-identical — regression only)
- `GoldenDeterminismTest` (`libs/strategy-engine`) — re-run; assert byte-identical. **Do NOT
  regenerate.** (No scalper / no `RiskService` on the harness path.)
- `BacktestParityTest` (`services/backtest-service`) — re-run; the three byte-match asserts stay
  green.

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
| `daily-target-caps` | **S** | **PR-1** `feat(strategy-signal): daily profit/loss/deployment caps + seed (account-side)` — `RiskService` (3 keys + shared `limitInr` + generalized `recordTrip`), `RiskController.KEYS`, `V011__seed_risk_defaults.sql`, the afternoon taper in `ScalperAccountModel`, IT + e2e. |
| `daily-loss-maxpositions-wiring` | **S** | **folded into PR-1** — the schema-doc comment + scalper-YAML-header note that the keys are advisory (the seed in PR-1 is the enforcer). One-line doc change, no code. |
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
