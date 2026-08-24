# What the minervini 12-slot cap is actually doing

**Date:** 2026-08-13 · **Scope:** measurement only, no change proposed · **Status:** findings

---

## Verdict

**The 12 is a diversification / return-optimisation number in ORIGIN that has become a capital-deployment
limit in EFFECT — and the two are welded together by design, not by coincidence** `[sourced + computed]`.
The slot count came first, from a real backtest slot sweep; the per-name sizing (6.5%) and the deployment
cap (80%) were then *reverse-engineered to make exactly 12 fit*, and the commit that did so says so in
words. The consequence today is that the slot cap and the capital cap bind within **₹1,294.79** of each
other, so the slot count is now a capital limit wearing a slot-count costume — but it arrived at that
costume from the other direction than the premise supposes.

Against the four candidate explanations:

| Explanation | Verdict | Strength |
|---|---|---|
| **Capital constraint** ("12 × typical size ≈ the book") | **TRUE in current effect**, by construction | **Very strong** `[computed]` — 79.10% deployed against an 80% cap; ~₹1,295 of headroom vs a ~₹9,315 next position |
| **Diversification / concentration rule** | **TRUE in origin** | **Very strong** `[sourced]` — v7 slot sweep {8,12,16,20,24} picked 12 on net CAGR + drawdown |
| **Risk proxy** (standing in for the absent open-risk cap) | **FALSE** | **Strong** `[computed]` — actual open risk is 6.36% of equity, already *above* the 6.0% cap manas enforces. If 12 were a risk proxy it is calibrated too loose to be one |
| **Arbitrary / never revisited** | **PARTLY TRUE** | **Moderate** `[sourced + computed]` — the sweep ran on a **₹10 L** book; the book was cut to **₹1.5 L** six hours later and the slot number was never re-derived |

**The single most decision-relevant fact:** at the published 6.5%-of-equity sizing and the enabled 80%
deployment cap, the capital supports **`floor(80 / 6.5) = 12`** positions `[computed]`. The answer to "how
many positions would the capital actually support?" is **exactly 12**. Raising the slot cap alone would
therefore not admit trades — it would move the refusal from the emission gate to the fill gate.

### Premise check (STEP 0)

Every factual element of the premise verified, with one correction.

- ✅ 12-slot cap, blocking entry at run start — verified in the live log and DB `[sourced]`.
- ✅ 16 of 20 would-be entrants were minervini — verified: minervini `would-enter 16`, manas `would-enter 4` `[sourced]`.
- ✅ `PyramidPolicy.NONE` for minervini, no portfolio **open-risk** cap `[sourced]`.
- ❌ **CORRECTION — "position count is the ONLY brake bounding that family's exposure" is WRONG.**
  minervini carries `max_deployment_pct = 80%`, **enabled**, and it is a portfolio-level *capital* rail
  evaluated on every entry `[sourced]`. It is not merely present-but-dormant: the book sits at **79.10%**
  of it. The premise is right that there is no open-**risk** cap; it is wrong that slots are the only brake.
  This correction is the finding that changes the decision.

---

## 1. Where the cap lives and how it is enforced

**One value gates, and it is the DB row — not the YAML** `[computed]`.

| Layer | Value | Enforced? | Evidence |
|---|---|---|---|
| Flyway seed | 12 | — (seeds the row) | `deploy/flyway/strategy/V021__paper_books.sql:59`, comment at `:57` |
| Live DB row `strategy.risk_settings` | **12, `enabled: true`** | **YES — this is the gate** | SQL below |
| Published strategy config `risk.max_positions` | **10** | **NO — provably inert** | `StrategyCompiler.java:65-70` |
| YAML `minervini-*.yaml` | 10 | NO | `minervini-vcp.yaml:45` (+3 siblings) |

Live row, never touched since seeding `[sourced]`:

```
 book      | key                      | value                          | updated_ist
 minervini | max_open_paper_positions | {"value": 12, "enabled": true} | 2026-07-05 14:02:34.844003
 minervini | max_deployment_pct       | {"value": 80.0, "enabled": true} | 2026-07-05 14:02:34.844003
```

### The DB and the config disagree — 12 vs 10 — and this is a live finding

