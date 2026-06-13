# frontend-ui runtime image (Phase 25): nginx 1.27-alpine serving the
# host-built dist (same host-build pattern as the Java services). ~32 MB,
# pre-compressed assets via gzip_static when present, /healthz probe.
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/frontend-ui/browser /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=10s --retries=6 --start-period=5s \
  CMD wget -qO- http://127.0.0.1/healthz | grep -q ok || exit 1
