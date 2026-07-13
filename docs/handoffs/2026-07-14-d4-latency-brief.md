# Brief: d4-latency (P2-5 latency instrumentation)
Date: 2026-07-14 · Architect: Claude · Builder: Codex (unsandboxed worktree)
Ledger: D4 P2-5 (L3 latency instrumentation) · reserved migration **strategy V041** · Tier: **PARITY-SENSITIVE** (HOLD-tier — the Architect runs the parity ladder + presents to the owner before deploy)
Branch: `feat/d4-latency` (you are already ON it, in a worktree off origin/main)

## Goal
There is no decision-time wall clock (audit L3): `signals.generated_at` is the deterministic bar-bucket instant BY DESIGN, and signal-emit wall time is recorded nowhere, so tick→signal→fill latency is unrecoverable. Add, backend-only, **without touching any deterministic path**:
1. **Persist emit wall-time on signals** — a new nullable `emitted_at` (+ `emit_latency_ms`), stamped LIVE only.
2. **Persist tick→signal→fill latency on paper fills** — a new nullable `tick_to_fill_ms` on `paper_orders`, computed in the PAPER module.
3. **Prometheus histograms** — Micrometer timers for bar-publish→emit and signal→fill (p50/p95 visible on `/actuator/prometheus`).

## ⛔ THE PARITY FIREWALL — read this THREE times; it is the whole risk
The golden/parity vectors (`GoldenDeterminismTest`, `BacktestParityTest`) must stay **byte-identical**. The rules:
1. **NEVER touch `signals.generated_at`** — it is the bar-bucket instant (`SignalEngine.java:991,:1330`), deterministic and parity-load-bearing.
2. **NEVER add wall-clock to `strategy-engine`** — do NOT modify `libs/strategy-engine/**` at all. Specifically NEVER touch `golden/GoldenSignalsJson.java` (the `SignalEvent` record + the frozen `write()`), `golden/TickwiseGoldenRunner.java`, or `ReplayEngine`. The deterministic replay is verified wall-clock-FREE (zero `Instant.now`/`Clock` in TickwiseGoldenRunner) and must stay that way.
3. **The live `SignalEngine` NEVER constructs a `SignalEvent`** — `SignalEvent` is produced ONLY by the deterministic replay. So wall-clock lives ENTIRELY on the LIVE persistence path (new nullable DB columns + live stamps). The golden vectors are DB-blind → your columns are parity-inert by construction. Do NOT try to thread wall-clock through `SignalEvent`.
4. Wall-clock uses the **already-injected `clock`** (`SignalEngine.java:105,:190,:208`, `config/ClockConfig.java`) — never a raw `Instant.now()`.
5. **Module cycle rule (Spring Modulith `ModularityTest`)**: `signals` must NEVER import `paper`/`notifier` (one-way `paper → signals` only). Signal-emit latency stays in the `signals` module; **fill latency MUST be computed in the `paper` module** (it already depends on `signals` and reads `SignalRepository`). Computing fill latency in `signals` = a forbidden `signals→paper→signals` cycle = `ModularityTest` fails.

## The deliverable (all additive, all live-only)

### 1. Migration `deploy/flyway/strategy/V041__latency_stamps.sql`
Additive nullable columns (the one-line pattern of `V035__signal_fired_diagnostic.sql:14`):
```sql
ALTER TABLE signals      ADD COLUMN emitted_at      TIMESTAMPTZ;  -- live emit wall-clock (NULL on replay/legacy)
ALTER TABLE signals      ADD COLUMN emit_latency_ms BIGINT;       -- bar-publish -> emit wall latency (live only)
ALTER TABLE paper_orders ADD COLUMN tick_to_fill_ms BIGINT;       -- signal.generated_at -> fill wall latency (live only)
```
Header comment in the V035/V040 style. (strategy schema is owned by `artha` — match the no-GRANT idiom of the sibling ALTER migrations; verify against V035/V040.)

