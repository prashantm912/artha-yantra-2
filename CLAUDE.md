# CLAUDE.md

Single-owner algorithmic-trading research platform (multi-module Maven + a Python
optimizer, Dockerized, loopback-only gateway). Before non-trivial work read
`README.md`, `PHASE_GATES.md` (current phase + parking list), and the frozen design
set under `docs/design/` (COMMON_REFERENCE + stage files A–G — the design authority).

## Working principles
Behavioral guardrails (adapted from [Karpathy's coding guidelines](https://github.com/multica-ai/andrej-karpathy-skills))
to cut the common LLM coding mistakes. They bias toward caution over speed — use judgment on trivial tasks.

1. **Think before coding.** State assumptions explicitly; if uncertain, ask. If multiple
   interpretations exist, surface them — don't pick one silently. If a simpler approach exists,
   say so and push back when warranted. If something is unclear, stop and name what's confusing.
2. **Simplicity first.** The minimum code that solves the problem, nothing speculative — no
   unrequested features, no abstractions for single-use code, no error handling for impossible
   cases. If 200 lines could be 50, rewrite it. Ask: "would a senior engineer call this
   overcomplicated?"
3. **Surgical changes.** Touch only what the request needs — every changed line should trace
   to it. Don't refactor working code or "improve" adjacent formatting; match existing style
   even if you'd do it differently. Remove orphans *your* change created, but leave pre-existing
   dead code (mention it, don't delete it).
4. **Goal-driven execution.** Turn tasks into verifiable goals ("fix the bug" → "write a test
   that reproduces it, then make it pass") and loop until they pass. For multi-step work, state
   a brief plan with a verify check per step.

## Delegation model (owner standing rule, REVISED 2026-07-25 — supersedes the 2026-07-10 rule)
Applies to the MAIN session loop only — if you were spawned as a subagent, ignore this section
and just execute your brief. **Opus 5 is the main loop: orchestrator + final gate.** It never
builds substantive code; it orients, classifies, briefs, runs the testing gate, audits, merges,
deploys, verifies live, and talks to the owner. Four stages, each with its own model
(full table + fallbacks: `.claude/skills/codex/ROUTING.md`).

⚠️ **CODEX IS RATIONED (owner, 2026-08-15 — $20/mo tier).** It is no longer the default reviewer;
it is a scarce slot spent ONLY on **money · parity · exit doctrine · migrations · live engine**, and
always **PRE-merge** (a slot on merged code buys an audit, not a gate). Everything else gets
`claude-review` — Opus subagent, FRESH thread, DISTINCT lens — which is **weaker than what it
replaced**, so buy back what diversity you can and **write the loss into the verdict line** rather
than letting "reviewed" imply what it used to. Never spend a slot on builds, docs, an advisory ask,
or anything already merged. ⚠️ The budget is an ASSUMPTION until measured — record the first
post-2026-08-20 run's cost. Local models do NOT restore cross-vendor review (seven scored 0/2); see
`.claude/skills/local-model/` for what they ARE measured to do.

| Stage | Model | Note |
|---|---|---|
| **Plan** | **Fable 5** (Agent tool, `model: "fable"`) → Opus on capacity error | ONLY for real items: HOLD tier, migrations, money/parity surfaces, or >~3 files / multi-PR. Small chips skip straight to a brief. Non-trivial plans get `codex-plan-review` if a slot is free (cheapest leverage), else an Opus fresh-thread round. |
| **Build** | **Opus subagent** (`delegated-ship`) for parity / money / exit doctrine / migrations / the live engine → **Sonnet 5** for MECHANICAL work only | Never degrade a money or parity path to Sonnet to save tokens — that is exactly where green suites have hidden defects. |
| **Review** | money/parity/migration/live-engine → **`codex-code-review`** (rationed slot, PRE-merge) · everything else → **`claude-review`** (Opus, FRESH thread, DISTINCT lens) | ⚠️ Same-vendor for the majority. A DISTINCT gate from the audit — see below. |
| **Audit + ship** | **Opus 5** (main loop) | Final gate, then PR → CI → merge → deploy → live-verify → ledger. |

⚠️ **The review round and the Architect audit are two gates, not one — never collapse them.**
"The orchestrator reviews it itself" must mean *audit on top of a review round*, never *instead of*
one. Evidence (2026-07-25): the T21 cross-vendor review found a LIVE Critical no test could reach
(`premium_pct` exits resolved against the INDEX entry price = a one-bar force-exit on every held-PE
take), and a later review round caught a foreign hunk the Architect had already read past in audit.

Rules proven over the first delegated runs (#675–#680), model-independent:
- ⚠️ **EVERY brief opens with a STEP 0: "verify this brief's premise against the code before writing
  anything; reporting the premise is wrong is a SUCCESSFUL outcome."** On 2026-08-02 five builders were
  dispatched from one enumeration pass and **all five items were already shipped**; every one stopped itself
  at STEP 0, so the cost was minutes rather than five junk PRs. Two further briefs that day had premises that
  were simply wrong — a global `BigDecimal`→string converter (position-blind, would have retyped 28 REQUEST
  surfaces where `number` is TRUE) and a set of divergences that did not exist. **The builder falsifying the
  brief is the single highest-yield thing in this pipeline; write briefs so that is rewarded, not resisted.**
- ⚠️ **A `recalled` fact about LIVE state has a shelf life of days — re-query before it is load-bearing.**
  Same day, the Architect asserted `paper_positions id=28` was an open SELL three times from a stale memory
  note; it had been CLOSED since 2026-07-17, and the error reached a cross-vendor reviewer's findings before
  anyone caught it. Two audit docs also went stale DURING their own writing (a deploy and a data re-fetch
  landed mid-audit). Corollary for closeouts: **re-measure at write time, not at investigation time.**
- ⚠️ **A builder that reports "build still running, I'll report when it lands" is STOPPED, not waiting.** The
  task notification fires only when no background child is live, so its watcher died with it. Treat the
  message as a stall: send it back to re-read its own log, and treat a frozen log with no `BUILD` line as
  truncated rather than passed. Tell builders to run long verifies in the FOREGROUND and read Maven's own
  exit code — backgrounding buys nothing and adds a way to die silently.
- The brief must be self-contained: goal, constraints, **relevant memory-trap content pasted in**
  (subagents get this file but never the memory files), and a required receipt shape — diff, test
  output, claims WITH evidence (file:line / SQL+result / log line) **each labeled
  computed / sourced / recalled / assumed** (recalled + load-bearing belongs in open-doubts,
  not the claims list), and a mandatory **open-doubts**
  section (builders' self-flagged doubts have caught real regressions).
- Audit the RECEIPT against the real artifact (read the actual diff, spot-rerun tests, verify
  citations); depth tiered by risk — docs/mechanical = diff read, engine/money/parity =
  verify-ladder rerun. Small audit fixes land directly; big ones go back to the same agent via
  SendMessage.
- The main loop keeps: merge decisions, live deploys + anything touching secrets/.env, ledger and
  memory writes, owner communication, and tiny fixes where delegation overhead exceeds the work.
  **Parity/money changes gate on a byte-identical Golden+Parity rerun by the main loop itself** —
  never on any builder's or reviewer's say-so, whichever model it was.
- **Availability is detected at FAILURE time, not by preflight probes** — call the primary, fall
  back when it errors (ROUTING.md's ladder). Don't burn a turn checking whether a model is up.
Detailed playbook + outcome log: memory topic `opus-delegation-standard`.

## Build & test
- **Integration tests must be named `*IntegrationTest` or `*Test`** — there is **no
  failsafe** plugin configured; `*IT` classes are silently skipped (never run).
- **Build services with the full reactor + `-am`**
  (`-pl services/<svc> -am package -DskipTests`), never a bare `-pl` on a leaf lib —
  a `-pl` install skips parent POMs and nested lib submodules
  (`libs/common-web/servlet`, `libs/black76-math`), so the compose fat JAR silently
  embeds a stale lib. ⚠️ **SIBLING CASE, and it does NOT look like staleness (2026-08-03):
  with a CONCURRENT SESSION building, a bare `-pl` resolves the lib from the SHARED `~/.m2`,
  where the other session may have installed a DIFFERENT `common-web-core` — so your build
  fails with a PHANTOM COMPILE ERROR naming a class you never touched** (measured:
  `SchemaNameCollisionDetector not found`, on a red-proof run that had nothing to do with it).
  The rule is unchanged — always `-am` — but the symptom is a compile error in *your* code
  rather than a silently-wrong jar, so it reads as your bug and is easy to chase for an hour.
  **Tell: the missing symbol is in a lib you did not edit.** Re-run with `-am` before debugging.
  In **PowerShell**, a `-D` property containing dots must be QUOTED
  (`'-Dcontracts.capture=true'`) — unquoted, PS hands Maven a split token and it dies with
  `Unknown lifecycle phase ".capture=true"`. (An earlier example here used
  `-Dspotless.check.skip=true`, which is INERT — no spotless plugin exists in this repo; it rode
  along in ~8 builder briefs on 2026-07-31 before two builders independently caught it. Checkstyle
  is the formatting gate.)

  ```bash
  # ALWAYS -am. A bare -pl embeds a stale lib and fails as a PHANTOM error in code you never touched.
  ./mvnw.cmd -pl services/<svc> -am package -DskipTests > build.log 2>&1; echo "MAVEN_EXIT=$?"
  grep -E "BUILD (SUCCESS|FAILURE)" build.log   # no BUILD line at all = truncated, NOT passed
  ```
- ⚠️ **Two ways a Maven run reports GREEN without having run** (both hit builders on 2026-08-01):
  **(1) `mvnw … | Out-File` (or any pipe) reports the PIPELINE's exit code, not Maven's** — a run
  truncated mid-`testCompile`, with no `BUILD` line at all, was reported as "completed, exit 0" and
  reads exactly like a passing gate. Redirect instead and read Maven's own code:
  `./mvnw.cmd … > run.log 2>&1; echo "MAVEN_EXIT=$?"`, then confirm the log actually contains a
  `BUILD SUCCESS`/`BUILD FAILURE` line (a frozen mtime with no BUILD line = truncated, not passed).
  **(2) `mvnw surefire:test` as a bare GOAL does NOT recompile** — it runs whatever is already in
  `target/classes`, so a red-proof done that way exercises stale bytecode: you break the production
  code on purpose, see BUILD SUCCESS, and wrongly conclude your test cannot detect the break. Always
  red-proof through a lifecycle PHASE (`test` / `verify`). Both fail in the safe-looking direction,
  which is what makes them worse than a crash.
  **(3) A STALE `.class` NEWER than its `.java` makes Maven report a failure that contradicts the
  source you just read** (2026-08-03). Measured: a ratchet test failed with an expected-set missing an
  entry demonstrably present in the source; the `.class` mtime was newer than the `.java`; re-running
  that test ALONE passed 20/20, and a fresh full `verify` was clean. **Tell: a failure whose message
  disagrees with the file you are looking at.** Remedy: re-run the single test, then a clean full
  `verify`; do not "fix" the source to match a phantom. It surfaced as a false RED, but the same race
  produces a false GREEN — which is why it belongs next to the two above.
- ⚠️ **A red-proof can be BROKEN by being TOO STRONG, not only by staying green** (2026-08-03, caught
  by a builder on itself). Red-proofing a rule by reverting to a *stricter* wrong rule reddened only 1
  of the 2 tests that the *actually shipped* wrong rule reddens — under-reporting the blast radius and
  the tests' real coverage. **Red-proof by restoring the LITERAL pre-fix body**, not by writing a rule
  you think is equivalent. The weaker-looking result is the informative one.
- ⚠️ **A red-proof can also go RED for a MECHANICAL reason and prove nothing** (2026-08-03, two in one
  PR, both caught by the builder re-checking its own proofs). Neither stayed green — both "failed
  convincingly": one died on a **duplicate-class compile error** (the restore glob copied a `signals`
  file into `paper/`), the other on `The column index is out of range: 5, number of columns: 4`
  because reverting only the SQL predicate left 6 args bound to 4 placeholders. A red-proof proves
  detection **only if the failure message names YOUR assertion**. Two checks: assert
  `compile-errors: 0` on every proof, and read the actual failure text — a JDBC/compile/fixture error
  is a broken proof wearing a passing gate's clothes. Completes the set: a proof can be broken by
  staying green, by being too strong, or by reddening for the wrong reason.
- IT harness: singleton Testcontainers (Timescale 2.18.2-pg17 + redis 7.4), real
  Flyway lineages, `@DynamicPropertySource` for `currentSchema`. Services connect to
  Postgres as `artha` (D10 single-writer by convention); per-schema roles like
  `ay_backtest` are read-only, asserted via SET ROLE grant tests.
- **ITs share the singleton DB with NO per-method cleanup** — each test method needs a
  unique slug+name; `RegistryService.create` 409s on a duplicate slug OR name. State
  persists across methods *and* across surefire reruns.
- JaCoCo gate ≥ 60% line on services; Modulith `verify` runs in CI.
- **Extend engine records parity-safely:** golden vectors compare byte-string (signals)
  + record-equality (trades); `GoldenSignalsJson.write()` is FROZEN, so new
  `SignalEvent`/`Trade` fields ride as a NON-serialized side-channel — golden stays
  byte-identical and parity holds *iff* both deterministic replays compute the same value
  (compute at entry, e.g. `ExitEvaluator.entryLevels`, never per-run random). Verify with
  GoldenDeterminismTest + BacktestParityTest. **Premium-exit semantics are pinned by a shared
  fixture** (`contracts/fixtures/exit-equivalence.json`, #505): backtest's PremiumExitEvaluator
  and the live bracket chain (PremiumBracketRules + PaperBracketEvaluator) both test against it —
  change exit semantics only by updating the fixture + BOTH suites in one PR. **Paper tick-freshness
  doctrine (#694):** fills reject a stale live tick (>15s, `artha.paper.tick-max-age-seconds`) with
  422 DATA_STALE; settles use the last REAL tick at ANY age (stale = counted+alerted, never refused)
  and refuse only when NO tick was ever seen — "entries need fresh truth (you can always NOT enter),
  exits need the best available truth (you cannot refuse to leave forever)". Never reintroduce an
  `avgEntryPrice` breakeven fallback on a close path.
- **Modulith module cycles (strategy-signal):** `notifier` imports `signals` (`SignalEmitted`), so
  signals code must NEVER import notifier — alert via an in-process event record published from
  signals + an `@EventListener` in notifier (`DotInputAlert`/`DotAlertListener` is the template).
  Same class of rule: signals cannot import paper (forced the `PremiumBracketRules` copy).
  `ModularityTest` (per service) catches violations locally only with a full `-am verify`.
- **Mock-stack backtest testing:** candle data is real-time/rolling (accrues from
  boot) — derive a recent covered window, never hardcode dates; every windowed run's
  regime pre-flight needs ~272 daily benchmark sessions, so backfill `NIFTY 50` 1d
  via cache-first GET `/api/v1/market/candles` first. Results/trades/folds/montecarlo
  are keyed by the **run id** (the job's `resultRef`), not the jobId. Submission + the
  worker now **auto-warm** the primary 1m + benchmark (+ contexts) via market-data's
  cache-first GET before the pre-flight, so a fresh window no longer 422s — but
  `libs/market-calendar` covers a FIXED bundled set (currently **2024–2026**; NSE Tuesday +
  BSE Thursday weekly expiries via `MarketCalendar.nse()`/`.bse()`), so a window outside it
  (e.g. 2023 or 2027) 500s with "NSE holiday calendar covers years [...]". A horizon-canary
  test goes red ~45 days before the max covered year ends — the CD-2 yearly-CSV-refresh reminder.
- **Run the Playwright e2e vs a running mock stack:** `cd e2e &&
  E2E_OWNER_PASSWORD=<your .env owner pw> npx playwright test` — global-setup reuses a
  healthy stack and won't overwrite an existing `.env`; the helper password defaults to
  `e2e-owner-password`, so override it to match your hash.

  ```bash
  cd e2e && E2E_OWNER_PASSWORD=<your .env owner pw> npx playwright test
  ```
- **Drive the gateway API from PowerShell** (live/mock verification): `Invoke-WebRequest
  -UseBasicParsing` (PS5.1's IE engine prompts otherwise); POST `/api/v1/auth/login`
  `{"password":...}` (it answers **204**, not 200 — check `-notin 200,204`), then a GET to seed the
  `XSRF-TOKEN` cookie, echoed as the `X-XSRF-TOKEN` header on mutating calls. **A bodyless
  `-Method Post` defaults to `application/x-www-form-urlencoded` and the endpoint answers 415**
  (`HttpMediaTypeNotSupportedException` mapped in #1021; edge-gateway-local endpoints unaffected —
  gateway uses common-web-core, not the servlet handler): always pass
  `-ContentType 'application/json' -Body '{}'`. In-container SQL: DB is `artha`/`artha_mock`
  (not `arthayantra`).
- **optimizer-service is Python (FastAPI), not Java** — `/api/v1/optimizations/*` lives there
  (backtest-service owns `/api/v1/backtests/*`). Tests: `(cd services/optimizer-service && python -m
  pytest tests/ -q)` + `python -m ruff check app tests` (Python 3.14 global, no venv). A sweep needs
  the strategy to carry a `backtest.optimize` block (`method`+`max_trials`+`objective`+`parameters`,
  all required) else 422 "no tunable parameters in the optimize block". **ci-optimizer / ci-margin are
  separate workflows, but their `optimizer-lint-test` / `margin-lint-test` jobs ARE required contexts
  and DO appear in the default `gh pr checks` rollup** (corrected 2026-08-04 — this bullet asserted the
  exact opposite, and the stale form sent three separate check-hunts off to `gh run list` for a gate
  that was in the rollup all along). It was true until 2026-08-03: both workflows were then
  `paths:`-filtered and genuinely absent. #1252 removed that filter precisely so the jobs could be
  promoted, because a `paths:`-filtered workflow can NEVER be a required check — a path-skipped
  workflow's checks stay PENDING forever, while a job or step skipped by an `if:` reports SUCCESS.
  Both now ALWAYS trigger; a cheap `changes` classifier decides whether real work runs, and the
  required job carries `if: always()` + a fail-closed first step (measured: `optimizer-lint-test` 33s
  on #1291, which touched Python, vs 3s on #1290, which did not — both PASS, both in the rollup).
  **Read the rollup, not `gh run list`.**
  ⚠️ **The "Python 3.14 global, no venv" line above NO LONGER HOLDS for the CONTRACT tests** (#1209,
  2026-08-02): `test_openapi_contract.py` in BOTH optimizer- and margin-service now asserts the running
  interpreter matches `requirements-dev.lock`, so a plain `python -m pytest` on the ambient interpreter
  **FAILS BY DESIGN** — measured: ambient fastapi 0.136.3 vs the lockfile's 0.115.6, 2 failed / 1 passed.
  That is the guard working, not breakage: FastAPI's own generated `ValidationError` schema gains
  `ctx`/`input` across versions, and the committed margin spec had in fact been captured under the
  WRONG version before this landed. The failure prints the exact remedy — build a venv from the
  lockfile (`uv venv --python 3.12 .venv-pinned`, `uv pip install --python .venv-pinned
  --require-hashes -r requirements-dev.lock`) and run capture/tests through it, never the ambient
  interpreter. Everything ELSE in those services (ruff, the non-contract tests) still runs fine on the
  global interpreter, so only the contract tests need the venv.


  ```bash
  # Ambient interpreter is FINE for ruff + non-contract tests:
  (cd services/optimizer-service && python -m pytest tests/ -q && python -m ruff check app tests)
  # CONTRACT tests need the pinned venv -- on the ambient interpreter they FAIL BY DESIGN:
  uv venv --python 3.12 .venv-pinned
  uv pip install --python .venv-pinned --require-hashes -r requirements-dev.lock
  ```
## CI, contracts & gates
- **CI `build-test` is sharded per-service** (`.github/workflows/ci-java.yml`): a 3-leg
  matrix (`market-data` / `backtest` / `strategy-gateway` = strategy-signal + edge-gateway),
  each runs `mvnw -pl <svc> -am verify` on its own runner (Testcontainers ITs are the
  2-core bottleneck; serial reactor was ~23m, sharded ~5m). Safe because `jacoco-check`
  binds PER MODULE, not an aggregate root goal. **Adding a new service?** Add a matrix
  shard or its tests NEVER run in CI, **and add it to `KNOWN_SERVICES` +
  the path mapping in `.github/scripts/classify_java_shards.sh`** (since #1252, 2026-08-03, each leg
  gates on its OWN classifier boolean — a `services/<new>/` path no shard claims is reported as
  `unowned_services` and HARD-FAILS the run, deliberately, because fanning out to three existing
  shards only looks busy and builds nothing new). Libs ride upstream via `-am` (covered in ≥1 shard),
  and a `libs/` edit sets all three booleans so shared-lib breaks still fan out.
- **Contract spec drift (springdoc):** `ContractCaptureTest` snapshots `/v3/api-docs`;
  re-capture with `-Dcontracts.capture=true`, regen TS via `npx openapi-typescript@7` →
  `contracts/gen/*.d.ts`. ⚠️ **CAPTURE WITH `-Dtest=ContractCaptureTest` ONLY — NEVER during a full
  `verify`** (found 2026-08-01): `RecordRequiredModelConverter` is STATEFUL and springdoc caches the
  document, so a capture that rides a whole-suite run emits a spec with `required` **stripped from
  schemas the change never touched** (~10 of them, incl. `MinerviniRow`/`Report`, in the measured
  case). Committing that ships a `required.decreased` BREAK the author never made. CI captures the
  narrow way, so `main` is unaffected — the hazard is purely local, and it looks like a legitimate
  diff. Generic `Map<String,Object>` returns are NOT enumerated, so adding
  response keys does NOT drift the spec; new query params + new `@*Mapping` paths DO.
  ci-contracts warns on gen drift and requires `tsc --strict`. Its breaking gate diffs the
  **MERGE BASE**'s committed spec vs THIS branch's code spec (task_b3b59719 — it used to diff the
  branch against itself, so re-capturing the spec, which we mandate, silently blinded it; both
  sides were the branch and it never saw main). Re-capturing no longer silences it. **A removed or
  RENAMED response key in a record-backed schema IS caught** (task_ade97df8): openapi-diff 2.1.7
  cannot diff `openapi: 3.1.0` schemas (a retype reads "No differences"), so the gate relabels both
  sides to 3.0.1 for the diff step; and bare springdoc emits no `required` for record responses, so
  common-web-core's `RecordRequiredModelConverter` + `ResponseRequiredCustomizer` emit `required`
  for the record components Jackson always writes (RESPONSE schemas only — on a request body it
  would falsely claim the client must send every key). With both, a renamed response key reports
  "Missing property" and fails the gate — the `UniverseResolver` wire-read class of break is
  covered. STILL blind, deliberately: request-body property renames, and a rename inside a record
  reachable from BOTH a request and a response (none exists today) — the ci-contracts.yml header
  is the authority on what is and isn't caught. Intentional breaks: a
  `Contract break: APPROVED (<reason>)` line in the PR body; `hotfix/*` exempt.
  **Nullable response fields: `@Schema(nullable = true)` is a SILENT NO-OP at 3.1** (swagger-core's
  3.1 serializer drops `nullable`) — spell it `@Schema(types = {"number", "null"})`, which emits the
  3.1 type array; the breaking gate's relabel step downgrades exactly that shape to 3.0's
  `nullable: true` for the diff (genuine unions still refuse), and openapi-typescript renders it
  `number | null`.
  ⚠️ **`types` UNIONS with the inferred type, it does NOT replace it** (found 2026-08-01). The bare
  form above is correct ONLY when the declared base already matches what springdoc inferred —
  `{"number","null"}` on a `BigDecimal` field, `{"integer","null"}` on a `Long` — which is why it has
  always looked right. **To CHANGE the base type you must declare both:**
  `@Schema(type = "string", types = {"string", "null"})`. Bare `types = {"string","null"}` on a
  `BigDecimal` captures as **`["number","string","null"]`**, still advertising the impossible type.
  This bites hardest on decimals: `ArthaJacksonAutoConfiguration` registers `ToStringSerializer` for
  `BigDecimal` platform-wide, so **every decimal is a JSON string on the wire while springdoc infers
  `number`** — a repo-wide pre-existing lie that existing `{"number","null"}` annotations encode
  rather than fix. Verify by reading the CAPTURED SPEC, never by trusting the annotation.

  ```bash
  # ONLY with -Dtest=ContractCaptureTest. A capture riding a full `verify` strips `required`
  # from schemas you never touched (RecordRequiredModelConverter is STATEFUL + springdoc caches).
  ./mvnw.cmd -pl services/<svc> -am test -Dtest=ContractCaptureTest '-Dcontracts.capture=true'
  npx openapi-typescript@7 contracts/<svc>.openapi.json -o contracts/gen/<svc>.d.ts
  ```
- **EVERY new endpoint returns a typed record, never `Map<String,Object>`** — edge-gateway's
  `MapReturnRatchetTest` freezes the Map-returning handler COUNT per service (Maps are invisible
  to the contract gate); a new Map endpoint fails the strategy-gateway CI shard. Cost 2 CI cycles
  on 2026-07-03 (both new endpoints caught). Records also enumerate into the spec — strictly better.
- **margin-service (Python SPAN appliance, `/api/v1/margin`) now carries the same two-artifact
  contract gate as optimizer-service** — it was the one service with no committed OpenAPI spec at
  all until closed. `services/margin-service/tests/test_openapi_contract.py` captures BOTH
  `contracts/margin-service.api-surface.json` (asserted every run) and the FastAPI-native
  `contracts/margin-service.openapi.json` (asserted by `.github/scripts/margin_spec_staleness.py`,
  a required, unconditional ci-contracts step — same `app routes -> api-surface.json -> openapi.json`
  chain as optimizer-service). **Re-capture:** `cd services/margin-service && CONTRACTS_CAPTURE=1
  python -m pytest tests/test_openapi_contract.py` writes both files, then regenerate the TS client
  with `npm run gen:api` (frontend-react) or directly `npx openapi-typescript@7
  contracts/margin-service.openapi.json -o contracts/gen/margin-service.d.ts`. Classified NON_JAVA
  in `.github/scripts/contract_service_inventory.sh` (no `pom.xml`) — that membership is what
  CATEGORICALLY excludes it from the openapi-diff breaking gate (that loop iterates the JAVA list
  only, regardless of spec content) and the Java-only warn-vs-code step. `openapi_relabel_30.py`
  refusing its spec (exit 2, first at `paths./health.get.responses.200`) is a SEPARATE, additional
  reason it could not ride the breaking gate even if it were added to that loop: 6 of its pydantic
  `Optional` fields (`LegIn`/`PositionIn.expiry`+`strike`, `SizeResponse.limitingRail`,
  `SizeRequest.stop`) carry a primitive `anyOf: [{type: X}, {type: "null"}, ...]` WITH a `title`
  sibling, and its plain-dict `/health` response carries the SAME primitive nullable-`anyOf` shape
  but BARE — no `title` on the `anyOf` node itself (the title sits on the parent object schema
  wrapping it). Closing this gap for real needs BOTH a converter fix (today's converters handle
  only a bare `$ref`+null `anyOf` or a `type` ARRAY nullable, never a primitive `anyOf`, titled or
  not) AND including non-Java specs in the breaking loop — a separate, higher-risk redesign, not
  attempted here. margin-service gets the semantic staleness gate instead — which, like
  optimizer-service's, projects route surface only (method/path/params/requestBody/response-codes),
  never component properties or types. **Coverage gap, permanent, not a first-PR artifact:** a
  response-field rename (e.g. `SizeResponse.target` → `targetPrice`) changes neither the route
  surface nor any component KEY, so it passes every margin-service gate silently — measured, not
  assumed. ⚠️ Its 422 responses still use FastAPI's stock `HTTPValidationError` shape, not the
  shared `{code,message,details}` envelope other services converged on (§8.3) — a pre-existing
  runtime inconsistency, not a spec/CI defect, left unfixed by this gating change.


  ```bash
  # Re-capture BOTH contract artifacts, then regenerate the TS client:
  (cd services/margin-service && CONTRACTS_CAPTURE=1 python -m pytest tests/test_openapi_contract.py)
  npx openapi-typescript@7 contracts/margin-service.openapi.json -o contracts/gen/margin-service.d.ts
  ```
## Market data, candles & instruments
- **Candle sources split by interval:** `CandleReader.read()` serves the `candles_<iv>`
  caggs (5m/15m/1h/1d/1w), **sparse on a fresh boot**; native daily lives in `candles`@1d
  (dense — `readDailyWithWarmup`). The two diverge for 1d (chart overlays hit this).
- **Cache-first candle reads re-fetch only a 10-min trailing tail** (B-4 recency, NARROWED from
  2 h in #490 — the old window made every `to`≈now read re-fetch 2 h of covered bars from Kite all
  session). `GapDetector` marks a bucket missing iff absent OR its END clears now−10m, so the
  in-progress bucket (today's 1d especially) always refreshes. **Candle writes are TWO upserts**
  (#507): fetched history rides `upsertAuthoritativeAll` (REPLACES o/h/l/c/volume/oi — a poisoned
  tick-agg spike is correctable by re-fetch), the GREATEST/LEAST merge is tick-agg-only
  (`BarWriter`), bhavcopy stays DO-NOTHING; BOTH keep the original `source` on a value-identical
  write (provenance stays diagnosable), and the fetch `source` label comes from
  `HistoricalCandleGateway.sourceLabel()`, never the Spring profile. Live watchers:
  `GET /api/v1/market/health/data` (per-token tick/bar divergence + capture freshness),
  `GET /api/v1/signal-rejections/dot-health` (per-dot gate-input liveness),
  `GET /api/v1/market/health/ingest` + the `/data-ops/ingest-health` page (per-source EOD ingest
  coverage over `marketdata.ingest_runs`, #686/#699), and the scheduled canaries (ingest-coverage
  08:45, notifier-health 08:30, paper reconcilers 08:50/08:52, PartialBucketCanary every 60s — all
  IST) — check these BEFORE hand-digging a "feed looks dead" / "batch missed" report.
  **Measured schedule, re-read 2026-08-20 from `docker inspect`.** #1358 (`34a7d39c`, 08-17) moved
  every job inside 08:00–19:00 IST and the reconcilers to MORNING; **#1418 (`f9398ed4`, 08-20) then
  moved the two swing settles 16:00/16:02 → 18:52/18:53 and buyable-alerts 18:53 → 18:59** (ledger
  H27). ⚠️ **THIS BLOCK HAS NOW BEEN STALE TWICE — treat any dated cron list here as a HINT, and
  re-read `docker inspect` every time the value is load-bearing.** The 08-17 revision replaced a
  ~13-day-stale 19:30–21:15 list that had itself been added as a "measured" correction and had
  already reached a #1354 review comment; this revision replaced three entries that went stale
  within three days of that. Never quote the YAML `${ENV:default}` values; they differ from what is
  deployed.
  Morning: **08:30** swing-canary + notifier-health · **08:35** swing-catchup (⚠️ the only
  AUTOMATIC path that takes swing ENTRIES — see below) · **08:45** ingest-coverage · **08:50**
  paper-reconciliation · **08:52** past-expiry-recon. Afternoon: **16:05** bhavcopy-close-prefetch.
  Evening: **18:20** upstox-canary · **18:45** bhavcopy-eod · **18:46** nse-eod · **18:47**
  minervini-screen · **18:48** manas-screen · **18:49** market-context · **18:50** data-quality ·
  **18:51** equity-breadth · **18:52** minervini-swing-settle · **18:53** manas-swing-settle ·
  **18:54** heartbeat-swing · **18:55** graduation · **18:56**/**18:57** insights · **18:58**
  bhavcopy-close · **18:59** buyable-alerts. Also `ARTHA_HEARTBEAT_SESSION` every 10 min 09:00–15:59.
  ⚠️ **The swing settle is an EXITS-ONLY pass and legitimately reports 0 candidates AND 0 exits** —
  the entries pass is the NEXT MORNING's 08:35 catch-up (`SwingBatchCatchUp:276` guards on
  `hasRunWithEntries`, not `hasRun`, precisely so the settle marker cannot suppress it).
  ⚠️ **"Only path" is wrong and was corrected in cross-vendor review 2026-08-20: 08:35 is the only
  AUTOMATIC entry path.** The manual `POST /api/v1/signals/<batch>-swing/run` controllers
  (`MinerviniSwingController:64`, `ManasAroraSwingController:64`) call `runAndRecord(doctrine)` →
  `entriesEnabled = true` with `sessionDate = null`, so they take entries and never reach
  `scheduledSettleSession()`. Reason about entry paths as TWO, not one.
  ⚠️ **WHY 18:52 AND NOT 16:00, because the hour is load-bearing (H27):** most cash equities have no
  intraday 1d bar at all — their session bar is written by the **18:45 bhavcopy EOD ingest**. At
  16:00 the settle priced every held stop off the PREVIOUS session's close and still reported a
  clean "0 exits, 0 exit-skipped". Measured 08-20 after the move: STALE bars fell 10 → 4 and a real
  stop fired at 18:52:06 (INOXINDIA, exit price = that day's close exactly).
  ⚠️ **Judge a settle on the STALE-bar count and `exit_skipped`, NEVER on the exit count** — 0 exits
  is the common correct outcome, and reading it as failure is the exact misreading H27 exists to
  prevent. A `swing_catchup_runs` row missing before 08:35 is **not** evidence of a missed batch —
  reading it that way produced a false "Friday's screens were never consumed" alarm on 2026-08-17.
  Read `marketdata.ingest_runs` + `marketdata.canary_runs`/`strategy.canary_runs` for the real times,
  never the defaults.
- **3m reads are a read-time 1m→3m rollup** (`CandleRepository.rangeRolledFromOneMinute`, #365): the
  live SignalEngine 3m-primary depends on this rollup. The unused `candles_3m` cagg + its refresh
  policy were DROPPED (V027, #427) — 3m has no materialized view; only the 1m base feeds it.
- **Historical OI is VIRTUAL (read-time derived), never a snapshot backfill:** there are no real
  `options_chain_snapshots` rows before ~2026-06-15 (live capture start). `CandleDerivedChainReader`
  pivots the per-contract `candles` + `expired_contracts` into the StrikePoint shape on the fly
  (bucketed `last(oi)`, `oi_change` = bucket-lag, coverage-gated on `complete`); a `HistoricalOiReader`
  facade (snapshots-first, candle-derived fallback for fully-PAST empties — **LIVE/today never
  derives**) is swapped into `OptionsAnalyticsController`'s one `reader` field so the whole OI-page
  suite + Connecting-Dots + OI-attribution work on history with ZERO new rows/migration (materializing
  ~1.12B snapshot rows would re-OOM the compressed hypertable). Derived rows carry `derived`/`oiDerived`
  provenance; iv/greeks are null (ATM-band IV is recomputed via Black-76, the future-close `spot` proxy
  IS the forward), and **Dow + IV factors degrade to NEUTRAL on history** → the composite rarely reaches
  strong confluence on backtests, so the OI edge reads MUTED on derived history (it's a data-fidelity
  artifact, not a strategy verdict — judge OI-led strategies on FORWARD paper with real captured OI,
  not a weak historical backtest). ⚠️ **COROLLARY, and it is sharper than the rule it follows: an A/B
  between two strategies whose OI-GATING DIFFERS cannot be run on derived history AT ALL** (2026-08-03,
  caught in scoping before it was proposed). The muting is not noise that averages out — it is a
  one-sided handicap, so the OI-gated arm loses by construction and the backtest returns a decisive,
  confident, WRONG winner. Concretely: `scalp-connect-the-dots-nifty` runs `oi_confluence_gate.enabled:
  true` and `scalp-golden-crossover-nifty` runs it `false`, so any historical comparison of that twin
  pair is contaminated by design. **The tell is that the arms differ in a factor history cannot
  represent** — check that BEFORE reaching for a backtest as a discriminator, not after reading the
  result. Forward paper is the only valid comparison for such a pair. **Timestamp-key trap (root cause of #214):** an `OffsetDateTime`
  map key SILENTLY misses across data sources with different UTC offsets — the futures-spine bars carry
  `+05:30` but JDBC `time_bucket` returns `+00`, so `map.get(bar.bucket)` missed EVERY lookup and 3
  Connecting-Dots factors (activeStrikeOi/IV/VIX) read NEUTRAL on every history session for months. Key
  cross-source time maps by `.toInstant()`, never the offset-bearing `OffsetDateTime`.
- **SPAN margin = Upstox server-side, NO `.spn` file (F9 source, #510):** `UpstoxMarginClient`
  POSTs a ≤20-leg basket to Upstox `POST /v2/charges/margin` on the login-free analytics token and
  gets `span_margin`/`exposure_margin`/`total_margin` + basket `required_margin`/`final_margin`
  back — Upstox loads the NSCCL file, we never do. `POST /api/v1/market/margin` (typed record,
  fail-soft: `unpriced` reason on any gap, never a 5xx) takes structured legs
  `(exchange, underlying, optionType, expiry, strike, quantity, side, product)`, resolves each to
  the Upstox `instrument_key` via `UpstoxFnoMasterClient.keyFor`, defaults `product=D` (NRML, full
  SPAN — conservative). Bound only when `artha.upstox.analytics.enabled=true` (ObjectProvider → mock
  stack returns `unpriced`). **`quantity` MUST be a lot multiple** or Upstox 400s `UDAPI1104`
  (surfaced as the unpriced reason) — the scalper already emits lot-aligned qty. The marginism
  appliance (#126) stays the offline/backtest fallback. Verified live 2026-07-04 (1-lot short →
  span 337004.85 / final 188604.45). The path is `/v2/charges/margin`; the doc's `/charges/margin`
  404s.
- **Kite REST → full-mirror DTOs (`kite/wire/`):** one record per endpoint (quote / historical /
  session / profile / instrument-CSV) mirrors **every** documented Kite field, each
  `@JsonIgnoreProperties(ignoreUnknown=true)` so a field Kite ADDS can never crash the live feed.
  Gateways `.body(KiteXxx.class)` then map to the domain port records (`Quote`, `Candle`,
  `InstrumentRecord`, `TokenSession`); DTOs stay domain-free. **NEVER** enable global
  `FAIL_ON_UNKNOWN_PROPERTIES` on the live mapper (couples the live path to Kite's exact shape).
  Drift (rename/remove/retype) is caught OFF the critical path by the daily `ContractCanary`
  (raw-JSON vs `kite-contract-manifest.json` — sentinels for CONSUMED fields, not a full mirror) +
  `KiteWireContractTest`. The WS ticker uses the javakiteconnect SDK; REST is hand-rolled
  `RestClient` because the SDK pins `Routes._rootUrl` (no setter) → unstubbable by WireMock. EVERY
  new Kite call follows this — see `kite/wire/package-info.java`.
- **Cross-source symbol normalization (Kite / Upstox / OpenAlgo):** ONE canonical key — the `Instrument`
  record keyed by **`(exchange, tradingsymbol)` in Kite's grammar**, stored in `marketdata.instruments`
  (PK same). Numeric tokens (Kite `instrument_token`, Upstox `instrument_key`) are source-local session
  handles, **resolved through the master, never stored as identity / never compared across sources**. Each
  source has an edge mapper: Kite tradingsymbol IS canonical (token→key via `InstrumentRegistry`); Upstox
  index/equity via static maps (`NSE_INDEX|Nifty 50` / `NSE_EQ|<ISIN>`) but **F&O key = an opaque numeric
  token NOT derivable from the symbol** → tuple-looked-up `(segment,underlying,type,expiry,strike)` against
  Upstox's public instrument-master JSON (`UpstoxFnoMasterClient`); OpenAlgo requests are **built from the
  structured leg fields, never the Kite symbol** (`OpenAlgoSymbols`, its `DDMMMYY` token differs). Load-bearing
  reconcilers: `normalizeStrike()` (strip trailing zeros so `18000`≡`18000.00`), per-source expiry conversion
  (Kite `DDMMMYY` / Upstox epoch-millis / canonical `LocalDate`), `UnderlyingRef` (`NIFTY`→`NIFTY 50`). **Kite
  is the always-on fallback** (Upstox/OpenAlgo mapping is additive, must never break the feed). ⚠️ **But this
  is DEFAULT-only, NOT a runtime composite (audit EXT-04, 2026-07-18):** the alternate-source flags select
  MUTUALLY-EXCLUSIVE beans (`LiveKiteConfig:241`) — flipping the quote/ticker source to Upstox/OpenAlgo REMOVES
  the Kite bean entirely (`UpstoxQuoteGateway:75` drops unmapped keys with no Kite delegate), so a live miss
  does NOT fall through to Kite. Kite-as-fallback holds only while the source flag stays Kite (the current
  default). ⚠️ **`application.yml:104` and `UpstoxQuoteGateway:39` BOTH claim "Kite stays the fallback for
  unmapped keys" — that comment is FALSE; there is no delegate and unmapped keys are silently absent.**
  Composite primary+fallback is unbuilt — but **W-U4 is NO LONGER declined: the owner reversed it
  2026-08-17 and asked for Upstox primary + Kite as a rate-limit fallback (ledger H26).** Building that
  composite is the item; the blocker is instrument identity, not candles. ⚠️ **H26's OUTAGE argument is
  GONE as of 2026-08-20** — the recurring "Kite outages" of 08-19/08-20 were the HOST's outbound network:
  five destinations failed in the same window (Kite REST, Kite WS, **Upstox**, Telegram, the liveness
  heartbeat). Upstox-primary would have bought nothing. The row survives on rate limits and removing the
  daily 06:00 Kite login; do not re-justify it on resilience.
  ⚠️ **BE-series NSE equities resolve through a `-BE` twin (H29, #1424, live since 2026-08-20).** Kite's
  canonical NSE tradingsymbol for a BE-series stock carries a `-BE` suffix while bhavcopy/screens/swing
  books use the BARE symbol, so `marketdata.instruments` holds both — a tokenless bare row and a tokened
  `<SYM>-BE` row. `TokenResolverAdapter` falls back to the twin on NSE only, never recursing, never on BSE,
  and COUNTS every fallback (`ay_instrument_be_suffix_fallback_total`) because the original defect was
  invisible precisely by fail-softing. ⚠️ **BOTH halves are now shipped — this bullet said the second was
  "NOT fixed" for four days after it was (corrected 2026-08-25).** H29 keyed on `instrument_token IS NULL`,
  the NARROWER half; ledger H36 covers the wider one, where the bare row carries a token Kite REJECTS
  (`400 … invalid token`) and therefore *resolves*, so the H29 fallback never fired for it. The twin now
  also wins when the bare row is `is_active = f`, on a SECOND counter
  (`ay_instrument_be_suffix_inactive_fallback_total`) so the two halves stay separable. `sourced`:
  `TokenResolverAdapter.resolve` gates on `direct.isPresent() && directRow.get().active()`. `computed`
  2026-08-25 from the live DB: `DIACABS` and `MENONBE` are exactly this shape — bare row inactive WITH a
  token, active `-BE` twin carrying a different one. ⚠️ Still **strictly additive by design**: an inactive
  bare row with NO twin returns its own (rejected) token rather than a 404, so ~389 such NSE rows are
  deliberately untouched. Drift caught by 3 contract canaries (Kite/Upstox/OpenAlgo,
  CONSUMED-field sentinels). Full map: `docs/symbol-normalization.md`.

## Strategy engine & paper book
- **Backtest/optimizer submission identity + precedence (2b):** `strategyId` is the registry **UUID**
  (NOT the slug); omit `strategyVersion` → the optimizer/runner pins the latest published, else latest
  draft. Terminal job status string is `completed`; results are keyed by `resultRef` (the run id), read
  from `backtest_runs` (`total_return`, `trade_count`) + `backtest_trades`. **The optimizer reads
  `optimize.parameters` FROM the YAML but takes `walkForward` + `objective` + `maxTrials` FROM the
  REQUEST** (`service.py`), not the YAML — so OOS fold tuning needs `objective:{metric: oos_fold_mean}`
  + a `walkForward:{train_days,test_days,step_days}` block in the `POST /optimizations/run` body (a
  YAML-only `walk_forward` runs as a plain sweep with empty OOS folds).
- **Scalper three-way instrument decoupling (ADR-0003, #224/#225):** `universe.signal_underlying` /
  `universe.strike_reference` / `universe.underlying` are 3 independent optional instrumentRefs — the
  signal/indicator series, the ATM-strike anchor spot, and the option-execution root — each defaulting
  to the prior so existing configs stay byte-identical. Backtest option legs resolve via
  `expired_contracts.underlying_symbol` (`"NIFTY"`/`"SENSEX"`); `OptionsPremiumReplay.registryUnderlying`
  strips at the first space (`"NIFTY 50"`→`"NIFTY"`), so `universe.underlying` carries the INDEX exchange
  (`NSE` for NIFTY 50, `BSE` for SENSEX) while options resolve to NFO/BFO. The OI-confluence gate index
  defaults to the option-root, overridable via `oi_confluence_gate.index`; it is MUTED on derived history
  (Dow+IV → NEUTRAL) so the niftyoi-vs-sensexoi A/B is a FORWARD-paper discriminator (identical on backtests).
- **3m primary in the tick-wise runner (#228, same parity-safe-additive pattern as above):**
  `TickwiseGoldenRunner.intervalDuration` rolls a 1m stream up to a coarse primary; it had 5m/15m/1h
  only, so EVERY 3m-primary scalper backtest failed at submission (`"rolls up 5m/15m/1h primaries; got
  3m"`). Fixed with an additive `case "3m" -> Duration.ofMinutes(3)` — 3m is a valid aggregate, the
  bucketing is generic (epoch floor mod interval-seconds) and 09:15 IST aligns to a 3m boundary, so
  5m/15m/1h goldens stay byte-identical.
- **Live scalper signal series = the DATED front contract, NOT `NIFTY-FUT-CONT`:** the continuous
  future (NFO `NIFTY-FUT-CONT`) is BACKTEST/replay-only and intentionally stale (max bar = backfill
  end). LIVE, `FuturesUniverseResolver` maps `universe.signal_underlying` → the live dated front/next
  contract (e.g. `NIFTY26JULFUT`, roll re-resolves daily ~08:40 IST). Don't alarm that CONT is stale —
  that's by design ("the continuous-series counterpart is replay territory").
- **Published versions + signals persistence:** `strategy_versions.status='published'` (LOWERCASE —
  `'PUBLISHED'` queries return empty) + the strategy's `published_version_id` pointer; the live engine
  loads `enabled && publishedVersionId != null`. `strategy.signals` holds only FIRING bars (gate pass
  AND composite≥threshold); rejected evals return a breakdown but never a row. `score_breakdown` =
  frozen ScoreBreakdownDto (`composite = Σ(w·s)/Σw`; an optional activates iff score≥optionalMinScore
  AND required-only composite≥threshold−optionalGateMargin). Scalper enrichment rides the
  `scalper_detail` V009 side-channel (option leg + confluence dots + `manual_checks`).
- **Empty `strategy.signal_rejections` does NOT mean the engine is dead** — `recordRejection`'s two call
  sites are BOTH downstream of the `chart != FIRED` early return (`SignalEngine:~1132`), so ONLY the
  `confluence-blocked` outcome writes a row. On a down tape every scalper exits at the chart/composite stage
  and the table stays empty ALL session — normal, not starvation. NEVER restart on an empty rejections table
  alone (cost a needless live restart 2026-07-20). ⚠️ **CORRECTION 2026-08-01 — this bullet used to say
  "Liveness = Σ `ay_signal_eval_outcome_total` ADVANCING", which is the OVERSTATED form and contradicts the
  ledger (the authority, §0 group-G note): Σ(outcomes) is an ATTRIBUTION primitive — an evidence panel —
  and NOTHING may be armed on it.** Its increment site sits inside the `else` of `if (activeEntry.isPresent())`
  below two `continue`s, and btst never evaluates from `onClosedBar` at all, so Σ is **LEGITIMATELY FLAT while
  bars flow** in four normal states: outside a session window, whole book in position, context-only symbols,
  btst — measured live as structurally flat 15:00–15:30 EVERY non-expiry day. Correct reading: **advancing ⇒
  alive (sound positive proof); flat ⇏ dead (unsound negative proof).** The signals safe to key liveness on are
  `lastBarReceivedAtMs` / `lastBarEvaluatedAtMs`.
- **A scalper YAML/config change is a SILENT NO-OP until RE-PUBLISHED.** `ScalperStrategySeeder` mints a
  fresh DRAFT on boot (`resyncConfig`→`update`), never publishes; the live engine runs the *published*
  version. After deploying a config change, `POST /api/v1/strategies/{id}/publish` each affected strategy
  (reconcile keys on published version-id, hot-swaps at the next bar) and verify the published config carries
  the change before trusting it. ⚠️ **Pick WHICH to republish by "latest version row ≠ `published_version_id`",
  never "latest DRAFT ≠ published"** (#1016 wave, 2026-07-25): `resyncConfig`→`update` dedupes against
  `latestVersion(strategyId)` of **ANY status**, so a strategy already current with its YAML mints nothing and
  keeps a months-old leftover draft — the draft-based query flagged all 38 enabled scalpers and would have
  *reverted* `scalp-gap-theory-nifty` to a 07-06 draft **missing the armed `relative-volume-floor` tag**.
  Before publishing a batch, diff what each republish GAINS vs LOSES (tags + exit_rules), not just that it
  differs.
- **A 2nd `PaperService.openPosition` on the same `(book,exchange,tradingsymbol,side)` AVERAGES into the open
  position** (pyramiding, `newQty = qty + qty`), it does NOT reject — `uq_paper_positions_open` guards the
  ROW, never the qty. Idempotency for anything that opens positions must claim BEFORE the open (atomic
  marker/lock), never rely on the unique index.

## Deploy & live verification
- **In-container `now()`/`::date` is UTC, not IST:** a 02:xx-IST row is the *previous* calendar day in
  the DB (e.g. 02:xx IST on 06-29 stores as `2026-06-28` UTC). Filter `signals.generated_at` / candle
  `bucket` by explicit `+05:30` ISO bounds, never `::date = CURRENT_DATE` (off-by-one across IST midnight).
  **But `+05:30` is for BOUNDS, not DISPLAY:** `AT TIME ZONE '+05:30'` **INVERTS** (POSIX sign convention) — it renders
  14:20 IST as 03:20. Bound literals (`timestamptz '2026-07-17T09:15:00+05:30'`) are correct; to RENDER use
  `AT TIME ZONE 'Asia/Kolkata'`. Cost a live investigation a false path 2026-07-17. Same class on the host:
  **Git Bash ignores `TZ=`** — `TZ=Asia/Kolkata date` prints UTC. Use python `zoneinfo` for wall-clock IST.
- **Rebuild + redeploy ONE service (no `ay` build verb):** build the artifact (`(cd frontend-react &&
  npm run build)` or the service JAR), set `$env:ARTHA_DB_NAME`/`$env:ARTHA_REDIS_DB` to the LIVE
  values (`artha`/`0`, mock `artha_mock`/`1`), then `docker compose -f deploy/docker-compose.yml
  --env-file .env build <svc> && up -d <svc>` — recreates only `<svc>`; unset vars drift the others.

  ```powershell
  # 0. PRE-FLIGHT (the branch-parked trap: a fresh .class proves a REBUILD, never the SOURCE)
  git rev-parse --abbrev-ref HEAD                  # on main?
  git log HEAD --oneline | Select-String <sha>     # is the PR's commit actually in history?
  # 1. Artifact FIRST -- the Dockerfile COPYs a pre-built jar / dist
  ./mvnw.cmd -pl services/<svc> -am package -DskipTests
  # 2. Fingerprint the jar for a symbol only the NEW code has, BEFORE building the image
  # 3. Deploy (never bare `docker compose` -- unset vars drift siblings onto the wrong DB)
  $env:ARTHA_DB_NAME = 'artha'; $env:ARTHA_REDIS_DB = '0'      # mock: artha_mock / 1
  docker compose -f deploy\docker-compose.yml --env-file .env build <svc>
  docker compose -f deploy\docker-compose.yml --env-file .env up -d <svc>
  ```
- **A deploy carrying a NEW migration needs flyway-init FORCED:** `up -d <svc>` treats the exited
  `flyway-init` one-shot as satisfied and may NOT re-run it — `up -d --force-recreate flyway-init`
  first, then ALWAYS DB-probe the new object (`to_regclass`/information_schema). A healthy container
  + an "up to date" flyway log do NOT prove the migration applied (a stale checkout deployed
  "healthy" without its migration once, 2026-07-11; only the probe caught it).

  ```powershell
  docker compose -f deploy\docker-compose.yml --env-file .env up -d --force-recreate flyway-init
  docker compose -f deploy\docker-compose.yml --env-file .env up -d <svc>
  # THEN probe the object itself -- a healthy container + "up to date" log do NOT prove it applied:
  docker exec ay-timescaledb psql -U artha -d artha -c "SELECT to_regclass('<schema>.<table>');"
  ```
- ⚠️ **"13/13 healthy" says NOTHING about whether a service runs current code — and neither does an image
  timestamp.** Found 2026-08-02: five services were silently stale while every container was healthy.
  **The deploy-currency check, in order:** (1) per service, `git log origin/main -1 -- services/<svc>/src/main
  libs/` — note `libs/` makes a shared-lib change stale EVERY Java service at once, which is how four went
  stale on one PR; (2) compare against `docker image inspect <img> --format '{{.Created}}'` — **that field is
  UTC**, so convert before comparing with IST commit times (misreading it cost a false "stale" alarm the same
  night); (3) **PROBE what the service actually SERVES.** A timestamp says a service *might* be stale; a probe
  says what it *is*. The decisive case: committed spec said `BacktestTradeItem.entryPrice: {"type":"string"}`
  while the deployed service served `{"type":"number"}` — a lie the sweep had already removed from the repo,
  still being published. Cheap live probes: `/v3/api-docs` for a known field, the frontend's served bundle
  hash vs `dist/index.html` (an IDENTITY check, not an mtime), `pg_constraint` for a migration object.
- ⚠️ **The main CHECKOUT's BRANCH is part of the deploy, and a migration check will NOT catch it**
  (2026-08-05). Deploying #1303 from the REAL checkout (worktree rule satisfied) still shipped
  **pre-#1303 code**, because the checkout was parked on a branch merged BEFORE it. `MAVEN_EXIT=0`,
  image `Built`, `UP_EXIT=0`, healthy, gateway `UP`, 0 ERROR lines — **and the recompiled `.class`
  files carried the current timestamp**, which is what sells it. A fresh timestamp proves a REBUILD,
  never the SOURCE it was built from. The false comfort was checking `deploy/flyway/<lineage>/`
  against `origin/main` and finding them identical: **migrations only move when someone adds one, so
  a checkout can be arbitrarily far behind on SOURCE with a matching migration set** — and a PR that
  adds no migration makes that check pass by construction. **Pre-flight, in order:** (1)
  `git rev-parse --abbrev-ref HEAD` — on `main`? (2) confirm the PR's commit is in HEAD's history
  (`git log HEAD --oneline | grep <sha>`) — `--is-ancestor` alone is NOT enough, a merged older
  branch is also an ancestor; (3) **grep the working tree for a symbol only the new code has** before
  building; (4) fingerprint the JAR for it before `docker compose build`, not after deploying.
- ⚠️ **Fingerprinting a NESTED lib class returns 0 from the outer fat jar — that is a FALSE NEGATIVE, not
  absence.** `unzip -l /app/*.jar | grep <LibClass>` = 0 for anything in `libs/`; and `unzip -p … | unzip -l
  /dev/stdin` does not work either (unzip cannot read stdin). Extract first:
  `cd /tmp && unzip -o -q /app/*.jar "BOOT-INF/lib/common-web-core-*.jar" && unzip -l BOOT-INF/lib/common-web-core-*.jar | grep <Class>`.
  Also expect MORE hits than classes — a nested record (`Foo$Decimal`) counts separately.
- ⚠️ **`engine_reloads.installed=f` on the row right after a deploy is NORMAL, not a failed install.** Rows
  alternate `t` then `f` ~38 s apart on every deploy (measured across all three on 2026-08-02: 97→98, 99→100,
  101→102, each 38 loaded / 0 unresolved / 0 errors). The first reload installs; the second is the periodic
  reconcile finding no drift. Judge health on `loaded`/`unresolved`/`load_errors`, never on `installed` alone.
- **Deploy migrations IN VERSION ORDER.** Deploying V044 before V043 makes flyway-init fail *validation*
  (`Detected resolved migration not applied: 043`), which blocks EVERY future migration, not just the skipped
  one. Fix = renumber the stranded (never-applied) migration HIGHER, never `outOfOrder=true` (2026-07-20).
- **Thread-dump a stalled JVM service:** `docker exec ay-<svc> sh -c 'kill -3 1'` → dump lands in
  `docker logs` (jstack/jcmd absent in the slim image).

  ```bash
  docker exec ay-<svc> sh -c 'kill -3 1'   # dump lands in `docker logs`, not stdout of this command
  ```
- **Actuator/prometheus ports are per-service, NOT 8080** (8080 = edge-gateway/SPA): strategy-signal
  `127.0.0.1:8082`, market-data `127.0.0.1:8081`. Probe via
  `docker exec ay-<svc> sh -c 'wget -qO- http://127.0.0.1:<port>/actuator/prometheus'`.

  ```bash
  docker exec ay-strategy-signal-service sh -c 'wget -qO- http://127.0.0.1:8082/actuator/prometheus'
  docker exec ay-market-data-service     sh -c 'wget -qO- http://127.0.0.1:8081/actuator/prometheus'
  ```
## Frontend (`frontend-react` — React 19 + Vite 6 + Tailwind v4 + shadcn)
The app is `frontend-react` (the Angular `frontend-ui` was removed after the React cutover, PR #104).
Stack: React 19, Vite 6, **Tailwind v4 CSS-first** (`@import 'tailwindcss'` + `@theme inline`, NO
`tailwind.config.js`), Zustand, TanStack Query v5, react-router 7, echarts 5 + lightweight-charts 5,
shadcn/ui (controls/overlays only). 5 swappable themes via `data-theme` on `<html>`; all colour from
per-theme `--ay-*` CSS vars. Mobile target S24 Ultra ~480px. a11y gated by axe + Playwright role/name.
- **Tailwind v4 traps** (each cost a build cycle): `font-size` goes on `<body>` only (on `<html>` it
  rescales every `rem`); type-ramp vars live in a plain `:root`, NOT `@theme` (else the auto-generated
  `text-*` utilities collide with the `@utility text-h1` classes); never leave a `*/` substring inside
  a CSS comment (lightningcss closes the comment early). See [[frontend-revamp-state]].
- **shadcn bridge:** `accent` is the ONE token name that collides — never re-alias `--color-accent` in
  the shadcn `@theme inline` bridge (it clobbers the app's brand accent app-wide; axe/e2e miss it, a
  live old-vs-new screenshot catches it).
- **lightweight-charts** paints blank unless `createChart(el,{autoSize:true})`; **monaco** workers
  don't register → the repo uses a `<textarea>` editor + a plain LCS diff instead.
- **The shared `DataTable` paints TWICE in jsdom** — the desktop `<table>` AND an `md:hidden` card list — so every
  cell's text exists twice and a bare `getByText` on a converted page fails with "multiple elements". **Scope specs to
  the desktop table** (`GraduationPage.spec.tsx:82-86` is the convention). Bit two builders on the #890/wave-2 slices.
  Its `header` is typed **`string`** (`DataTable.tsx:43`): a JSX header (`<Link>`, a chip, a select-all checkbox) cannot
  convert — but a *hidden text* header can and must (`header: 'Actions'` + `headerClassName: 'ay-sr-only'`, the shipped
  pattern at `JobsPage.tsx:707-712`; a builder wrongly called that a blocker and skipped 2 pages over it). **The 2026-07-19
  wave added three OPTIONAL props — all default-off / omitted-render byte-identical** (#945/#946): `maxHeight` (overrides the
  `max-h-[68vh]` cap — a bounded panel is NO LONGER a skip), `onRowClick` (a **MOUSE-ONLY** row/card click), and
  `renderExpanded` (inline expandable detail rows via a leading expander column). ⚠️ **THE a11y RULE THEY CARRY (audit
  M23/#596, re-broken and re-fixed in #945): NEVER put `role="button"` on a `<tr>`** — it overrides the row's implicit
  `role="row"`, strips every `<td>` of its required `row` parent (axe `aria-required-parent`) and makes AT see a table with
  ZERO data rows. A clickable/selectable/expandable row keeps `role="row"`; the keyboard/AT control is a **real in-cell
  `<button>`** (`onRowClick` is mouse convenience only; the expander / `aria-pressed` selector is the keyboard path —
  templates: `SignalsPage.tsx:203-218`, `BacktestResultsPage.tsx` per-trade `#` cell, `Leaderboard.tsx` RobustScore).
  **Adoption is a REFACTOR:**
  prove it by dumping every header + cell `textContent` before/after and diffing (empty or it didn't happen) — "the spec
  passes before and after" proves nothing, since an unconverted table has no accessible name and `within(table)` throws
  for unrelated reasons. Honest claim is *same content/order/formatting*, **not pixel-identical** (adoption intentionally
  normalizes density, drops UPPERCASE headers, adds zebra + sticky, and switches mobile to cards).
- **List endpoints return an `{items:[...]}` envelope** (signals/paper/journal/screener/
  watchlists); only `instruments/search` + `instruments/underlyings` return bare arrays.
- **Section nav** (`MegaMenu.tsx`): each oipulse section is its own top-level menu-bar trigger (the
  old single "All Menu" mega-dropdown was split, PR #177).
- **Verify trio** (PowerShell `Push-Location frontend-react`): `npm run lint` +
  `npm run test:ci` + `npm run build`. After a rebuild HARD-reload (Ctrl+Shift+R) — a stale cached
  chunk renders the old UI. **Deploy gotcha:** the Dockerfile COPYs the HOST-built `dist/`, so
  `npm run build` on the main checkout FIRST, then `docker compose build frontend-react`.
- **Full-suite vitest timeouts in UNTOUCHED specs CAN BE load contention rather than your bug — run the
  ladder below before blaming the branch** (⚠️ they can also be real: an untouched spec regresses through
  CHANGED SHARED CODE, and the A/B ladder is what distinguishes the two — do not read "untouched spec" as
  "not my change") (#1061,
  2026-07-28; ⚠️ **re-measured and CORRECTED 2026-08-03, #1269 — the old "heaviest render specs sit at
  96–99% of the default 5s budget" was ONE moderate-load sample and materially understated this**):
  heavy page-render specs cost **1.2–2.2s in isolation vs 3.0–9.1s under full-suite worker contention —
  a 2.2–6.2× multiplier that tracks MACHINE LOAD, not code**, spanning 60–182% of the 5s default. The
  default sits INSIDE that spread, so *which* specs fail is decided by what else the box is doing: CI
  stays green on its 2-core runner while a 16-CPU box sharing Docker + other agents went red on **6 of
  6** unmodified runs, a different failing set each time, every one green in isolation. Cleanest tell —
  the control group: `OrdersPage` / `OiExpiryStrategyPage` measure 4075–9140ms in EVERY run and pass
  ONLY because they already carry `}, 15_000);`. Debug ladder: name the failures → run them in
  ISOLATION → full suite on MAIN's warm checkout (baseline) → full suite at the branch on that same
  checkout (the only decisive gate); a fresh worktree's cold caches make it worse, so warm a worktree
  with one DISCARDED run before trusting any number from it. A borderline spec gets an explicit
  `}, 15_000);` budget + dated comment, never a global testTimeout bump. ⚠️ **A SECOND, DISTINCT
  failure mode that NO budget can fix:** a name-matcher `findBy*` exhausting Testing Library's default
  **1000ms `asyncUtilTimeout`** (it recomputes accessible names over the whole DOM on every 50ms poll)
  fails as `Unable to find role="…"`, **NOT** as a test timeout — widen the wait at the CALL SITE
  (`{ timeout: 3000 }`). Measured on `InsightsPage` at 1869ms + 1578ms; **the repo was NOT swept for
  other name-matcher `findBy*` sites, so that class is only PARTLY closed.** Also: fake-timer
  specs — `findBy*`/`vi.waitFor` poll with the FAKED setTimeout and stall; flush with
  `act(async () => { …; await vi.advanceTimersByTimeAsync(0); })` then plain `getBy*`; and a
  force-close that UNMOUNTS a Radix dialog never fires `onCloseAutoFocus` (focus repair needs a
  post-unmount effect, and `.focus()` on a disabled trigger is a silent no-op).

## Database / migrations
- **Applied Flyway migrations are checksum-locked** in the dev stack and CI — editing
  an applied migration (even a comment) fails `flyway validate` / flyway-init.
  Corrections go in a **new suffix-versioned migration**, never an in-place edit.
- `ay reset-db` drops volumes and rebuilds all four schema lineages from empty.
- **A minor TimescaleDB bump can break QUERY PLANNING, not just APIs** — `ALTER EXTENSION
  UPDATE` succeeding + containers going healthy PROVES NOTHING. 2.18.2 reintroduced a
  planner assertion (`ERROR: non-Var pathkey not expected for compressed batch sorted
  merge`) that aborts planning for a top-level `DISTINCT`/`ORDER BY`/`GROUP BY` on a
  **computed expression** (`time_bucket(iv, ts - INTERVAL '1 second', tz)`, a cast, arithmetic)
  **+ a `LIMIT`** over a hypertable with **any compressed chunk** in the (time-unpruned) scan,
  when `timescaledb.enable_decompression_sorted_merge = on` (the shipped default). It took all
  three OI-confluence dots offline for a whole session before it was caught
  (docs/signal-analysis/2026-07-20-session-findings.md §6.2; fixed by the two-`max(ts)`-aggregate
  form in `FuturesDigestService`/`FuturesSnapshotReader`/`OptionsSnapshotReader`, guarded by
  `CompressedSortedMergeRegressionIntegrationTest`). `GROUP BY <expr>, <col>` (multi-key) and
  `time_bucket(iv, ts, tz)` on the **bare** column are safe; only the expression-arg-under-LIMIT
  shape trips. **After ANY Timescale bump, smoke the read paths** — especially the OI dot feeders
  and anything ordering/grouping/distinct-ing by an expression over compressed chunks — don't
  trust green healthchecks. A DB-level GUC flip
  (`ALTER DATABASE artha SET timescaledb.enable_decompression_sorted_merge = off`) is the
  emergency mitigation; the query rewrites are the real fix so the optimisation stays on.

- ⚠️ **BOTH equity source tables are RETRO-MUTABLE — a persisted decision row CANNOT be reproduced
  from current data** (found 2026-08-03, and it silently invalidates A-vs-B measurements). `candles`
  is retroactively rewritten (one symbol's ENTIRE July series was rewritten on 2026-07-31), and
  `nse_eod_bhavcopy` was **still gaining rows for April–June trade dates months later**.
  ⚠️ **THE TWO HALVES ARE DIFFERENT MECHANISMS AND THIS BULLET USED TO CONFLATE THEM (corrected
  2026-08-17, N30).** `candles` genuinely MUTATES — an existing bar's value changes from A to B.
  `nse_eod_bhavcopy` does NOT: measured on the 2026-08-10 event that prompted this, all **13**
  historical trade dates it wrote had **ZERO rows beforehand** and gained 40,862 — **absence
  becoming presence, never a rewrite.** **The warning is unchanged and still binding** (a screen that
  ran when 2026-04-30 was missing saw a different world from one run today, so a persisted decision
  still cannot be reproduced), but the DETECTION differs and that is why the distinction earns its
  place: a bhavcopy gap is visible as a per-date row COUNT, while a `candles` rewrite is invisible
  unless you compare VALUES. Do not reach for a value-diff on bhavcopy or conclude `candles` is safe
  because row counts match. So comparing
  "what the screen decided then" against "what the data says now" compares two different worlds and
  reports the difference as a divergence. **Any cross-table or historical A-vs-B comparison must gate
  on `fetched_at`** — and note `fetched_at` is an UPSERT timestamp, not first-seen, so it bounds
  rather than pins. Concretely: this is why a "CA-plane split" investigation found **46 of 47**
  exposures had no corporate action anywhere near them and all 38 clean cases had **byte-identical
  closes** — the disagreement was entirely in a 50-bar mean computed over retro-mutated history.
- ⚠️ **`nse_eod_bhavcopy` is MULTI-SERIES — filtering `series='EQ'` silently drops real screen
  symbols** (measured 2026-08-04, and it manufactured a false "the guard FAILED" reading). The screen
  universe spans **`EQ` AND `BE`** (13 series exist overall: EQ, BE, BZ, E1, GB, GS, IV, MF, N1, RR,
  SM, ST, SZ). A staleness probe written as `series='EQ'` reported **53 screen symbols with no bar at
  all and 76 more stale** — BODALCHEM, AUTOIND, BGRENERGY and 50 others trade `BE`, so their bars
  were simply outside the filter. Series-agnostic (`series IN ('EQ','BE')`) the same probe returns
  **0 / 0 / 1772 fresh**. **Tell: a "missing data" result that includes liquid, obviously-trading
  names.** That is a join or filter artifact, not an outage — check one symbol across ALL series
  before believing it. Same family as the equity `exchange=` filter rule.
- ⚠️ **A YAML default is NOT a deployed value.** `application.yml`'s `${ENV:default}` says what happens
  when the env var is absent — it says nothing about production. Reading the default as the live
  setting put a wrong "still on `native`, needs flipping" claim into **four** documents for ~28 days
  while the flag had in fact been flipped the whole time (2026-08-03). **Read `.env` or, better,
  `docker inspect <container>`'s env — then PROBE what the service actually serves.**

## Docker / compose
- **Never invoke `docker compose` directly without `--env-file .env`** — compose
  resolves `.env` relative to `deploy/` and silently blanks vars (e.g. the owner
  password hash → gateway 401). Use the **`ay` / `ay.ps1` CLI**, which always passes
  it. Project-scoped compose only — **never `docker kill`**.
- Mock vs live is `SPRING_PROFILES_ACTIVE` in `.env`, orthogonal to compose profiles;
  mock needs zero secrets. PHC password hashes in `.env` need every `$` escaped `$$`.
- **An `application.yml` `${ENV_NAME}` placeholder must match the compose + `.env` passthrough name
  EXACTLY** — a mismatch silently swallows the `.env` override (falls to the YAML default), with no error
  (e.g. `ARTHA_PAPER_RISK_PER_TRADE_PCT` vs `..._PER_TRADE_RISK_PCT`, #653). Grep both sides when adding a knob.
- **Mock and live use SEPARATE databases + Redis logical DBs** — live → `artha`/db0,
  mock → `artha_mock`/db1 — derived from the profile by `ay.ps1` (exports
  `ARTHA_DB_NAME`/`ARTHA_REDIS_DB` into compose env); the `db-create` one-shot makes both,
  flyway-init migrates only the active `${ARTHA_DB_NAME}`. ALWAYS switch profiles via `ay`
  (raw compose leaves the vars unset → mock writes to `artha`); `ay reset-db` wipes BOTH
  (one shared volume).
- **NEVER `docker compose up` from a git worktree — it CRASHES the live container.** A worktree has no
  `deploy/secrets/*` (gitignored), so docker bind-mounts a non-existent path and **creates a DIRECTORY**
  there; flyway-init dies `cat: /run/secrets/postgres_password: Is a directory` and the service inherits
  the poisoned mount (`SecretFilePasswordPostProcessor` → `IOException: Is a directory`, crash-loop).
  Cost a live strategy-signal outage 2026-07-25 while deploying #995 from a worktree (used to avoid a
  concurrent session's checkout). `docker compose **build**` from a worktree IS safe — the image tag is
  global and `name: arthayantra` + `container_name:` are pinned in the compose file — so split it: build
  in the worktree, then `up -d --force-recreate <svc>` **from the real checkout**. Plain `up -d` is not
  enough: it STARTS the already-`Created` poisoned container instead of replacing it. Confirm the fix with
  `docker inspect <c> | ConvertFrom-Json | %{$_.Mounts}` — the secret `Source` must be the real repo path.
- **Image build context differs per service** — `market-data-service` and
  `optimizer-service` Dockerfiles COPY repo-root paths (`deploy/dev-certs/`,
  `services/*/target/`) so they build with **repo-root context + `-f <dockerfile>`**
  (compose `context: ..`); edge-gateway/strategy-signal/backtest use a service-dir
  context. Keep CI image-build context in lockstep with compose. `deploy/dev-certs/`
  holds the AV CA (keytool/pip trust it); empty in CI/prod so the layer is a no-op.

## Git & line endings
- `.gitattributes` pins **`*.json eol=lf`** — byte-identical schema/golden-vector
  tests fail if JSON checks out CRLF on Windows. After adding an eol rule,
  `git add --renormalize`.
- Trunk-based: short-lived `feat/|fix/|chore/|docs/` branches, **Conventional Commits**
  (scope = service/lib name), **squash-merge only**, never push to `main`. A stage =
  one branch, one commit per phase, single final PR.
- **`git reset --hard origin/main` wipes uncommitted TRACKED edits** (untracked new files survive) —
  commit/stash a DIFFERENT in-flight feature BEFORE resetting to land another (lost the #2 edits once).
- **`gh pr merge && git pull` RACES the remote** — the pull can complete before the squash-merge
  lands, silently leaving main one commit stale; verify `git log origin/main -1` equals the PR's
  mergeCommit BEFORE building/deploying. And **never pipe a git command whose failure must stop a
  chain** (`git rebase 2>&1 | tail` exits with tail's 0 — a conflicted rebase then push ships a
  mid-rebase branch); check `git status -sb` for `## HEAD (no branch)` after any scripted rebase.
- **Branch cleanup is now automatic on the REMOTE, manual on this machine.** `delete_branch_on_merge`
  is ENABLED on the repo (set 2026-07-26), so GitHub deletes the head branch on every merge whoever
  does it — never hand-delete a remote branch again. Nothing server-side can touch this box, though,
  and the local leftovers are what actually bite (see the next bullet). After any merge run
  **`bash tools/git-prune-merged.sh`** (add `--dry` to preview): it removes worktrees + local
  branches whose upstream is GONE and whose tree is CLEAN, and refuses to touch `main`, a
  never-pushed local WIP branch, a dirty worktree, or a branch **whose own changes are not yet in
  main** — so the parked `worktree-agent-abb02bf43adbb895d` swing catch-up (1 genuinely unmerged
  commit) survives it. ⚠️ **That last clause used to read "still holding commits main lacks", and as
  written the script was INERT — it never deleted anything** (fixed 2026-07-30). Under SQUASH-merge
  the squash is a brand-new commit, so a merged branch's own commits are never ancestors of main and
  `git rev-list --count origin/main..<branch>` is ALWAYS > 0; since squash is this repo's only merge
  mode, the check vetoed 100% of candidates. It now compares **only the paths the branch touched**:
  a squash puts identical content on main, so that diff is empty however many commits it collapsed
  and however far main has moved on other files, while genuinely-unmerged work still differs on its
  own paths. Two plausible-looking alternatives are wrong and were measured: a two-dot
  `git diff origin/main..<branch>` conflates "behind main" with "has unmerged content", and
  `git cherry`/patch-id can't match because the squash collapsed N commits into one. If a LATER
  commit on main touched one of those paths the test is inconclusive, and it asks `gh` (authoritative)
  before keeping — the failure direction is deliberately a false KEEP, never a false delete.
  `tools/git-prune-merged-test.sh` pins all of it, including that the old check fails ONLY the
  squash case.
- **`gh pr merge --delete-branch` can fail its LOCAL step while the merge SUCCEEDS** — with a
  concurrent session holding `main` in a worktree it aborts with `fatal: 'main' is already used by
  worktree at …` and no other output. **Same failure when a worktree holds the BRANCH BEING MERGED**
  (`fatal: '<branch>' is already used by worktree at …`) — hit twice on 2026-07-26. Cheapest fix:
  `git worktree remove <path>` BEFORE merging, or just run `tools/git-prune-merged.sh` after. The squash-merge already landed server-side, so **never re-run
  the merge**: confirm with `gh pr view <n> --json state,mergeCommit`, then
  `git push origin --delete <branch>` by hand. Same cause blocks `git checkout main` in the primary
  checkout — cut new branches from `origin/main` directly instead.
- The **Bash tool is bash, not PowerShell** — PS here-strings (`@'…'@`) are taken
  literally and corrupt commit subjects; pass multi-line commit messages via
  `git commit -F -` with a heredoc.
- The **`guard-paths.py` PreToolUse hook resolves its path relative to the Bash cwd** —
  a persisted `cd <subdir>` makes every later Edit/Write fail (`can't open
  .../<subdir>/tools/claude/guard-paths.py`). Keep the Bash cwd at repo root, or subshell.
- ⚠️ **Same persisted cwd silently builds the WRONG CHECKOUT when a worktree is in play**
  (2026-07-26, #1044). A bare `./mvnw.cmd -pl <svc> -am verify` inherits whatever cwd the last
  command left, so one polling command that starts `cd <repo-root>` sends the NEXT build to the
  main checkout instead of the worktree — and it reports **BUILD SUCCESS for code you did not
  write**, which is worse than a failure because it reads as a passing gate. Always
  `(cd <worktree> && ./mvnw.cmd …)` in a subshell. Two tells if you suspect it: the compiler/
  checkstyle paths lack the worktree segment, and the run executes classes your branch deletes or
  renames. Related: `target/surefire-reports/` is NEVER pruned, so a renamed or deleted test class
  leaves its old report behind and any `cat *.txt | awk` tally **double-counts** — match each
  report to its full package path under `src/test/java`, or just read Maven's own reactor summary.
- CI runs on a **fresh compose stack + 2-core runner** — code green locally can still
  fail several CI iterations (cold start, constrained cores). Gate e2e readiness on
  container healthchecks, not gateway HTTP (a 401 is the gateway auth filter, not
  upstream readiness).
- **`--admin` is NO LONGER the normal way to merge (2026-07-26).** `main` carried
  `lock_branch: {"enabled": true}` — the branch was READ-ONLY — so with `enforce_admins: false`
  EVERY merge had to use `gh pr merge --admin`, which bypasses ALL required status checks (six at
  the time; nine since 2026-08-04) including a genuinely red one. It hid behind two unrelated, real
  bugs in the same row (task_db8bdf1e): a dead `frontend` required context, and path-filtered
  workflows never reporting — the same never-reports trap that later drove the `paths:` filter out
  of ci-java, ci-optimizer and ci-margin.
  Both were fixed first, and a plain `--squash` STILL returned `the base branch policy prohibits the
  merge` with every required context SUCCESS and `mergeable: MERGEABLE` — that mismatch (MERGEABLE
  but BLOCKED, no failing check) is the fingerprint of a branch-level lock, not a check problem.
  Owner ruled it accidental; `lock_branch` is now `false`. **Merge normally. If you find yourself
  reaching for `--admin`, something is actually wrong — read the failing check.** `hotfix/*` keeps
  its own fast-lane.
  ⚠️ **The protection API is a WHOLE-OBJECT `PUT`** — a partial payload silently drops every field
  you omit (required contexts, `strict`, force-push bans). Build it by mirroring the live `GET`
  field-by-field and **diff before/after** to prove only the intended key moved. Also check
  `GET /repos/{o}/{r}/rulesets` — rulesets are a SECOND, independent mechanism that can block a
  merge with classic protection looking clean (ours is `[]`).
- **Every non-`hotfix/*` PR body needs the anchored `Cross-vendor review:` verdict line** — the
  `verdict` check (`ci-review-verdict.yml`, reads the body LIVE so an edit + rerun fixes it).
  ⚠️ **`verdict` is NOT in branch protection's required contexts** (re-verified against the protection
  API 2026-08-04: the **NINE** required are `contracts`, `e2e`, `gitleaks`, the three `build-test`
  shards, `optimizer-lint-test` + `margin-lint-test` (added 2026-08-03 with #1252), and
  **`runbook-hygiene`** (added 2026-08-04 with #1298 — owner call, applied by the Architect). It was
  six when last checked 2026-08-01 and eight earlier on 2026-08-04; the count keeps moving, so
  **re-read the API rather than quoting any number here**. `verdict` is in none of those lists, and
  it failed red on #1156 and blocked nothing). The discipline is convention enforced by
  the Architect, not by GitHub; promoting it to required is a one-call owner decision. The check
  accepts `APPROVED`/`REQUEST_CHANGES (resolved)`/`NEEDS_REWORK (resolved)` each with
  `— <routed model> (<Vendor>)` model↔vendor PAIRED, or `SKIPPED (<reason>)`. Open builder PRs with
  `PENDING (...)` — red verdict until the review resolves is the DESIGN, not a failure. And with
  `strict: true` on main, a green PR goes `BEHIND` whenever main moves — fix with
  `gh api -X PUT repos/{o}/{r}/pulls/<n>/update-branch` (server-side, no local checkout needed),
  then let CI re-run; hit twice per evening on busy nights.
- **The `e2e` job's two former 2-core flakes are FIXED (#903, 2026-07-18)** — `tests/signals.spec.ts` +
  `tests/ws-reconnect.spec.ts` intermittently timed out on the cold-stack `/signals` table-settle for two
  reasons: while `GET /api/v1/signals` was pending the page showed a `qs-loading` skeleton the locator
  didn't match, and a stale `tr[role="button"]` locator matched NO real row (rows became plain `<tr>` in
  `<tbody>` after the M23 audit). Fixed by a `global-setup` readiness gate (warm `GET /api/v1/signals` to
  200, authenticated + best-effort, before any test), correct `table tbody tr` locators, and CI retries
  1→2. **If `e2e` reds now, INVESTIGATE — do not reflexively admin-merge it as a "known flake".** (Proven
  green 4× consecutively at merge.)
- **`build-images` is skipped on `pull_request` (#903)** — on a PR it only VALIDATED that the Dockerfiles
  build (the push is main-only), yet `needs: build-test` made it wait out the ~8 m market-data shard first;
  ~2.5 m of serial tail off every PR. The Dockerfile build still runs on the main-push (pre-deploy); it is
  NOT a required check. ⚠️ **"the ~8 m `build-test (market-data)` shard is the PR floor" held only until
  2026-08-03** (corrected 2026-08-04): #1252 gave the classifier one boolean PER SHARD, so a leg with no
  work runs zero steps and reports SUCCESS in seconds rather than running the suite. The floor is now
  whichever shard the PR actually touches — measured: #1290 (strategy-signal) paid `strategy-gateway`
  5m51s while `market-data` reported in 2s, and docs-only #1293 cleared all three in 2–6s. A `libs/` edit
  still sets ALL three booleans (the fail-safe direction is RUN), so a shared-lib PR does still pay the
  ~8 m market-data shard — and for that case a source-level module split (breaks the per-module JaCoCo
  BUNDLE gate as one shard) or a larger runner remain the only ways under it, both owner decisions.

## Where things live
- `services/` services · `libs/` shared libs · `deploy/` compose + flyway · `e2e/`
  Playwright · `contracts/` OpenAPI/schema · `docs/design/` design authority.
- `docs/superpowers/plans/` = active forward-work plans (NOT frozen design). The
  OpenAlgo-ecosystem + React-migration + strategy re-platform authority is
  `docs/superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md` —
  read §17 (Errata) + §18 (Gap Addendum) FIRST; they override §1–§16 on conflict.
- **"What's left to build?" — do NOT answer from the §0 tables alone.** Open items live in
  SIX places and a top-down §0 read has already produced a wrong "queue is empty" answer
  (2026-07-25: five blocked tunes + T9 + F-OPT + F-SYNC were all open with no §0 row). The
  enumeration recipe — six locations, the exact grep for each, the single-status rule and the
  promotion rule — is the block at the **top of §0** in
  `docs/superpowers/plans/2026-07-02-remaining-items.md`. Follow it every time. Two traps it
  encodes: `awk`, not `grep`, finds OPEN chips in §4b (the huge DONE cells swallow the match),
  and the whole **`T`-namespace** (tune/build proposals T1…T23) exists ONLY in the newest
  `docs/signal-analysis/*-session-findings.md` table unless someone promoted it to §0 group G.
- `.claude/skills/` = executable runbooks. **Start every non-trivial task with
  `fable-method`** (orient / decompose / classify / **§2a plan gate** / verify / decide-next),
  then the matching routine skill —
  `ship-a-change` (single-session), `delegated-ship` (subagent-builder pipeline for queue
  items / autonomous runs — the delegation model's executable form), `build-service`,
  `adversarial-review`, `swing-backtest`, `scalper-backtest`, `live-verify`, `arm-flag`,
  `daily-ops`, `session-analysis`, `run-artha-yantra`, `mock-walk`, `new-migration`,
  `research` (no-code spike → findings + BUILD/DEFER verdict), `write-tests` (author tests in
  our harness — naming/Testcontainers/parity/seam-ladder/coverage-debt), `hotfix` (live-incident
  fast-lane: snapshot-first, minimal fix, admin-merge, deploy + canary), `comprehensive-audit`
  (owner-triggered 360° platform audit — tiered sharded read-only analyst convergence → one signed
  doc in `docs/audits/`; analyst is an Opus subagent — a 13-shard Codex audit would eat the whole
  monthly ration) — instead of improvising inline.
- **Review + local-model lanes.** `claude-review` (Opus subagent, FRESH thread, DISTINCT lens) is
  the ONLY review path and judges `.claude/skills/codex/checklist.md` (path is historical; the
  checklist is vendor-neutral), emitting `APPROVED`/`REQUEST_CHANGES`/`NEEDS_REWORK`. Canonical
  order: testing gate → review round → Architect audit (final gate) → tiered promotion. ⚠️ **The
  review and the audit are two gates; the tier lenses inside `delegated-ship` are audit depth, NOT a
  review round.**
- **`local-model` skill** (`.claude/skills/local-model/`) = the two ollama models on this box,
  `qwen3.5:9b` (6.6 GB, 43 tok/s, interactive) and `qwen3.8:27b-q4_K_M` (17 GB, 2.6 tok/s,
  unattended). They exist for TOKEN BURN, never speed, and they never decide anything. Measured
  lanes: CI-failure digestion (5/5 both), psql dumps (5/5), service logs (q3.8 5/5, 9b 3/5), doc
  summaries, SQL drafts (run them and diff the rows), commit-message drafts, prod code from a tight
  spec on non-money surfaces (q3.8 8/8 vs hidden tests), and defect CANDIDATES before a review round.
  ⚠️ **Never a review verdict — seven models scored 0/2.** ⚠️ **Any generated test is worthless until
  red-proofed** — one emitted a 4/4-GREEN suite detecting neither of two planted bugs. ⚠️ **Read
  `local-model/PROMPTING.md` before writing any prompt for them**: on 2026-08-15 three capability
  verdicts flipped on a prompt change alone (review 0/2→1/2, psql 0/5→5/5), i.e. we were measuring
  our probes, not the models — and two wrong verdicts reached merged ledger entries before the owner
  caught them.
- **Codex suite — RATIONED 2026-08-15 (owner, $20/mo tier).** `.claude/skills/codex-*` + the
  `codex/` harness stay ENABLED but budget-gated: `codex-code-review` on money/parity/migration/
  live-engine PRE-merge, `codex-plan-review` on a size-gate plan if a slot is free. **Never** on
  builds (`delegated-ship` builds free), docs/mechanical, an advisory `codex-ask`, or merged code.
  A money/parity item WAITS for a slot with `Cross-vendor review: PENDING (awaiting rationed Codex
  slot)` — the red `verdict` check is the design; **two missed slots** → ship same-vendor and record
  it. ⚠️ Always via the harness, never a hand-rolled `codex exec` (the harness owns the sandbox
  decision). ⚠️ A failed resume leaves the PREVIOUS round's `review.txt` intact — compare its mtime
  before/after or you will read a confident review of the wrong revision. Full rules + the slot
  procedure: `.claude/skills/codex/ROUTING.md`.
