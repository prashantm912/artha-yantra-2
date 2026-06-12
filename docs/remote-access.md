# Remote access (A.1.2 / Q3 final design)

## Default posture

The edge-gateway's single published port is **hardcoded
`127.0.0.1:8080:8080`** in `deploy/docker-compose.yml` — deliberate (D13) and
**not parameterized** by env vars (a stale `.env` line must never silently
rebind on `ay up`). Host exposure is decided **solely by the compose `ports:`
mapping**, never by an in-container `server.address` (inside its namespace the
gateway listens on all interfaces; loopback-binding the process would break
the published port). `timescaledb 127.0.0.1:5432` is the only other published
port (dev tooling). **`0.0.0.0` is never a sanctioned value.**

## Phone/tablet: Tailscale-first (Q3)

```
tailscale serve --bg http://127.0.0.1:8080
```

on the Windows host reverse-proxies the tailnet to the gateway, so a phone
reaches `https://<machine>.<tailnet>.ts.net` with automatically
issued/renewed certificates and working WebSocket proxying — no rebind, no
certificates to manage, nothing exposed beyond the WireGuard mesh. The
gateway honors `X-Forwarded-Proto: https` from the proxy to mark the session
cookie `Secure`.

## LAN exposure (deliberate threat-model change only)

LAN access without Tailscale is possible **only** via an explicit
`compose.lan.yaml` override file that **atomically couples** the LAN-IP
publish with a mounted mkcert certificate — an owner-acknowledged
threat-model change requiring a Section-11 review **before use**. It is never
an env knob in the default file. mkcert/TLS-at-the-gateway as a *default* is
out of scope.

## CORS / TLS

CORS stays **disabled in every variant** — the SPA is served through the
gateway, so any-hostname access remains same-origin (A.2.5). Plain HTTP on
localhost is acceptable: traffic never leaves loopback; Tailscale provides
TLS for the phone path.
