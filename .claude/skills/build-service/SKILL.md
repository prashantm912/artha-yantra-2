---
name: build-service
description: Use when building, compiling, packaging, or producing a JAR or Docker image for an ArthaYantra Maven service or lib, or when `./mvnw` fails to download Maven (TLS-intercepting AV on this box).
---

# build-service

Build an ArthaYantra service/lib correctly on this machine. Two traps this avoids:
`./mvnw` tries to **curl-download** Maven and the local TLS-intercepting AV blocks it;
and a bare `-pl` on a leaf module skips parent POMs + nested lib submodules, so the
compose fat JAR silently embeds a **stale** lib.

## Command

Always build the **full reactor with `-am`** so nested libs (`libs/common-web/servlet`,
`libs/black76-math`, …) are rebuilt and installed before the service is packaged:

```bash
# use the Maven the wrapper already cached (skips the AV-blocked download)
MVN=$(ls ~/.m2/wrapper/dists/apache-maven-*/*/bin/mvn 2>/dev/null | head -1)
[ -z "$MVN" ] && MVN=./mvnw   # fall back to the wrapper if nothing is cached yet

# Windows-ROOT truststore lets the JVM trust the AV's intercepting CA
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" \
  "$MVN" -pl services/<service-name> -am package -DskipTests
```

Build the container image afterward via compose (never a bare `docker compose` — it
needs `--env-file`):

```bash
docker compose -f deploy/docker-compose.yml --env-file .env build <service>
# or just `./ay.ps1 up` / `./ay.sh up`, which rebuilds changed services
```

## Notes
- **Modules:** `services/{edge-gateway,market-data-service,strategy-signal-service,backtest-service}`,
  `libs/{black76-math,common-web,market-calendar,strategy-engine,strategy-schema}`.
- **Run tests:** drop `-DskipTests`. Integration tests must be named `*IntegrationTest`
  or `*Test` — there is no failsafe plugin, so `*IT` classes are silently skipped.
- **PowerShell 5.1:** never pipe the build into `Select-Object -First N` — it kills Maven
  mid-build (exit 255). Capture to a variable or use `-Last`. Quote dotted props
  (`"-Dcheckstyle.skip=true"`).
