# M39 VCP-caps decision — 2026-07-06 (deploy-free geometry analysis)

The queued post-close backtest A/B for PR #591's M39 VCP base-depth/duration caps was resolved
**without a backtest** — a direct query of the live geometry table is decisive.

## Method
The M39 caps reject a VCP base if `deepest_pct >= 60` OR `base_weeks` outside `[3, 65]`. Ran that
predicate against the current live VCP setups (`marketdata.minervini_setups`, latest screen 2026-07-03,
`is_vcp = true`, n=106 — detected by the main-branch VcpDetector, i.e. WITHOUT the caps).

## Result — M39 as written would reject 105 of 106 VCP setups
| metric | value |
|---|---|
| VCP setups (latest screen) | **106** |
| rejected by depth cap (≥60%) | **0** (avg depth 8.3%, max 17.4% — never near 60%) |
| rejected by duration floor (weeks <3 or >65) | **105** |
| **rejected by ANY M39 cap** | **105 / 106 (99%)** |
| base_weeks: avg / min / max | **0.7 / 0 / 6** |

## Verdict: do NOT merge #591's M39 as-is
- The **depth cap (60%) is fine** — it never fires on the current geometry (bases are shallow, ~8%).
- The **`min_base_weeks=3` floor is mis-calibrated**: this detector measures SHORT consolidations
  (`base_weeks` avg 0.7, max 6 — i.e. days-to-a-few-weeks), not the classical 7–65-week VCP base the
  cap assumes. A 3-week floor therefore rejects ~99% of VCP passers → the `vcp` / `cheat_3c` /
  `power_play` setups (all gate on `isVcp`) would almost never fire live. The backtest A/B would only
  confirm this (caps-on ≈ zero VCP trades).

## Recommendation
Before merging #591, either:
1. **Drop / lower the duration floor** — set `min_base_weeks` to 0 or 1 (keep the ≥60% depth reject +
   the 65-week ceiling, which are harmless), OR
2. **Fix the detector's base-length measurement first** — if the intent is the classical multi-week
   base, `VcpDetector`'s `base_weeks = round(durationDays/5)` is measuring only a short leg; align the
   detector to the doctrine, THEN apply the 3-week floor.

Keep M35 (liquidity 50×) and M12 (RS tie-break) from #591 regardless — those are unrelated and correct.

*Resolved deploy-free (no market-data rebuild, no 20:00-batch risk). The scheduled 15:38 backtest task
fired but stalled on tool-perms in its headless session; this analysis replaces it.*
