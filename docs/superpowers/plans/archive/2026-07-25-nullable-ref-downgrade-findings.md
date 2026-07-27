> **ARCHIVED 2026-07-28 — research fully consumed by #1034 (task_bd871971 DONE 2026-07-26).** Kept for the measured evidence (allOf option dead 3 ways).

# Nullable `$ref` response components — measured findings + implementation plan (2026-07-25)

Chip **task_bd871971**, the structural carve-out the nullable-contract sweep left open (sweep doc:
`2026-07-25-nullable-contract-sweep.md`, "Verified constraints" #2 + the 3d slice's "$ref real-null
parking"). Read-only research spike; every claim below was produced by RUNNING the real tool
(swagger-core 2.2.30 jars from `~/.m2`, the `origin/main` relabel script, `openapitools/openapi-diff`,
`openapi-typescript@7`, `tsc --strict`, `jsonschema` Draft2020-12) against copies of the committed
specs. No repo file was modified by the spike.

## Verdict

**Extend `openapi_relabel_30.py` with a `$ref`-`anyOf` STRIP downgrade, and emit
`anyOf: [{$ref}, {"type": "null"}]` from a springdoc customizer.** The `allOf` wrapper idea is dead;
the strip downgrade costs no approval token because the 3.0 diff copy comes out **byte-identical** to
today's copy.

## Why `allOf` is dead (three independent measurements)

1. **swagger-core 2.2.30 cannot emit it.** No annotation form yields `allOf` in 3.1 mode. What
   `@Schema(types = {"object","null"})` on a record-typed component actually emits is
   `{"type":["object","null"],"$ref":"…"}` — i.e. **`$ref` WITH SIBLINGS**, precisely the shape the
   relabel script refuses. (`@Schema(nullable = true)` is dropped, re-confirming the house rule.)
2. **`allOf` is not nullable.** Validating `null` under Draft2020-12: `allOf[$ref]+type[object,null]`
   → INVALID; `allOf[$ref,{type[object,null]}]` → INVALID; `$ref`+`type[object,null]` sibling →
   INVALID. Only **`anyOf:[{$ref},{"type":"null"}]`** → VALID. That is the one honest spelling.
3. **TypeScript erases it.** The `allOf` shape generates `(T | null) & T`, which `tsc --strict`
   collapses to `T` (`TS2322: Type 'null' is not assignable to type 'T'`) — the FE type would keep
   lying. The `$ref`+sibling shape generates plain `T` (siblings ignored outright). Only `anyOf`
   generates `T | null`.

`allOf` also breaks the gate for free: relabel accepts it, but `openapi-diff` then reports 28×
`Changed property type: portfolio (object -> object)`. A control run proved the break comes from the
`allOf` wrapping alone, not from nullability — so that route would cost a blanket
`Contract break: APPROVED` (which waives the gate for *every* service in the PR) for a spec that is
*still* not null-honest.

## Why the strip downgrade is sound (not an assumption — measured)

Refusal matrix against `origin/main`'s script (exit codes captured without a pipe):

| variant | relabel exit | why |
|---|---|---|
| base spec | 0 | — |
| `$ref` + `type:["object","null"]` | 2 | `scan()`: `$ref with sibling keys` |
| `anyOf:[{$ref},{"type":"null"}]` | 2 | the 3.0-validator backstop rejects `type:"null"` |
| `oneOf:[{$ref},{"type":"null"}]` | 2 | same backstop |

Both blockers in the sweep doc's constraint #2 are confirmed exactly as written.

The prototype pre-pass (+22 lines, mirroring the existing `downgrade_nullable_type_arrays`) matches
**only** a node whose sole key is `anyOf` with exactly two branches — one a lone `$ref`, one exactly
`{"type":"null"}` — and replaces it with the bare `$ref`. Results:

- relabel exits **0**; the produced 3.0 copy is **SHA-256 identical** to today's `base-30.json`, so
  `openapi-diff` reports *"No differences. Specifications are equivalent"* — **no spurious break, no
  approval token, ever**.
- **The gate keeps its teeth on those very properties:** deleting an annotated property still fails
  (`Missing property: portfolio`), and renaming a field *inside* the referenced target still fails
  (`Missing property: portfolio.annual`).
- **Discarding nullability in the 3.0 copy hides nothing**, because openapi-diff 2.1.7 is blind to
  nullability in BOTH directions — *removing* nullability from 5 already-nullable scalars diffs as
  "backward compatible". The copy drops only information the gate provably cannot see.
- `scan()` is **not** widened: the deliberate `$ref`-with-siblings refusal, and the `oneOf`-null
  refusal, both still return 2.
- The alternative "canonical 3.0 idiom" downgrade (`{"allOf":[$ref],"nullable":true}`) relabels fine
  but reintroduces the 28 spurious breaks. Strip wins.

## Answers to the two side questions

**`JsonNode` components are NOT lying — leave them alone.** `components.schemas.JsonNode` is
literally `{}`, which validates `null` (and everything else), and openapi-typescript renders it
`unknown`, which TS forces you to narrow. Annotating would *tighten* an intentionally-open schema.
The fair criticism is "vacuous", not "dishonest". (5 such props in strategy-signal, 1 in market-data,
2 in backtest.)

**`FundamentalsController:46` → return 404.** `repo.find(...).orElse(null)` from a `@RestController`
is a 200 with an EMPTY BODY while the spec declares a fully-`required` `EquityFundamentals` — a
response-level lie invisible to component annotations. It is free to fix: **no consumer exists**
(`grep` over `frontend-react/src`, `e2e`, `services`, `libs` hits only the controller and the
generated `.d.ts`; the FE reads fundamentals nested inside the Minervini/Manas candidate payloads).
Use `ResponseEntity.of(...)` (200-or-404 natively) + an `@ApiResponse(responseCode = "404")`; adding a
response code is diff-compatible (openapi-diff only has rules for *removed* codes). 204 is weaker
semantics; document-as-is leaves the 200 schema a lie.

## Implementation plan (A + B1 + B2 must land in ONE PR)

**Piece A alone is worthless** — the annotation emits the refused sibling shape, so without B2 the
capture cannot even be relabeled.

- **B1 — `.github/scripts/openapi_relabel_30.py`**: add `downgrade_nullable_ref_anyof(node)` called
  right after the two `downgrade_nullable_type_arrays` calls in `main()`. The docstring MUST state why
  the copy legitimately drops nullability (3.0 has no spelling for a nullable `$ref`; openapi-diff is
  blind to nullability in both directions, measured here; the copy therefore stays byte-identical to a
  pre-annotation copy) — otherwise a future reader will "fix" the intended divergence. Do **not**
  widen `scan()`.
- **B2 — `libs/common-web/core/.../openapi/NullableRefCustomizer.java`** + a bean in
  `ArthaOpenApiAutoConfiguration`: walk `components.schemas` + `paths` and rewrite any schema having
  BOTH a `$ref` and a `types` set containing `"null"` into `anyOf:[{$ref},{types:Set.of("null")}]`,
  clearing `$ref`/`types` on the wrapper. Serialization is settled:
  `new Schema<>().setTypes(Set.of("null"))` emits `{"type":"null"}` (exactly what B1 matches), while
  `setType("null")` is silently dropped in 3.1 mode. ~40 lines; reuse the `refsIn`-style walk in
  `ResponseRequiredCustomizer`.
- **A — annotate the parked components** with `@Schema(types = {"object", "null"})`: market-data
  `Report.portfolio/.portfolioRsPriority/.portfolioRsPriorityNet`, `BacktestResult.rotation`,
  `Funnel.regime` (**+ every Manas twin, annotated identically** — sweep constraint #3),
  `SourceHealth.lastRun`, `FuturesDigest.banks/.termStructure`, `EquityDigest` ×4, `FiiDigest` ×5,
  `OptionsDigest` ×6, `DayContext.options/.vix/.indexPriceAction`; strategy-signal
  `PositionDetail.openingSignal`, `ChainTableLeg.deltas`. **Skip** every `JsonNode` (above) and
  `ActResponse.ticket/.journal` (class-level `@JsonInclude(NON_NULL)` → already honest, constraint
  #1). Sizing: the three specs carry 50 record-typed `required`-`$ref` props (strategy-signal 6,
  market-data 42, backtest 2); ~26–30 are the verified-nullable subset.

**Verify ladder:** capture both affected services with `-am`; grep the fresh specs for a stray
`"$ref"`-with-`"type"` sibling — **zero allowed** (that is the proof B2 fired); relabel all four
services → exit 0; SHA-256 the relabeled copy against the same copy built from the merge-base spec →
**identical**; `openapi-diff --fail-on-incompatible` → exit 0 (**if a `Contract break: APPROVED` token
turns out to be needed, B1 is wrong**); regen `contracts/gen` + `tsc --strict`; FE trio; and a
negative test — temporarily delete one annotated property and confirm the gate still reports
`Missing property`.

## Open doubts (carried from the spike — settle #1 FIRST, it decides the design)

1. **Untested seam: does springdoc 2.8.9 pass the `$ref`+`types` sibling through to the assembled
   document?** The probe exercised raw swagger-core `ModelConverters`, not springdoc's pipeline. If
   springdoc normalizes or drops the sibling, B2's trigger vanishes and the builder must switch to a
   repo-local `@NullableRef` marker read by a `ModelConverter` (the
   `RecordRequiredModelConverter`/`ResponseRequiredCustomizer` pattern) — same total cost, one more
   file. Cheap to settle: annotate ONE component and capture that service's spec.
2. `ResponseRequiredCustomizer` ordering vs B2 — probably order-insensitive (`required` is computed
   from record component NAMES, not schema shape), but springdoc's ordering semantics for two
   adjacent-precedence `Ordered` beans were not verified.
3. openapi-diff's nullability blindness was measured on **2.1.7 / `:latest` as pulled today**. A
   future image that *does* diff nullability would turn the strip from "invisible" into "hides a real
   signal". Treat an openapi-diff image bump as requiring a re-measure — same class of rule as the
   Timescale-bump smoke test.
4. Only openapi-typescript consumes these specs today; Swagger-UI's rendering of the `anyOf` shape is
   unverified (cosmetic risk only).
5. The 3.0 copy will say "non-nullable `$ref`" where the 3.1 spec says nullable. That divergence is
   intended and argued, but it is a real "the two labelled copies disagree" property — it must be
   spelled out in the docstring.
6. The spike did **not** re-derive the construction-site verdicts for the parked components (it was
   told not to); its check was structural (they are `$ref`-typed and `required` in the committed
   spec), not a re-read of every `blocked()` shell. The builder should spot-check before annotating.
7. `FundamentalsController`: no *current* consumer was found. If the endpoint is ever surfaced in the
   FE, 404-vs-204 becomes a live UX choice rather than a free cleanup.
