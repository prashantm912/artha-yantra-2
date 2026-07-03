# Always-on host — decision brief (roadmap F5)

**Status:** DECISION NEEDED (owner). This brief exists so the hardware call is a 10-minute read.
Measured on the live stack 2026-07-03: live DB **32 GB** (mock 63 MB), Docker footprint **~86 GB**
(images 25 + volumes 36 + build-cache 22 + containers 3), 13 containers, **~3 GB RSS** running
(compose mem-limits sum ~9.3 GB), current laptop 32 GB RAM.

## Why (the problem being bought away)

- **Capture holes:** the 3-min OI chain capture and 1m tick-agg stop whenever the laptop lid
  closes — every hole is unrecoverable forward data (the platform's moat). This is the oldest
  standing constraint on the scalping-goal track.
- **Agent reliability:** the 09:42/15:47 Claude scheduled agents run only while the desktop app is
  open on an awake machine.
- **Operator freedom:** today the owner must babysit a laptop 09:00–15:45 every trading day; the
  Telegram bot (F6, live) only helps if the stack itself stays up.

## Requirements (from the running system)

| dimension | need | note |
|---|---|---|
| RAM | 16 GB comfortable, 32 GB = builds+stack together | limits sum 9.3 GB; Timescale alone capped 4 GB |
| Disk | 512 GB min, **1 TB right** | 86 GB today, DB grows (capture ~GBs/week; expired backfill spiked to 310 GB once) |
| CPU | 4c/8t fine for the stack; 8c+ if it also builds | CI runs remote; local builds optional on the box |
| Network | stable home broadband is enough | 3-min REST capture + 1 WS; latency is irrelevant at this cadence |
| Uptime | market hours + nightly backup window | UPS/inverter matters more than nines |
| Access posture | **loopback/LAN only stays the design** | remote reach via Tailscale/WireGuard, never a public port |

## The two owner-workflow constraints that decide this

1. **Kite daily login** is a browser redirect to `127.0.0.1` — wherever the gateway runs, the
   owner's browser must reach it. On a LAN box or over Tailscale this is identical to today
   (open `http://<box>:8080`, click login). Zero change.
2. **Claude agents** run in the desktop app. A **Windows mini-PC can run the Claude app itself**
   (agents + capture on the same always-on box — laptop becomes a pure client). A Linux VPS
   cannot; agents would need a re-plumb to headless `claude -p` cron or stay on the laptop
   (reintroducing the lid-close problem for the 15:47 run).

## Options

| | A. Windows mini-PC on LAN (recommended) | B. VPS (Mumbai) | C. Stay laptop |
|---|---|---|---|
| Hardware | Beelink/Minisforum class, 32 GB / 1 TB NVMe | 16 GB / 4 vCPU / 500 GB | — |
| Cost | **~₹35–50k one-time** (+ ~₹300/mo power) | ~₹5–9k/**month** → passes the mini-PC in <1 yr, disk growth billed forever | ₹0 |
| Architecture fit | Identical to today (Win11 + Docker Desktop + `ay.ps1`); restore via `ay backup/restore` (proven round-trip) | OS switch to Linux: `ay.ps1`→`ay.sh`, Docker native, PHC `$$` escaping revisit, machine-quirks re-learn | — |
| Kite login | Same as today via LAN/Tailscale | Same via Tailscale | Same |
| Claude agents | **On the box** — fully unattended days | Re-plumb to headless CLI cron, or stay laptop-bound | Laptop-bound (status quo pain) |
| Data locality | On-prem (broker tokens, trade data stay home) | Off-prem; encrypt disks, trust provider | On-prem |
| Failure modes | Home power/net (UPS + phone-hotspot failover cover most) | Provider outage (rare); billing creep | Lid close (daily!) |

**Recommendation: A.** One-time cost below one year of B, zero architectural change (the whole
Windows/compose/`ay.ps1` operating knowledge transfers as-is), and it is the ONLY option that
also makes the Claude agents unattended. B is the fallback if home power is unreliable even with
a UPS. C is what F5 exists to end.

## Migration runbook (option A, one weekend)

1. Box arrives: Win11 Pro (RDP), install Docker Desktop + git + JDK/Maven cache + Claude app +
   Tailscale; clone repo.
2. Copy `deploy/secrets/*` + `.env` (hand-carry, never commit); trust the AV CA if any.
3. `ay backup` on laptop → copy dump → `ay restore` on box (whole-db pipeline, round-trip proven
   #395) → `ay up` live.
4. Kite login from any browser at `http://<box>:8080`; verify canaries GREEN, capture accruing.
5. Move the two scheduled agents: open Claude on the box, recreate `live-data-health-check` +
   `post-market-session-analysis` (prompts live in `~/.claude/scheduled-tasks/` — copy the two
   SKILL.md files).
6. One parallel session both machines up (laptop stack STOPPED after cutover — two writers is the
   mock/live-pollution class of mistake), then laptop demotes to browser + dev box.
7. Nightly `ay backup` target: keep writing to a share the laptop syncs, or a cloud drive on the
   box — off-box survival is the point (audit P0-5 posture unchanged).

## Decision asks (owner)

- [ ] Option A / B / C.
- [ ] If A: budget band OK (~₹35–50k)? UPS present at home?
- [ ] If A: buy list can be drafted on ask (no purchase without explicit go).
