#!/usr/bin/env python3
"""Published-config drift sweep — is every strategy running the config the repo says?

Why this exists: a scalper YAML change is a SILENT NO-OP until republished (the seeder
mints drafts, never publishes), and the reverse also happens — a strategy left behind by
a batch republish keeps running a months-old config. task_76d8f2a4 recorded exactly
that: `scalp-trend-change-sensex-sensexoi-pe` published 1.0.1 while its YAML had gained
`relative-volume-floor` and the T21 exit block. The pre-publish diff only fires when
someone publishes; silent staleness needs a sweep.

Compares, for every strategy with a published version:
  - published `tags` vs the repo YAML's `tags` (ORDERED — first tag is the paper book)
  - published `exit_rules` vs the repo YAML's (ordered, per-rule normalized JSON)
  - whether the LATEST version row is the published one (a newer draft/published row
    means someone changed something that is not live — the #1016 signal, judged by
    "latest row != published_version_id", never "latest draft != published")

Read-only. DB access via `docker exec ay-timescaledb psql` (live DB, SELECT only).
Diffs are reported as GAINS/LOSES per CLAUDE.md's republish doctrine, so the output is
directly actionable: republish only after judging what the strategy would gain vs lose.

Exit code: always 0 (reporting aid; the post-market routine copies findings into the
findings doc and proposes republishes — it never publishes anything itself).
"""

from __future__ import annotations

import sys as _sys

# Windows consoles default stdout to cp1252; the docs these scan are UTF-8 (arrows, section
# marks). Reconfigure here so the ROUTINE does not have to remember PYTHONIOENCODING — this
# exact trap cost two debugging rounds on 2026-07-29/30.
if hasattr(_sys.stdout, "reconfigure"):
    _sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import glob
import io
import json
import os
import subprocess
import sys

import yaml

QUERY = """
SELECT json_build_object(
  'slug', s.slug,
  'enabled', s.enabled,
  'published_version', pv.version,
  'published_tags', pv.config->'tags',
  'published_exits', pv.config->'exit_rules',
  'latest_is_published', (lv.id = s.published_version_id),
  'latest_version', lv.version,
  'latest_status', lv.status,
  'latest_created', lv.created_at::date)
FROM strategy.strategies s
JOIN strategy.strategy_versions pv ON pv.id = s.published_version_id
LEFT JOIN LATERAL (
  SELECT id, version, status, created_at
  FROM strategy.strategy_versions v WHERE v.strategy_id = s.id
  ORDER BY created_at DESC LIMIT 1) lv ON true
WHERE s.published_version_id IS NOT NULL
ORDER BY s.slug;
"""

YAML_GLOB = "services/strategy-signal-service/src/main/resources/*strategies/*.yaml"


def db_rows() -> list[dict]:
    proc = subprocess.run(
        ["docker", "exec", "ay-timescaledb", "psql", "-U", "artha", "-d", "artha",
         "-t", "-A", "-c", QUERY],
        capture_output=True, text=True, encoding="utf-8")
    if proc.returncode != 0:
        print(f"DB query failed: {proc.stderr.strip()[:300]}", file=sys.stderr)
        sys.exit(0)  # stack down = skip gracefully, same doctrine as the routine
    return [json.loads(line) for line in proc.stdout.splitlines() if line.strip()]


def yaml_by_slug() -> dict[str, dict]:
    out: dict[str, dict] = {}
    for path in glob.glob(YAML_GLOB):
        slug = os.path.splitext(os.path.basename(path))[0]
        try:
            with io.open(path, encoding="utf-8") as f:
                out[slug] = yaml.safe_load(f) or {}
        except Exception as e:  # a broken YAML is itself a finding
            out[slug] = {"__parse_error__": str(e)[:120]}
    return out


def norm_rules(rules) -> list[str]:
    """Ordered, per-rule canonical JSON — order is load-bearing (first stop_loss wins)."""
    return [json.dumps(r, sort_keys=True) for r in (rules or [])]


def main() -> int:
    rows = db_rows()
    yamls = yaml_by_slug()
    findings: list[str] = []
    clean = 0

    for row in rows:
        slug = row["slug"]
        y = yamls.get(slug)
        marks: list[str] = []

        if not row.get("latest_is_published", True):
            marks.append(
                f"STALE-PUBLISH: published {row['published_version']} but the latest version row is "
                f"{row['latest_version']} ({row['latest_status']}, {row['latest_created']}) — "
                f"something newer exists that is NOT live")

        if y is None:
            # DB-only strategy (UI-created); no repo YAML to drift from
            pass
        elif "__parse_error__" in y:
            marks.append(f"YAML-PARSE-FAIL: {y['__parse_error__']}")
        else:
            db_tags = row.get("published_tags") or []
            y_tags = y.get("tags") or []
            if db_tags != y_tags:
                gains = [t for t in y_tags if t not in db_tags]
                loses = [t for t in db_tags if t not in y_tags]
                detail = []
                if gains:
                    detail.append(f"republish GAINS {gains}")
                if loses:
                    detail.append(f"republish LOSES {loses}")
                if not detail:
                    detail.append(f"ORDER differs (first tag = paper book!): db={db_tags[:3]}... yaml={y_tags[:3]}...")
                marks.append("TAGS-DRIFT: " + "; ".join(detail))
            db_exits = norm_rules(row.get("published_exits"))
            y_exits = norm_rules(y.get("exit_rules"))
            if db_exits != y_exits:
                gains = [r for r in y_exits if r not in db_exits]
                loses = [r for r in db_exits if r not in y_exits]
                detail = []
                if gains:
                    detail.append(f"republish GAINS {gains}")
                if loses:
                    detail.append(f"republish LOSES {loses}")
                if not detail:
                    detail.append("ORDER differs (first stop_loss is the initial-risk basis)")
                marks.append("EXIT-DRIFT: " + "; ".join(detail))

        if marks:
            flag = "ENABLED " if row.get("enabled") else "disabled"
            findings.append(f"{slug} [{flag}, published {row['published_version']}]")
            findings.extend(f"    {m}" for m in marks)
        else:
            clean += 1

    print(f"published-config-drift: {len(rows)} published strategies checked, "
          f"{clean} clean, {len(rows) - clean} with findings "
          f"({len(yamls)} repo YAMLs)")
    if findings:
        print("\n".join(findings))
        print("\nRepublish doctrine (CLAUDE.md): judge each by what it GAINS vs LOSES; "
              "pick candidates by 'latest row != published_version_id', and NEVER republish "
              "without diffing — a republish can REVERT armed tags.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
