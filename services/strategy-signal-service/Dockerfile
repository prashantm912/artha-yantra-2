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

ENTRYPOINT ["java", "-Xmx448m", "-XX:+UseSerialGC", "-jar", "/app/strategy-signal-service.jar"]
