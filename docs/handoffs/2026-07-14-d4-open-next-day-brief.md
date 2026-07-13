# Brief: d4-open-next-day (#15 — swing open-next-day fill variant)
Date: 2026-07-14 · Architect: Claude · Builder: Codex (unsandboxed worktree)
Ledger: D4 #15 (audit B8) · Tier: clean-but-CAREFUL (additive, default-preserving; deep-sims are OUTSIDE the golden/parity firewall) · NO migration
Branch: `feat/d4-open-next-day` (you are already ON it, in a worktree off origin/main)

## Goal
The swing deep-sims fill entries+exits at the SAME signal bar's close — consistent but optimistic (audit B8): "must be priced (open-next-day variant) before real-money graduation." Add an **OPT-IN `next_open` fill variant** to the deep-sims so the fill sensitivity can be priced, **without changing any existing variant's results or the live batch**. When done: a new variant fills at the NEXT day's open; every current variant is byte-identical; the live `SwingBatchEngine` is untouched.

## ⚠️ The invariant (the whole risk)
This changes real-money-graduation-critical swing-sim numbers IF done wrong. The rule: **the new fill timing is a per-Variant flag defaulting to `at_close` (today's behaviour). ONLY the new opt-in grid entry uses `next_open`. Every existing variant's output must be byte-identical.** The deep-sims are OUTSIDE the golden/parity firewall (they live only in `market-data-service`; `BacktestParityTest`/`GoldenDeterminismTest` never touch them — do NOT worry about golden vectors, DO worry about not perturbing existing variant results).

## The deliverable (all in `market-data-service`, additive)

### 1. `Variant` record + a shared fill helper
- `ManasAroraSwingBacktest.java` — `Variant` record (~:73-74) `(name, useRealRs, rsMin, turnoverFloor, pyramiding)`: add a `String fillTiming` field. `MinerviniSwingBacktest.java` — its `Variant` (~:380-385) similarly. **Default `"at_close"`** everywhere it's constructed except the one new opt-in entry.
- Add a shared static helper (put it on one engine or a small util; the two engines are structurally identical) — `double fillPrice(List<DailyBar> bars, int i, String fillTiming)`:
  - `at_close` → `bars.get(i).close()` (today's behaviour, byte-identical).
  - `next_open` → if `i + 1 < bars.size()` return `bars.get(i+1).open()`, else signal "cannot fill" (drop this trade — see the bounds guard).
- `DailyBar` exposes `open()` (`geometry/DailyBar.java:10-11`); the full `List<DailyBar> bars` is in scope at every fill site (Manas `simulateSetup` :248, Minervini `simulate` :159).

### 2. Apply at the 6 fill sites (replace the bare `close[i]` fill reads)
- **Manas (4):** entry `:265-269` (first-lot fill), pyramid add `:319-327`, square-off exit `:291-295`, per-lot stop exit `:302-306`.
- **Minervini (2):** entry `:173-177`, exit `:180-182`.
- **What moves vs what stays:** ONLY the FILL PRICE moves to the helper. The signal DECISION stays on bar `i` (entryFires/exit fires unchanged). **The stop LEVEL stays computed from `close[i]`** (the signal bar) — the stop is a level, not a fill; only the entry/exit executes at next-open. Comment this decision at the site.
- **Bounds guard (`next_open`, last bar):** if a fill can't happen because `i+1 >= n` (signal on the final bar), DROP that trade — consistent with the existing open-at-end drop (Manas `:331`, Minervini `:192`). For `at_close` there is no guard change (byte-identical).

### 3. One new opt-in grid entry per engine
- `jobVariants()` (Manas `:376-384`, Minervini `:380-385`): add ONE new `Variant` with `fillTiming="next_open"` (e.g. clone the current best/representative variant and suffix its name `"-nextopen"`, e.g. `"rs-turnover-nopyramid-nextopen"`). **Do NOT modify the existing grid entries** (they keep the default `at_close` → byte-identical).

### 4. Make the frictionless-fills caveat variant-aware
- `DeepSwingService.java:226-227` hardcodes "Frictionless per-trade fills (entry+exit at the signal bar's own close)... (audit B2)". Make it variant-aware (additive) so a `next_open` variant isn't mislabelled — e.g. branch the caveat text on the variant's fill timing, or append the fill timing to the note.

### 5. Results distinguishability (should be automatic — verify)
Results key by variant already: `manas_arora_backtest_runs` `Report.variant` (`ManasAroraBacktestService.java:919-927`), and the job path embeds the variant in `strategy_version_tag=FAMILY:variant` / `source_tag` / `metrics.variant` (`DeepSwingService.java:171,188,210`). So the new variant is auto-distinguishable — NO new param-echo/migration. Confirm the new grid entry flows through.

## Constraints & traps (pasted)
- **Direct-mvn** (worktree `mvnw` can't download maven under AV TLS): `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" /c/Users/prash/.m2/wrapper/dists/apache-maven-3.9.16-bin/*/apache-maven-3.9.16/bin/mvn -pl services/market-data-service -am ... -o`. NEVER `mvnw | tail` (masks failure; use `; echo EXIT=$?`).
- **DEFAULT-PRESERVING is the bar:** the existing deep-sim tests (find `ManasAroraSwingBacktest`/`MinerviniSwingBacktest` tests) MUST stay green unchanged — if an existing test's numbers move, you changed default behaviour (a bug) → STOP. Do NOT edit an existing test's expected numbers to make it pass.
- **IT/test naming** `*IntegrationTest`/`*Test`; unique fixtures.
- **JaCoCo ≥ 60%**, ModularityTest in `-am verify`.
- **NO migration, NO new endpoint** (the variant rides the existing `DeepSwingRunRequest.variant` discriminator). If you think one's needed, STOP + doubt.
- The deep-sims are `market-data-service` only — do NOT touch `strategy-signal-service`'s `SwingBatchEngine` (the LIVE batch stays same-bar per B8), and do NOT touch `libs/strategy-engine` or any golden vector.

## Mode & boundaries (UNSANDBOXED)
Run as the real user in THIS worktree. **HARD NEVER LIST:** deploy / docker / flyway-migrate / edit `.env`/secrets / `rm -rf` / `git reset --hard` / `git clean -fdx` / push to `main` / merge / force-push / edit an applied migration / edit the ledger or `docs/superpowers/plans/*` / edit `SwingBatchEngine` / edit `libs/strategy-engine`. Touch ONLY `services/market-data-service/**` + this brief's receipt. STOP + doubt if anything on the NEVER list is needed.
You MAY: direct-mvn, commit, push THIS branch, `gh pr create` (leave OPEN).

## Verify ladder (run ALL; paste real outputs)
1. `... -pl services/market-data-service -am -q -DskipTests package -o` — compiles.
2. `... -pl services/market-data-service -am verify -o` — ALL green, incl. the EXISTING deep-sim tests UNCHANGED (proves default-preserving) + your new next_open test + ModularityTest + JaCoCo. Paste `Tests run:`.
3. `gh pr create --base main --head feat/d4-open-next-day --title "feat(market-data): swing open-next-day fill variant (#15)" --body "<what/why + additive opt-in + default-preserving + the 6 fill sites + test evidence + receipt path>"` — leave OPEN.

## Receipt (write to `docs/handoffs/2026-07-14-d4-open-next-day-receipt.md`)
- Diff summary (files + line counts) + PR URL.
- Real outputs of package / verify (`Tests run:`), + an explicit statement that the EXISTING deep-sim tests passed UNCHANGED (default-preserving proof).
- Claims WITH evidence (file:line), labeled computed|sourced|recalled|assumed.
- **Open-doubts (mandatory):** (a) the new test — how it asserts next_open fills at `bars[i+1].open()` vs at_close at `bars[i].close()`; (b) confirm existing variants byte-unchanged (no existing expected-number edits); (c) the stop-level-stays-close[i] decision; (d) the last-bar bounds-guard drop; (e) that `SwingBatchEngine`/`libs/strategy-engine` are untouched.
- End commits with `Co-Authored-By: OpenAI Codex <noreply@openai.com>`.

## Stop conditions
- An EXISTING deep-sim test's numbers move → STOP (you changed default behaviour); report which.
- The fill logic turns out to be spread beyond the 6 cited sites → STOP + report the extra sites (don't guess).
- Anything on the NEVER list (esp. touching SwingBatchEngine / strategy-engine) would be required.
