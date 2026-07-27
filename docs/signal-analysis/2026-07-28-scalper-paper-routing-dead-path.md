# Scalper → paper routing has never worked (G7 / T25)

**Verdict (computed 2026-07-28 from live DB + code):** scalper auto-paper is a **dead money path
with zero observable signal**, and has been since the book was created. It is a **bug, not an
un-armed flag** — the flag is deliberately ON. **15 of 15** scalper ENTRY signals between
2026-07-07 and 2026-07-27 carry `suggested_qty` NULL; **no scalper row has ever existed** in
`paper_positions` or `paper_orders`. Root cause: position sizing is computed against the **index
future** (price × index lot) instead of the **option leg** (premium × option lot), which floors to
0 lots every time.

This was invisible for three weeks because a 0-lot size returns `null` and the listener returns
**silently** — no log line, no `risk_audit` row, no `paper_order_rejections` row (all three
verified empty for the affected dates).

## Why it stayed hidden

Three independent reasons, each worth its own lesson:

1. **No fires to test against.** Scalpers fired on 2026-07-27 for the first time since 07-20. Every
   prior session had zero scalper signals, so "zero scalper paper positions" was indistinguishable
   from "nothing fired". The session that finally produced fires is the session that exposed it.
2. **Backtest could never catch it.** The backtest replays **premium-as-primary** and builds its own
   option `CostConfig`, so it has always sized off the option premium — correctly. Live and backtest
   diverged precisely at the sizing input, and only the live side was wrong. A green backtest was
   never evidence about this path.
3. **The failure mode was a silent `return`.** See "the silence is the deeper defect" below.

## The chain, and where it stopped

| # | Step | Outcome |
|---|---|---|
| 1 | `SignalEngine` fires, gates + composite pass | OK |
| 2 | `emissionGuard.entryVeto(book)` — scalper governors | OK, clears |
| 3 | `emissionGuard.suggestedQty(...)` with the **index future** basis | **STOPS HERE** → 0 lots → `null` |
| 4 | Signal row inserted with `suggested_qty` NULL; `SignalEmitted` published | OK |
| 5 | `AutoPaperListener` → book = `scalper`, `auto_paper_trade` enabled = **true** | passes |
| 6 | `AutoPaperListener` null-qty check | **silent return** — no trace anywhere |
| 7 | `PaperSignalListener` / `PaperService.openOrder` | never reached |
| 8 | Signal sits ACTIVE for its 60-min TTL | lapses `EXPIRED` |

The arithmetic at step 3, for the 2026-07-27 fires: NIFTY26AUGFUT at 24,092.00 × lot 65 =
₹1,565,980 per lot against a ₹15,000 budget → `FLOOR(15000/1565980)` = **0**. On the correct
(option) basis the same signal sizes to **1 lot = 65 units at ~₹152.65 ≈ ₹9,922**.

## Four defects, not one

1. **Sizing basis** (root cause) — index future instead of the picked option leg. The correct
   pattern already existed twelve lines away in the same method (`heroZeroSuggestedQty` passes the
   picked candidate's symbol and ltp).
2. **Exchange stamp** — the option leg is stamped with the index future's exchange on the premise
   that "options trade on the same derivatives exchange as the index future". **False for SENSEX**:
   a live row carries `tradeable_exchange=NFO` with `tradeable_tradingsymbol=SENSEX26JUL76300CE`,
   which the instrument master says is **BFO**. Fixing defect 1 alone makes this *worse* — the
   NFO lookup 404s, falls back to an equity proxy with lot 1, and produces a non-lot-aligned qty
   that also fails the Upstox margin call (`UDAPI1104`). **The same wrongly-stamped field feeds the
   real-money path** (`LiveOrderService`), which is currently unarmed (`artha.scalper.execution`
   defaults to `paper`) — a latent landmine, fixed at the same stamp site.
3. **A second blocker, introduced 2026-07-27 22:31 IST by #1036** — the swing effect-lease
   (`requireEntry`) is an UPDATE of a row only `SwingBatchEngine.expectEntry` creates, so for a
   scalper it updates zero rows and bails. Masked today because defect 1 bails two lines earlier,
   so there was **no live behaviour change** — but fixing defect 1 alone would have moved the
   silent bail down, not removed it. Fixed by scoping the lease to swing books, which is what the
   table (`swing_paper_effects`, keyed by batch + session date) was built for.
4. **The silence** — see below. This is the one that actually cost three weeks.

## The silence is the deeper defect

A money path can be completely dead and produce **no** signal: not a log line, not a DB row, not a
metric. Everything downstream looked healthy — the engine was live, signals persisted, the
governors were green, the flag read `enabled: true`.

The rule this earns: **a guard that declines to act on a money path must leave a durable trace.**
Not a log line alone — container restarts destroy logs, and that is exactly what denied this
diagnosis its direct evidence (the 22:31 IST restart wiped the session's buffer, so the conclusion
had to be reconstructed from row state and code). It must be queryable from the database.

This is the same family as the `signal_rejections` trap already recorded in `CLAUDE.md`: an empty
table means "the code path that writes rows never ran", which is *not* the same as "nothing was
wrong". Absence of evidence keeps being read as evidence of absence.

## Residual, deliberately not fixed (owner number)

After the fix, the `budget_inr: 15000` uniform across every scalper YAML admits a NIFTY lot
(~₹9,922) but **not** a SENSEX one (₹776 × 20 = ₹15,520). So the fix arms roughly the NIFTY family
and leaves the SENSEX family dormant **for a different reason** — budget, not basis. That is an
owner decision, chipped as `task_79b20900`; defect 4's new observability is what makes it visible
rather than silent.

## Open, worth its own look

- **Straddle qty accounting under the lease** — `openSwingEffect` compares read-back against a
  single-leg `expectedQty`, while a straddle opens two legs at a combined qty. Never exercised for
  a scalper; wants an integration test rather than a code read.
- The scalper sub-account round-robin (E10 five-account model) is a ledger key that has **never**
  been exercised in production.
