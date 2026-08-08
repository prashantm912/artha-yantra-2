# F9 heat cap — armed since 2026-07-05, structurally unable to fire (2026-08-05)

Analysis date: 2026-08-05 (~16:35–17:10 IST), Architect, read-only against the live stack.
Trigger: the post-market routine's NEW-1 finding in
[`2026-08-05-session-findings.md`](2026-08-05-session-findings.md) §6.1.

**Verdict (computed): the F9 portfolio heat cap has never been capable of blocking a scalper entry.
It measures `spanMargin`, and every position the scalper book has ever opened is a LONG option, which
carries no SPAN — 10 of 10 priced snapshots are exactly `0.00`, so book heat is `0.00%` against a
`60%` cap on every entry.** Today's "gate inert" WARN is a second, unrelated and much smaller defect.
Nothing here is a live loss; it is a safety control that reports healthy while measuring the wrong
quantity. **Owner decision required — the fix changes what "risk" means for this book.**

The routine's own §7 NEW-1 row ("investigate the content-type defect") would have chased the smaller
defect and never reached this one. That is recorded deliberately: the misdiagnosis is the useful part.

---

## 1 What the routine saw, and what it actually was

The routine reported (`2026-08-05-session-findings.md` §6.1) a *"wire/content-negotiation defect …
NOT a margin-unpriced case"* on `POST /api/v1/market/margin`, from this pair of live WARNs:

```
05:34:22.900Z WARN PaperMarginClient :: paper margin heat call failed: Error while extracting
              response for type [PaperMarginClient$Quote] and content type [application/octet-stream]
05:34:22.900Z WARN RiskService      :: book 'scalper' heat-cap enforcement ON but heat unassessable
                                        — gate inert this entry
05:34:23.453Z WARN PaperMarginClient :: (identical, second call)
```

**Actual mechanism (computed from timestamps + configured timeouts, three independent facts):**

| # | fact | source |
|---|---|---|
| 1 | `PaperMarginClient` read timeout = **2000 ms** (connect 1500 ms) | `PaperMarginClient.java:37-38` (sourced) |
| 2 | market-data logged `Upstox F&O instrument master loaded: 37028 mapped legs` at **05:34:23.435Z**, on `tomcat-handler-1899` — i.e. *while both client calls were in flight* | `docker logs ay-market-data-service` (sourced) |
| 3 | `UpstoxFnoMasterClient` fetches a **5 MB+ gzip** master **lazily**, on the first lookup after a 12 h `REFRESH` window, with its OWN timeouts of connect 15 s / read **60 s** | `UpstoxFnoMasterClient.java:44,63-66` (sourced) |

Both WARNs fired at exactly **2000 ms** after their calls began, and the master finished loading
535 ms *after* the first one gave up. **The callee is budgeted 60 s for a cold load; the caller allows
it 2 s.** The `application/octet-stream` text is the symptom of the aborted read, not a
content-negotiation bug — Spring falls back to that media type when it cannot read a Content-Type off
the (here, incomplete) response. *(The precise converter path is **assumed**; the timeout is
**computed** and is what matters.)*

**Only ONE F&O master load occurred all day** (05:34:23), so this was the day's first `keyFor()`
lookup. The margin endpoint is its only live caller in this deployment (the Upstox quote/ticker source
flags default to Kite). So the shape is deterministic: **the first funded entry after any container
start — or after the 12 h refresh window lapses mid-session — pays the cold load and gets an inert
heat gate.** It is not intermittent and not a wire defect.

