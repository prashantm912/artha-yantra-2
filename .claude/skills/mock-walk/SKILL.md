---
name: mock-walk
description: Use to run an ArthaYantra stage manual-testing walk on the mock stack — bring up/confirm the mock stack, rebake any changed services, backfill the benchmark, then run the Playwright e2e suite (the automated companion to docs/manual-tests/archive/manual-testing-stage-*.md).
disable-model-invocation: true
---

# mock-walk

Runs a stage walk on the **mock stack with zero Kite credentials**, end to end. The
automated companion to `docs/manual-tests/archive/manual-testing-stage-*.md`. The app is same-origin through the
gateway at `http://127.0.0.1:8080` (owner password is the `.env` hash — `MyPassword123` in
the usual mock setup).

> The **guard hook resolves relative to the Bash cwd** — keep every Bash call at the repo
> root (`C:\Trading\ArthaYantra\artha-yantra-2`) or subshell; a persisted `cd <subdir>`
> breaks later Edit/Write.

## 1. Confirm (or bring up) the mock stack

```bash
docker ps --format '{{.Names}}\t{{.Status}}'   # is a stack already up?
```
- `ay-wiremock` + `ay-frontend-ui` present and `market-data-service` **healthy** ⇒ it's the
  **mock** stack (the live profile fail-fasts at boot without Kite secrets, so a healthy
  market-data proves mock). Reuse it — do **not** tear it down.
- Nothing up ⇒ `.\ay.ps1 up` then `.\ay.ps1 status` (needs `.env` = `SPRING_PROFILES_ACTIVE=mock`).
  PASS when every container `(healthy)` incl. `ay-wiremock`/`ay-frontend-ui` and
  `ay-flyway-init` `Exited (0)`.

## 2. Rebake ONLY if you changed stage code

Service images **COPY pre-built `target/*.jar` + frontend `dist/`** — they are not built
in-Docker. So after a code change you must repackage *then* rebuild the image, else the stale
artifact bakes in (see [build-service]):

```bash
MVN=$(ls ~/.m2/wrapper/dists/apache-maven-*/*/bin/mvn | head -1)
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" \
  "$MVN" -pl services/<changed-svc> -am package -DskipTests      # full reactor + -am
( cd frontend-ui && npm run build )                              # only if UI changed
docker compose -f deploy/docker-compose.yml --env-file .env build <changed-svc>
docker compose -f deploy/docker-compose.yml --env-file .env up -d <changed-svc>
# wait for health: until [ "$(docker inspect -f '{{.State.Health.Status}}' ay-<svc>)" = healthy ]; do sleep 3; done
```

## 3. Log in + backfill the benchmark (needed by the backtest/regime steps)

The regime pre-flight needs ~272 daily `NSE:"NIFTY 50"` sessions; warm them cache-first
(re-run until `total ≥ 272` — the daily cagg materializes lazily):

```bash
cd /tmp
curl -s -c ay.jar -d "password=MyPassword123" http://127.0.0.1:8080/api/v1/auth/login -o /dev/null
curl -s -b ay.jar "http://127.0.0.1:8080/api/v1/market/candles?exchange=NSE&tradingsymbol=NIFTY%2050&interval=1d&from=2024-01-01T00:00:00%2B05:30&to=2026-12-31T00:00:00%2B05:30&limit=5000" | jq '.total'
```

## 4. Run the Playwright suite (the automated guide companion)

```bash
cd e2e && E2E_OWNER_PASSWORD=<your .env owner pw> npx playwright test --reporter=list
```
- **You must pass `E2E_OWNER_PASSWORD`** to match your running stack's hash — the helper
  defaults to `e2e-owner-password`, which won't match a `MyPassword123` stack.
- `global-setup` **reuses a healthy stack** and **won't overwrite an existing `.env`**, so it
  runs against exactly the stack from step 1.

## 5. Spot-check the visual/curl-only steps the headless suite can't assert

Walk the remaining `docs/manual-tests/archive/manual-testing-stage-*.md` steps by hand or curl — e.g. indicator
overlays (`/api/v1/indicators/{id}/series?...`), the WireMock notifier count
(`curl -s http://127.0.0.1:9099/__admin/requests | jq '[.requests[]|select(.request.method=="POST")]|length'`).
Fix any step whose response isn't the guide's expected output, then re-verify.

## Notes
- Don't read/write secret material (`.env`, `deploy/secrets/*`, `deploy/dev-certs/*`) — the
  guard hook denies it. Back up/restore `.env` via `cp` only, never by reading its contents.
- See [build-service] for the Maven/AV-truststore and `-am` rules. Candle sources split by
  interval: caggs (`candles_<iv>`) are sparse on a fresh boot; native daily lives in
  `candles`@1d.
