# Should the scalper paper book be split into per-strategy sub-books?

**Research spike — 2026-08-03. No production code. Docs-only, not merged.**
Owner-approved. Unblocks (or closes) the structural half of ledger row **E1**
(`forward-paper-reliability-month`, `2026-07-02-remaining-items.md:724`, status OWNER).

Every claim below is labelled `computed` / `sourced` / `recalled` / `assumed`.
All live figures were re-queried against `artha` on 2026-08-03, not recalled.

---

## 0. Verdict

> **SKIP per-strategy sub-books. DEFER the underlying split.**
> Sub-books are strictly dominated by a cheaper mechanism for the same goal, and no split of
> any kind can be justified from the current sample — but the spike found that **E1's
> per-scalper question is already partly answerable from data that exists today, with no
> code at all.**

Three findings carry the verdict:

1. **The twins are not redundant, and they are not symmetric — but the asymmetry is on
   ENTRY COUNT, not on P&L.** `scalp-connect-the-dots-*` fired on **14** entry bars; **all
   14** were also `scalp-golden-crossover-*` entry bars. Connect-the-dots has contributed
   **zero independent entry decisions, n=14, 0 solo bars**. Golden-crossover fired solo on 7
   further bars. (`computed`, §2.1.)
2. **What actually distinguishes them is the EXIT, and the merged position destroys it.**
   All **10/10** closed scalper positions were closed by exactly one twin's exit signal —
   whichever fired first. Connect-the-dots owns **5/5** `TIME_STOP` closes; golden-crossover
   owns **5/5** `STRUCTURAL_STOP` closes. Neither strategy's own exit doctrine is ever fully
   expressed. (`computed`, §2.2; mechanism `sourced`, §2.3.)
3. **Therefore no split can be measured without changing live money.** Separating the twins
   necessarily separates their exits, which changes realised P&L on every trade in the
   sample. The sample is n=10 over 3 sessions with a sign that already fails robustness
   (−₹3,109.70 → −₹161.12 leave-3-out → **+₹69.58** dropping two sessions — #1245). A
   live-money change cannot be justified from it. (`computed` + `sourced`, §3.)

**The one-line reason to skip sub-books specifically:** if a split is ever built, the correct
mechanism is a **strategy-scoped position key inside the single `scalper` book** (option D,
§3.4) — same discrimination, zero capital-governor changes — not a second `book`, which drags
in capital allocation, five per-book count governors, the boot governor assert, the frontend
book union type and a stranded-row migration for no additional information.

**Tier: HOLD** (changes live money behaviour). Nothing here is armed, built or deployed.

---

## 1. Does the premise still hold?

### 1.1 Re-measured, and it holds exactly — n=10 positions / 20 entry orders / 3 sessions

`computed` — `docker exec ay-timescaledb psql -U artha -d artha`, 2026-08-03.

```
 book    | status | n  | qty | pnl      | first_open (IST)    | last_open (IST)
 scalper | CLOSED | 10 | 850 | -3109.70 | 2026-07-29 11:07:20 | 2026-08-03 11:55:22
```

No OPEN scalper rows. Sessions:

| Session (IST) | Trades | Realised |
|---|---|---|
| 2026-07-29 | 4 | −₹2,435.95 |
| 2026-07-31 | 5 | +₹69.58 |
| 2026-08-03 | 1 | −₹743.33 |
| **Total** | **10** | **−₹3,109.70** |

This is the same n and the same total as #1245 — the sample has **not** grown since that
measurement (07-31's five trades and 08-03's one were already in it). `computed`.

### 1.2 The same-bar / same-price claim: exact, 10 of 10

Every closed position is built from exactly **two** entry orders from **two different
strategies**, joined `paper_orders → signals → strategy_versions → strategies`:

| Pos | Symbol | Order/signal A | Order/signal B | Bar (IST) | Fill A | Fill B | Δt |
|---|---|---|---|---|---|---|---|
| 41 | SENSEX26JUL77200CE | 54/97 golden-crossover-sensex | 55/99 connect-the-dots-sensex | 07-29 11:06 | 482.05 | 482.05 | 1.15 s |
| 42 | SENSEX26JUL77200CE | 57/102 gc-sensex | 58/104 ctd-sensex | 07-29 12:12 | 498.05 | 498.05 | 0.21 s |
| 43 | SENSEX26JUL77200CE | 60/107 gc-sensex | 61/109 ctd-sensex | 07-29 13:09 | 518.05 | 518.05 | 0.21 s |
| 44 | SENSEX26JUL77300CE | 63/112 gc-sensex | 64/114 ctd-sensex | 07-29 14:03 | 479.20 | 479.20 | 0.22 s |
| 47 | NIFTY2680424200CE | 72/128 gc-nifty | 73/129 ctd-nifty | 07-31 11:15 | 199.30 | 199.30 | 2.15 s |
| 48 | NIFTY2680424200CE | 75/133 gc-nifty | 76/134 ctd-nifty | 07-31 12:15 | 216.65 | 216.65 | 0.12 s |
| 49 | NIFTY2680424250CE | 78/136 gc-nifty | 79/137 ctd-nifty | 07-31 12:48 | 197.70 | 197.70 | 0.33 s |
| 50 | NIFTY2680424250CE | 81/139 gc-nifty | 82/140 ctd-nifty | 07-31 13:00 | 196.05 | 196.05 | 0.11 s |
| 51 | NIFTY2680424250CE | 84/142 gc-nifty | 85/143 ctd-nifty | 07-31 13:06 | 198.90 | 198.90 | 0.13 s |
| 55 | SENSEX2680678200CE | 91/149 gc-sensex | 92/150 ctd-sensex | 08-03 11:54 | 749.80 | 749.80 | 2.01 s |

`computed`. **Fill prices are byte-identical within every pair, 10/10** — the entry edge is
exactly zero, not approximately zero. `opening_signal_id` is the golden-crossover signal in
10/10 cases (it always fires first). NIFTY legs are 65+65 → qty 130; SENSEX legs 20+20 → 40.

### 1.3 Two corrections to the brief's framing

- **It is two twin PAIRS, not one.** The brief names the NIFTY pair. The SENSEX pair
  (`scalp-golden-crossover-sensex-niftyoi` / `scalp-connect-the-dots-sensex-niftyoi`) behaves
  identically and accounts for **5 of the 10** positions. `computed`.
- **`Books.eodManaged()` does not exist.** The EOD-managed set is a Spring property,
  `artha.paper.eod-managed-books`, defaulting to `minervini,manas-arora`
  (`PaperStaleTickAlerter.java:78`, consulted at `:118`). `sourced`. **`scalper` is not in it,
  so any `scalper-*` sub-book is automatically not EOD-managed — this surface needs no change
  under any option below.** One fewer thing to break than the brief assumed.

### 1.4 One live claim on `main` is false and is already being fixed

`PaperService.upsertPosition` carries the comment *"it can never span two strategies (the
per-book open-key means only one strategy holds a given key open at a time)"*
(`PaperService.java:~925`). Measured above: false, 10/10. `sourced` + `computed`. **PR #1259
already corrects exactly this line** — no separate action needed.

