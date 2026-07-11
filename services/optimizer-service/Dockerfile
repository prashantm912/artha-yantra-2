# optimizer-service (§D.1): Python 3.12 + FastAPI + Optuna, internal port 8084.
# Build context is the repo root (see deploy/docker-compose.yml + ci-optimizer.yml)
# so the conditional dev-machine CA-trust layer can read deploy/dev-certs/.
FROM python:3.12-slim

WORKDIR /app

# Dev-machine CA trust for pip (TLS-intercepting AV such as Avast Web Shield):
# if deploy/dev-certs/ holds PEMs, pip verifies through the AV root; in CI and
# prod the directory is empty so pip verifies normally against the default bundle.
COPY deploy/dev-certs/ /tmp/dev-certs/
# requirements.lock is the hash-locked install set (generated from requirements.txt
# via `uv pip compile --generate-hashes`); --require-hashes blocks any supply-chain swap.
COPY services/optimizer-service/requirements.lock .
RUN set -e; \
    CERT_OPT=""; \
    if ls /tmp/dev-certs/*.pem >/dev/null 2>&1; then \
      cat /tmp/dev-certs/*.pem > /tmp/devca.pem; CERT_OPT="--cert /tmp/devca.pem"; \
    fi; \
    pip install --no-cache-dir --require-hashes $CERT_OPT -r requirements.lock; \
    rm -rf /tmp/dev-certs /tmp/devca.pem

COPY services/optimizer-service/app ./app
# The shared trial-metrics catalog (remaining-items §9 candidate 03) — the single source of truth for
# the rankable metric names the backtest emits; app/metrics_catalog.py resolves it by walking up to
# the nearest ancestor holding contracts/ (here the image WORKDIR /app, the repo root in dev/CI).
COPY contracts/metrics/trial-metrics-catalog.json ./contracts/metrics/trial-metrics-catalog.json

RUN useradd --create-home --uid 10001 artha
USER artha

EXPOSE 8084

HEALTHCHECK --interval=10s --timeout=3s --retries=12 --start-period=20s \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8084/health').read() else 1)" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8084"]
