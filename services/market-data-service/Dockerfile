# market-data-service runtime image (A.7a). Jar is host-built by the reactor
# before compose build (same pattern as edge-gateway). Non-root, wget
# healthcheck, explicit heap cap under the 640 MB compose mem_limit.
FROM eclipse-temurin:21-jre-alpine

# Dev-machine CA trust (TLS-intercepting AV such as Avast Web/Mail Shield).
# The PEM is generated once per dev machine by the setup script and lives in
# deploy/dev-certs/ (gitignored). In CI and prod this directory is empty so
# the COPY is a no-op and the layer is skipped by BuildKit.
COPY --chown=root:root deploy/dev-certs/ /tmp/dev-certs/
RUN set -e; \
    for pem in /tmp/dev-certs/*.pem; do \
      [ -f "$pem" ] || break; \
      alias=$(basename "$pem" .pem); \
      keytool -importcert -noprompt -trustcacerts \
        -alias "dev-$alias" \
        -file "$pem" \
        -keystore "$JAVA_HOME/lib/security/cacerts" \
        -storepass changeit; \
    done; \
    rm -rf /tmp/dev-certs

RUN addgroup -S artha && adduser -S artha -G artha
USER artha
WORKDIR /app

COPY services/market-data-service/target/market-data-service.jar /app/market-data-service.jar

EXPOSE 8081
HEALTHCHECK --interval=10s --retries=10 --start-period=30s \
  CMD wget -qO- http://127.0.0.1:8081/actuator/health | grep -q '"status":"UP"' || exit 1

# Explicit G1 (audit P2): SerialGC paired stop-the-world single-threaded collections with
# the tick-normalizer / signal-eval hot paths — full GCs froze the whole JVM during bar
# bursts. The flag must be explicit: below ~1792MB visible RAM the JVM's ergonomics
# still pick SerialGC, so merely dropping -XX:+UseSerialGC changed nothing (live-verified
# via PrintFlagsFinal: "UseSerialGC = true {ergonomic}").
ENTRYPOINT ["java", "-Xmx448m", "-XX:+UseG1GC", "-jar", "/app/market-data-service.jar"]
