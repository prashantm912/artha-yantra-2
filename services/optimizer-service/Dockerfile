# optimizer-service (§D.1): Python 3.12 + FastAPI + Optuna, internal port 8084.
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

RUN useradd --create-home --uid 10001 artha
USER artha

EXPOSE 8084

HEALTHCHECK --interval=10s --timeout=3s --retries=12 --start-period=20s \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8084/health').read() else 1)" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8084"]