`risk.max_positions: 10` sits in all four published minervini configs and is **never read by anything**
`[computed]`. `StrategyCompiler` parses the `risk` block and takes only `position_sizing` and `session`:

```java
JsonNode risk = config.path("risk");
StrategyDefinition.SizingSpec sizing =
    new StrategyDefinition.SizingSpec(
        risk.path("position_sizing").path("method").asText(),
        risk.path("position_sizing").path("params") ...);
```
— `libs/strategy-engine/src/main/java/.../config/StrategyCompiler.java:65-70`

`StrategyDefinition` has **no field** for it, so the value is discarded at compile time. Zero production-Java
references to `maxPositions` exist; the only survivors are `strategy-schema-v1.json` (declares it required)
and `ParameterPaths.java:18,42` (whitelists it as an *optimizer-tunable* path). **The 10 has no effect —
the live gate is the DB's 12.**

Worth noting for contrast: **manas is consistent** — its YAML says `max_positions: 7` and its DB rail is
`7` `[sourced]`. Only minervini drifted, because the 10 was written 2026-07-04 (#549) and the 12 arrived
2026-07-05 via a live `PUT` + V021, without anyone updating the YAML.

### Enforcement path

```
SwingBatchEngine.entryBlocked()          :578   → emissionGuard.entryAllowed(book)
  ├─ checked ONCE at run start           :439   → logs "entry pass skipped … book gate blocks entry"
  └─ re-checked BEFORE EVERY emit        :514   → "book gate tripped mid-run after N entries"
        ↓
RiskService.entryVeto(book)              :408   → rails in order:
   1. kill_switch                        :409
   2. max_open_paper_positions           :412-416   ← BLOCKING TODAY (12 >= 12)
   3. daily_loss_limit                   :417
   4. daily_profit_target                :425   (no minervini row → skipped)
   5. max_deployment_pct                 :435-450  ← 79.10% of cap, NOT yet tripping
   6. heat_cap_pct                       :455+  (minervini row disabled)
        ↓ (separately, at the FILL)
RiskService.deploymentWouldCross()       :160-171 → PaperService.java:945 → 422 RISK_ENTRY_BLOCKED
```

`max_open` is checked **second**, before the deployment rail, which is why today's refusal is labelled
`max_open_paper_positions` rather than `max_deployment_pct` `[computed]`. The ordering — not the relative
tightness of the two rails — is what determines which name appears in the log.

The live refusal, 2026-08-13 03:05:37Z = **08:35:37 IST** `[sourced]`:

> `minervini swing: entry pass skipped — the minervini book gate blocks entry at run start; 118 funnel candidate(s) not scanned`

---

## 2. Which rails apply — minervini vs manas

`PyramidPolicy.NONE` confirmed at `MinerviniDoctrine.java:190-191`; its `wouldBreachRiskCap` is a hardcoded
`return false` at `PyramidPolicy.java:86`, documented "the Minervini/default family carries no portfolio-risk
cap" `[sourced]`.

| Rail | Mechanism | minervini | manas-arora | Evidence |
|---|---|---|---|---|
| `kill_switch` | `risk_settings` | enabled, not tripped | enabled, not tripped | live row |
| **`max_open_paper_positions`** | `entryVeto` :412 | **12 — BINDING NOW (12/12)** | 7 — slack (6/7) | live row |
| **`max_deployment_pct`** | `entryVeto` :435 + `deploymentWouldCross` :160 | **80% — 79.10% used, ~1 pp of slack** | 80% — 66.76% used | live row |
| `daily_loss_limit` | `entryVeto` :417 | 10% pct, enabled | 10% pct, enabled | live row |
| `daily_profit_target` | `entryVeto` :425 | **no row** (swing books exempt by design) | **no row** | V021 comment `:44-45` |
| `heat_cap_pct` (F9 SPAN) | `entryVeto` :455 | 60% but **`enabled: false`** | 60% but **`enabled: false`** | live row |
| **Aggregate portfolio OPEN-RISK cap** | `PyramidPolicy.wouldBreachRiskCap` + `RiskService.manasAggregateRiskCheck` | **NONE** — `PyramidPolicy.NONE` returns `false`; the manas check is hard-scoped out at `RiskService.java:295` | **6.0% — ENFORCED and BINDING (5.995%)** | `MinerviniDoctrine:191`, `PyramidPolicy:86`, `RiskService:295`, env `ARTHA_MANAS_ARORA_PYRAMID_MAX_PORTFOLIO_RISK_PCT=6.0` |
| Pyramiding / adds | `PyramidPolicy.hasRoom` | none (single-lot) | `ManasPyramidPolicy`, flag-gated | `MinerviniDoctrine:191` |
| Per-name sizing | published config | `percent_equity` **6.5%** | `atr_risk` `risk_pct_equity` **1.0** | published config |
| `risk.max_positions` | — | 10 — **INERT** | 7 — inert but consistent | `StrategyCompiler:65-70` |

`RiskService.java:295` is the categorical exclusion — the aggregate risk check returns `ADMIT` for any
non-manas book before computing anything:

```java
if (!BookResolver.MANAS_ARORA.equals(book) || fillPrice == null || qty <= 0) {
```

### The two families are bound by *different* rails — and this is the sharpest contrast in the report

`[computed]`, from the same 08:35 run:

| | minervini | manas-arora |
|---|---|---|
| Open positions | **12 / 12 slots** (full) | 6 / 7 slots (**one free**) |
| Deployed | **79.10%** of an 80% cap | 66.76% of an 80% cap |
| Aggregate open risk | **6.36%** — *uncapped* | **5.995%** — *capped at 6.0%* |
| Bound by | slots (with capital ~1 pp behind) | **risk** |
| would-enter / admitted | 16 / 0 | 4 / 0 |

manas had a **free slot** and still admitted nothing, because its risk cap bit first — the run log names
four refusals, `BIRLACABLE`, `HAPPYFORGE`, `BLUSPRING`, `AUTOIND`, each *"would breach the open-risk cap —
skipped"* `[sourced]`. Note `strategy.risk_audit` carries only **one** row for that date (`BIRLACABLE`,
08:35:00). `RiskService.recordPyramidRiskCapBreach` dedups on `(book, pyramid_risk_cap)` per **IST day**
(`if (today.equals(trippedOn.get(dedupKey))) return;`), so the first refusal of the day writes the row and
every later one is silent — **the audit table undercounts refusals 4:1 here** `[sourced]`. Anyone sizing
this rail's impact from `risk_audit` alone will read it as four times gentler than it is. (`trippedOn` is
in-memory, so a restart also re-arms it mid-day.)

