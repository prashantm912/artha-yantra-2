#!/usr/bin/env python3
"""Phase 19 indicator reference-vector generator (A4 exception, like Phase 14's
py_vollib goldens): run ONCE, outputs committed to
libs/strategy-engine/src/test/resources/vectors/, never regenerated at test time.

Mirrors the engine's arithmetic exactly: Decimal precision 32, ROUND_HALF_UP
(= ta4j DecimalNum(32) + the engine's MathContext), boundary-rounded to 8 dp.
The synthetic OHLCV series is integer-cents-exact and re-created bar-for-bar by
VectorFixtures.java from the same integer formulas - the CSVs hold EXPECTED
outputs only.

Recurrences mirrored from ta4j 0.22.0 SOURCES (AbstractEMAIndicator,
MMAIndicator, RSIIndicator, ATRIndicator, the adx package, EMAIndicator):

  EMA(n)   NaN for index < n; SEEDS at index == n with the input value
           (prev is NaN -> return current); after: prev + (cur - prev) * k
           where k = numOf(2.0/(n+1)) - a DOUBLE first, so we mirror with
           Decimal(repr(2.0/(n+1))).
  MMA(n)   same shape with k = Decimal(repr(1.0/n)).
  SMA(n)   mean of the last min(i+1, n) values (no NaN gate beyond n-1).
  RSI(n)   gain_0 = loss_0 = 0; ag/al = MMA(gain/loss, n) (both SEED at n with
           the raw gain/loss at n); al == 0 -> (ag == 0 ? 0 : 100);
           else 100 - 100/(1 + ag/al).
  ATR(n)   TR_0 = h-l; TR_i = max(h-l, |h-pc|, |l-pc|); ATR = MMA(TR, n).
  ADX(n)   +DI = MMA(+DM, n)/ATR(n)*100 (0 when ATR == 0); DX from |+DI - -DI|
           over the sum (*100); ADX = MMA(DX, n) - so ADX SEEDS at n with
           DX(n) and recurses after (NOT seeded at 2n).
  MACD_HIST(f, s, g)  macd_i = EMA(c, f)_i - EMA(c, s)_i (NaN until s);
           signal = EMA over the macd values (seeds at the first non-NaN macd,
           i.e. index s); hist = macd - signal (hist_s = 0).

SUPERTREND is deliberately NOT vector-pinned here (ta4j-internal band
ratchet); it is behavior-tested (monotonic/V-shape direction) and frozen
end-to-end by the Phase 23 golden harness. PSAR (§7.4) shares the same
acceleration-factor ratchet (ta4j-internal) and is likewise behavior-tested
(PsarBehaviorTest), NOT vector-pinned.

VWMA(20), BASIS_PCT and ADVANCE_DECLINE_RATIO (§7.3/§7.6/§7.7) ARE closed-form
(no ratchet) and pinned below. BASIS_PCT/ADVANCE_DECLINE_RATIO read the context
series (front-month futures / breadth) the same way RS_VS_INDEX/VIX_LEVEL do;
the synthetic primary+context fixture already covers them (same bucket times,
so the context bar at-or-before primary bar i is context[i]).
"""

from decimal import Decimal, getcontext, ROUND_HALF_UP
from pathlib import Path

getcontext().prec = 32
getcontext().rounding = ROUND_HALF_UP

OUT = Path(__file__).resolve().parents[2] / "libs" / "strategy-engine" / "src" / "test" / "resources" / "vectors"
BARS_PER_DAY = 40
DAYS = 2
N = BARS_PER_DAY * DAYS


def cents(c):
    return Decimal(c) / Decimal(100)


def series():
    bars = []
    for i in range(N):
        close_c = 10000 + 7 * i + (i * i % 13) * 3
        open_c = close_c - 5 - (i % 4) * 3
        high_c = max(open_c, close_c) + 5 + (i % 3) * 5
        low_c = min(open_c, close_c) - 5 - ((i + 1) % 3) * 5
        volume = 1000 + (i * 37) % 250
        oi = 100000 + i * 50 - (i % 7) * 120
        bars.append({
            "o": cents(open_c), "h": cents(high_c), "l": cents(low_c), "c": cents(close_c),
            "v": Decimal(volume), "oi": Decimal(oi),
        })
    return bars


def context_series():
    bars = []
    for i in range(N):
        close_c = 20000 + 3 * i + (i % 5) * 4
        bars.append({"c": cents(close_c)})
    return bars


def day_of(i):
    return i // BARS_PER_DAY


def session_start(i):
    return day_of(i) * BARS_PER_DAY


def r8(v):
    return v.quantize(Decimal("0.00000001"))


def write(name, values):
    """values: list of (index, Decimal-or-None); None rows are emitted blank."""
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / f"{name}.csv", "w", newline="\n") as f:
        f.write("index,expected\n")
        for i, v in values:
            f.write(f"{i},{'' if v is None else r8(v)}\n")


