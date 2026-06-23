# frontend-react runtime image (React cutover): nginx 1.27-alpine serving the host-built
# Vite dist (same host-build pattern as frontend-ui + the Java services). Vite emits a FLAT
# dist/ (no nested <project>/browser/ like Angular), so the COPY source differs. ~32 MB,
# pre-compressed assets via gzip_static when present, /healthz probe.
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=10s --retries=6 --start-period=5s \
  CMD wget -qO- http://127.0.0.1/healthz | grep -q ok || exit 1