### 1.5 A related hypothesis I checked and had to withdraw

I expected the per-sub-account capital ceiling to be evaluated against the *second* twin's
freshly-assigned idle sub-account while the money landed on the *first* twin's — a systematic
under-count. **It is not.** `PaperService.java:849-857` reads the EXISTING row's
`subaccount_idx` (`positions.openSubAccountIdx(...)`) and validates against that, precisely
because an averaging add keeps the original idx. `sourced`. The rail is correct. Recorded
because the wrong version of this claim would have looked persuasive.

---

## 2. What would make two strategies that trade identically distinguishable at all?

**They do not trade identically.** They *enter* identically. They *exit* differently, and the
merged position silently resolves the difference.

### 2.1 On entries, connect-the-dots is a strict subset of golden-crossover

`computed`, from every `scalp-*` ENTRY signal ever emitted (`strategy.signals`, all time):

| Family | GC entry bars | CTD entry bars | CTD bars that are also GC bars | CTD-solo bars |
|---|---|---|---|---|
| NIFTY | 13 | 7 | **7 / 7** | **0** |
| SENSEX-niftyoi | 8 | 7 | **7 / 7** | **0** |
| **Both** | **21** | **14** | **14 / 14** | **0** |

Golden-crossover fired solo on **7** bars (6 NIFTY, 1 SENSEX). Connect-the-dots has **never**
fired an entry that golden-crossover did not also fire. **n=14 CTD entry bars, 0 independent.**

Cross-checked by set arithmetic rather than by reading the per-bar table (the query is in §8):
`gc_entries=21, ctd_entries=14, ctd_bars_also_gc=14, ctd_solo=0, gc_solo=7`. The hand count and
the set query agree exactly. `computed`.

This is the strongest per-strategy statement E1 can make today, and it required no code, no
sub-books and no #1259. It is a direct observation of emitted signals, not a counterfactual.

Only **5** scalper strategies have ever emitted a signal at all
(`golden-crossover-nifty` 24, `straddle-nifty` 13, `golden-crossover-sensex-niftyoi` 13,
`connect-the-dots-sensex-niftyoi` 12, `connect-the-dots-nifty` 11), against **38**
published+enabled. `computed`. `straddle-nifty` is now `enabled=false` and last fired 07-20.

### 2.2 On exits, the merged position takes whichever twin fires FIRST

Every scalper position closed 1–3 minutes after exactly one twin emitted an EXIT signal on
the index future, and the position's `close_reason` matches that signal's `exit_reason`:

