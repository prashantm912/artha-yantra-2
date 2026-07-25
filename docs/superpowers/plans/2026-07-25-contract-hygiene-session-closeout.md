# Contract-hygiene session closeout — findings + remaining items (2026-07-25)

One-page record of the session that closed the nullable-contract sweep and the four findings its
final slice surfaced. Ledger rows are authoritative for status; this doc exists so the *reasoning*
and the **owner decisions** are not spread across eight PR bodies.

## Verdict

The sweep is **CLOSED** and all four successor chips are resolved or explicitly parked. Two REAL
defects were found that nobody had filed, and **three times the chip brief itself was wrong** — each
caught by running the tool or by an adversarial cross-vendor reviewer, never by re-reading the brief.
One item remains genuinely owner-facing: `main`'s required-status-check list is unsatisfiable, so
every PR is `--admin`-merged and **no required check is actually enforcing anything today.**

## What shipped

| PR | What |
|---|---|
| [#1003](https://github.com/prashantm912/artha-yantra-2/pull/1003) `a393a9d2` | Sweep slice 3d — 184 nullable components / 63 schemas. Closes task_79d12a4d + task_0b14da09. |
| [#1005](https://github.com/prashantm912/artha-yantra-2/pull/1005) `fb59a502` | `WebSession` framework-type leak (other session; row flipped here after verifying against main's spec). |
| [#1010](https://github.com/prashantm912/artha-yantra-2/pull/1010) | Nullable-ENUM carve-out (other session). |
| [#1012](https://github.com/prashantm912/artha-yantra-2/pull/1012) `a8217785` | Duplicate simple-name dedupe — 20 components renamed out of 9 collapsed names + 90 annotations. |
| [#1007](https://github.com/prashantm912/artha-yantra-2/pull/1007) `9278cfef` | `ci-review-verdict` gate: live body read + a matcher that stops rejecting honest verdicts. |
| #1006 / #1011 / #1013 | Ledger rows, the `$ref` findings doc, post-merge verification. |

## Findings that were NOT in any chip

**1. A live published lie in the contract.** `POST /api/v1/auth/kite/session` was publishing
`BhavcopyBackfillService.ExchangeResult`'s `days` / `bhavRows` / `candleRows` — and **none** of its
own `connected` / `kiteUserId` / `tokenValidUntil`. Two records shared the simple name
`ExchangeResult`, so springdoc's scan order decided which one the world saw. Found by the
**cross-vendor review**; missed by the chip, the builder, and the sweep doc, which had explicitly
recorded `ExchangeResult` as field-identical. Fixed with one approved break (those three keys leaving
that endpoint) — independently re-verified: exit 1 with exactly three incompatible findings, all 16
other changed endpoints backward compatible.

**2. `required` was being computed as a cross-twin INTERSECTION.** Where simple names collapsed, the
published `required` array was the winner's properties ∩ the last-resolved twin's always-emitted set.
Published `Status` carried 8 properties but only 5 required. Nobody was looking for this.

**3. Five ways to pass the review-verdict gate with no review at all.** Found over 3 rounds by the
cross-vendor review of the gate fix itself: an unspaced ASCII dash let `APPROVED round-1 (zero
findings)` pass with `-1` as the "model"; an unanchored label let `No Cross-vendor review: APPROVED …`
pass by *denying* a review; `- no reviewer (OpenAI)` and `? No - review skipped (Anthropic)` passed
prose as a model name; and a mis-paired `Opus (OpenAI)` passed. **One of these was introduced by the
Architect's own audit fix** — same-vendor review would likely have missed it.

**4. A record returning `Map<String,Object>` cannot collide.** springdoc never resolves it, so
`ScreenerService.Row` was a phantom entry in the chip's collision list. Corollary to the standing
"Maps are invisible to the contract gate" rule.

## Where the brief was wrong (three times)

- **`frontend` → `react`** (my branch-protection chip): would have rebuilt the same trap, because
  `react` is path-filtered too. Caught by comparing required contexts against what actually reports.
- **the `allOf` wrapper** (my `$ref` chip): dead three independent ways — swagger-core 2.2.30 cannot
  emit it, `allOf` does not validate `null`, and TS collapses `(T|null)&T` back to `T`. Caught by
  running swagger-core, `jsonschema`, and `tsc` rather than reasoning.
- **"don't touch `ScreenResponse`/`Funnel`"**: their field-identity was skin-deep — their `items` /
  lists `$ref` the row schema, so once the rows split the parents diverged and had to split too.

## Remaining items

**OWNER DECISION — `task_db8bdf1e`, the structural half (top of the queue).** `main` requires
`contracts`, `gitleaks` and `build-test (×3)`, all of which come from **path-filtered** workflows, so
a PR touching none of those paths never reports them and is `BLOCKED` forever; `e2e` is the only
required check that runs on every PR. Every merge this session therefore used `--admin`, **which
bypasses every required check** — a genuinely-red one would sail through unnoticed. The dead
`frontend` context is already removed. The fix is a real cost tradeoff: drop the path filters (every
docs PR then pays the ~8 m market-data shard, which CLAUDE.md calls the PR floor), or add
change-detection / always-reporting shim jobs reporting one stable check name — mind the
skipped-job-vs-required-check semantics and the double-report race when `paths` and `paths-ignore`
both match a mixed PR.

**BUILD QUEUED — `task_bd871971`, nullable `$ref`.** Verdict and plan measured and written up in
`2026-07-25-nullable-ref-downgrade-findings.md`: emit `anyOf:[{$ref},{"type":"null"}]` from a
springdoc customizer + a strip pre-pass in `openapi_relabel_30.py` that keeps the 3.0 diff copy
byte-identical (so no approval token). **Settle open-doubt #1 first** — does springdoc 2.8.9 pass the
`$ref`+`types` sibling through? One annotated component plus one capture answers it; if not, switch to
a `@NullableRef` marker + `ModelConverter`. A + B1 + B2 must land in ONE PR; piece A alone cannot even
be relabeled.

**Carried, documented, no action:** `KiteSessionExchangeResult.tokenValidUntil` unannotated while
`KiteStatus.tokenValidUntil` is nullable (different reachability); `BacktestResult.note` vs
`Report.note`, same asymmetry class; `JsonNode` components are vacuous rather than dishonest; the
verdict gate cannot prove a review happened (no regex can) so a semantic contradiction still passes —
it is a non-required reminder gate. Adding a reviewer model family now means editing the workflow
**and** ROUTING.md together.

**NOT DEPLOYED, deliberately.** Every JAR delta from the sweep and its successors is
annotation/metadata-only (served at `/v3/api-docs`). Each rides its service's next substantive deploy.

## Process notes worth keeping

- The cross-vendor review earned its cost twice in one session: it found the `ExchangeResult` live lie
  and five gate bypasses. Both times the useful instruction was **"attack this"**, not "review this".
- Repo tooling run from the shared checkout can be STALE — the checkout sat on another session's
  branch, so `openapi_relabel_30.py` was an older copy and a breaking-gate dry-run failed in a way
  that looked like a spec defect. Verify someone else's work with tooling read from a known ref
  (`git show origin/main:<path>`).
- A codex review session is keyed by its resolved `--cd`; a `cd` that persists between tool calls
  silently targets a different key, the harness exits non-zero, and the STALE `review.txt` still
  reads like a fresh verdict. Check the mtime and the commit range the review claims to have read.
