# INT I4 — insight delivery arming (ledger C8)

The intelligence layer has run in SHADOW since 2026-07-12: rows + read APIs, **no push delivery**.
I4 turns delivery on. Owner has decided both open questions; implement them, do not re-derive.

## Owner decisions

1. **Severity floor = NOTICE and above.** NOTICE + WARN + CRITICAL deliver; **INFO never pushes**
   (it stays in-app). Measured from the live shadow table over the last 7 days, that is
   ~3.6 pushes/day — NOTICE 1.9, CRITICAL 1.3, WARN 0.4, none of them currently suppressed. Raw INFO
   is 32.7/day and **400 of its 413 all-time rows are `suppressed`**, which is the evidence for
   leaving it out rather than a guess.
2. **The calibration half of I4 is NOT in scope.** The design gates priority-weight tuning on
   "4+ weeks of quality reports" and type retirement on "dismiss-rate evidence"; there are 14 days of
   data. **Leave the five priority weights and the type set exactly as they are.** Earliest honest
   date for that pass is ~2026-08-09. Do not touch `insights.priority.*`.

## Scope

- Wire insight publication to the existing notifier delivery path, gated on the severity floor.
  `InsightPublisher` and `NotificationEventsRepository` already exist — extend the existing seam
  rather than building a parallel one, and reuse whatever ntfy/Telegram client the signal path
  already uses (do NOT introduce a second notifier client).
- Config: a severity-floor knob and per-channel on/off, DEFAULT to the decided floor.
  `insights.delivery.ws` already exists as a YAML-only flag whose own comment says the compose
  passthrough lands at I4 — so **this is the PR that adds the `${ENV}` placeholders and the compose
  passthroughs** for the delivery flags.
  ⚠️ Every `application.yml` `${ENV_NAME}` must match the compose passthrough AND `.env.example`
  **EXACTLY** — a mismatch silently swallows the override with no error (#653). Grep all three.
- Every delivered insight must write its `notification_events` audit row, same as the signal path.
- **Mutes must work.** A per-type (and per-strategy where the insight is strategy-scoped) mute must
  suppress delivery without suppressing the in-app row. The owner needs a way to silence one noisy
  type on Monday morning without redeploying.
- Respect the existing `suppressed` flag and `cooldown_until` — a suppressed or cooling insight must
  never deliver. That machinery already works (it is eating 97% of INFO); consume it, do not
  reimplement it.

## Constraints

- **Modulith:** `notifier` imports `signals`, so signals-side code must NEVER import notifier — alert
  via an in-process event record + an `@EventListener` in notifier (`DotInputAlert`/`DotAlertListener`
  is the template). Check which module `insights` sits in and respect the same direction.
- Any new endpoint returns a typed record, never `Map<String,Object>` (`MapReturnRatchetTest` freezes
  the Map-handler count per service), and needs the edge-gateway `Path=` allowlist entry or the
  gateway serves the SPA index.html. Prefer NOT adding an endpoint.
- Tests `*Test` / `*IntegrationTest` only — no failsafe plugin, `*IT.java` is silently never run. ITs
  share ONE singleton Testcontainers DB with NO per-method cleanup.
- Build with the FULL reactor + `-am`.

## Receipt must include

A **dry-run count**: given the insights already in the table, how many would have delivered in the
last 7 days at the configured floor, broken down by severity and type. The owner is arming this — that
number is what they will check on Monday, and it should land close to ~3.6/day.

EDIT-ONLY: no commit, branch, push, PR, deploy or arming. The Architect owns the commit and the flag.