| Pos | Closed (IST) | `close_reason` | Exit signal | Emitted by |
|---|---|---|---|---|
| 41 | 07-29 11:37:20 | TIME_STOP | #100 @11:36 | **connect-the-dots**-sensex |
| 42 | 07-29 12:19:17 | STRUCTURAL_STOP | #105 @12:18 | **golden-crossover**-sensex |
| 43 | 07-29 13:40:20 | TIME_STOP | #110 @13:39 | **connect-the-dots**-sensex |
| 44 | 07-29 14:34:12 | TIME_STOP | #115 @14:33 | **connect-the-dots**-sensex |
| 47 | 07-31 11:46:15 | TIME_STOP | #132 @11:45 | **connect-the-dots**-nifty |
| 48 | 07-31 12:46:12 | TIME_STOP | #135 @12:45 | **connect-the-dots**-nifty |
| 49 | 07-31 12:55:20 | STRUCTURAL_STOP | #138 @12:54 | **golden-crossover**-nifty |
| 50 | 07-31 13:04:12 | STRUCTURAL_STOP | #141 @13:03 | **golden-crossover**-nifty |
| 51 | 07-31 13:34:12 | STRUCTURAL_STOP | #144 @13:33 | **golden-crossover**-nifty |
| 55 | 08-03 11:58:20 | STRUCTURAL_STOP | #151 @11:57 | **golden-crossover**-sensex |

`computed`. **Perfect separation: connect-the-dots owns 5/5 `TIME_STOP` closes,
golden-crossover owns 5/5 `STRUCTURAL_STOP` closes.**

That separation is exactly what the YAMLs predict (`sourced`, diff of
`scalp-golden-crossover-nifty.yaml` vs `scalp-connect-the-dots-nifty.yaml`):

| | golden-crossover | connect-the-dots |
|---|---|---|
| chart gate | `vwma20 > vwap` **and** `supertrend > 0` | `close > vwap` (deliberately permissive) |
| `time_stop` | **12 bars** (36 min) | **10 bars** (30 min) |
| structural stop | `entry-candle-stop` tag (crossover-candle low) | `stop_loss / index_points 60` |
| `signal_exit` | `vwma20 < vwap` | `close < vwap` |
| `oi_confluence_gate` | **disabled** | **enabled** |
| distinct rails | `supertrend-15m`, `iv-buyer-cap` | `indicator-distance-veto`, `iv-per-strike`, `flat-oi-stand-aside`, `max-oi-sr-gate` |

Connect-the-dots always wins the time race (10 < 12 bars); golden-crossover's entry-candle
stop is always tighter than a 60-point index stop. So the merged position exits on the
**pointwise minimum** of the two doctrines, and which twin supplies it flips per trade.

### 2.3 The mechanism, in one join

`sourced` — `PaperPositionRepository.openForSignal`, `PaperPositionRepository.java:396-410`:

```sql
FROM paper_positions p
JOIN paper_orders o
  ON o.book = p.book AND o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol
  AND o.side = p.side
WHERE p.status = 'OPEN' AND o.signal_id = ?
```

The join is on `(book, exchange, tradingsymbol, side)` — **not** on the strategy. So *either*
twin's anchor resolves to the *same* row, and `EngineExitListener.onSignalExited` →
`PaperService.closeForSignal` settles the **whole** merged position, including the other
twin's 65 units. The loser's exit signal later resolves to zero open positions and is a
silent no-op. `sourced` (`EngineExitListener.java:64`, `PaperService.java:1398-1411`).

`upsertPosition` compounds it: an averaging add "keeps its original bracket levels AND its
original sub-account … likewise KEEPS its original `opening_signal_id`"
(`PaperService.java:~920`, `sourced`). So the merged position carries **golden-crossover's**
brackets and attribution while being closed by **either** twin's exit rule.

### 2.4 Consequence for #1259, and the honest answer to the brief's question

The brief asks what would make the pair distinguishable. The answer:

- **#1259's fill-basis attribution cannot.** Correct, and its PR body says so. Its formula is
  `R·(q/Q) + sign·(A − f)·q`; with `f_A = f_B = A` the edge term is exactly zero and both
  lots report identical per-unit P&L **for arithmetic reasons, at any n**. `sourced` + the
  identical fills measured in §1.2.
- **The exit dimension can — but only by actually separating the exits**, i.e. by giving each
  twin its own position. There is no read-only view of the current ledger that recovers it,
  because the alternative trajectory was never traded.
- **A shadow/counterfactual cannot recover it either**, and this is not speculation: PR #1242
  (MERGED, `b06699de`) established the exact obstacle for this shape in its round-4 finding —
  an exit counterfactual computed from live rows suffers both **overcount** (a bar the
  counterfactual would not have exited can still be closed that bar by a lower-priority rule)
  and **censoring** (once the live position closes, the oracle stops running, so the bars the
  counterfactual would have lived through *have no rows at all*). Recovering it needs a
  **trajectory replay**, which "does not exist and was deliberately not built". `sourced`.

**So: measuring the twins apart and trading them apart are the same act.** That is the crux
of the whole decision, and it is why this is HOLD tier rather than an instrumentation change.

---

## 3. The real options, with honest costs

### 3.0 What is shared by all of them

`sourced` unless noted:

