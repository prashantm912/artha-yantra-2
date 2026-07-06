---
name: arm-flag
description: Use when arming, un-arming, or tuning a live feature flag or strategy knob in ArthaYantra — .env flag flips (ARTHA_* vars), compose passthroughs, and strategy-config tag arming via draft+publish. Owner-gated — only on the owner's explicit ask.
---

# arm-flag

Two arming planes, different mechanics. **Arming anything that changes live behaviour is
owner-gated** — do it only on an explicit ask, and record the un-arm path in the same
message. All shipped features default OFF; arming is config, never a code change.

## Plane 1 — `.env` flags (`ARTHA_*` env vars)

Examples: `ARTHA_MINERVINI_SWING_ENABLED`, `ARTHA_MANAS_ARORA_PYRAMID_ENABLED`,
`ARTHA_PAPER_RISK_ENABLED`, `artha.scalper.oi.relativeVolume*` knobs.

1. **Passthrough check first**: the var must be listed in the service's `environment:`
   block in `deploy/docker-compose.yml`, else the flip is silently inert. Missing → that
   is a (clean-tier) code PR before any arming.
2. **Edit `.env` without reading it into context** (it holds secrets):
   ```bash
   grep -c '^ARTHA_MY_FLAG=' .env                      # present?
   sed -i 's/^ARTHA_MY_FLAG=.*/ARTHA_MY_FLAG=true/' .env    # flip existing
   printf 'ARTHA_MY_FLAG=true\n' >> .env                    # or append if absent
   ```
   Never `cat`/Read `.env`. No inline `# comments` on the value line (they parse as part
   of the value). PHC hashes need every `$` escaped as `$$`.
3. **Recreate the one service** (env changes need a container recreate, not a rebuild):
   ```powershell
   $env:ARTHA_DB_NAME='artha'; $env:ARTHA_REDIS_DB='0'
   docker compose -f deploy\docker-compose.yml --env-file .env up -d <svc>
   ```
4. **Verify armed from behaviour** ([live-verify]): the boot log line printing the
   config, or the first run showing the armed path. Never assume from the file edit.
5. **Un-arm** = flip back + `up -d` again. State this path when arming.

## Plane 2 — strategy-config tags (published YAML)

Scalper gates arm from **PUBLISHED strategy config tags** (`cfg.has(tag)` default-OFF
pattern), not from the identity row and not from env. The live engine loads
`enabled && publishedVersionId != null`.

Routine (via the sss-publish sidecar, `http://127.0.0.1:8082`, no auth):
1. `GET /api/v1/strategies` → find the UUID; `GET /api/v1/strategies/{id}/versions` →
   latest published version's YAML.
2. Add/remove the gate tag (+ params) in the YAML → create a new **draft** version.
3. `POST /api/v1/strategies/{id}/publish` → the engine reloads on the new published
   config. (Precedent: relative-vol floor #605 armed on all 21 NIFTY scalpers this way.)
4. Verify: next session's `signal_rejections` rows show the new gate/threshold in the
   breakdown (e.g. a relative threshold instead of the absolute 125000).
5. Un-arm = publish another version without the tag.

## After arming

Log it: memory topic (which flag, date, owner directive, un-arm path) + the remaining-items
ledger if it closes an item. If the first real exercise is a scheduled batch/session,
schedule a durable verification ([daily-ops]) with a bug-vs-correct discriminator.