**minervini's 6.36% already exceeds the 6.0% that manas is held to.** If the 12 were serving as a risk
proxy it would be a failing one; the book is carrying more aggregate open risk than its sibling is
permitted, and nothing in the minervini path measures that.

---

## 3. Capital arithmetic

All 12 holdings are NSE equities → `InstrumentClass.EQUITY` → `capitalUsed` is **full notional at
`avg_entry_price`** (`PaperAccountService.usageFor`, `RestInstrumentMetaClient:52-58`; even the lookup-failure
fallback is `EQUITY_PROXY`) `[sourced]`.

### A prerequisite finding: unrealised P&L is structurally ZERO for swing books

`equity(book) = startingCapital + realizedTotal + unrealizedTotal`, and `unrealizedTotal` marks each
position via `lastTick.lastPrice(...).orElse(pos.avgEntryPrice())` — Redis hash `ticks:last`
(`PaperAccountService.java:74, 93-99`) `[sourced]`.

Measured at **11:32 IST with the market open** `[computed]`:

- `ticks:last` (live db0) holds **307** keys: 152 NFO, 148 BFO, 2 BSE, 5 NSE — and the 5 NSE keys are
  **indices only** (`NIFTY 50`, `INDIA VIX`, `NIFTY MID SELECT`, `NIFTY BANK`, `NIFTY FIN SERVICE`).
- **All 12 minervini holdings: ABSENT.**
- Zero `TICK_AGG` candles have *ever* existed for these symbols (only `BACKFILL` / `BHAVCOPY` / `KITE`),
  so this is structural non-subscription, not a time-of-day artifact.

⇒ **`unrealizedTotal("minervini") = 0.00` always.** Every swing position marks at its entry price for
equity purposes. The rail therefore measures against an equity that is **₹18,120.20 lower** than the book's
real mark-to-market value (computed from 2026-08-12 daily closes) — i.e. **the deployment rail is ~₹14,500
tighter in rupees than a working MTM would make it.**