- Sizing is **per signal, from the strategy's own YAML** — `risk.position_sizing.method:
  premium_budget, params: {budget_inr: 15000, max_lots: 5}`, identical in both twins.
  `lots = floor(budget ÷ (premium × lot))`; a zero result is refused as `ZERO_SIZE`
  (`PaperEmissionGuard.java:268-289`, `PaperOrderRejectionRecorder.java:69-78`).
- **This means splitting the book does NOT by itself change position size.** Two twins firing
  produce 2 × ₹15,000 of premium whether that lands in one row or two. The brief's
  "halves each strategy's size or doubles total exposure" fork is a consequence of how the
  **capital** is split, not of the row split — see §4.1.
- All 9 enabled `ZERO_SIZE` rejections in the whole table are the `budget_inr` rail refusing an
  unaffordable premium; **7 of 9 are golden-crossover solo bars**, which is why 6 of its 7 solo
  entries never became positions. `computed`.

### 3.1 Option A — status quo (+ #1259 if merged)

**Cost: zero. Information gained: countability, not discriminability.**

Keeps the pyramid. #1259 makes each contributor's qty and pooled P&L share readable, which is
genuinely useful for *risk* (you can see the book is running 2× the configured per-strategy
budget) but tells you nothing about which twin is better, for the reason in §2.4.

Note #1259's stated merge-order dependency on #1242 is now **discharged** — #1242 merged as
`b06699de` and `V056__exit_oracle_shadow.sql` is on `main`; #1259's `V057` is the next free
number and is unclaimed. `computed`.

### 3.2 Option B — per-strategy sub-books (`book` = strategy or sub-family)

**Cost: the largest of the four. Information gained: full discrimination.** Detailed in §4.

### 3.3 Option C — capital-partitioned sub-accounts

**Cost: near zero to *reuse*; but it does not discriminate.**

Worth stating because the machinery already exists and is live: `strategy.scalper_subaccount`
(5 rows, `capital_fraction` 0.2000 each) with `ScalperAccountModel` enforcing first-loss
freeze, ~1% profit-lock and a per-account rupee ceiling. `computed` + `sourced`.

But sub-accounts are assigned **per position, round-robin by deployed capital**
(`ScalperAccountModel.nextFreeAccount`), not per strategy, and an averaging add inherits the
row's existing idx. Routing them by strategy instead would break the round-robin the
first-loss discipline depends on, and would still leave both twins in one row unless the
position key also changes. **It solves nothing on its own.** Rejected.

### 3.4 Option D — one book, strategy-scoped position key (non-averaging entries)

**This is the option that dominates sub-books for the stated goal.**

Change `uq_paper_positions_open` from `(book, exchange, tradingsymbol, side) WHERE status =
'OPEN'` to include the opening strategy, so each twin holds its own row with its own brackets
and its own exit.

What it buys, that sub-books also buy:
- Each twin exits on its own doctrine → the exit dimension becomes measurable.
- Per-strategy P&L is a plain `GROUP BY`, with no attribution arithmetic at all.

What it buys, that sub-books do **not**:
- **Every book-level governor is untouched.** One `book='scalper'`, one ₹150,000 capital row,
  one `heat_cap_pct` (60, *enabled*), one `daily_loss_limit` (3%), one `daily_profit_target`
  (1.5%), one `max_deployment_pct` (80%), one kill switch, one 5-wins/day cap, one set of five
  sub-accounts. `computed` from `strategy.risk_settings` + `strategy.paper_account`.
- **Total exposure is unchanged** — the same 2 × ₹15,000, just in 2 rows.
- **Sub-account accounting can be preserved exactly** by charging both rows to ONE sub-account,
  which is precedented: `openScalperPair` already "assigns the sub-account ONCE and charges
  BOTH legs to it" for the #11 straddle (`PaperService.java:598-602`, `sourced`).
- No `Books.all()` change, no governor-seed migration, no frontend `PaperBook` union change,
  no e2e/Telegram/RiskController default-book changes.

What it costs (all real, none free):
- A migration dropping/recreating `uq_paper_positions_open` (V058+, after #1259's V057).
- `openForSignal` (§2.3) must become strategy-scoped or *both* rows still close together — this
  is the load-bearing change and the easiest thing to get wrong.
- `openSubAccountIdx(book, exchange, tradingsymbol, side)` becomes ambiguous (two open rows on
  the key) and must be resolved deliberately, not by `LIMIT 1`.
- Open row count doubles against `max_open_paper_positions = 20`.
- **The entry wrapper rule applies:** any new wrapper must keep anchor (4801) **before** book
  (4802) — `PaperService.lockAnchorsBeforeBook`, `PaperService.java:618-631`. Two concurrent
  money-path opens deadlock otherwise, invisibly to tests. `sourced`.
- **It changes live P&L on every trade in the sample** — same as sub-books. This is the
  blocking cost, not the engineering.

### 3.5 Option E — disable one twin

**Cost: zero to build, instantly reversible, owner-gated. It is the only option that does not
require a line of code.** `strategies.enabled` is a row flag; 21 of 51 `scalp-*` strategies
are already `enabled=false`. `computed`.

The asymmetry from §2.1 makes this concrete and one-sided:

- **Disabling `scalp-connect-the-dots-*` removes ZERO entry decisions** (0 of 14 bars were
  CTD-solo). It halves the per-bar deployment on those 14 bars, and it removes the 10-bar time
  stop that closed 5 of the 10 positions early.
- **Disabling `scalp-golden-crossover-*` removes 7 of 21 entry bars** and would be the larger
  behavioural change.

The honest counter-arguments, stated plainly:

1. It **decides** E1's keep/cut question by fiat rather than measuring it. Once CTD is off you
   can never learn whether its tighter time stop was the better doctrine.
2. It is still a live-money change — size halves on the co-fire bars. The standing prior
   (T1/T7/G13/G10: every measured change to the live scalper entry path has lost money) was
   established on **gate loosenings**, and this is a **size reduction on a net-negative book**,
   so the prior does not transfer cleanly in either direction. Do not present it as
   prior-compliant, and do not present it as prior-exempt.
3. Its main appeal is the §6 reduction below, not the P&L.

---

## 4. Sub-books specifically: what breaks

### 4.1 Capital allocation is the crux — and there is no free choice

Today: one `paper_account` row, `book='scalper'`, `starting_capital = 150,000.00`.
Five sub-accounts × `capital_fraction 0.2000` → **₹30,000 allocation each**.
`computed`.

Measured deployment per position:

| Pos | Deployed | Per contributor | Sub-acct | Headroom vs ₹30,000 |
|---|---|---|---|---|
| 41 | ₹19,282.00 | ₹9,641.00 | 1 | ₹10,718 |
| 44 | ₹19,168.00 | ₹9,584.00 | 4 | ₹10,832 |
| 47 | ₹25,909.00 | ₹12,954.50 | 1 | ₹4,091 |
| 48 | ₹28,164.50 | ₹14,082.25 | 2 | ₹1,835 |
| **55** | **₹29,992.00** | **₹14,996.00** | 1 | **₹8** |

`computed`. Position 55 sat **₹8** inside its sub-account allocation.

That near-miss is not luck — it is structural: each contributor deploys at most `budget_inr`
= ₹15,000, and **2 × ₹15,000 = ₹30,000 = the allocation exactly**. The ceiling test is `>`,
not `>=` (`ScalperAccountModel.wouldExceedSubAccount`, "landing exactly ON the allocation is
inside it", `sourced`), so the **second** contributor is always admitted and a **third** at
full budget never is. At the *observed* per-contributor costs (₹9,584–₹14,996) a third
contributor would have fitted on positions 41/43/44 and not on 47–51/55. So the pyramid depth
is bounded by the sub-account rail at roughly 2–3 contributors, premium-dependent —
**not** at N contributors for N firing families. `computed`. The "9 families → 9× exposure"
fear is unfounded; the "two families → 2× the configured risk unit" fact is real.

Now the fork the brief asks to be resolved:

**(a) Split the ₹150,000 (e.g. 75/75).** Each sub-book's sub-account allocation becomes
**₹15,000**. Position 55's contributor cost was ₹14,996 — **₹4 of headroom**. A single
`budget_inr`-full entry at a premium of ₹750.20 or more would be **refused** by the
sub-account rail where today it fills. This silently converts the sizing rail from
"budget_inr binds" to "sub-account allocation binds", which is a sizing-doctrine change
nobody asked for, arriving as a side effect. `computed`. **Rejected.**

**(b) Give each sub-book ₹150,000.** Total book capital 2× (₹300,000), and every %-based
governor (`daily_loss_limit` 3%, `max_deployment_pct` 80%, `heat_cap_pct` 60) doubles in
rupee terms. Total exposure ceiling doubles even though nothing about the strategies changed.
**Rejected.**

**(c) Re-scale `capital_fraction` so each sub-book's allocation stays ₹30,000.** Arithmetically
possible (`paper_account.starting_capital` is owner-editable per book via
`POST /api/v1/paper/account`, `PaperController.java:109-122`, audited — no migration needed,
`sourced`). But it means each sub-book runs 5 sub-accounts at ₹30,000 = ₹150,000 notional
against a ₹75,000 book, i.e. the allocation stops meaning what its javadoc says it means
("an account that can be overdrawn is not an allocation, it is a label"). **Rejected.**

**None of the three is neutral.** This is the single strongest argument against sub-books, and
it is why option D — which never touches capital at all — dominates.

### 4.2 The count governors double, and that is not neutral either

`computed` from `strategy.risk_settings`:

| Governor | Today (per book) | Under 2 sub-books |
|---|---|---|
| `max_open_paper_positions` | 20 | 20 + 20 = **40** |
| `ScalperAccountModel.MAX_WINS_PER_DAY` | 5 | 5 + 5 = **10** |
| first-loss freeze accounts | 5 | 5 + 5 = **10** |
| `kill_switch` | 1 flip stops scalping | **2** flips needed |
| `daily_loss_limit` (3%) | one latch | two independent latches |

The 5-wins/day cap and the all-frozen block are Siva's overtrading discipline
("overtrading is the killer — §2.14", `ScalperAccountModel` javadoc, `sourced`). Splitting the
book **doubles the daily trade budget** without anyone deciding to. The kill-switch row is the
worst of these operationally: after a split, flipping `scalper`'s kill switch stops nothing.

### 4.3 The named surfaces

| Surface | Verdict |
|---|---|
| `uq_paper_positions_open (book, exchange, tradingsymbol, side) WHERE status='OPEN'` | **Separates for free** under sub-books — different `book` ⇒ different key. This is the one thing sub-books do more cheaply than option D (no migration). `sourced`. |
| `PaperStaleTickAlerter` / EOD-managed books | **No change needed.** Property `artha.paper.eod-managed-books` defaults `minervini,manas-arora`; scalper is absent, so `scalper-*` sub-books are non-EOD-managed by default. `sourced` (`:78`, `:118`). |
| Risk governor heat cap (per book) | Needs a seeded row per sub-book. `heat_cap_pct` is **enabled** on `scalper` and disabled on every other book — the only enforcing heat cap in the system. Forgetting to seed it silently disables the rail. `computed`. |
| `Books.all()` + `PaperBookGovernorInitializer` | Adding a book without a seed migration **auto-seeds `auto_paper_trade: {"enabled": false}`** — fail-safe, but it means a sub-book ships **silently not trading** until seeded. `sourced` (`PaperBookGovernorInitializer.java:39-59`). |
| `BookResolver.fromTags` / `Books.fromTags` | First recognised family tag. Both twins carry `scalper` as their first tag, so **the tag mechanism cannot produce per-strategy books without changing every scalper YAML's tag list** — and tags are also the arming mechanism for rails (`relative-volume-floor`, `entry-candle-stop`, …). Touching them risks disarming a rail. `sourced`. This alone makes tag-derived sub-books a bad idea. |
| `BookResolver.SCALPER` hardcoded | 3 sites in `ScalperAccountModel` (`:71`, `:148`, `:189`), `PaperService.java:633` (`lockBookCapital`), `PaperEmissionGuard.java:198` (hero-zero budget), `RiskController.DEFAULT_BOOK`, `TelegramCommandBot` (2 sites). Each becomes a loop or a parameter. `sourced`. |
| Frontend | `PaperBook` is a closed union `'scalper' \| 'minervini' \| 'manas-arora'` (`frontend-react/src/api/paper.ts:19`), plus `PaperPage.tsx:53`, `CockpitPage.tsx:87`, `PaperBookPanel.tsx:80`, `PositionDetailDrawer.tsx:206`. `sourced`. |
| `book` is operator-selectable | Confirmed — free-text on the manual open path (`PaperController.java:45`) and `startingCapital` is owner-editable per book (`:109-122`). So a typo'd book name creates an ungoverned book at runtime; the boot initializer only seeds books in `Books.all()`. Pre-existing, worsened by having more valid book names. `sourced`. |

---

## 5. Migration / backfill: existing positions cannot be retro-split

**They stay as they are, under `book='scalper'`, permanently un-split.** `computed` + `sourced`.

Why retro-splitting is impossible, not merely awkward:

1. `paper_orders` has **no `position_id`** (`\d strategy.paper_orders`, `computed`) — the
   fill→position link does not exist. This is exactly the gap #1259 exists to close, *going
   forward only*.
2. The obvious reconstruction — join orders to positions on `(book, exchange, tradingsymbol,
   side)` — **fans out**: `NIFTY2680424250CE` was entered three times on 07-31 (positions 49,
   50, 51), so six entry orders match three positions. #1259 reached the same conclusion
   independently. `computed`.
3. The realised P&L of a merged position is a single number produced by a single exit that
   only one twin's doctrine chose. There is no arithmetic that splits it into "what each twin
   would have realised" — that is the trajectory replay of §2.4, which does not exist.

Consequences to state explicitly in any build:

- The **10 closed scalper rows** (and the 35 rows in the other books — 45 total, 28 CLOSED /
  17 OPEN as of #1259's measurement) report as **UNTAGGED**. `computed`.
- **`'scalper'` must be retained in `Books.all()` as a legacy book** even after a split, or
  those rows become ungoverned and drop out of the frontend book selector. This is a
  permanent tail, not a transitional one.
- Any before/after P&L comparison across the split is **not like-for-like** and must never be
  presented as one — pre-split rows are 2-strategy merges with min-of-two exits, post-split
  rows are single-strategy with own-doctrine exits.

---

## 6. Verdict, tier, and what would flip it

### 6.1 Verdict

**SKIP per-strategy sub-books (option B). DEFER the underlying split (option D).**
**Tier: HOLD** — every variant changes live money behaviour.

Reasoning, in order of weight:

1. **The sample cannot carry a live-money change.** n=10 closed trades, 3 sessions, sign fails
   robustness (#1245: −₹3,109.70 → −₹161.12 leave-3-out → **+₹69.58** dropping two sessions;
   78% of the loss is one session, 3 of whose 4 trades are the same contract re-entered).
   `sourced`. Splitting changes the exit on all 10.
2. **Sub-books are dominated.** Option D delivers identical discrimination with no capital
   fork (§4.1), no doubled count governors (§4.2), no `Books.all()` / governor-seed / frontend
   / Telegram changes. The *only* thing sub-books do more cheaply is the unique-index change.
   Paying §4.1 and §4.2 to avoid one migration is a bad trade.
3. **E1's per-scalper question is partly answerable now, for free.** §2.1 (CTD contributes 0 of
   14 independent entry decisions) and §2.2 (5/5 vs 5/5 exit ownership) are direct observations
   requiring no build at all. Neither was in #1245. **Record these against E1 before building
   anything.**

### 6.2 The question reduces further than "which twin is better"

If connect-the-dots contributes no independent entry decision (§2.1), then its only distinct
effect on the book is a **10-bar time stop instead of 12** (§2.2 — which closed 5 of 10
positions). Running a second published strategy, a second ₹15,000 budget and a whole
attribution problem is a very expensive way to express one exit parameter that is already a
declared tunable in both YAMLs (`exit_rules[type=time_stop].params.max_bars`, ranges [8,18]
and [6,16]). `sourced`.

**I am naming this reduction, not proposing the tune.** The `max_bars` question belongs to
T29 (`time_stop` vs `structural_stop`), is HOLD, and is the owner's. Nothing here requires
re-tuning a gate — `max_bars` is an exit parameter and no gate threshold is touched by any
option in §3.

### 6.3 What would flip the verdict to BUILD

Stated as deciding variables, so a future session does not re-litigate from scratch:

- **A CTD-solo entry bar appears.** The §2.1 subset result is the load-bearing finding. One
  connect-the-dots entry that golden-crossover did not fire makes the twins genuinely
  independent strategies and materially strengthens the case for option D. **Re-run the §2.1
  query before acting on this document — it has a shelf life of days.**
- **n reaches a sample that can carry a sign.** The book is accruing ~3 trades/session at
  best; #1245's ~30-trade bar is weeks away and its sign robustness must pass, not just its n.
- **A third family starts firing on the same bars.** Depth is bounded at ~2–3 contributors by
  the sub-account rail (§4.1), but a 3-way merge makes the min-of-doctrines exit qualitatively
  worse and the reduction in §6.2 stops applying.
- **A trajectory replay gets built** (for any other reason). It would let the twins be
  discriminated *without* a live change, which removes the blocking objection entirely.

### 6.4 What would flip it to a faster SKIP

If the owner accepts §6.2, **option E on `scalp-connect-the-dots-*`** closes the pyramid
today, at zero build cost, reversibly, and removes 0 entry decisions. It is owner-gated
(live behaviour change) and is **presented, not recommended-and-executed**. Its cost is
foreclosing the measurement (§3.5).

---

## 7. Open doubts

1. **The §2.1 subset result rests on n=14 CTD entry bars across 6 sessions.** It is a clean
   direct observation, but 14 is small, and the two strategies' gates are genuinely different
   (§2.2) so independence is *expected* eventually. **This is the finding most likely to flip
   the verdict**, and it is the one thing in this document that must be re-measured before
   anyone acts on it.
2. **I did not quantify the P&L of the exit divergence.** Doing so honestly needs the
   trajectory replay of §2.4; doing so dishonestly (joining changed bars back to fills) is
   exactly the overcount/censoring error #1242 round 4 documents. So the document says
   "the exits differ and it changes money" without saying by how much, or even in which
   direction. **If the direction matters to the decision, this document is insufficient.**
3. **A backtest is a contaminated discriminator here and I nearly recommended one.**
   Connect-the-dots runs `oi_confluence_gate: enabled: true`, golden-crossover
   `enabled: false` (`sourced`). Per CLAUDE.md, derived-history OI degrades Dow + IV to
   NEUTRAL so the OI edge reads MUTED on backtests — which would systematically disadvantage
   exactly the OI-gated twin. **Do not settle this pair by backtest.** `sourced` + `assumed`
   (that the muting is material at this sample size — not measured here).
4. **Two golden-crossover entries (07-31 11:15, 12:15) have no matching exit signal.** Signals
   expire 60 minutes after emission (`computed` — #128 gen 11:15 / expires 12:15), so anchor
   expiry, not an exit, appears to have released them. I did not chase this. If entry anchors
   are being released by expiry rather than by an exit rule, the "each twin exits on its own
   doctrine" premise of option D is weaker than §2.2 suggests. **This is the second-most
   likely thing to change the design.**
5. **`realized_pnl` net-of-costs and the fill model** are inherited from #1245's harness check,
   not re-verified here. `recalled`.
6. **The pyramid-depth bound in §4.1 is derived, not stress-tested.** I computed it from
   `budget_inr`, `max_lots`, the ₹30,000 allocation and the `>` comparison; I did not
   construct a 3-family co-fire and observe the refusal.
7. **The `-pe` twins are inert today but not disabled.** Re-queried 2026-08-03: `scalp-*-pe`
   strategies have emitted **0 signals ever** (`computed`), so they cannot currently pyramid.
   But they are `enabled=true`, and if they begin firing, the pair structure — and the
   §4.1 depth bound, which assumes two contributors — may differ. Not analysed here.
8. **Option D's `openForSignal` change is the risky part and I did not design it.** Making
   that join strategy-scoped without breaking the swing books, the straddle pair path, or the
   `openSubAccountIdx` resolution is where a build would actually go wrong. This document
   scopes the decision, not the implementation.

---

## 8. Appendix — reproduction

All read-only. Live DB is `artha`; in-container `now()`/`::date` are UTC, so bounds use
explicit `+05:30` and rendering uses `AT TIME ZONE 'Asia/Kolkata'` (never `'+05:30'`, which
inverts).

```bash
# §1.2 — the pyramid, orders joined to strategies
docker exec ay-timescaledb psql -U artha -d artha -c "
SELECT o.id ord, o.signal_id sig, o.qty, o.fill_price, o.tradingsymbol,
       to_char(o.placed_at AT TIME ZONE 'Asia/Kolkata','MM-DD HH24:MI:SS.MS') placed_ist,
       st.slug, to_char(s.generated_at AT TIME ZONE 'Asia/Kolkata','MM-DD HH24:MI') sig_bar