def ema_ta4j(values, n):
    """ta4j AbstractEMAIndicator: NaN (None) below n; seed at the first non-None
    input at/after n; recurrence prev + (cur - prev) * k with k = repr-double."""
    k = Decimal(repr(2.0 / (n + 1)))
    out = [None] * len(values)
    for i in range(len(values)):
        if i < n:
            continue
        cur = values[i]
        if cur is None:
            continue
        prev = out[i - 1] if i > 0 else None
        out[i] = cur if prev is None else prev + (cur - prev) * k
    return out


def mma_ta4j(values, n):
    """ta4j MMAIndicator: same shape with k = repr(1.0/n)."""
    k = Decimal(repr(1.0 / n))
    out = [None] * len(values)
    for i in range(len(values)):
        if i < n:
            continue
        cur = values[i]
        if cur is None:
            continue
        prev = out[i - 1] if i > 0 else None
        out[i] = cur if prev is None else prev + (cur - prev) * k
    return out


def main():
    bars = series()
    closes = [b["c"] for b in bars]
    ctx = context_series()

    # EMA(9): NaN below 9, seeds at 9
    ema9 = ema_ta4j(closes, 9)
    write("EMA_period9", [(i, ema9[i] if i >= 9 else None) for i in range(N)])

    # SMA(5): unstable = 4
    sma = []
    for i in range(N):
        nreal = min(5, i + 1)
        window = closes[i - nreal + 1: i + 1]
        sma.append(sum(window) / Decimal(nreal))
    write("SMA_period5", [(i, sma[i] if i >= 4 else None) for i in range(N)])

    # RSI(14): ag/al = MMA(gain/loss, 14), both seeding at 14
    gains = [Decimal(0)] + [max(closes[i] - closes[i - 1], Decimal(0)) for i in range(1, N)]
    losses = [Decimal(0)] + [max(closes[i - 1] - closes[i], Decimal(0)) for i in range(1, N)]
    ag = mma_ta4j(gains, 14)
    al = mma_ta4j(losses, 14)
    rsi = []
    for i in range(N):
        if i < 14:
            rsi.append(None)
        elif al[i] == 0:
            rsi.append(Decimal(100) if ag[i] != 0 else Decimal(0))
        else:
            rs = ag[i] / al[i]
            rsi.append(Decimal(100) - Decimal(100) / (Decimal(1) + rs))
    write("RSI_period14", [(i, rsi[i]) for i in range(N)])

    # ATR(14) = MMA(TR, 14), seeding at 14 with TR(14)
    tr = [bars[0]["h"] - bars[0]["l"]]
    for i in range(1, N):
        pc = closes[i - 1]
        tr.append(max(bars[i]["h"] - bars[i]["l"], abs(bars[i]["h"] - pc), abs(bars[i]["l"] - pc)))
    atr = mma_ta4j(tr, 14)
    write("ATR_period14", [(i, atr[i]) for i in range(N)])

    # ADX(14) = MMA(DX, 14) seeding at 14 with DX(14); engine wrapper nulls below 2n=28
    plus_dm = [Decimal(0)]
    minus_dm = [Decimal(0)]
    for i in range(1, N):
        up = bars[i]["h"] - bars[i - 1]["h"]
        down = bars[i - 1]["l"] - bars[i]["l"]
        plus_dm.append(up if (up > down and up > 0) else Decimal(0))
        minus_dm.append(down if (down > up and down > 0) else Decimal(0))
    mma_pdm = mma_ta4j(plus_dm, 14)
    mma_mdm = mma_ta4j(minus_dm, 14)
    dx = []
    for i in range(N):
        if i < 14 or atr[i] is None:
            dx.append(None)
            continue
        if atr[i] == 0:
            dx.append(Decimal(0))
            continue
        pdi = mma_pdm[i] / atr[i] * Decimal(100)
        mdi = mma_mdm[i] / atr[i] * Decimal(100)
        denom = pdi + mdi
        dx.append(Decimal(0) if denom == 0 else abs(pdi - mdi) / denom * Decimal(100))
    adx = mma_ta4j(dx, 14)
    write("ADX_period14", [(i, adx[i] if i >= 28 else None) for i in range(N)])

    # MACD_HIST(3, 9, 3): macd NaN until 9; signal seeds at 9 (hist_9 = 0); emit from 10
    ema_fast = ema_ta4j(closes, 3)
    ema_slow = ema_ta4j(closes, 9)
    macd = [
        None if (ema_fast[i] is None or ema_slow[i] is None) else ema_fast[i] - ema_slow[i]
        for i in range(N)
    ]
    signal = ema_ta4j(macd, 3)
    hist = [
        None if (macd[i] is None or signal[i] is None) else macd[i] - signal[i]
        for i in range(N)
    ]
    write("MACD_HIST_3_9_3", [(i, hist[i] if i >= 10 else None) for i in range(N)])

    # VWAP (cumulative session)
    vwap = []
    for i in range(N):
        s = session_start(i)
        pv = Decimal(0)
        vol = Decimal(0)
        for j in range(s, i + 1):
            typical = (bars[j]["h"] + bars[j]["l"] + bars[j]["c"]) / Decimal(3)
            pv += typical * bars[j]["v"]
            vol += bars[j]["v"]
        vwap.append(pv / vol)
    write("VWAP_session", [(i, vwap[i]) for i in range(N)])

    # VOLUME_RATIO(20): unstable = 20 (mean of PRIOR 20 volumes)
    volr = []
    for i in range(N):
        if i < 20:
            volr.append(None)
            continue
        mean = sum(b["v"] for b in bars[i - 20:i]) / Decimal(20)
        volr.append(bars[i]["v"] / mean)
    write("VOLUME_RATIO_lookback20", [(i, volr[i]) for i in range(N)])

    # OI_CHANGE_PCT(6): unstable = 6
    oic = []
    for i in range(N):
        if i < 6:
            oic.append(None)
            continue
        base = bars[i - 6]["oi"]
        oic.append((bars[i]["oi"] - base) / base * Decimal(100))
    write("OI_CHANGE_PCT_lookback6", [(i, oic[i]) for i in range(N)])

    # ORB_HIGH/LOW(15): defined from the 16th session minute on
    orb_h, orb_l = [], []
    for i in range(N):
        s = session_start(i)
        if i - s < 15:
            orb_h.append(None)
            orb_l.append(None)
            continue
        window = bars[s:s + 15]
        orb_h.append(max(b["h"] for b in window))
        orb_l.append(min(b["l"] for b in window))
    write("ORB_HIGH_window15", [(i, orb_h[i]) for i in range(N)])
    write("ORB_LOW_window15", [(i, orb_l[i]) for i in range(N)])

    # PREV_DAY_* + GAP_PCT: day 2 only
    d1 = bars[:BARS_PER_DAY]
    pd_high = max(b["h"] for b in d1)
    pd_low = min(b["l"] for b in d1)
    pd_close = d1[-1]["c"]
    gap = (bars[BARS_PER_DAY]["o"] - pd_close) / pd_close * Decimal(100)
    write("PREV_DAY_HIGH", [(i, pd_high if day_of(i) == 1 else None) for i in range(N)])
    write("PREV_DAY_LOW", [(i, pd_low if day_of(i) == 1 else None) for i in range(N)])
    write("PREV_DAY_CLOSE", [(i, pd_close if day_of(i) == 1 else None) for i in range(N)])
    write("GAP_PCT", [(i, gap if day_of(i) == 1 else None) for i in range(N)])

    # DAY_HIGH / DAY_LOW (running session extremes)
    day_h, day_l = [], []
    for i in range(N):
        s = session_start(i)
        day_h.append(max(b["h"] for b in bars[s:i + 1]))
        day_l.append(min(b["l"] for b in bars[s:i + 1]))
    write("DAY_HIGH", [(i, day_h[i]) for i in range(N)])
    write("DAY_LOW", [(i, day_l[i]) for i in range(N)])

    # RS_VS_INDEX(5) against the context series; VIX_LEVEL = context close
    rs = []
    for i in range(N):
        if i < 5:
            rs.append(None)
            continue
        own = (closes[i] - closes[i - 5]) / closes[i - 5] * Decimal(100)
        cbase = ctx[i - 5]["c"]
        cref = (ctx[i]["c"] - cbase) / cbase * Decimal(100)
        rs.append(own - cref)
    write("RS_VS_INDEX_lookback5", [(i, rs[i]) for i in range(N)])
    write("VIX_LEVEL", [(i, ctx[i]["c"]) for i in range(N)])

    # VWMA(20): rolling volume-weighted MA = sum(c*v, 20)/sum(v, 20); unstable = 19
    vwma = []
    for i in range(N):
        if i < 19:
            vwma.append(None)
            continue
        win = bars[i - 19:i + 1]
        pv = sum(b["c"] * b["v"] for b in win)
        vol = sum(b["v"] for b in win)
        vwma.append(pv / vol)
    write("VWMA_period20", [(i, vwma[i]) for i in range(N)])

    # BASIS_PCT: (spot - futures) / futures * 100; futures = context close at-or-before
    # primary bar i (identical bucket times -> context[i]); unstable = 0
    basis = []
    for i in range(N):
        f = ctx[i]["c"]
        basis.append((closes[i] - f) / f * Decimal(100))
    write("BASIS_PCT", [(i, basis[i]) for i in range(N)])

    # ADVANCE_DECLINE_RATIO: the context-series close (contextLevel mechanism, no math)
    write("ADVANCE_DECLINE_RATIO", [(i, ctx[i]["c"]) for i in range(N)])

    print(f"wrote vectors to {OUT}")


if __name__ == "__main__":
    main()