### The numbers as the code computes them

| Quantity | Value | Source |
|---|---|---|
| Starting capital | ₹150,000.00 | `paper_account` id=2 |
| Realised P&L (9 closed trades) | −₹6,689.84 | `paper_positions` |
| Unrealised (as the code sees it) | **₹0.00** | structural, above |
| **Equity** | **₹143,310.16** | computed |
| **`capitalUsed`** (Σ qty × avg_entry) | **₹113,353.34** | computed |
| **Deployment cap** (80% × equity) | **₹114,648.13** | computed |
| **Headroom** | **₹1,294.79** | computed |
| **Deployed** | **79.10% of an 80.00% cap** | computed |
| Next position size (6.5% × equity) | **₹9,315.16** | `PaperEmissionGuard.java:157-158` |

**The 13th position needs ~₹9,315 and ₹1,294.79 is available.** It does not fit — and no *cheaper* name
fits either `[computed]`: for any price `P`, the sized notional is `floor(9315/P) × P`. If `P ≤ 1,294.79`
the result is ≥ ₹9,058; if `P > 1,294.79` the result is ≥ `P` > headroom; if `P > 9,315` the size floors to
0 and the order is rejected `ZERO_SIZE`. **There is no equity price at which a 13th position fits.**

Even on the generous view — crediting the real +₹18,120.20 unrealised — equity would be ₹161,430.36, the cap
₹129,144.29, headroom ₹15,790.95 and the next size ₹10,492.97: **one** position, not sixteen `[computed]`.

### How many positions does the capital support? Exactly 12.

`floor(80% / 6.5%) = floor(12.31) = 12`. The 13th would need 84.5% of equity against an 80% cap `[computed]`.
The observed book confirms the design: all 12 notionals cluster just under 6.5% of the then-current equity
(₹8,801.90 – ₹9,752.46; mean ₹9,446.11), the spread being integer-share rounding, not policy.

### What would happen if only the slot cap were raised

`entryVeto`'s deployment check is `used >= cap`, and ₹113,353.34 < ₹114,648.13, so **emission would
proceed** `[computed]`. The refusal would then land at the fill, via
`deploymentWouldCross` (₹113,353.34 + ₹9,315.16 = ₹122,668.50 > ₹114,648.13) → `PaperService.java:945-958`
→ **422 `RISK_ENTRY_BLOCKED`**, one `paper_order_rejections` row per attempt.

So the book would move from *cleanly gated at emission* to *emitting entry signals that all bounce at the
fill*, writing `strategy.signals` rows with no positions behind them. **The refusal relocates; it does not
disappear.**

One further consequence `[computed]`: because a close frees ~₹9,400 of capital and one slot *together*,
the deployment rail permits roughly **one entry per close** regardless of the slot cap. At the current book
size and sizing, raising the slot number above 12 changes throughput by approximately nothing until the
book's capital grows.

---

## 4. Provenance

Verified verbatim against git; every quote below re-read from the repo `[sourced]`.