Cost of the misdiagnosis had it stood: the proposed investigation ("reproduce the octet-stream reply,
check gateway/actuator error-path content type") looks at a component that is behaving correctly.

## 2 The larger defect the WARN led to

Chasing why *today's* row had no `margin_snapshot` exposed that **no row has ever had a non-zero one**.

```sql
SELECT book, side, count(*) n, count(margin_snapshot) priced, max(margin_snapshot) max_span
FROM strategy.paper_positions GROUP BY 1,2;
```
| book | side | n | priced | max_span |
|---|---|---|---|---|
| scalper | BUY | 11 | 10 | **0.00** |
| manas-arora | BUY | 14 | 0 | — |
| manas-arora | SELL | 1 | 0 | — |
| minervini | BUY | 21 | 0 | — |

(computed, live `artha` DB, 2026-08-05 ~16:50 IST, read-only.)

- Every scalper position is **BUY** — the book buys CE/PE outright, it never writes options.
- All 10 priced snapshots are **exactly `0.00`**, and `margin_pct` likewise. The one NULL is today's
  position 57 — the timeout in §1.
- The swing books are unpriced by design (`heat_cap_pct` is set for `scalper` only).

**Why this is structural, not a data gap.** A bought option has no SPAN requirement — the buyer pays
premium and can lose no more; SPAN exists for the writer. Upstox correctly returns `span_margin: 0`
for a long basket and carries the real capital requirement in the other components. The gate reads:

```java
// RiskService.java:503-510
if (!q.priced() || q.spanMargin() == null) { return null; }          // → "unassessable", gate inert
return q.spanMargin().multiply(100).divide(equity, 2, HALF_UP);      // → 0.00 for a long book
```

so `heatPct` is `0.00`, `heatPct.compareTo(capPct) >= 0` is false against the `60` cap
(`RiskService.java:468`), and the branch that blocks an entry is **unreachable for this book's
position type**. Armed since 2026-07-05 (#576); enforcement confirmed ON today by the live WARN's own
wording (`enforcement ON but heat unassessable`).

**Contributing drift (sourced).** `PaperMarginClient.Quote` has **7** components; market-data's
`MarginResponse` has **10** — the client is blind to `equityMargin`, `netBuyPremium` and
`additionalMargin`. `netBuyPremium` is precisely the field that carries a long-option basket's outlay.
Jackson matches by name, so the drift is silent and nothing fails; it just means the one number that
would make this gate meaningful is not even deserialized. (`PaperMarginClient.java:46-53` vs
`MarginController.java:69-79`.)

## 3 What is NOT wrong

Stated explicitly so a fix does not sprawl:

- **No mis-booking, no loss, no live money impact.** The cap only ever *fails to block*; it has never
  blocked anything incorrectly. Today's single funded entry (−₹2,583.73) was sized by `budget_inr`,
  which worked.
- **Fail-soft is correct as designed.** An advisory pricing call must never block the tick thread —
  the 2 s timeout exists for exactly that reason (`PaperMarginClient.java:28-33`) and the #694 doctrine
  agrees. §1 is not an argument for removing the timeout.
- **market-data is behaving correctly** on both counts: the lazy master load is deliberate (no
  scheduler needed), and `span_margin: 0` on a long basket is the true answer.
- **`margin_snapshot = 0.00` is not a bug in the annotator.** `PaperMarginAnnotator:59` correctly
  stores what it was given.

## 4 Options for the owner

Not started. Each is a distinct decision; (a) is the one that matters.

| | option | effect | tier |
|---|---|---|---|
| **a** | **Re-base book heat on capital at risk, not SPAN** — for a long-option book use premium outlay (`netBuyPremium`/`finalMargin`, once the client record carries it); keep SPAN for any short leg. Makes the 60% cap mean something. | the cap becomes capable of blocking entries — **changes live behaviour**, and on a ₹1.5 L book a 60% premium-outlay cap will bind | **HOLD / owner** — money path |
| **b** | Accept and re-label — say plainly that the cap is a short-option control, inert for a long-only book, and stop reporting it as an armed risk gate | zero behaviour change; removes a false sense of coverage | owner (docs + settings comment) |
| **c** | Widen `Quote` to mirror all 10 `MarginResponse` components | prerequisite for (a); harmless alone | clean |
| **d** | Warm the F&O master (boot preload, or a scheduled touch inside the 12 h window) — fixes §1 without touching the 2 s timeout | first entry after boot gets a real heat read instead of gate-inert | clean |
| **e** | Decide whether a repeated `gate inert` should page rather than WARN | observability only | owner |

**Recommendation:** (c) + (d) are clean and can ship independently — (d) is what makes §1 stop
recurring, and neither changes a number the owner sees. **(a) is the real question and is HOLD-tier**:
it arms a cap that is currently decorative, and on this book size it will start refusing entries.
(b) is the honest fallback if the owner does not want the cap to bind.

## 5 Open doubts

- `netBuyPremium` / `finalMargin` for these baskets is **assumed** to be non-zero and to equal the
  premium outlay — only `spanMargin` was ever persisted, so this is not measured. A live one-leg probe
  against `POST /api/v1/market/margin` settles it in one call; **not run** (this pass was read-only).
- Whether §1 recurs on *every* boot or only when the first entry lands within the cold-load window is
  **computed from one observation**. Two data points would confirm; the next session's first funded
  entry is the natural check.
- The 12 h `REFRESH` means a long-running container can also go cold **mid-session**. Not observed.
- `margin_pct = 0.00` (not NULL) means `BookHeatReader` sums a real zero rather than filtering the row
  out — so the `/api/v1/paper/margin-heat` endpoint and the risk-heat insight have been reporting a
  confident 0%, not "unknown". **Not separately verified**; flagged because it is the difference
  between an honest gap and a misleading reading.
