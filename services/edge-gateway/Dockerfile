# edge-gateway runtime image (A.2). The fat jar is built by the reactor
# (`mvnw -pl services/edge-gateway -am package`) before `docker compose
# build` — both locally and in CI the same host-built artifact is copied in,
# so the image build needs no network. Non-root, wget healthcheck, explicit
# heap cap under the 512 MB compose mem_limit. (AOT/AppCDS land in Stage G
# hardening, Phase 47.)
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S artha && adduser -S artha -G artha
USER artha
WORKDIR /app

COPY target/edge-gateway.jar /app/edge-gateway.jar

EXPOSE 8080
HEALTHCHECK --interval=10s --retries=10 --start-period=30s \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/edge-gateway.jar"]