FROM strategy.paper_orders o
LEFT JOIN strategy.signals s ON s.id=o.signal_id
LEFT JOIN strategy.strategy_versions sv ON sv.id=s.strategy_version_id
LEFT JOIN strategy.strategies st ON st.id=sv.strategy_id
WHERE o.book='scalper' ORDER BY o.id;"

# §2.1 — per-bar co-firing (the subset result). RE-RUN THIS BEFORE ACTING.
docker exec ay-timescaledb psql -U artha -d artha -c "
WITH sig AS (
 SELECT s.id, st.slug, s.generated_at, s.signal_type
 FROM strategy.signals s
 JOIN strategy.strategy_versions sv ON sv.id=s.strategy_version_id
 JOIN strategy.strategies st ON st.id=sv.strategy_id WHERE st.slug LIKE 'scalp-%')
SELECT to_char(generated_at AT TIME ZONE 'Asia/Kolkata','MM-DD HH24:MI') bar_ist, signal_type,
       string_agg(slug||'#'||id, ' | ' ORDER BY id) fired, count(*) k
FROM sig GROUP BY 1,2 ORDER BY 1,2;"

# §2.1 — the same result as set arithmetic (independent of reading the table above)
docker exec ay-timescaledb psql -U artha -d artha -c "
WITH e AS (
 SELECT st.slug, date_trunc('minute', s.generated_at) bar
 FROM strategy.signals s
 JOIN strategy.strategy_versions sv ON sv.id=s.strategy_version_id
 JOIN strategy.strategies st ON st.id=sv.strategy_id
 WHERE s.signal_type='ENTRY' AND st.slug LIKE 'scalp-%'),
