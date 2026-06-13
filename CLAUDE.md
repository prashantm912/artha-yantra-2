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

## Build & test
- **Integration tests must be named `*IntegrationTest` or `*Test`** — there is **no
  failsafe** plugin configured; `*IT` classes are silently skipped (never run).
- **Build services with the full reactor + `-am`**
  (`-pl services/<svc> -am package -DskipTests`), never a bare `-pl` on a leaf lib —
  a `-pl` install skips parent POMs and nested lib submodules
  (`libs/common-web/servlet`, `libs/black76-math`), so the compose fat JAR silently
  embeds a stale lib.
- IT harness: singleton Testcontainers (Timescale 2.17.2-pg17 + redis 7.4), real
  Flyway lineages, `@DynamicPropertySource` for `currentSchema`. Services connect to
  Postgres as `artha` (D10 single-writer by convention); per-schema roles like
  `ay_backtest` are read-only, asserted via SET ROLE grant tests.
- JaCoCo gate ≥ 60% line on services; Modulith `verify` runs in CI.
- **Mock-stack backtest testing:** candle data is real-time/rolling (accrues from
  boot) — derive a recent covered window, never hardcode dates; every windowed run's
  regime pre-flight needs ~272 daily benchmark sessions, so backfill `NIFTY 50` 1d
  via cache-first GET `/api/v1/market/candles` first. Results/trades/folds/montecarlo
  are keyed by the **run id** (the job's `resultRef`), not the jobId.

## Database / migrations
- **Applied Flyway migrations are checksum-locked** in the dev stack and CI — editing
  an applied migration (even a comment) fails `flyway validate` / flyway-init.
  Corrections go in a **new suffix-versioned migration**, never an in-place edit.
- `ay reset-db` drops volumes and rebuilds all four schema lineages from empty.

## Docker / compose
- **Never invoke `docker compose` directly without `--env-file .env`** — compose
  resolves `.env` relative to `deploy/` and silently blanks vars (e.g. the owner
  password hash → gateway 401). Use the **`ay` / `ay.ps1` CLI**, which always passes
  it. Project-scoped compose only — **never `docker kill`**.
- Mock vs live is `SPRING_PROFILES_ACTIVE` in `.env`, orthogonal to compose profiles;
  mock needs zero secrets. PHC password hashes in `.env` need every `$` escaped `$$`.
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
- The **Bash tool is bash, not PowerShell** — PS here-strings (`@'…'@`) are taken
  literally and corrupt commit subjects; pass multi-line commit messages via
  `git commit -F -` with a heredoc.
- CI runs on a **fresh compose stack + 2-core runner** — code green locally can still
  fail several CI iterations (cold start, constrained cores). Gate e2e readiness on
  container healthchecks, not gateway HTTP (a 401 is the gateway auth filter, not
  upstream readiness).

## Where things live
- `services/` services · `libs/` shared libs · `deploy/` compose + flyway · `e2e/`
  Playwright · `contracts/` OpenAPI/schema · `docs/design/` design authority.
