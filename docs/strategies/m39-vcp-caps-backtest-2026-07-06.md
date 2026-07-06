# M39 VCP-caps decision — 2026-07-06

PR #591's M39 adds VCP base-depth/duration caps to `VcpDetector` (`artha.minervini.vcp.*`):
reject a base if `deepest_pct >= 60` (`max-base-depth-pct`) OR `base_weeks` outside `[3, 65]`
(`min-base-weeks` / `max-base-weeks`). This file records the decision from two independent lines of
evidence — a deploy-free geometry query (§1, resolved 15:58) **and** the full deep backtest A/B that
the scheduled post-close task actually ran (§2). Both agree.

**Verdict: do NOT merge #591's M39 as written. The `min-base-weeks=3` floor is the problem; the depth
cap is harmless.** Keep M35 (liquidity 50×) + M12 (RS tie-break) from #591 — unrelated and correct.

---

## §1 — Deploy-free geometry query (predictive)
Ran the M39 predicate against the live VCP setups (`marketdata.minervini_setups`, latest screen
2026-07-03, `is_vcp=true`, n=106 — detected by main's cap-less VcpDetector).

| metric | value |
|---|---|
| VCP setups (latest screen) | **106** |
| rejected by depth cap (≥60%) | **0** (avg depth 8.3%, max 17.4% — never near 60%) |
| rejected by duration floor (weeks <3 or >65) | **105** |
| **rejected by ANY M39 cap** | **105 / 106 (99%)** |
| base_weeks: avg / min / max | **0.7 / 0 / 6** |

Prediction: caps-on ≈ zero VCP trades in the backtest.

## §2 — Deep backtest A/B (confirmatory) — the scheduled run
One branch build (`origin/fix/batch-b-screener` @ `c2019576`), two arms via config toggle over the LIVE
`candles`@1d equity universe (~11y, scanned 1796 symbols). Caps injected via `JAVA_TOOL_OPTIONS` `-D`.
Variant reported = **rs-turnover** (the live-funnel analogue). `primary-base` (Minervini) / `breakout`
(Manas) are `isVcp`-INDEPENDENT → **sanity controls, must be unchanged**.

- **BASELINE / caps-OFF (≡ no-M39):** `max-base-depth-pct=100`, `min-base-weeks=0`, `max-base-weeks=100000`
- **M39 / caps-ON:** `60 / 3 / 65`

### Minervini — rs-turnover
| setup | trades OFF→ON | Δtr | exp% OFF→ON | payoff OFF→ON | win% OFF→ON |
|---|---|---|---|---|---|
| vcp | 7458 → **71** | −7387 | 4.55 → 0.73 | 4.21 → 2.88 | 32.5 → 31.0 |
| cheat-3c | 5800 → **40** | −5760 | 4.53 → 5.27 | 4.38 → 5.02 | 31.8 → 47.5 |
| power-play | 2313 → **4** | −2309 | 6.77 → 5.36 | 5.23 → 9.56 | 31.6 → 25.0 |
| primary-base *(control)* | 7218 → 7218 | 0 | 5.49 → 5.49 | 3.52 → 3.52 | 37.0 → 37.0 |
| **ALL** | 22789 → **7333** | −15456 | — | — | — |

Portfolio (8-slot):
| portfolio | CAGR% OFF→ON | maxDD% OFF→ON | Sharpe OFF→ON |
|---|---|---|---|
| FIFO gross | 14.32 → **11.63** | 42.76 → 34.58 | 0.60 → 0.52 |
| RS-priority NET *(realistic-live)* | 19.15 → **5.33** | 59.48 → 52.70 | 0.59 → 0.32 |

### Manas Arora — rs-turnover
| setup | trades OFF→ON | Δtr | exp% OFF→ON | payoff OFF→ON | win% OFF→ON |
|---|---|---|---|---|---|
| vcp | 5554 → **15** | −5539 | 3.32 → 2.96 | 1.94 → 1.95 | 45.9 → 46.7 |
| breakout *(control)* | 6177 → 6179 | +2 | 3.45 → 3.46 | 2.02 → 2.02 | 45.6 → 45.7 |
| **ALL** | 11731 → **6194** | −5537 | — | — | — |

Portfolio (8-slot):
| portfolio | CAGR% OFF→ON | maxDD% OFF→ON | Sharpe OFF→ON |
|---|---|---|---|
| FIFO gross | 26.63 → **22.85** | 50.29 → 51.03 | 0.78 → 0.77 |
| RS-priority NET | 13.91 → **19.47** | 57.52 → 53.43 | 0.53 → 0.73 |

## §3 — Reading the numbers
- **Controls validate the harness:** `primary-base` (Minervini) byte-identical, `breakout` (Manas) ±2
  trades — the caps touch ONLY `isVcp`-gated setups, as designed.
- **The caps are a near-total guillotine, not a quality filter.** Minervini's 3 isVcp setups lose
  **15,456 of 15,571 trades (−99.3%)**; Manas vcp loses **5539 of 5554 (−99.7%)**. §1 predicted this and
  §2 confirms it — and confirms the CAUSE is the duration floor (depth never binds, avg base ~8% / 0.7 wk).
- **Rejected trades were WINNERS, not losers.** Minervini vcp: removed ~7387 trades at avg
  expectancy ≈ **+4.59%/trade** (full set 4.55 vs 71 survivors at 0.73). The cap discards profitable
  trades — the opposite of an overhead-supply quality gate. The handful of survivors (71 / 40 / 4;
  Manas 15) are statistically meaningless; their occasional higher expectancy is noise on n<100.
- **The edge drops on the primary Minervini portfolios:** CAGR 14.32→11.63 (gross) and 19.15→**5.33**
  (RS-priority net). Manas is mixed (gross down 26.6→22.9, net up 13.9→19.5) because Manas is
  breakout-dominated and dropping the small diluting vcp sleeve helps net — but that is Manas becoming
  breakout-only, not M39 improving the VCP setup.

Decision rule (caps-ON CAGR/expectancy ≥ caps-OFF ⇒ validate; else too aggressive): **caps-ON drops the
Minervini edge and annihilates the setup population ⇒ M39 as-tuned is too aggressive.**

## §4 — Recommendation
Before merging #591, either:
1. **Lower/remove the duration floor** — set `min-base-weeks` to 0 or 1 (keep the ≥60% depth reject and
   the 65-week ceiling; both are harmless — depth never binds), OR
2. **Fix the detector's base-length measurement first** — `VcpDetector.base_weeks = round(durationDays/5)`
   measures only the tight trailing contracting leg (avg 0.7 wk), NOT the classical multi-week VCP base
   the `[3,65]` window assumes. Align the detector to the doctrine, THEN apply a week floor.

A follow-up isolation A/B (depth-cap-only vs duration-cap-only) would pinpoint the loosened threshold, but
is not needed for the merge decision — the duration floor is unambiguously the cause.

Keep **M35 (liquidity 50×)** and **M12 (RS tie-break)** from #591 regardless — screener-only, not in the
sim path, judge on the live funnel.

---
*§1 resolved deploy-free 15:58. §2 backtest ran post-close 15:54–18:32 on the branch build over live
`candles`@1d (LIVE db, market-data heap), then market-data was rebuilt+redeployed from `origin/main`
(image `30578e1`, 0 M39 strings in the deployed jar, healthy) BEFORE the 19:30 live screen so tonight's
20:00 swing batch runs clean main code. #591 left UNMERGED for the owner's call. Note: the shared working
tree was concurrently used by the merged `feat/relative-volume-floor` work (#605) — one early caps-ON run
was clobbered by a mid-run container recreate and re-run; every reported arm was gated on a fresh persisted
`*_backtest_runs` row (new run_at) to guarantee it is not a stale capture.*
