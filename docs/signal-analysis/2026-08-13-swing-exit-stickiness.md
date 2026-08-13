# Swing books: why every open position decides HOLD (2026-08-13)

**Verdict — (a): HOLD is correct on all 18 open positions, and the books' inability to take new
entries is NOT an exit defect.** [computed] No position is within 6.1% of its governing exit level
except two (minervini INOXINDIA at 0.78%, minervini PRECOT at 3.48%); 16 of 18 are in profit; zero
have a NULL `stop_loss`; the exit pass ran with `exit_skipped = 0`. The lock is caused by two
*different* admission caps on the entry side, and the premise mis-attributes both. Elements of (b)
and (c) are true but are **not** what is holding the books shut — they are documented in
[§5](#5-reasoning) so they are not lost.

---

## 0. STEP 0 — premise check

| Premise claim | Verdict | Evidence |
|---|---|---|
| 18 OPEN rows, manas 6 / minervini 12 | **CONFIRMED** [computed] | `strategy.paper_positions` GROUP BY book, status |
| Ages 6–37 days as of 2026-08-13 | **CONFIRMED** [computed] | oldest AUTOIND `2026-07-07`, newest SKYGOLD `2026-08-07` |
| Last exits: manas 2026-08-06, minervini 2026-07-31 | **CONFIRMED** [computed] | `max(closed_at)` per book |
| Lifetime closed 10 / 9 | **CONFIRMED** [computed] | `paper_positions` status CLOSED |
| Batch counters (102/118 candidates, 0 entries, 0 exits, cap-exceedance 4/16) | **CONFIRMED** [sourced] | `strategy.swing_batch_runs` + container log, run `2026-08-13T03:05:35Z` / `03:06:21Z` |
| "persisted 6 / 15 sell-decision row(s)" | **CONFIRMED** [sourced] | `SwingBatchRecorder` log lines, same run |
| **"both sit at the 6% portfolio open-risk cap"** | **WRONG for minervini** [computed] | The 6% cap is **manas-only**. `PyramidPolicy.java:42`: "the Minervini/default family carries no portfolio-risk cap". `strategy.risk_audit` holds **8 `pyramid_risk_cap` TRIP rows, all `book='manas-arora'`, zero for minervini**. |
| **"so refuse every new entry"** | **Right outcome, two different causes** [sourced] | minervini: `"minervini swing: entry pass skipped — the minervini book gate blocks entry at run start; 118 funnel candidate(s) not scanned"` — that gate is `max_open_paper_positions = 12` and the book holds exactly 12. manas: `"manas-arora swing: fresh entry for AUTOIND would breach the open-risk cap — skipped"` + `risk_audit` TRIP for BIRLACABLE. |
| "nothing has exited in 7–13 days" | **CONFIRMED, and exits do work** [computed] | 19 lifetime closes carry real `close_reason` values (`TRAILING_STOP` 11, `STOP_LOSS` 5, `MANUAL` 3). The machinery fires; it simply has not been triggered. |

Two further corrections found while measuring:

- **`strategy.sell_decisions` is not the exit decision log — it is a *survivor* log.** [computed]
  All **498 rows ever written** are `verdict='HOLD'`, `selling_now=false`, `sell_reason` NULL. It is
  written by `SwingBatchRecorder` *after* the engine's passes
  (`SwingBatchRecorder.java:230-235`), so a position that exited that day is simply absent. Proof:
  manas wrote 6 rows on 2026-08-05, **5** on 2026-08-06 (GRWRHITECH closed at 20:05:31, recorder ran
  at 20:05:33), 6 again on 2026-08-07. A 100%-HOLD table is therefore expected and carries no signal.
- **The row population is anchors, not positions.** [computed] minervini wrote **15** sell-decision
  rows against **12** open positions. The three extras — INDUSINDBK (signal 26), SENORES (20), TMB
  (23) — have no open `paper_positions` row in that book at all. `exitPass` enumerates
  `signals.activeEntries()` (`SwingBatchEngine.java:791` → `:587-595`), never `paper_positions`. This
  also explains `open_at_start = 15` in `swing_batch_runs` while the book gate counts 12.

---

## 1. Table 1 — every sell decision, most recent run

Run `run_date = 2026-08-13`, written 08:35:37 / 08:36:26 IST (catch-up for session 2026-08-12).
All 18 open positions are covered; 3 extra anchor-only rows are marked †. [computed, from
`strategy.sell_decisions`]

| book | symbol | entry | current | unreal % | `stop_level` | `trail_level` | `still_buyable` | `selling_now` | `sell_reason` | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| manas-arora | AVALON | 1759.50 | 1973.70 | +12.17 | 1589.53 | 1820.65 | f | f | — | HOLD |
| manas-arora | KANORICHEM | 152.21 | 157.29 | +3.34 | 137.86 | *(null)* | f | f | — | HOLD |
| manas-arora | PRECOT | 809.95 | 799.75 | −1.26 | 728.96 | *(null)* | f | f | — | HOLD |
| manas-arora | SANSERA | 3335.20 | 3925.00 | +17.68 | 3095.07 | 3684.06 | f | f | — | HOLD |
| manas-arora | SCPL | 615.00 | 650.00 | +5.69 | 553.50 | *(null)* | f | f | — | HOLD |
| manas-arora | SKYGOLD | 723.00 | 838.00 | +15.91 | 665.21 | 775.18 | f | f | — | HOLD |
| minervini | ATHERENERG | 1200.00 | 1572.60 | +31.05 | 1104.00 | 1180.38 | f | f | — | HOLD |
| minervini | AUTOIND | 87.82 | 99.77 | +13.61 | 80.79 | 86.21 | f | f | — | HOLD |
| minervini | AVALON | 1759.50 | 1973.70 | +12.17 | 1618.74 | 1737.36 | f | f | — | HOLD |
| minervini | CHENNPETRO | 1180.70 | 1377.00 | +16.63 | 1086.24 | 1194.89 | f | f | — | HOLD |
| minervini | DIACABS | 231.33 | 367.95 | +59.06 | 212.82 | 249.76 | f | f | — | HOLD |
| minervini | HFCL | 201.00 | 221.05 | +9.98 | 184.92 | 203.20 | f | f | — | HOLD |
| minervini | INDUSINDBK † | 974.35 | 1022.50 | +4.94 | 896.40 | 974.65 | f | f | — | HOLD |
| minervini | INOXINDIA | 1875.90 | 1905.00 | +1.55 | 1725.83 | 1890.21 | f | f | — | HOLD |
| minervini | KANORICHEM | 152.21 | 157.29 | +3.34 | 140.03 | **130.77** | f | f | — | HOLD |
| minervini | KAPSTON | 474.90 | 584.50 | +23.08 | 436.91 | **412.59** | f | f | — | HOLD |
| minervini | MENONBE | 220.13 | 235.63 | +7.04 | 202.52 | **187.73** | f | f | — | HOLD |
| minervini | PRECOT | 809.95 | 799.75 | −1.26 | 745.15 | 771.89 | f | f | — | HOLD |
| minervini | SENORES † | 1381.90 | 1495.00 | +8.18 | 1271.35 | 1319.25 | f | f | — | HOLD |
| minervini | SOTL | 607.45 | 700.00 | +15.24 | 558.85 | 595.49 | f | f | — | HOLD |
| minervini | TMB † | 778.65 | 856.15 | +9.95 | 716.36 | 796.86 | f | f | — | HOLD |

† anchor-only row: no open `paper_positions` row exists for this book/symbol.
**Bold** `trail_level` = trail sits *below* the initial stop, so the initial stop governs
(precedence is `stop_loss` before `trailing_stop`, `ExitEvaluator.java:325-329`).

`still_buyable = false` on all 21 rows is the entry screen re-run on a held name, not an exit signal.

---

## 2. Table 2 — per-position distance to the governing exit

`effective exit = max(stop_level, trail_level)` — whichever is higher is hit first, given the
precedence above. Current price = last 1d close, session **2026-08-12** (the newest bar for all 18;
`marketdata.candles` interval `1d`). Sorted by distance, tightest first. [computed]

| # | book | symbol | qty | entry | current | init stop | trail | **effective exit** | **dist %** | unreal ₹ | unreal % |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | minervini | INOXINDIA | 5 | 1876.84 | 1905.00 | 1725.83 | 1890.21 | **1890.21** | **0.78** | +140.80 | +1.50 |
| 2 | minervini | PRECOT | 12 | 810.35 | 799.75 | 745.15 | 771.89 | **771.89** | **3.48** | −127.20 | −1.31 |
| 3 | manas-arora | SANSERA | 6 | 3336.87 | 3925.00 | 3095.07 | 3684.06 | 3684.06 | 6.14 | +3528.78 | +17.63 |
| 4 | manas-arora | SKYGOLD | 24 | 723.36 | 838.00 | 665.21 | 775.18 | 775.18 | 7.50 | +2751.36 | +15.85 |
| 5 | manas-arora | AVALON | 8 | 1760.38 | 1973.70 | 1589.53 | 1820.65 | 1820.65 | 7.75 | +1706.56 | +12.12 |
| 6 | minervini | HFCL | 46 | 201.10 | 221.05 | 184.92 | 203.20 | 203.20 | 8.08 | +917.70 | +9.92 |
| 7 | manas-arora | PRECOT | 18 | 810.35 | 799.75 | 728.96 | — | 728.96 | 8.85 | −190.80 | −1.31 |
| 8 | minervini | KANORICHEM | 62 | 152.29 | 157.29 | 140.03 | 130.77 | 140.03 | 10.97 | +310.00 | +3.28 |
| 9 | minervini | AVALON | 5 | 1760.38 | 1973.70 | 1618.74 | 1737.36 | 1737.36 | 11.97 | +1066.60 | +12.12 |
| 10 | manas-arora | KANORICHEM | 100 | 152.29 | 157.29 | 137.86 | — | 137.86 | 12.35 | +500.00 | +3.28 |
| 11 | minervini | CHENNPETRO | 8 | 1181.29 | 1377.00 | 1086.24 | 1194.89 | 1194.89 | 13.23 | +1565.68 | +16.57 |
| 12 | minervini | AUTOIND | 111 | 87.86 | 99.77 | 80.79 | 86.21 | 86.21 | 13.59 | +1322.01 | +13.56 |
| 13 | minervini | MENONBE | 43 | 220.24 | 235.63 | 202.52 | 187.73 | 202.52 | 14.05 | +661.77 | +6.99 |
| 14 | manas-arora | SCPL | 23 | 615.31 | 650.00 | 553.50 | — | 553.50 | 14.85 | +797.87 | +5.64 |
| 15 | minervini | SOTL | 16 | 607.75 | 700.00 | 558.85 | 595.49 | 595.49 | 14.93 | +1476.00 | +15.18 |
| 16 | minervini | ATHERENERG | 8 | 1200.60 | 1572.60 | 1104.00 | 1180.38 | 1180.38 | 24.94 | +2976.00 | +30.98 |
| 17 | minervini | KAPSTON | 19 | 475.14 | 584.50 | 436.91 | 412.59 | 436.91 | 25.25 | +2077.84 | +23.02 |
| 18 | minervini | DIACABS | 42 | 231.45 | 367.95 | 212.82 | 249.76 | 249.76 | 32.12 | +5733.00 | +58.98 |

**Totals** [computed]: unrealized **manas +₹9,093.77**, **minervini +₹18,120.20**, combined
**+₹27,213.97**. Stop-less positions: **0 of 18**. Positions with a NULL `take_profit`: **18 of 18**
— and this is by design, not a missing bracket: neither book declares a `take_profit` rule at all.
Verified against the **published live config** the engine actually runs, not the YAML source
[computed]: `strategy_versions.config->'exit_rules'` for `manas-arora-vcp` v1.1.1 = `stop_loss` +
`trailing_stop` + `square_off`; for `minervini-vcp` v1.0.1 = `stop_loss` + `trailing_stop`. No
`take_profit` and no `time_stop` in either.

Distance measured against the *initial stop only* (the figure the premise asked for) ranges
6.83% (minervini PRECOT) to 42.16% (DIACABS) — i.e. on the persisted-stop basis nothing is remotely
close. The table above is the stricter and more honest reading.

---

## 3. Table 3 — risk-budget consumption

Reproduced from `PaperEmissionGuard.openRiskInr`
(`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/paper/PaperEmissionGuard.java:86-100`),
**not an invented formula**:

```
openRiskInr(book) = Σ over OPEN rows in book of  qty × max(0, avgEntryPrice − effectiveStop)
effectiveStop(p)  = ManasGoverningStopCache.get(p.id())  ?: p.stopLoss()        // :113-117
```

Skips, per the code: a row whose `effectiveStop` is null `continue`s (`:91-93`); a row whose
`perUnit ≤ 0` contributes 0 (`:95`) — which silently zeroes every SHORT and every long already
trailed above cost. **Neither skip is active here: 0 of 18 rows are stop-less and 0 are SELL.**
[computed]

Denominator: `bookEquity` → `PaperAccountService.equity` (`:74-76`) =
`startingCapital + Σ realized + Σ mark-to-market unrealized`. Cap: `6.0`, and I read the **deployed**
value rather than the YAML default — `docker inspect ay-strategy-signal-service` shows
`ARTHA_MANAS_ARORA_PYRAMID_MAX_PORTFOLIO_RISK_PCT=6.0`. [sourced] Comparison is strictly `>`
(`ManasPyramidPolicy.java:167`).

### Book level [computed]

| book | starting | Σ realized | Σ MTM unrealized **as the code sees it** | equity | 6% cap ₹ | open risk ₹ | % of equity | headroom ₹ | % of cap used |
|---|---|---|---|---|---|---|---|---|---|
| manas-arora | 150,000.00 | −7,053.62 | **0.00** | 142,946.38 | 8,576.78 | 8,569.23 | 5.9947 | **+7.55** | **99.91** |
| minervini | 150,000.00 | −6,689.84 | **0.00** | 143,310.16 | *(n/a — no cap)* | 9,120.29 | 6.3640 | *(n/a)* | *(n/a)* |

The **0.00** is measured, not assumed: `unrealizedTotal` marks each position via
`LastTickReader.lastPrice` (Redis hash `ticks:last`) and falls back to `avgEntryPrice` on a miss
(`PaperAccountService.java:96`). `HLEN ticks:last` = **307**, and `HGET ticks:last NSE:<sym>` returns
**empty for all ten swing symbols probed** (AUTOIND, DIACABS, SANSERA, AVALON, PRECOT, SKYGOLD,
KANORICHEM, SCPL, ATHERENERG, HFCL). [computed] So the equity denominator is blind to the full
**+₹27,213.97** of unrealized gain.

### Per position [computed]

| book | symbol | qty | per-unit risk | position risk ₹ | % of equity | % of the 6% budget |
|---|---|---|---|---|---|---|
| manas-arora | KANORICHEM | 100 | 14.6923 | 1,469.23 | 1.028 | 17.13 |
| manas-arora | PRECOT | 18 | 81.3950 | 1,465.11 | 1.025 | 17.08 |
| manas-arora | SANSERA | 6 | 241.8041 | 1,450.82 | 1.015 | 16.92 |
| manas-arora | SCPL | 23 | 61.8100 | 1,421.63 | 0.995 | 16.58 |
| manas-arora | SKYGOLD | 24 | 58.1506 | 1,395.61 | 0.976 | 16.27 |
| manas-arora | AVALON | 8 | 170.8529 | 1,366.82 | 0.956 | 15.94 |
| | **manas total** | | | **8,569.23** | **5.995** | **99.91** |
| minervini | AUTOIND | 111 | 7.0656 | 784.28 | 0.547 | 9.12 |
| minervini | PRECOT | 12 | 65.1960 | 782.35 | 0.546 | 9.10 |
| minervini | SOTL | 16 | 48.8960 | 782.34 | 0.546 | 9.10 |
| minervini | DIACABS | 42 | 18.6264 | 782.31 | 0.546 | 9.10 |
| minervini | ATHERENERG | 8 | 96.6000 | 772.80 | 0.539 | 8.99 |
| minervini | MENONBE | 43 | 17.7204 | 761.98 | 0.532 | 8.86 |
| minervini | CHENNPETRO | 8 | 95.0460 | 760.37 | 0.531 | 8.84 |
| minervini | KANORICHEM | 62 | 12.2568 | 759.92 | 0.530 | 8.84 |
| minervini | INOXINDIA | 5 | 151.0120 | 755.06 | 0.527 | 8.78 |
| minervini | HFCL | 46 | 16.1800 | 744.28 | 0.519 | 8.66 |
| minervini | KAPSTON | 19 | 38.2320 | 726.41 | 0.507 | 8.45 |
| minervini | AVALON | 5 | 141.6400 | 708.20 | 0.494 | 8.24 |
| | **minervini total** | | | **9,120.29** | **6.364** | *(no cap applies)* |

---

## 4. Does the stop ratchet? (file:line)

| | manas-arora | minervini |
|---|---|---|
| Initial stop | `2 × ATR(20)@entry`, capped at 10% — `ExitEvaluator.java:484-500`, fired at `:451-455` | fixed `entry × 8%` — `ExitEvaluator.java:480-483` |
| Trail | rolling-ATR chandelier — `ExitEvaluator.rollingAtrTrailLevel:519-557`; arms at `+9%`, `breakeven_floor: true` | **plain `sma50`** — `ExitEvaluator.java:659-671`, level read fresh each bar at `:662` |
| Ratchet? | **Effectively yes** — `:554` `level = level.max(c)` is a running max re-walked from entry each run | **No. None.** A 50-day SMA falls as freely as it rises |
| Persisted? | **No** | **No** |

**Neither book persists a stop and neither `max()`es against a prior run** — the level is recomputed
from current market data every run. Manas's monotonicity is emergent (history is replayed each run),
not durable. `paper_positions.stop_loss` is never updated after open; its only writer is the manual
bracket-edit endpoint (`PaperPositionRepository.updateBrackets:267-274` ← `PaperService.java:1538`).
[sourced, via code trace]

Empirically, over all 498 sell-decision rows [computed]:

- `stop_level`: **0 real downward moves in 457 transitions** (1 real upward move, manas KANORICHEM
  +0.19%). The apparent moves I first counted were float jitter in the 7th–9th decimal
  (`3095.06586900 → 3095.06587038`) and are not real — flagged here because the naive query reports
  them as ratchet failures.
- `trail_level`: ratchets up in the overwhelming majority, but has **12 genuine downward moves**
  (>0.01%), all minervini, magnitude −0.03% to −0.36% — consistent with an SMA drifting down, and
  exactly what the code predicts.

`ManasGoverningStopCache` is a **strict ratchet** (`stops.merge(id, newStop, BigDecimal::max)`,
`ManasGoverningStopCache.java:76`) but it is an in-memory `ConcurrentHashMap`, manas-only, and — per
its own javadoc at `:11-14` — **exit-neutral**: it is read only by `PaperEmissionGuard.effectiveStop`,
`openRiskInr` and `RiskService.manasAggregateRiskCheck`. **No exit decision consults it.**

---

## 5. Reasoning

### Why HOLD is correct (this is (a))

Every one of the 18 was evaluated (`exit_skipped = 0` on both books, `swing_batch_runs`
2026-08-12 row) and none met a trigger. The books declare only four applicable triggers
(`stop_loss`, `trailing_stop`, and for manas two `square_off` shapes); **no `take_profit` and no
`time_stop` exists in either config**, so there is no age-based or target-based exit that "should
have" fired at 37 days. On the numbers, 16 of 18 are profitable, the combined mark is **+₹27,214**,
and the median distance to the governing exit is ~12%. Explanation (a) fits.

The premise's framing — "hovering just above a stop that never tightens" — is measurably false for
16 of 18. It is *arguably* true for **minervini INOXINDIA (0.78% above its `sma50`)**, which is the
one position genuinely on the edge, and for minervini PRECOT (3.48%, and the only loss-making pair
in the book alongside its manas twin).

### What actually locks the books (neither (b) nor (c) as stated)

**minervini — the slot cap, not the risk cap.** `max_open_paper_positions = 12`
(`strategy.risk_settings`), book holds exactly 12, and `entryPass` early-outs before scanning
anything: `SwingBatchEngine.java:439-445`, logged verbatim at 08:36:21 IST. There is no 6% risk cap
for this family at all. Only an exit frees a slot — and correctly, none was due.

**manas — the 6% cap, binding to within ₹7.55.** Open risk ₹8,569.23 against a ₹8,576.78 cap =
**99.91%**. And this is structural, not coincidental: per-trade sizing is 1% of equity
(`ARTHA_PAPER_RISK_PER_TRADE_RISK_PCT=1.0`, [sourced] `docker inspect`; measured per-position risks
0.956–1.028% confirm it), so **1% × 6 positions = 6% = the cap exactly**. The slot cap is 7. *The
7th manas slot is unreachable by construction* — the risk cap saturates one position before the slot
cap does. `risk_audit` shows the trip on every business day since 2026-08-03 (KAPSTON, E2E,
KABRAEXTRU, INDOTECH, MTARTECH, CONFIPET, PANACHE, BIRLACABLE).

### The structural finding that *is* real, and it is on the risk side, not the exit side

`openRiskInr` is computed from **`avgEntryPrice − stop`**, not `currentPrice − stop`. A position up
59% (DIACABS) consumes exactly the same budget as on day one. **Price appreciation never frees risk
budget.** The only mechanism that can release it is `ManasGoverningStopCache`, and that mechanism
was **provably inert on this run** [computed]:

1. It is **manas-only** — minervini has no release valve whatsoever (moot, since minervini has no
   risk cap either).
2. `entryPass` is invoked at `SwingBatchEngine.java:316-319`, `exitPass` at `:321-324` — **entries
   run before exits**, and `cacheGoverningStop` is called only from the exit pass (`:906-930`,
   reached at `:876-879`). The entry pass therefore reads a cache populated by the *previous* run.
3. The container started **2026-08-13T02:33:17Z = 08:03:17 IST** [sourced, `docker inspect`]; the
   batch ran **08:35 IST**. The map is JVM heap, empty on boot — so at entry-pass time it was cold.

The arithmetic confirms it rather than assuming it: three manas positions (AVALON +12.1%,
SANSERA +17.6%, SKYGOLD +15.9%) are past the `arm_pct: 9` threshold and carry trails *above* their
entry prices, so a warm cache would zero their contributions and drop open risk to
**₹4,355.97 = 3.05%** — under which a fresh ~₹1,400 entry lands near 4.0% and would have been
**admitted**. It was refused. Cold cache confirmed.

### Where (b) is partly true, though not causal

- minervini's trail is a bare `sma50` with **no ratchet and no breakeven floor**, currently sitting
  14–32% below price on the winners (DIACABS 32.1%, KAPSTON 25.3%, ATHERENERG 24.9%). On a reversal
  those give back a great deal. This is a doctrine property, not a defect, and I make no
  recommendation on it.
- The "7 of the last 8 exits were losses" framing does **not** by itself indicate loose stops: the
  realized losses (−₹519 to −₹1,921) sit at or below the per-trade risk budget (~₹1,400 manas,
  ~₹780 minervini), i.e. the stops sized and paid out roughly as designed. The one clear overshoot
  is manas SATIN at −₹1,921 against a ~₹1,400 budget (a gap-through, not a stop failure).

### Where (c) is partly true, though not causal

- **0 of 18 have a NULL `stop_loss`** — that settles the NULL-stop hypothesis outright.
- A real staleness path exists but did not bite this run. On the **scheduled** 16:00/16:02 IST path
  `requiredBarDate` is null, so `truncateToSession` short-circuits (`SwingBatchEngine.java:1010-1012`)
  and the exit decides off whatever bar is newest. Measured consequence: BHAVCOPY-sourced symbols
  land ~18:00 IST or later, so on **2026-08-12 at 16:02** KANORICHEM's newest bar was 2026-08-11
  (close 160.62 — precisely the `current_price` recorded that run), while its 08-12 bar (157.29)
  only arrived at 08-13 08:03. That is a genuine one-session lag for BHAVCOPY names on the
  scheduled path. The **catch-up** path pins the session and is not affected — which is the path
  that produced the run analysed here.
- The `MarketDataCandlesClient` "STALE ... data used unchanged; visibility only" warnings in the log
  are exactly that: a counter and a log line, never a refusal gate (`:119-125`).

---

## 6. OPEN DOUBTS

1. **[assumed] Cache state on days before 2026-08-13.** I proved the cache was cold on this run
   (restart 08:03, batch 08:35). For 2026-08-05…08-12 — days on which SANSERA/AVALON/SKYGOLD already
   carried armed trails — the cap tripped anyway, which *would* imply a cold cache on those days too,
   but I could not reconstruct historical JVM heap state or the container's full restart history.
   Either the process restarted daily, or `SwingDoctrine.governingStop` returns something lower than
   the recorded `trail_level`. **Not resolved.** A single log line emitting `openRiskInr` at
   entry-pass time would settle it; adding one is a change, so out of scope here.
2. **[computed but bounded] `marketdata.candles` is retro-mutable.** Every price in this document is
   *as read on 2026-08-13 ~09:00 IST*. I recorded `fetched_at` per bar (Table 2's source rows: KITE
   bars fetched 2026-08-12 16:00–16:02 IST, BHAVCOPY bars 2026-08-13 08:03 IST) and did **not** make
   any then-vs-now comparison, so no conclusion here depends on historical immutability. `fetched_at`
   is an upsert timestamp and bounds rather than pins.
3. ~~[computed, narrow sample] The Redis tick probe covered 10 of 14 distinct swing symbols.~~
   **RESOLVED** [computed]: all **14** distinct swing symbols were subsequently probed against
   `ticks:last` (the remaining four — INDUSINDBK, SENORES, TMB, MENONBE — also return empty). The
   ₹0.00 mark-to-market term is measured across the whole population, not sampled.
4. **[unresolved] The three anchor-only minervini rows** (INDUSINDBK, SENORES, TMB) are exit-managed
   as held anchors with no backing position. They inflate `open_at_start` to 15 and, because
   `openLotsBySymbol` marks them held, the entry pass would treat those three symbols as
   un-re-enterable. They do **not** consume the slot cap (that reads `paper_positions`, which shows
   12) and do **not** consume risk budget. Whether they are legitimate (a superseded-version anchor)
   or orphans is not established here — SENORES's only position (manas id=10) closed 2026-07-20, and
   INDUSINDBK/TMB have never had a `paper_positions` row at all.
5. **[computed] `swing_batch_runs.run_date` labelling.** The catch-up wrote a row for session
   `2026-08-12` at 08-13 08:35 while `sell_decisions` from the same run carry `run_date = 2026-08-13`.
   The two tables disagree on which date that run belongs to. Cosmetic for this analysis; noted
   because it will mislead any future query that joins them on date.
6. **[computed] `SwingSellDecisionService.decide` has no session pin.** It calls `candles.fetch`
   directly with no `truncateToSession`, and computes `stop_level`/`trail_level` with
   `Math.max(entryIndex, 0)` — so a position whose entry bar falls outside the fetch window still
   gets levels written, computed against bar 0 of the window. Table 1's numbers could in principle
   be off for such a row. None of the 18 is in that state (all entry dates are well inside the
   520-day warmup window), so this does not affect the conclusions.
7. **[not measured] Whether the exit rules *should* be different** is deliberately out of scope, per
   the brief. Nothing here is a doctrine recommendation.

---

*Method: read-only. No files edited, no DB writes, no deploys. All SQL run via
`docker exec ay-timescaledb psql -U artha -d artha`; IST bounds given as explicit `+05:30` literals
and rendered with `AT TIME ZONE 'Asia/Kolkata'`.*

*Checkout provenance [computed]: the investigation opened on branch `docs/ledger-2026-08-12-pm` and
a concurrent session moved the shared checkout to `fix/ingest-run-reaper` (`eef3fb9a`) partway
through — the known shared-checkout hazard. Verified this does not invalidate any citation:
`git diff docs/ledger-2026-08-12-pm HEAD` over `strategysignal/paper/`, `strategysignal/swing/` and
`strategyengine/eval/` is **empty**, so every `file:line` above resolves identically on both. Live
runtime evidence (DB, Redis, container env, logs) is checkout-independent.*
