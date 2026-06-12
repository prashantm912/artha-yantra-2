# tools/greeks-vectors — Black-76 golden-vector generator (Phase 14 / amendment A4)

**A4 exception, recorded:** this is the sanctioned *non-runtime* exception to
"Python ONLY in optimizer-service". `generate.py` runs **offline at dev time
on a developer machine only** — it is **never containerized, never in any
service image, never in any CI runtime path**. The only artifact anything
consumes is the committed JSON fixture, read by JUnit:

```
services/market-data-service/src/test/resources/black76-golden-vectors.json
```

The fixtures pin the Java solver (`marketdata.options.Black76`) against
py_vollib's analytical Black-76 implementation — the independent oracle that
protects the irreplaceable IV archive (review S4, spike S1, B-10). The Phase 15
snapshot job enables its computed IV/Greeks columns **only while this suite is
green** (the S1 gate); raw-quote capture is never blocked by it.

## Grid (B-10)

- moneyness `F/K` ∈ {0.85, 0.90, 0.95, 1.00, 1.05, 1.10, 1.15} at F = 22000
- `T` ∈ {0.5, 2, 7, 30, 90} calendar days (ACT/365)
- `σ` ∈ {8, 16, 24, 32, 40, 50, 60} %
- CE + PE → 7 × 5 × 7 × 2 = **490 vectors**
- `r` pinned 6.5 % (RBI 91-day T-bill convention; per-fixture constant)

## Conventions pinned by the fixtures

- Model: **Black-76 on the forward** — never Black-Scholes on spot.
- theta **per calendar day**; vega **per 1 vol point**; rho per 1 % rate move
  (matching py_vollib's analytical-greeks units exactly).
- Values serialized via Python `repr` (17 significant digits) as JSON strings.

## Regenerating (dev machine only)

```powershell
pip install py_vollib
python tools/greeks-vectors/generate.py
```

Regeneration is only legitimate when the grid or conventions change; the JUnit
suite must stay green in the same commit. Fixtures are NEVER generated at test
runtime (Phase 14 FAIL criterion).
