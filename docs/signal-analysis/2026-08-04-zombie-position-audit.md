# Zombie paper-position audit — 2026-08-04 (scheduled, 08:30 IST)

Owner-authorized audit of OPEN paper positions' exit coverage (ledger row **H4** /
`t10-stale-open-swing-positions`, bug-queue B11c). Scope per the 2026-08-03 re-scoping: the
Chandelier-trail / EOD-batch mechanism was already proven live (#1227, H4 row); this audit answers
only the residual question — **is any open position NOT selected by the EOD exit path?**

**Verdict: 18/18 MANAGED. Zero zombies. Zero closes performed. Read-only run.**

## 1. Inventory (re-measured 08:31 IST, 2026-08-04)

`strategy.paper_positions WHERE status='OPEN'` = **18** rows (was 17 on 08-03; the 08-03 20:00 IST
minervini batch entered HFCL, id 56). 6 manas-arora + 12 minervini, 0 scalper, 0 manual-book.
`SELECT DISTINCT book … WHERE status='OPEN'` returns exactly `manas-arora, minervini` — no position
sits in a book neither swing family owns, and no human-managed (#891 MANUAL-book) rows are open.

| id | book | symbol | qty | entry | anchor sig | anchor status | anchor version | verdict |
|---|---|---|---|---|---|---|---|---|
| 26 | manas-arora | PRECOT | 18 | 810.35 | 59 | TAKEN | 1.0.3 **archived** | MANAGED (adopted version — see §3) |
| 33 | manas-arora | SANSERA | 6 | 3336.87 | 78 | TAKEN | 1.1.1 published | MANAGED |
| 36 | manas-arora | KANORICHEM | 100 | 152.29 | 82 | TAKEN | 1.1.1 published | MANAGED |
| 40 | manas-arora | GRWRHITECH | 2 | 7163.58 | 96 | TAKEN | 1.1.1 published | MANAGED |
| 46 | manas-arora | AVALON | 8 | 1760.38 | 126 | TAKEN | 1.1.1 published | MANAGED |
| 53 | manas-arora | SCPL | 23 | 615.31 | 147 | TAKEN | 1.1.1 published | MANAGED |
| 13 | minervini | AUTOIND | 111 | 87.86 | 34 | TAKEN | cheat-3c published | MANAGED |
| 15 | minervini | ATHERENERG | 8 | 1200.60 | 37 | TAKEN | primary-base published | MANAGED |
| 16 | minervini | DIACABS | 42 | 231.45 | 38 | TAKEN | cheat-3c published | MANAGED |
| 19 | minervini | INOXINDIA | 5 | 1876.84 | 41 | TAKEN | cheat-3c published | MANAGED |
| 23 | minervini | PRECOT | 12 | 810.35 | 56 | TAKEN | vcp published | MANAGED |
| 24 | minervini | SOTL | 16 | 607.75 | 57 | TAKEN | vcp published | MANAGED |
| 25 | minervini | CHENNPETRO | 8 | 1181.29 | 58 | TAKEN | vcp published | MANAGED |
| 34 | minervini | KANORICHEM | 62 | 152.29 | 80 | TAKEN | vcp published | MANAGED |
| 35 | minervini | MENONBE | 43 | 220.24 | 81 | TAKEN | vcp published | MANAGED |
| 45 | minervini | AVALON | 5 | 1760.38 | 123 | TAKEN | vcp published | MANAGED |
| 52 | minervini | KAPSTON | 19 | 475.14 | 145 | TAKEN | primary-base published | MANAGED |
| 56 | minervini | HFCL | 46 | 201.10 | 156 | TAKEN | power-play published | MANAGED |

All six anchor strategies are `enabled=t`; every anchor's `strategy_versions.status` is lowercase
`published` except signal 59's (see §3). All claims below `computed` on 2026-08-04 08:31–08:45 IST
unless labeled otherwise.

## 2. Coverage mechanism (code-verified, not inferred from table state)

Selection chain, `sourced` from `SwingBatchEngine.java`:

1. `exitPass` (`:758`) drives off `openLotsBySymbol` (`:562`), which iterates
   `SignalRepository.activeEntries()` (`SignalRepository.java:177`):
   `SELECT * FROM signals WHERE signal_type='ENTRY' AND status IN ('ACTIVE','TAKEN')` — **all
   strategies, no version/enabled filter**. Every one of the 18 positions' anchors is `TAKEN`, so
   every position is in the candidate set.
2. Each anchor resolves via `AnchorResolution.resolve` (`:351`): published versions directly;
   anything else through `adoptVersion` (`:365`), which loads the version row's **own frozen
   config** and exit-manages it. The javadoc is explicit that `enabled` is deliberately not
   checked — a disabled strategy no longer enters but its open positions stay exit-managed. So the
   "strategy no longer enabled+published" zombie class **cannot occur** for a position whose anchor
   version row still exists and compiles.
3. Anchor-expiry cannot orphan a taken position: the only bulk expiry is
   `UPDATE signals SET status='EXPIRED' WHERE status='ACTIVE' …` (`SignalRepository.java:285`) —
   it never touches `TAKEN`.
4. Failure of a per-position evaluation is **counted, not silent**: series-missing and
   entry-bar-outside-window paths increment `exit_skipped` and log
   `STOP NOT EVALUATED TODAY` at ERROR (`:808-831`).
   `SELECT … FROM swing_batch_runs WHERE exit_skipped > 0` → **0 rows, all-time.**

Live confirmation of the last batch (2026-08-03): minervini ran 20:01 IST
(`strategies=4, exit_skipped=0, open_at_start=14`), manas-arora 20:05
(`strategies=2, exit_skipped=0, open_at_start=6`).

**Count reconciliation** (this is the decisive check): `open_at_start` counts symbols whose anchors
*resolved* through `openLotsBySymbol`. manas-arora holds 6 open positions on 6 distinct symbols and
`open_at_start=6` — so all 6 anchors resolved, **including PRECOT's archived-version anchor** (a
resolution failure would have counted 5). minervini family has 15 distinct anchored symbols (12
positions + the 3 phantom anchors of §4); 15 − HFCL (entered during that run) = **14 = open_at_start** ✓.

## 3. Position 26 (manas-arora PRECOT) — the one non-trivial case

Its anchor (signal 59) carries `strategy_version_id = 9aa2f9a8…` = manas-arora-vcp **1.0.3,
`status='archived'`**; the strategy's current `published_version_id` is 1.1.1. This is exactly the
superseded-version case `adoptVersion` exists for (audit-H2 double-exposure path): the batch adopts
the anchor and exit-manages it with 1.0.3's frozen config. Proof it resolves in practice is the
manas `open_at_start=6` reconciliation above (a compile/universe-mode failure would log
`OPEN POSITION UNMANAGED` at ERROR and drop the count). Verdict: **MANAGED**, via adoption — worth
knowing it trails under the *old* config's exit params, which is the designed behavior ("a held
position must reach its stop whichever bucket admitted it").

## 4. Observation (not a zombie, opposite polarity): 3 phantom TAKEN anchors

The anchor sweep returned **21** live ENTRY anchors vs 18 open positions. Signals **20 (SENORES),
23 (TMB), 26 (INDUSINDBK)** — all minervini, all generated 2026-07-03 00:00 IST, all `TAKEN` — have
**no paper position, open or closed, ever** (`opening_signal_id` join and symbol+book scan both
empty; the one SENORES close belongs to a different book/anchor). These date from the first
minervini batch day and appear to be takes that never opened a position. Impact is the reverse of a
zombie: `exitPass` evaluates them harmlessly each session, but they **count as held symbols**,
inflating `open_at_start` and consuming pyramid/slot-cap headroom and re-entry suppression for
SENORES/TMB/INDUSINDBK in the minervini family. Left untouched (out of audit scope — no live-money
risk); flagged for a separate owner-tier cleanup decision.

## 5. SQL used

```sql
-- inventory + coverage join
SELECT p.id, p.book, p.exchange, p.tradingsymbol, p.side, p.qty, p.avg_entry_price, p.stop_loss,
       p.opening_signal_id, p.opened_at AT TIME ZONE 'Asia/Kolkata',
       s.status, s.strategy_version_id, st.slug, st.enabled, st.published_version_id,
       (st.published_version_id = s.strategy_version_id)
FROM strategy.paper_positions p
LEFT JOIN strategy.signals s ON s.id = p.opening_signal_id
LEFT JOIN strategy.strategy_versions sv ON sv.id = s.strategy_version_id
LEFT JOIN strategy.strategies st ON st.id = sv.strategy_id
WHERE p.status='OPEN' ORDER BY p.book, p.id;

-- version status of every distinct anchor version
SELECT id, strategy_id, status, version FROM strategy.strategy_versions WHERE id IN (…6 ids…);

-- live anchor sweep (what activeEntries() sees, restricted to swing slugs)
SELECT s.id, s.tradingsymbol, s.status, s.expires_at, st.slug
FROM strategy.signals s
JOIN strategy.strategy_versions sv ON sv.id=s.strategy_version_id
JOIN strategy.strategies st ON st.id=sv.strategy_id
WHERE s.signal_type='ENTRY' AND s.status IN ('ACTIVE','TAKEN') AND st.slug IN (…);

-- skip ledger + batch health
SELECT batch, run_date, ran_at AT TIME ZONE 'Asia/Kolkata', strategies, candidates, entries,
       exits, exit_skipped, open_at_start FROM strategy.swing_batch_runs ORDER BY ran_at DESC LIMIT 10;
SELECT batch, run_date, exit_skipped FROM strategy.swing_batch_runs WHERE exit_skipped > 0;

-- phantom-anchor check
SELECT … FROM strategy.paper_positions WHERE opening_signal_id IN (20,23,26)
   OR (tradingsymbol IN ('INDUSINDBK','SENORES','TMB') AND book='minervini');
```

## 6. Closes performed / refused

None. No position met the zombie criteria (all four re-scoped classes came back empty: no
unresolvable anchor version, no missing/expired anchor, no unowned book, no `exit_skipped > 0`).
No API writes were made; the run was read-only end to end.

## 7. Disposition

- H4 / T10 row in `docs/superpowers/plans/2026-07-02-remaining-items.md` flipped CLOSED (this PR).
- §4 phantom anchors: surfaced as a separate owner-tier chip; not acted on here.
