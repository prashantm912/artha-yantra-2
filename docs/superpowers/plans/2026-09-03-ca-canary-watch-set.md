# CA canary watch set — measurement and proposal (2026-09-03)

**Owner asked for a small watch set to exercise the corporate-action fix sooner. Here it is — and the
arithmetic says a *small* set does not achieve that, so the proposal comes with the number attached
rather than a symbol list on its own.** Nothing is armed by this document.

Status: **PROPOSAL, awaiting owner. No `.env` change has been made.**

---

## 1. Why this exists

`CorporateActionJob`'s purge-before-fetch defect was fixed in `b14bb3e3` / [#1297] — the order became
fetch → verify → swap, so a failed multi-hour re-fetch can no longer leave a symbol's series gutted.
The job was then re-armed on a **two-symbol canary allowlist** (`ITC,HDFCBANK`, owner: "canary
first").

`computed` 2026-09-03: **it has processed nothing since.** Zero events detected or resolved after
2026-08-04; newest detection anywhere in the table is **2026-07-31**. The fix is deployed and has
never run against a real corporate action.

## 2. The base rate, measured

From `marketdata.eod_corporate_actions` (354 rows, ex_dates 2025-04-29 → 2026-08-28 — 17 months) and
`nse_eod_bhavcopy`:

| quantity | value |
|---|---|
| actions per month, whole universe | **20.8** |
| distinct symbols per month | **13.7** |
| distinct symbols with ≥1 action in 17 months | **233** |
| tradable universe (last 30 days, EQ+BE) | **3,665** |
| kinds present | BONUS 191, SPLIT 163 (dividends live in a separate table) |

Base rate ≈ **0.37 % per symbol per month**.

⚠️ **The current allowlist's own record is the decisive number: `ITC` has had ZERO actions in the
whole 17 months, and `HDFCBANK` exactly one (BONUS, 2025-08-26).** One event per two symbols per 17
months. Waiting for this canary to exercise the path is a multi-year proposition, not a slow one.

## 3. ⚠️ Corporate actions concentrate in the MOST liquid names, not the least

This was worth measuring because the natural assumption is the opposite — that bonuses and splits are
a small-cap phenomenon. Turnover deciles over the last 60 sessions, against symbols with ≥1 action:

| turnover decile (1 = most liquid) | symbols with an action | of 369 |
|---|---|---|
| 1 | **31** | 8.4 % |
| 2 | 23 | 6.2 % |
| 3 | 21 | 5.7 % |
| 4 | 18 | 4.9 % |
| 5–8 | 7 / 8 / 6 / 7 | ~2 % |
| 9, 10 | **0**, **0** | 0 % |

So a liquid watch set is the right shape — it roughly **doubles** the hit rate over a random one
(0.49 %/symbol/month in decile 1 vs 0.37 % universe-wide) — and it also happens to be the safer set,
because these symbols have full, re-fetchable history.

## 4. The arithmetic, stated before the list

Expected months to the first exercise, for `k` top-decile symbols at 0.49 %/symbol/month:

| watch set size | expected wait |
|---|---|
| 2 (today) | **~17 months** (and consistent with the observed 1 event in 17) |
| 10 | ~20 months → ~10 |
| **40** | **~4 months** |
| 70 | ~3 months |
| 200 | ~1 month |
| whole universe (empty knob) | days |

⚠️ **This is the concern with the instruction as given, stated once and then worked around rather
than used to refuse it: "a small watch set" and "exercise the fix soon" are in tension.** Forty
symbols is the smallest set that buys a wait measured in months rather than years. Ten buys almost
nothing.

## 5. ⚠️ How much a live exercise is actually worth — lower than it first appears

Before sizing the risk, the counterweight: **the fixed path is not untested.** `b14bb3e3` shipped
**+403 lines** in `CorporateActionResumeTest` and **+78** in `CorporateActionIntegrationTest`, plus
migrations V056/V057 for the partial-swap status and the rebuild staging table. The fetch → verify →
swap ordering, and resumption after interruption, are pinned offline against a real Timescale
container.

What a live exercise adds is only what tests cannot reach: real Kite rate-limiting across a
multi-hour fetch, real vendor data shapes, and the actual swap under production data volume. That is
worth having — it is not worth much risk.

## 6. The proposed set (40 symbols)

Top 40 by 60-session average turnover, restricted to `EQ`/`BE` and to symbols already holding
**≥ 260** `1d` bars, so any failure is recoverable from existing history plus a re-fetch:

```
HDFCBANK, KALYANKJIL, ICICIBANK, RELIANCE, BSE, INFY, BHARTIARTL, ETERNAL, SBIN, CUPID,
TCS, BAJFINANCE, AXISBANK, NETWEB, ATHERENERG, M&M, MCX, DIXON, LT, HSCL,
PAYTM, SWIGGY, HINDCOPPER, KOTAKBANK, IDEA, ADANIENSOL, MARUTI, SHRIRAMFIN, SILVERBEES, ADANIPOWER,
LAURUSLABS, ADANIENT, HFCL, KAYNES, COFORGE, TVSMOTOR, BHEL, HINDALCO, LICI, JIOFIN
```

Notes on the set, so it is judged rather than pasted:

- **`HDFCBANK` is already in the canary** and is retained. **`ITC` is not in the top 40 by turnover**
  and would be dropped — it has had no action in 17 months, so it contributes nothing but is also
  harmless to keep if preferred.
- **`SILVERBEES` is an ETF**, not an operating company. It is in the turnover ranking legitimately
  but is a poor CA canary; recommend dropping it, leaving 39.
- ⚠️ **`M&M` contains an ampersand.** The knob is a comma-separated `List<String>` bound from an
  environment variable through compose — `&` is safe in `.env` but must not be shell-interpolated
  when the file is edited. Related: [[env-file-acl-sed-trap]] — edit `.env` **in place**, never with
  `sed -i`, which strips its hardened ACL and makes SEC-01 refuse a live start.

## 7. ⚠️ The knob's semantics, and the way this goes wrong

From `application.yml`:

```yaml
    # ⚠️ CANARY ALLOWLIST — comma-separated tradingsymbols; EMPTY (the default) means EVERY active
    # equity, which is the pre-existing behaviour.
    symbols: ${ARTHA_CORPORATE_ACTIONS_SYMBOLS:}
```

**Empty does not mean "off" — it means the entire universe.** So the failure mode of editing this
knob is not a narrower sweep; it is arming everything at once, on the exact path whose remediation
**OOM-crashed live Postgres three times** before V057 made the rebuild safe. A typo that blanks the
value is the whole-universe sweep.

The same yml block records that this key had **no compose passthrough at all** until 2026-08-10, so
setting it was silently inert — a knob that looked armed and did nothing. Verify after any change by
reading `docker inspect ay-market-data-service`, never the yml default and never `.env` alone.

## 8. Recommendation

**Arm the 39-symbol set (the list above minus `SILVERBEES`), keep `ITC` if you want continuity, and
re-read `docker inspect` to confirm it bound.** Expected first exercise ~4 months, against a path
already covered offline by 481 lines of test — so this is a confirmation mechanism, not a
correctness gate.

If four months is too slow to be worth anything, the honest alternative is **not** a bigger
allowlist but a **deliberate rehearsal**: run the remediation against one already-fully-backfilled
symbol in a controlled window and watch the stage → verify → swap, which exercises the same code on
demand instead of waiting for the market to supply an event. That is a separate, larger piece of
work and is not proposed here.

**Not recommended:** clearing the knob to arm the universe. It removes the containment that exists
precisely because this path has crashed live Postgres before, and it buys days instead of months for
a fix whose ordering is already pinned by tests.