### 2. Signal-emit latency — `signals` module ONLY
- `SignalRepository`: add `void stampEmittedAt(UUID signalId, OffsetDateTime emittedAt, long latencyMs)` — a single `UPDATE signals SET emitted_at=?, emit_latency_ms=? WHERE id=?`, mirroring `stampFiredDiagnostic` (`SignalRepository.java:318`). Never touches `generated_at`.
- `SignalEngine.emitEntry` (post-insert, ~`:1073` where `emitted.increment()` fires) and `SignalEngine.emit` (exit, ~`:1345`): after the row is inserted, compute `now = OffsetDateTime.now(clock)` and `latencyMs = clock.millis() - lastBarReceivedAtMs` (the bar-publish stamp already at `:498`), then `signals.stampEmittedAt(id, now, latencyMs)`. Guard: if `lastBarReceivedAtMs` is 0/unset, write `emitted_at` but leave `emit_latency_ms` null (don't record a bogus latency). This is LIVE-path only — the deterministic replay never enters `emitEntry`/`emit`.
- Micrometer: register a `Timer ay_signal_bar_to_emit_seconds` (fields near `:172-174`, register near `:214` like `evalTimer`), and record `latencyMs` at each emit (`timer.record(latencyMs, MILLISECONDS)`). Enable percentile histograms: use `Timer.builder("ay_signal_bar_to_emit_seconds").publishPercentiles(0.5,0.95).register(meterRegistry)` so p50/p95 scrape.

### 3. Tick→signal→fill latency — `paper` module ONLY
- The fill site is `paper/PaperSignalListener` → `PaperService.openOrder` → `PaperOrderRepository` (`:92-133`, `filled_at` already `now()` at `:114`). Add the new column write there.
- Compute `tick_to_fill_ms = filled_at_millis - signal.generated_at_millis`. The listener has the `SignalTaken`/signal context; read the signal's `generated_at` via `SignalRepository` (paper already depends on signals) if not already in hand. Write `tick_to_fill_ms` into the `paper_orders` INSERT (or a follow-up UPDATE) — additive column, nullable.
  - NB: `generated_at` is the BAR BUCKET, not a true tick time (the audit's L3 accepts this — it's the best available decision-time anchor). Name/comment it honestly: "signal bar-close → fill wall latency".
- Micrometer: a `Timer ay_signal_to_fill_seconds` (publishPercentiles 0.5,0.95) registered in the paper module (template: `paper/PaperStaleTickAlerter.java:63-75`), recorded at fill.

### 4. NO new REST endpoint required
The histograms auto-expose on `/actuator/prometheus` (Micrometer). Do NOT add a controller (avoids the Map-ratchet + contract-drift surface entirely). If you believe a read endpoint is warranted, STOP and write a doubt instead — default is metrics-only.

## Constraints & traps (pasted)
- **Build the FULL reactor + `-am`** (`-pl services/strategy-signal-service -am` — the reactor pulls `libs/strategy-engine`). **Worktree `mvnw` can't re-download maven under AV TLS** — run the extracted binary directly: `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" /c/Users/prash/.m2/wrapper/dists/apache-maven-3.9.16-bin/*/apache-maven-3.9.16/bin/mvn ... -o`. **NEVER `mvnw | tail`** (tail's exit 0 masks failure; use `; echo EXIT=$?`).
- **PARITY TESTS MUST STAY BYTE-IDENTICAL** — run BOTH: `GoldenDeterminismTest` (libs/strategy-engine) + `BacktestParityTest` (services/backtest-service). If EITHER changes a byte, you broke the firewall — STOP and report (do NOT edit a golden vector to make it pass).
- **IT naming** `*IntegrationTest`/`*Test`; singleton Testcontainers, unique ids/names per method.
- **JaCoCo ≥ 60%**, `ModularityTest` MUST pass (the cycle rule above) — a full `-am verify` runs it.
- **No new endpoint** ⇒ no contract capture / TS regen needed (metrics-only). If you somehow add a `@*Mapping`, you must recapture — but the brief says don't.
- Add a small IT proving: a live emit stamps `emitted_at` + `emit_latency_ms` (use the test `Clock` override to make it deterministic), and a paper fill stamps `tick_to_fill_ms`. Assert `generated_at` is UNCHANGED (still the bar bucket).

## Mode & boundaries (UNSANDBOXED)
Run as the real user in THIS worktree. **HARD NEVER LIST:** deploy / docker / flyway-migrate / edit `.env`/secrets / `rm -rf` / `git reset --hard` / `git clean -fdx` / push to `main` / merge / force-push / edit an applied migration / edit the ledger or `docs/superpowers/plans/*` / **edit `libs/strategy-engine/**` (the golden/parity firewall) / edit any golden vector under `expected/`**. Touch ONLY `services/strategy-signal-service/**` + `deploy/flyway/strategy/V041__*.sql` + this brief's receipt. If a step needs anything on the NEVER list, STOP + doubt.
You MAY: direct-mvn, commit, push THIS branch, `gh pr create` (leave OPEN). The Architect runs the parity ladder + audits + presents to the owner + merges + deploys.

## Verify ladder (run ALL, paste real outputs into the receipt)
1. `... -pl services/strategy-signal-service -am -q -DskipTests package -o` — compiles.
2. `... -pl libs/strategy-engine -am test -Dtest=GoldenDeterminismTest -o` — **byte-identical (paste the pass line)**.
3. `... -pl services/backtest-service -am test -Dtest=BacktestParityTest -o` — **byte-identical**.
4. `... -pl services/strategy-signal-service -am verify -o` — ITs green (incl. your new latency IT) + `ModularityTest` + JaCoCo. Paste `Tests run:`.
5. `gh pr create --base main --head feat/d4-latency --title "feat(strategy-signal): latency instrumentation (P2-5)" --body "<what/why + the parity firewall statement + which module owns each stamp + test evidence incl. Golden/Parity byte-identical + receipt path>"` — leave OPEN.

## Receipt (write to `docs/handoffs/2026-07-14-d4-latency-receipt.md`)
- Diff summary (files + line counts) + PR URL. **Confirm `libs/strategy-engine/**` is UNTOUCHED (0 files).**
- Real outputs: package / **GoldenDeterminismTest pass** / **BacktestParityTest pass** / verify (`Tests run:` + `ModularityTest`).
- Claims WITH evidence (file:line), labeled computed|sourced|recalled|assumed.
- **Open-doubts (mandatory):** (a) confirm `generated_at` untouched + how the latency IT asserts it; (b) the `signals` vs `paper` module split you used for the two latencies + that no `signals→paper` import was added; (c) the `lastBarReceivedAtMs` unset guard; (d) whether you added histograms only or also considered a read endpoint (should be metrics-only); (e) any parity doubt at all — even 1%.
- End commits with `Co-Authored-By: OpenAI Codex <noreply@openai.com>`.

## Stop conditions
- EITHER GoldenDeterminismTest OR BacktestParityTest changes a byte → STOP immediately, report (do NOT touch a golden vector).
- `ModularityTest` fails (a module cycle) → STOP, report which import caused it.
- The fill-latency needs the signal's `generated_at` but the paper listener can't cleanly read it → land the signal-emit half first, scope fill-latency OUT + doubt.
- Anything on the NEVER list (esp. touching `libs/strategy-engine`) would be required.
