> **ARCHIVED 2026-07-02 — EXECUTED as STEP (#381/#382).** 27 PE drafts seeded (9 `-nifty-pe` published,
> 18 SENSEX-PE drafts pending owner). The open design choice resolved: STEP additive YAMLs, not signed-composite.

# PE-mirror — bidirectional scalpers (PARKED, owner will build later)

Status: **PARKED 2026-06-30** (owner: "leave for now, build later; make a planning doc so we
remember"). This captures the problem, what's already built, the two candidate designs, and the
single owner decision that unblocks it — so the next session resumes cold.

## The goal

Today the directional scalpers are **CE-only (long-side)**: a bullish read buys a call. The PE
(put-buy) mirror — a bearish read buying a put — is wired in the ENGINE but **not in the directional
config path**, so the long-only families never take the bearish side.

`direction: both` already collapses to CE for these strategies (the composite scorer is one-sided).

## What is ALREADY built (don't redo)

- **Engine half — #334** (`scalperPositionDirection`): the held-side→direction mapping (CE→LONG /
  PE→SHORT) is fixed at all 3 exit sites. So a PE position exits correctly. This was the latent-bug
  fix + the prerequisite for bidirectional.
- **`ScalperConfluenceGate`** already computes a `side` (CE/PE) from the VWAP-decisive read
  (close ≥ vwap → CE, else PE) and the BTST carry passes a `forcedSide`. The gate CAN emit a PE leg.
- **Hero-zero / BTST** already resolve a side from day-close LOCATION (those take PE today).

So the missing piece is **only** the directional families' COMPOSITE: it scores bullishness on a
[0,1] scale, so a bearish bar scores ~0 and never crosses the entry threshold for the PE side.

## The blocker — one genuine design decision (owner's call)

How to let the composite express a BEARISH conviction so the PE side fires:

### Option A — signed composite (the clean, larger redesign)
Change the confluence score from `[0,1]` (bullish-only) to `[-1,+1]` (signed): negative = bearish
conviction → fire PE, positive = bullish → fire CE. Needs:
- New normalizer types (the scorer's per-dot normalize must emit signed values).
- The composite aggregation + threshold logic to read magnitude-with-sign.
- **This is a FROZEN-SCHEMA change** (the `scoring` block + normalize enum) → a schema-version
  consideration + golden re-capture for any affected fixture. Larger, but the faithful model.

### Option B — STEP catch-all (the smaller hack, ALREADY TRIED + REVERTED)
A catch-all normalizer that NEUTRALISES the one-sided composite so the gate's own CE/PE `side`
decides. **This was piloted and REVERTED** (#327 → #328) because the owner flagged it "loosens the
CE filter" — it weakens the long-side discipline to admit the short side. Recoverable at commit
`f1adaf8` if ever wanted, but it re-ships the exact thing that was rejected, so do NOT auto-pick it.

## Recommendation when resumed

Option A (signed composite) is the faithful path — it lets a strategy take the side the confluence
actually points to, instead of hacking the threshold. Budget it as its own multi-PR effort:
1. Signed normalizer types + the `[-1,+1]` composite (schema + scorer + goldens).
2. Wire the directional families' PE leg through the existing gate `side`.
3. PE-mirror entry conditions per the operative doc (the doc states the bullish confluence + matrix
   explicitly but spells out FEWER bearish numeric thresholds — see operative doc §5 Open Question:
   "bearish mechanics are inferred mirrors rather than separately stated", so the bearish thresholds
   need an owner ruling or a faithful-mirror assumption).

## Operative-doc note

The doc's bearish path is "largely the mirror of the bullish path" but with fewer explicitly-stated
numeric thresholds. So PE-mirror is partly an **owner-numbers** task too (the bearish thresholds),
not purely an engineering one — fold that into step 3.

## Pointers
- Engine prerequisite: #334 (`scalperPositionDirection`).
- Reverted Option-B pilot: commit `f1adaf8` (#327→#328).
- Arming + side logic: `ScalperConfluenceGate.evaluate(...)` (the `side` + `forcedSide` params).
- Operative doc: `strategy-documents/options-scalper-siva-operative/Options_Scalper_Siva_Operative_Strategy.md`.
