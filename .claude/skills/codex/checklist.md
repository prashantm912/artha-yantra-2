# ArthaYantra Code-Review Checklist (single source of truth)

The `codex-code-review` and `codex-plan-review` prompts read THIS file for the review criteria,
severity classification, and approval gate. It encodes the project's load-bearing invariants —
the ones a generic reviewer misses. Full rationale lives in `CLAUDE.md`; this is the gate.

## Severity

- **Critical** — wrong results, data loss, money/rounding error, silent failure, a parity break,
  a secret leak. **Blocks `APPROVED`.**
- **Major** — a load-bearing invariant below is violated, a plan step is missing/wrong, an
  externally observable contract changed without its guard. **Blocks `APPROVED`.**
- **Minor** — real but non-blocking (naming, a narrow edge case real inputs won't hit). Case-by-case.
- **Suggestion** — optional improvement. Never blocks.

Cite `file:line` for every finding. Prefer a one-line fix over a paragraph. Do not flag: doc/spec
deltas the plan explicitly changes, environment limits the implementer can't resolve, type-aesthetics
beyond what the compiler requires, theoretical edge cases, or a prior finding already addressed/pushed-back.

## Parity firewall (Critical if violated)

- `GoldenSignalsJson.write()` is **FROZEN** — new `SignalEvent`/`Trade` fields ride as a
  NON-serialized side-channel, computed deterministically at entry (never per-run random), so
  `GoldenDeterminismTest` + `BacktestParityTest` stay byte-identical.
- The **live** `SignalEngine` must NEVER build a `SignalEvent` (only deterministic replay does).
- Premium-exit semantics are pinned by `contracts/fixtures/exit-equivalence.json` — a change must
  update the fixture AND both suites (backtest `PremiumExitEvaluator` + live bracket chain) in one PR.
- Never reintroduce an `avgEntryPrice` breakeven fallback on any close path.
- Any change under the engine/replay/premium path → the diff must keep Golden+Parity byte-identical.
  If it can't, that's a **Critical** — flag it, don't wave it through.

## Contract & endpoint discipline (Major)

- **Every new endpoint returns a typed record, never `Map<String,Object>`** (`MapReturnRatchetTest`
  freezes the Map-handler count per service; a new Map endpoint fails the strategy-gateway shard).
- A new `/api/v1/<x>` path needs the **edge-gateway `Path=` prefix allowlist** — a sibling prefix is
  not a match; without it the gateway serves SPA `index.html`. Only live-verify catches it.
- New query params + new `@*Mapping` paths DO drift the springdoc spec (`ContractCaptureTest`);
  adding keys to an existing `Map` return does NOT.
- **`@Schema(nullable = true)` is a SILENT NO-OP at OpenAPI 3.1** — a nullable response field must
  be spelled `@Schema(types = {"<type>", "null"})`. A reintroduced `nullable = true` is a Major.
- The **PR body must carry the anchored `Cross-vendor review:` verdict line** (`ci-review-verdict`
  fails without it): `APPROVED`/`REQUEST_CHANGES`/`NEEDS_REWORK` need `— <routed model> (<Vendor>)`
  with model↔vendor PAIRED (Opus/Sonnet/Fable↔Anthropic, gpt/Codex↔OpenAI); `REQUEST_CHANGES`/
  `NEEDS_REWORK` additionally need `(resolved)`; `SKIPPED` needs a non-empty parenthesised reason.
  A reviewer of a PR-bound diff should confirm the line exists and is honest.

## Modulith module boundaries (Major)

- strategy-signal: `notifier` imports `signals`, so **signals code must NEVER import notifier** —
  alert via an in-process event record + an `@EventListener` in notifier (`DotInputAlert` template).
- signals cannot import `paper` either (forced the `PremiumBracketRules` copy).
- A new `@Component` depending on `SignalEngine` must carry
  `@ConditionalOnProperty(artha.signals.engine-enabled, matchIfMissing=true)` or it kills the paper
  ITs (they boot engine-disabled).

## IST / time-key traps (Critical — root-caused real bugs)

- Cross-source time maps must be keyed by `.toInstant()`, **never** an offset-bearing `OffsetDateTime`
  (`+05:30` vs JDBC `+00` silently misses every lookup — root cause of #214).
- In-container `now()` / `::date` is **UTC**, not IST. Filter `generated_at`/`bucket` by explicit
  `+05:30` ISO bounds, never `::date = CURRENT_DATE` (off-by-one across IST midnight).

## Migrations & tests (Major)

- Applied Flyway migrations are **checksum-locked** — corrections go in a NEW suffix-versioned
  migration, never an in-place edit (even a comment).
- Integration tests must be named `*IntegrationTest` or `*Test` — `*IT` is silently skipped.
- A new service needs a CI matrix shard or its tests never run.

## Money / data fidelity (Critical)

- Money uses the project's rounding discipline; options pricing is Black-76. No float drift into
  persisted P&L or fill prices.
- Paper tick-freshness doctrine: entries reject a stale tick (>15s → 422 DATA_STALE); settles use
  the last REAL tick at any age. Don't refuse to leave a position forever.
- **Swing-exit ↔ paper-open serialization (V048/#1036, closed a live TOCTOU):** any writer touching
  the swing EXIT path or a signal-linked paper open must take the per-anchor advisory lock
  (`SignalRepository.lockAnchors`, `pg_advisory_xact_lock` namespace 4801) INSIDE its own
  transaction, ids ascending; `Math.toIntExact` must fail loud, never truncate into a colliding
  key. Discovery/decisions outside the transaction that commits them = Critical.
- **`SignalRepository.insert` takes `exitReason` as a REQUIRED last param, deliberately no
  overload** — the compiler enumerates every caller (that is how two discarded-reason sites were
  found). A convenience overload or defaulted-null reintroduction is a Major.
- **Safety gates FAIL CLOSED.** Any gate that blocks a dangerous action (market-hours block,
  freshness gate) must treat a parse/`catch`/non-finite path as BLOCKED, never as "allow"
  (`isMarketHoursIst` precedent: the old `toLocaleString` re-parse failed OPEN).
- **Operator-facing alert/page text must match actual machine behaviour under the current flags.**
  If an alert's remediation instruction changes when a flag arms (e.g. "run it by hand" vs "an
  automatic replay is queued — do NOT run by hand"), the message must branch on that flag
  (`SwingBatchCanary` precedent — the stale text prompted a duplicate money run).

## Security (Critical)

- No secret, owner password, PHC hash, Kite/Upstox token, or `.env` value in code, logs, tests, or
  fixtures. Codex/builders never edit `.env`, applied migrations, or CI secrets.

## Approval gate

- `APPROVED` — no Critical, no Major. Lint/typecheck/affected-tests summary (supplied by the
  requester) shows green, and any engine/money/parity change is asserted parity-safe.
- `REQUEST_CHANGES` — fixable Critical/Major findings.
- `NEEDS_REWORK` — structural: the approach violates a firewall/boundary and needs redesign.