**The number never began life in a migration.** V021 (commit `738b43c9`, #566, 2026-07-05 12:45 IST)
*transcribed* a value already applied live. The live `PUT`s are still visible in `strategy.risk_audit`
`[sourced]` — note `book` is NULL, i.e. pre-split global rows, and the two land **five minutes apart**:

```
 book | key                      | action | detail                            | ist
      | max_deployment_pct       | UPDATE | {"value":80.0,"enabled":true}     | 2026-07-05 06:11:35
      | max_open_paper_positions | UPDATE | {"value":12,"enabled":true}       | 2026-07-05 06:06:54
```

**"backtest-tuned" is a true and traceable claim.** It refers to the v7 slot sweep (PR #562), doc-of-record
`docs/strategies/minervini-swing-backtest-results.md` §6d:

| Slots | Taken | Gross CAGR | Net CAGR | Net DD | Net Sharpe |
|--:|--:|--:|--:|--:|--:|
| 8 | 733 | 51.5% | 38.8% | −65.8% | 0.75 |
| **12** | 1,112 | 52.1% | **39.6%** | **−51.5%** | 0.80 |
| 16 | 1,464 | 46.0% | 35.2% | −60.7% | 0.92 |
| 20 | 1,835 | 33.0% | 23.6% | −58.1% | 0.78 |
| 24 | 2,215 | 33.4% | 25.6% | −54.8% | 0.83 |

Verdict quoted: *"12 slots is the sweet spot — best net CAGR and lower drawdown (−51.5% vs −65.8%) and
higher Sharpe than 8."* Sweep source `MinerviniBacktestService.java:75` (`SLOT_SWEEP = {8,12,16,20,24}`).

**The causal chain is documented, and the 78%-under-80% is DELIBERATE.** Commit `928040e6` (#563,
2026-07-05 06:24 IST), verbatim:

> *"The v7 slot sweep found ~12 concurrent positions optimal (best net CAGR + lower drawdown than 8). To let
> the paper book actually reach 12 at ~80% deployment, size each name at 6.5% of equity (12 × 6.5% ≈ 78%).
> Pairs with the risk-setting changes max_open_paper_positions=12 + max_deployment_pct=80 (DB rows, applied
> live)."*

Order of derivation, each with a distinct origin `[sourced]`:

1. **12** ← the v7 backtest sweep (empirical).
2. **80%** ← an **owner pick**, raised from a prior global 20%, annotated *"fills the 12-slot book"*.
3. **6.5%** ← **derived arithmetic**, the residual chosen so 12 positions fit inside 80%.

So the near-coincidence in §3 is the *designed* headroom. The premise's "capital limit wearing a slot-count
costume" is right about today's mechanics but backwards about the history: the capital rails were tailored
to the slot count, not the other way round.

### What has changed since — the "never revisited" leg

⚠️ **The sweep ran on a ₹10 L book.** `MinerviniBacktestService.java:225` defaults
`artha.minervini.backtest.capital` to `1000000`, no env override exists on the live
`ay-market-data-service`, and the doc's own model config says *"book **₹10 L**"* (line 268) `[sourced]`.
**Six hours later V021 cut the book to ₹1.5 L and no slot number was re-derived** `[computed]`. Per-name
size fell from ~₹65,000 to ~₹9,315 — a 6.7× reduction.

This is not a blanket omission: the *v6 turnover-floor* sweep **did** sweep book size down to ₹1.5 L
(§6c, line 313) and its finding *was* applied. Only the **slot** dimension retains the ₹10 L basis.

Two facts bear on whether that matters, and they point in the same direction `[sourced]`:

- The sweep's own stated reason for preferring 12 over 8 includes *"smaller per-position size also lowers
  per-trade impact cost."* At ₹9,315/name that term is already negligible, so this leg of the rationale does
  not carry over to a ₹1.5 L book.
- The repo already knows the number is not portable across contexts: PR #615 found that for **manas**, 12
  slots is the *worst* of the sweep, with the explicit note *"the Minervini 12-slot lesson does NOT transfer."*

Neither the ₹1.5 L book size (an owner ruling, `docs/superpowers/plans/2026-07-05-manas-arora-and-book-separation-plan.md`)
nor the 12 has been changed since 2026-07-05 — **39 days** `[computed]`.

---

## 5. What would actually be admitted

**This did not need estimating — the engine already measures it** `[sourced]`. `SwingBatchEngine` runs a
measurement-only admission probe (`computeAdmissionProbe`, `:620-656`) that re-evaluates every non-held
funnel candidate **ignoring the cap** and reports `would-enter` / `admitted` / `cap-exceedance`. It applies
the real gates — `minBars`, setup routing and the frozen `EntryEvaluator` — so `would-enter` is a genuine
count of *entries that fired*, not a raw funnel count.

The 08:35 run `[sourced]`:

> `minervini swing batch: 4 strategies, 118 candidates, 0 entries, 0 exits, 0 exit-skipped (would-enter 16, admitted 0, cap-exceedance 16)`
> `manas-arora swing batch: 2 strategies, 102 candidates, 0 entries, 0 exits, 0 exit-skipped (would-enter 4, admitted 0, cap-exceedance 4)`

So of 118 funnel candidates, **16 fired a real entry** — the 118 are indeed not all viable, but 16 is a
measured, gate-passing figure, and it confirms the premise's 16-of-20.

**However, the probe models the SLOT cap only — it does not price the candidates.** Combining it with §3
`[computed]`:

| Slot cap N | Admitted by slots | Admitted after `max_deployment_pct` | Binding rail |
|---|---|---|---|
| 12 (today) | 0 | **0** | `max_open_paper_positions` |
| 13 | 1 | **0** | `max_deployment_pct` (at the fill, 422) |
| 15 | 3 | **0** | `max_deployment_pct` |
| 20 | 8 | **0** | `max_deployment_pct` |
| ∞ | 16 | **0** | `max_deployment_pct` |

**At every N, capital admits zero** — headroom ₹1,294.79 against a ₹9,315.16 minimum, with no price at which
a position fits (§3). On the generous full-MTM view the column becomes `1` rather than `0`, still not 16.

**The slot cap is not the real binding constraint.** It is merely the rail that is *checked first*.

### Historical context — the cap has bound continuously for a month

`strategy.swing_batch_runs`, `batch='minervini'` `[sourced]`:

| run_date | candidates | entries | open_at_start | would_enter | admitted | cap_exceedance |
|---|---|---|---|---|---|---|
| 2026-07-13 | 0 | 0 | 18 | 12 | 0 | 12 |
| 2026-07-15 | 103 | 2 | 15 | 11 | 2 | 9 |
| 2026-07-21 | 105 | 2 | 15 | 8 | 2 | 6 |
| 2026-07-31 | 111 | 1 | 14 | 20 | 1 | 19 |
| 2026-08-03 | 139 | 1 | 14 | 23 | 1 | 22 |
| 2026-08-06 | 0 | 0 | 15 | 18 | 0 | 18 |
| 2026-08-10 | 0 | 0 | 15 | 21 | 0 | 21 |
| 2026-08-11 | 109 | 0 | 15 | 8 | 0 | 8 |
| 2026-08-12 | 118 | 0 | 15 | 16 | 0 | 16 |

`cap_bound = true` on **every one of the 22 runs since 2026-07-13**; only **9 entries** were admitted across
that month, and none since 2026-08-03. Pressure is rising — `would_enter` averaged **8.8** across the 14
capped July runs and **17.3** across the 8 August runs `[computed]`.

**The shed names are mostly fresh, not a recurring queue** `[computed]`: across August, 137 drop events span
**115 distinct symbols**, with a maximum recurrence of 3 days (`HAPPYFORGE`, `SALSTEEL`). Minervini setups
are transient — an untaken breakout is generally gone, not waiting. So the ~16/day are largely genuine
distinct missed opportunities rather than the same names re-queuing.

---

## 6. Concentration check

**No sector or industry data exists anywhere in the platform** `[computed]` — an
`information_schema.columns` sweep of the `marketdata` and `strategy` schemas for `%sector%` / `%industry%`
returns only `segment` on `instruments` / `fii_derivative_stats`, which is exchange segment (NSE/NFO), not a
GICS-style classification. A sector concentration rule could not be evaluated or enforced today even if
someone wanted one.

**Correlation proxy** — pairwise Pearson correlation of daily returns, 63 sessions from 2026-05-13, all 66
pairs `[computed]`:

| Metric | Value |
|---|---|
| Mean pairwise correlation | **0.090** |
| Min / Max | −0.134 / **0.429** |
| Pairs > 0.5 | **0** |
| Pairs > 0.3 | 3 (`KANORICHEM`–`PRECOT` 0.429, `HFCL`–`PRECOT` 0.349, `DIACABS`–`PRECOT` 0.336) |

⚠️ Gated on the retro-mutability warning: `marketdata.candles` is rewritten retroactively, so this measures
*today's stored history*, not what was true at entry. It bounds rather than pins.

**The "12 correlated names" worry is not borne out** — mean 0.09 is close to independent, and nothing
exceeds 0.5. On the return-correlation measure this is genuine diversification.

But there is a **different, real concentration**: all 12 are small/micro-caps outside every tracked index
`[computed]`. A join against `marketdata.index_constituents` returns **zero rows** for all 12. Available
market caps span ₹106 Cr (`AUTOIND`) to ₹30,265 Cr (`HFCL`), with 4 of 12 absent from
`equity_fundamentals` entirely and 2 more carrying NULL caps (`as_of 2026-07-04`, ~5.5 weeks stale). The
book is uniformly concentrated in the small-cap *factor* even while individual names are uncorrelated —
a common-shock exposure a pairwise-correlation measure over a calm window will understate.

---

## 7. Incidental findings

1. **Three phantom held anchors** `[computed]`. The engine's held-skip set is built from active signal
   anchors, not paper rows — `open_at_start` reports **15** while the book holds **12** positions. The
   extra three are `INDUSINDBK`, `SENORES`, `TMB`, all anchored **2026-07-03**, i.e. before V021's
   `DELETE FROM paper_positions` wiped every book flat on 2026-07-05. Their `TAKEN` entry signals were
   never retired, so the engine has skipped those three names as "held" for 39 days. They do **not**
   consume a slot (the rail counts paper rows), but they do suppress re-entry.
2. **The deployment rail measures against a stale equity** (§3) — structurally-zero unrealised understates
   minervini's equity by ₹18,120.20 today, making the rupee cap ~₹14,500 tighter than a working MTM would
   make it. This affects `daily_loss_limit` and any `%`-of-equity rail, not just deployment.
3. **`risk.max_positions` is dead config in every strategy** (§1), yet `strategy-schema-v1.json` declares
   it **required**. A reader can reasonably believe it gates.

---

## OPEN DOUBTS

1. **Sizing at entry time was not reconstructed.** §3's ₹9,315.16 is *today's* 6.5% of *today's* equity.
   Each of the 12 was sized against the equity prevailing on its own entry date, which I did not
   reconstruct. The observed notionals (₹8,801.90–₹9,752.46) are consistent with 6.5% of an equity in the
   ₹135k–₹150k band, but I did not pin any individual one. `[assumed → the derived "12 positions is what
   capital supports" is robust to this, since it depends on the 6.5%/80% ratio, not the level]`
2. **No live API confirmation of `equity` / `capitalUsed`.** I computed both from SQL plus the code path
   rather than reading `GET /api/v1/paper/account`, deliberately avoiding the credential-entry step. A
   discrepancy between my arithmetic and what the service reports would change §3's headroom figure. The
   *direction* (headroom ≪ one position) is robust to plausible error, but the exact ₹1,294.79 is not
   independently confirmed.
3. **Stop distances are ~8.05%, not the configured 8.00%.** Every position's `(entry − stop)/entry` is
   8.04–8.05%, suggesting the stop is computed off the pre-slippage signal price while `avg_entry_price`
   carries slippage. Not investigated; it very slightly overstates §2's 6.36% open-risk figure. `[assumed]`
4. **The 6.36% vs 6.0% comparison is cross-family, not like-for-like.** minervini's risk uses the persisted
   `stop_loss`; `RiskService.manasAggregateRiskCheck` uses a *governing stop* that may be a cached trail.
   For a book whose positions have trailed up, a trail-aware measure would read **lower**. The claim
   "minervini exceeds what manas is permitted" is therefore directionally strong but not a strict apples-to-apples
   figure. `[computed, with this caveat]`
5. **Whether the owner re-ratified the 12 after the book shrank to ₹1.5 L is unrecoverable from git.** The
   build-log records the money-adjacent picks as owner-confirmed, but those confirmations happened at
   06:24–06:37 while the book was still ₹10 L; the shrink landed at 12:45. This is a conversation artifact.
6. **`max_positions` inertness is grep- plus compiler-read, not a traced proof.** I confirmed
   `StrategyCompiler` discards it and `StrategyDefinition` has no field, which is strong. I did not trace
   the Python optimizer's override path, which whitelists `risk.max_positions` as tunable — if anything
   there reads it, a 10-vs-12 conflict would be live in that context.
7. **The correlation window (63 sessions) is short and spans a rising tape.** Correlations typically rise
   in drawdowns, which is exactly when a concentration cap matters. Mean 0.09 should not be read as
   "diversified under stress".
8. **PR #562's own body was not retrieved** — the v7 sweep is sourced from the committed doc-of-record and
   the build-log, either of which could omit reasoning the PR carried.

---

*Measurement only. No cap change is recommended here; the owner decides.*