gc  AS (SELECT bar, replace(slug,'golden-crossover','X') fam FROM e WHERE slug LIKE '%golden-crossover%'),
ctd AS (SELECT bar, replace(slug,'connect-the-dots','X') fam FROM e WHERE slug LIKE '%connect-the-dots%')
SELECT (SELECT count(*) FROM gc) gc_entries,
       (SELECT count(*) FROM ctd) ctd_entries,
       (SELECT count(*) FROM ctd JOIN gc USING (bar,fam)) ctd_bars_also_gc,
       (SELECT count(*) FROM ctd WHERE NOT EXISTS
          (SELECT 1 FROM gc WHERE gc.bar=ctd.bar AND gc.fam=ctd.fam)) ctd_solo,
       (SELECT count(*) FROM gc WHERE NOT EXISTS
          (SELECT 1 FROM ctd WHERE ctd.bar=gc.bar AND ctd.fam=gc.fam)) gc_solo;"
# 2026-08-03: 21 | 14 | 14 | 0 | 7

# §2.2 — exit ownership
docker exec ay-timescaledb psql -U artha -d artha -c "
SELECT s.id, st.slug, s.exit_reason,
       to_char(s.generated_at AT TIME ZONE 'Asia/Kolkata','MM-DD HH24:MI') bar_ist
