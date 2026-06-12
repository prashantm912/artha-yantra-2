#!/usr/bin/env python
"""Black-76 golden-vector generator (Phase 14 / amendment A4 — OFFLINE dev tool).

Runs py_vollib's analytical Black-76 implementation over the B-10 grid and
writes the committed JSON fixture the JUnit golden suite consumes. Never
containerized, never in CI runtime — see README.md in this directory.
"""

import json
import sys
from pathlib import Path

from py_vollib.black import black
from py_vollib.black.greeks.analytical import delta, gamma, rho, theta, vega

REPO_ROOT = Path(__file__).resolve().parents[2]
OUT = (
    REPO_ROOT
    / "services/market-data-service/src/test/resources/black76-golden-vectors.json"
)

F = 22000.0  # NIFTY-scale forward so the IV round-trip tolerance is a real Rs 0.01
R = 0.065  # pinned RBI 91-day T-bill convention (B-10)
MONEYNESS = [0.85, 0.90, 0.95, 1.00, 1.05, 1.10, 1.15]  # F/K
DAYS = [0.5, 2.0, 7.0, 30.0, 90.0]  # ACT/365 calendar days
SIGMAS = [0.08, 0.16, 0.24, 0.32, 0.40, 0.50, 0.60]
FLAGS = [("c", "CE"), ("p", "PE")]


def main() -> int:
    vectors = []
    for m in MONEYNESS:
        k = F / m
        for days in DAYS:
            t = days / 365.0
            for sigma in SIGMAS:
                for flag, opt_type in FLAGS:
                    vectors.append(
                        {
                            "type": opt_type,
                            "F": repr(float(F)),
                            "K": repr(float(k)),
                            "T": repr(float(t)),
                            "sigma": repr(float(sigma)),
                            "r": repr(float(R)),
                            "price": repr(float(black(flag, F, k, t, R, sigma))),
                            "delta": repr(float(delta(flag, F, k, t, R, sigma))),
                            "gamma": repr(float(gamma(flag, F, k, t, R, sigma))),
                            "theta": repr(float(theta(flag, F, k, t, R, sigma))),
                            "vega": repr(float(vega(flag, F, k, t, R, sigma))),
                            "rho": repr(float(rho(flag, F, k, t, R, sigma))),
                        }
                    )
    fixture = {
        "fixtureFormat": 1,
        "model": "black76-on-forward",
        "oracle": "py_vollib analytical greeks",
        "conventions": "theta per calendar day; vega per vol point; rho per 1% rate",
        "grid": "F/K 0.85-1.15 @ F=22000; T {0.5,2,7,30,90}d ACT/365; sigma 8-60%; CE+PE",
        "vectors": vectors,
    }
    OUT.write_text(json.dumps(fixture, indent=1) + "\n", encoding="ascii")
    print(f"wrote {len(vectors)} vectors -> {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
