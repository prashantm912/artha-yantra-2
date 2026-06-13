# backtest-service runtime image (Stage D Phase 28). Jar is host-built by the
# reactor before compose build (same pattern as the other services). Non-root,
# wget healthcheck, explicit heap cap under the 896 MB compose mem_limit
# (~400 MB idle, sweep burst — D1). GC/RAM tuning is deferred to Stage G.
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S artha && adduser -S artha -G artha
USER artha
WORKDIR /app

COPY target/backtest-service.jar /app/backtest-service.jar

EXPOSE 8083
HEALTHCHECK --interval=10s --retries=10 --start-period=30s \
  CMD wget -qO- http://127.0.0.1:8083/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-Xmx640m", "-XX:+UseSerialGC", "-jar", "/app/backtest-service.jar"]