FROM strategy.signals s
JOIN strategy.strategy_versions sv ON sv.id=s.strategy_version_id
JOIN strategy.strategies st ON st.id=sv.strategy_id
WHERE st.slug LIKE 'scalp-%' AND s.signal_type='EXIT'
  AND s.generated_at > '2026-07-29T00:00:00+05:30' ORDER BY s.id;"

# §4.1 — deployment vs the sub-account allocation
docker exec ay-timescaledb psql -U artha -d artha -c "
SELECT id, tradingsymbol, qty, avg_entry_price, round(qty*avg_entry_price,2) deployed_inr,
       subaccount_idx, realized_pnl
FROM strategy.paper_positions WHERE book='scalper' ORDER BY id;"
```

Key code sites: `PaperPositionRepository.java:396-410` (the strategy-blind exit join) ·
`PaperService.java:849-857` (effective sub-account) · `PaperService.java:618-631` (anchor→book
lock order) · `PaperService.java:~914-950` (`upsertPosition` averaging) ·
`ScalperAccountModel.java` (5 sub-accounts, caps) · `PaperEmissionGuard.java:268-289`
(`ZERO_SIZE` / `budget_inr`) · `Books.java` + `PaperBookGovernorInitializer.java` (book domain
+ boot seed) · `PaperStaleTickAlerter.java:78,118` (EOD-managed books) ·
`frontend-react/src/api/paper.ts:19` (`PaperBook` union).
