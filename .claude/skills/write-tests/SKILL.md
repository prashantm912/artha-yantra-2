---
name: write-tests
description: Author tests correctly in our harness — *IntegrationTest naming, singleton Testcontainers, golden/parity byte-identical, the seam ladder, and a coverage-debt ledger instead of hidden gaps
---

# Write Tests

How to author tests that actually run and actually protect behavior in this repo. Use it whenever a
change adds new logic (the builder testing gate hands off here), or when backfilling coverage.

## Non-negotiables (get these wrong → silent skips or a broken gate)

- **Naming:** a test class MUST be `*IntegrationTest` or `*Test`. There is **no failsafe plugin** — a
  `*IT` class is silently skipped and never runs. This is the #1 way a "passing" suite hides dead tests.
- **Build with the full reactor + `-am`:** `mvnw -pl services/<svc> -am verify`. JaCoCo ≥ 60% line
  binds **per module**; a bare `-pl` skips parent POMs + nested libs. New service → add a CI matrix
  shard (`ci-java.yml`) or its tests never run in CI.
- **Parity is a test contract.** Any engine/replay/premium change → `GoldenDeterminismTest` +
  `BacktestParityTest` must stay **byte-identical**; premium-exit semantics → the
  `contracts/fixtures/exit-equivalence.json` fixture + BOTH the backtest and live-bracket suites in one
  PR. New `SignalEvent`/`Trade` fields ride a non-serialized side-channel computed deterministically at
  entry. If parity can't stay byte-identical, that's a design problem, not a test to loosen.

## IT harness facts

- Singleton Testcontainers (Timescale 2.17.2-pg17 + redis 7.4), real Flyway lineages,
  `@DynamicPropertySource` for `currentSchema`. Services connect as `artha` (single-writer);
  per-schema roles (`ay_backtest`…) are read-only — assert that with SET ROLE grant tests.
- **ITs share the singleton DB with NO per-method cleanup.** State persists across methods AND across
  surefire reruns → every method needs a **unique slug + name** (`RegistryService.create` 409s on a
  duplicate slug OR name). Method name alone is NOT enough — the same method recurs on a rerun and
  409s. Derive the id from the method name **plus a per-invocation nonce** (a `UUID.randomUUID()`
  fragment or `System.nanoTime()`), never a hardcoded literal.
- Mock-stack candle data is rolling/real-time — derive a recent covered window, never hardcode dates;
  the market calendar covers a fixed bundled year set.

## The seam ladder (pick the lowest rung that tests observable behavior)

1. **Pure function / helper** — no I/O; a plain unit test. Prefer this; refactor logic to a pure
   function to reach it.
2. **Constructor-injected collaborator** — inject a fake/stub; assert the behavior, not the calls.
3. **Module-level mock** — mock the port/gateway (e.g. a Kite/Upstox wire client) at the module edge.
4. **Full IT** — Testcontainers, real Flyway; reserve for cross-module/DB/parity behavior.

**Test observable behavior** (inputs → outputs / persisted effects / emitted events), never internal
wiring. In-container `now()`/`::date` is UTC — filter time by explicit `+05:30` bounds in test SQL too.

## Coverage-debt, not hidden gaps

- **Mock-pain tripwire:** if the mock/setup grows longer than the assertions, stop fighting it — find a
  higher seam, or add one row to `docs/testing/COVERAGE-DEBT.md` (`path | why hard | escape plan`) and
  move on. (Create the ledger on first use.)
- **Critical-path floor:** behavior touching money/rounding, deletion, persistence, parity, or an
  external request shape MUST keep ≥1 behavioral test or a manual integration check — coverage debt may
  defer internal-path depth, never safety-critical behavior.
- **Never hide untested code:** no coverage-ignore comments, no config exclusions, no lowering the
  JaCoCo gate. A pre-existing gap you didn't create → note it, don't silently inherit it.

## Handoff

Report the gate line the reviewer expects: `lint: clean | typecheck: clean | tests: N passed (M new)`.
Then `claude-review` — or `codex-code-review` on a rationed slot if the change is money/parity/
migration/live-engine, plus `adversarial-review` — → Architect audit → merge.

⚠️ **A test written by a LOCAL model does not count until it is red-proofed** — one emitted a 4/4-GREEN
suite that detected neither of two planted bugs. See the `local-model` skill.
