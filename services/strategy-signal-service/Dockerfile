# strategy-signal-service runtime image (Phase 21). Jar is host-built by the
# reactor before compose build (same pattern as the other services). Non-root,
# wget healthcheck, explicit heap cap under the 640 MB compose mem_limit.
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S artha && adduser -S artha -G artha
USER artha
WORKDIR /app

COPY target/strategy-signal-service.jar /app/strategy-signal-service.jar

EXPOSE 8082
HEALTHCHECK --interval=10s --retries=10 --start-period=30s \
  CMD wget -qO- http://127.0.0.1:8082/actuator/health | grep -q '"status":"UP"' || exit 1

# Explicit G1 (audit P2): SerialGC paired stop-the-world single-threaded collections with
# the tick-normalizer / signal-eval hot paths — full GCs froze the whole JVM during bar
# bursts. The flag must be explicit: below ~1792MB visible RAM the JVM's ergonomics
# still pick SerialGC, so merely dropping -XX:+UseSerialGC changed nothing (live-verified
# via PrintFlagsFinal: "UseSerialGC = true {ergonomic}").
ENTRYPOINT ["java", "-Xmx448m", "-XX:+UseG1GC", "-jar", "/app/strategy-signal-service.jar"]
