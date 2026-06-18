"""
Historical CSV -> TimescaleDB ingest (options / spot / equity / IV / fundamentals).

See DATA_SOURCES.md (field survey) and IMPORT_PLAN.md (design) for the full picture.

Routing is by tree (data-type is structural): a path is classified into one of
  OPTION | SPOT | EQUITY -> marketdata.candles
  IV                      -> marketdata.iv_history
  FUNDAMENTAL             -> marketdata.fundamentals

Within candle files, OI presence and date format are detected PER FILE from the header
and first data row -- never inferred from path or year (both Source-A variants and the
Source-B ISO form are intermixed; see DATA_SOURCES.md).

Incremental: a SQLite manifest fingerprints every file (size + mtime, optional sha256).
Re-runs load only NEW or MODIFIED files; UNCHANGED files are skipped without opening.

Usage:
  # dry run (parse only, no DB, no manifest writes)
  python ingest.py --root "D:/market-import/options" --dry-run

  # full / incremental load
  python ingest.py --root "D:/market-import/options" \
      --dsn "postgresql://artha:artha@localhost:5432/artha_mock" --workers 8
"""

import argparse
import calendar
import hashlib
import logging
import multiprocessing
import re
import sqlite3
import sys
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from zoneinfo import ZoneInfo

# psycopg + tqdm are imported lazily inside the DB/CLI paths so the pure parsing
# functions (and their unit tests) import without the DB driver installed.

IST = ZoneInfo("Asia/Kolkata")
UTC = timezone.utc

EMPTY_FILE_BYTES = 60  # header-only option files are ~41 bytes -> skip before open()

# Known indices: key -> (exchange, kite_tradingsymbol, instrument_type)
INDEX_MAP = {
    "nifty50": ("NSE", "NIFTY 50", "EQ"),
    "nifty 50": ("NSE", "NIFTY 50", "EQ"),
    "nifty": ("NSE", "NIFTY 50", "EQ"),
    "banknifty": ("NSE", "NIFTY BANK", "EQ"),
    "nifty bank": ("NSE", "NIFTY BANK", "EQ"),
    "sensex": ("BSE", "SENSEX", "EQ"),
    "bankex": ("BSE", "BANKEX", "EQ"),
    "finnifty": ("NSE", "NIFTY FIN SERVICE", "EQ"),
    "midcpnifty": ("NSE", "NIFTY MID SELECT", "EQ"),
    "niftynxt50": ("NSE", "NIFTY NEXT 50", "EQ"),
    "niftynext50": ("NSE", "NIFTY NEXT 50", "EQ"),
}

# *_spot.csv stem -> index key
SPOT_MAP = {
    "nifty_spot": "nifty",
    "banknifty_spot": "banknifty",
}

# BSE-rooted underlyings (options/futures on these trade at BFO/BSE)
BSE_UNDERLYINGS = {"SENSEX", "BANKEX"}

MON_MAP = {
    "JAN": 1, "FEB": 2, "MAR": 3, "APR": 4, "MAY": 5, "JUN": 6,
    "JUL": 7, "AUG": 8, "SEP": 9, "OCT": 10, "NOV": 11, "DEC": 12,
}

# Fundamentals statement-file stem -> (statement enum, granularity)
FUND_STATEMENTS = {
    "quarterly_results": ("quarterly_results", "Q"),
    "profit_and_loss": ("profit_and_loss", "A"),
    "balance_sheet": ("balance_sheet", "A"),
    "cash_flows": ("cash_flows", "A"),
    "ratios": ("ratios", "A"),
    "shareholding_pattern_yearly": ("shareholding_yearly", "A"),
    "shareholding_pattern_quarterly": ("shareholding_quarterly", "Q"),
}

# Regex: SYMBOL_STRIKE_(CE|PE|FUT)_DD_MON_YY
# Symbol class includes '-' and '.' for NSE symbols like BAJAJ-AUTO, M&M.
_OPT_RE = re.compile(
    r"^(?P<sym>[A-Z0-9&.\-]+)_(?P<strike>\d+(?:\.\d+)?)_(?P<otype>CE|PE|FUT)_"
    r"(?P<dd>\d{2})_(?P<mon>[A-Z]{3})_(?P<yy>\d{2})$",
    re.IGNORECASE,
)

_KITE_MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]


# --------------------------------------------------------------------------- #
# Classification (data-type by tree)                                          #
# --------------------------------------------------------------------------- #

def classify_path(path: Path) -> str:
    """Return data-type kind: OPTION | SPOT | EQUITY | IV | FUNDAMENTAL | UNKNOWN."""
    parts_lower = [p.lower() for p in path.parts]
    stem = path.stem
    stem_lower = stem.lower()

    if "implied volatility" in parts_lower:
        return "IV"
    if "fundamentals" in parts_lower and path.stem in FUND_STATEMENTS:
        return "FUNDAMENTAL"
    if stem_lower in SPOT_MAP:
        return "SPOT"
    if _OPT_RE.match(stem.upper()):
        return "OPTION"
    if "equity" in parts_lower:
        return "EQUITY"
    return "UNKNOWN"


# --------------------------------------------------------------------------- #
# Instrument parsing (candle targets)                                         #
# --------------------------------------------------------------------------- #

def parse_option(stem: str) -> dict:
    """Parse an option/future filename stem -> instrument dict."""
    m = _OPT_RE.match(stem.upper())
    sym = m.group("sym")
    otype = m.group("otype")
    dd = int(m.group("dd"))
    mon = MON_MAP[m.group("mon").upper()]
    yy = int(m.group("yy")) + 2000
    expiry = datetime(yy, mon, dd).date()
    mon_str = _KITE_MONTHS[mon - 1]
    yy2 = str(yy)[2:]

    if sym in BSE_UNDERLYINGS:
        exchange, underlying_exchange = "BFO", "BSE"
    else:
        exchange, underlying_exchange = "NFO", "NSE"

    if otype == "FUT":
        tradingsymbol = f"{sym}{yy2}{mon_str}FUT"
        strike = None
        instrument_type = "FUT"
    else:
        strike = Decimal(m.group("strike"))
        strike_int = int(strike) if strike == int(strike) else float(strike)
        tradingsymbol = f"{sym}{yy2}{mon_str}{strike_int}{otype}"
        instrument_type = otype  # CE or PE

    return {
        "exchange": exchange,
        "tradingsymbol": tradingsymbol,
        "instrument_type": instrument_type,
        "underlying_exchange": underlying_exchange,
        "underlying_tradingsymbol": sym,
        "expiry": expiry,
        "strike": strike,
    }


def parse_index(key: str) -> dict:
    """Index/spot instrument dict from an INDEX_MAP key."""
    exch, tsym, itype = INDEX_MAP[key]
    return {
        "exchange": exch, "tradingsymbol": tsym, "instrument_type": itype,
        "underlying_exchange": None, "underlying_tradingsymbol": None,
        "expiry": None, "strike": None,
    }


def parse_equity(stem: str) -> dict:
    """Equity instrument dict. Strips a leading numeric id: '0031_BAJAJFINSV' -> 'BAJAJFINSV'."""
    sym = re.sub(r"^\d+_", "", stem).upper()
    return {
        "exchange": "NSE", "tradingsymbol": sym, "instrument_type": "EQ",
        "underlying_exchange": None, "underlying_tradingsymbol": None,
        "expiry": None, "strike": None,
    }


# --------------------------------------------------------------------------- #
# Timestamp parsing (per-file detection)                                      #
# --------------------------------------------------------------------------- #

def _parse_split_date(date_str: str) -> datetime:
    """Source-A date column: 'YYYY-MM-DD' or 'DD-MM-YYYY' (detected per token)."""
    if re.match(r"^\d{4}-\d{2}-\d{2}$", date_str):
        return datetime.strptime(date_str, "%Y-%m-%d")
    return datetime.strptime(date_str, "%d-%m-%Y")


def _to_utc(dt_ist_naive: datetime) -> datetime:
    return dt_ist_naive.replace(tzinfo=IST).astimezone(UTC)


def detect_candle_schema(header: list[str]) -> dict:
    """
    From a header row, return how to read this candle file:
      mode: 'split'  -> separate date + time columns (IST implied)
            'iso'    -> single ISO-8601 timestamp column (tz-aware or naive->IST)
      ts_idx, date_idx, time_idx, ohlc indices, oi_idx (or None)
    """
    cols = [c.strip().lower() for c in header]
    idx = {c: i for i, c in enumerate(cols)}
    has_oi = "oi" in idx
    oi_idx = idx.get("oi")

    o, h, l, c_ = idx["open"], idx["high"], idx["low"], idx["close"]
    vol_idx = idx.get("volume")

    if "time" in idx:  # split date+time (date col may be 'date')
        return {"mode": "split", "date_idx": idx["date"], "time_idx": idx["time"],
                "o": o, "h": h, "l": l, "c": c_, "vol": vol_idx, "oi": oi_idx, "has_oi": has_oi}

    # single timestamp column: 'timestamp' (Source B) or 'date' (equity/nifty_spot)
    ts_idx = idx.get("timestamp", idx.get("date"))
    return {"mode": "iso", "ts_idx": ts_idx,
            "o": o, "h": h, "l": l, "c": c_, "vol": vol_idx, "oi": oi_idx, "has_oi": has_oi}


def parse_candle_rows(path: Path, instrument: dict, interval: str) -> list[tuple]:
    """Read a candle CSV -> rows ready for marketdata.candles."""
    exch = instrument["exchange"]
    tsym = instrument["tradingsymbol"]
    fetched_at = datetime.now(UTC)
    rows: list[tuple] = []

    with open(path, encoding="utf-8-sig", errors="replace") as fh:
        header = fh.readline().strip().split(",")
        if len(header) < 5:
            return rows
        schema = detect_candle_schema(header)

        for line in fh:
            line = line.strip()
            if not line:
                continue
            parts = line.split(",")
            try:
                if schema["mode"] == "split":
                    dt = _parse_split_date(parts[schema["date_idx"]].strip())
                    hh, mm = parts[schema["time_idx"]].strip().split(":")
                    bucket = _to_utc(dt.replace(hour=int(hh), minute=int(mm)))
                else:
                    raw = parts[schema["ts_idx"]].strip()
                    dt = datetime.fromisoformat(raw)
                    bucket = dt.astimezone(UTC) if dt.tzinfo else _to_utc(dt)

                open_ = Decimal(parts[schema["o"]])
                high = Decimal(parts[schema["h"]])
                low = Decimal(parts[schema["l"]])
                close = Decimal(parts[schema["c"]])
                volume = 0
                if schema["vol"] is not None and parts[schema["vol"]].strip():
                    volume = int(float(parts[schema["vol"]]))
                oi = None
                if schema["oi"] is not None and len(parts) > schema["oi"] and parts[schema["oi"]].strip():
                    oi = int(float(parts[schema["oi"]]))
                    if oi == 0:
                        oi = None
            except (ValueError, IndexError, InvalidOperation):
                continue
            rows.append((exch, tsym, interval, bucket,
                         open_, high, low, close, volume, oi, "BACKFILL", fetched_at))
    return rows


# --------------------------------------------------------------------------- #
# IV parsing                                                                  #
# --------------------------------------------------------------------------- #

def parse_iv_rows(path: Path) -> list[tuple]:
    """
    implied volatility/<SYMBOL>.csv ->
      Date,Open,High,Low,Close,IV,Rank,IV Percentile
    -> rows for marketdata.iv_history.
    """
    underlying = path.stem.upper()
    fetched_at = datetime.now(UTC)
    rows: list[tuple] = []
    with open(path, encoding="utf-8-sig", errors="replace") as fh:
        fh.readline()  # header
        for line in fh:
            line = line.strip()
            if not line:
                continue
            p = line.split(",")
            if len(p) < 8:
                continue
            try:
                trade_date = datetime.strptime(p[0].strip(), "%Y-%m-%d").date()
                vals = [(Decimal(x) if x.strip() else None) for x in p[1:8]]
            except (ValueError, InvalidOperation):
                continue
            o, h, l, c, iv, rank, pct = vals
            rows.append((underlying, trade_date, o, h, l, c, iv, rank, pct,
                         "BACKFILL", fetched_at))
    return rows


# --------------------------------------------------------------------------- #
# Fundamentals parsing (wide -> long transpose)                               #
# --------------------------------------------------------------------------- #

def parse_period(label: str) -> tuple[datetime, None] | tuple:
    """'Mar 2023' -> date(2023, 3, 31) (month-end). Returns None on unparseable."""
    label = label.strip()
    m = re.match(r"^([A-Za-z]{3})\s+(\d{4})$", label)
    if not m:
        return None
    mon = MON_MAP.get(m.group(1).upper())
    if not mon:
        return None
    year = int(m.group(2))
    last = calendar.monthrange(year, mon)[1]
    return datetime(year, mon, last).date()


def clean_metric(name: str) -> str:
    """Strip mojibake nbsp, footnote '+', surrounding whitespace."""
    return name.replace("\xa0", " ").replace("Â", "").replace("+", "").strip()


def clean_value(raw: str) -> tuple:
    """'1,361' -> (1361, False); '59.34%' -> (59.34, True); '' -> (None, False)."""
    raw = raw.strip().replace('"', "").replace(",", "")
    if not raw or raw in ("-", "NA", "N/A"):
        return None, False
    is_pct = raw.endswith("%")
    if is_pct:
        raw = raw[:-1].strip()
    try:
        return Decimal(raw), is_pct
    except InvalidOperation:
        return None, is_pct


def parse_fundamental_rows(path: Path) -> list[tuple]:
    """One wide statement CSV -> long rows for marketdata.fundamentals."""
    symbol = path.parent.name.upper()
    statement, granularity = FUND_STATEMENTS[path.stem]
    fetched_at = datetime.now(UTC)
    rows: list[tuple] = []

    with open(path, encoding="utf-8-sig", errors="replace") as fh:
        lines = [ln.rstrip("\n") for ln in fh if ln.strip()]
    if not lines:
        return rows

    # header: empty first cell, then period labels
    periods = [parse_period(p) for p in _split_csv(lines[0])[1:]]

    for line in lines[1:]:
        cells = _split_csv(line)
        metric = clean_metric(cells[0])
        if not metric or metric.lower() == "raw pdf":
            continue
        for col, cell in enumerate(cells[1:]):
            if col >= len(periods) or periods[col] is None:
                continue
            value, is_pct = clean_value(cell)
            if value is None:
                continue
            rows.append((symbol, statement, periods[col], granularity,
                         metric, value, is_pct, "BACKFILL", fetched_at))
    return rows


def _split_csv(line: str) -> list[str]:
    """Minimal CSV split honouring double-quoted fields (values like \"1,361\")."""
    out, cur, in_q = [], [], False
    for ch in line:
        if ch == '"':
            in_q = not in_q
        elif ch == "," and not in_q:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    out.append("".join(cur))
    return out


# --------------------------------------------------------------------------- #
# SQL                                                                          #
# --------------------------------------------------------------------------- #

_UPSERT_INSTRUMENT = """
INSERT INTO marketdata.instruments
  (exchange, tradingsymbol, instrument_type, underlying_exchange,
   underlying_tradingsymbol, expiry, strike, is_active,
   first_seen_at, last_seen_at, updated_at)
VALUES (%s, %s, %s, %s, %s, %s, %s, false, NOW(), NOW(), NOW())
ON CONFLICT (exchange, tradingsymbol) DO UPDATE SET
  last_seen_at = NOW(), updated_at = NOW()
"""

_STAGE_DDL = """
CREATE TEMP TABLE IF NOT EXISTS _candle_stage (
  exchange TEXT, tradingsymbol TEXT, interval TEXT, bucket TIMESTAMPTZ,
  open NUMERIC(18,4), high NUMERIC(18,4), low NUMERIC(18,4), close NUMERIC(18,4),
  volume BIGINT, oi BIGINT, source TEXT, fetched_at TIMESTAMPTZ
) ON COMMIT PRESERVE ROWS
"""

_STAGE_FLUSH = """
INSERT INTO marketdata.candles
  (exchange, tradingsymbol, interval, bucket,
   open, high, low, close, volume, oi, source, fetched_at)
SELECT exchange, tradingsymbol, interval, bucket,
       open, high, low, close, volume, oi, source, fetched_at
FROM _candle_stage
ON CONFLICT (exchange, tradingsymbol, interval, bucket) DO UPDATE SET
  open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low,
  close = EXCLUDED.close, volume = EXCLUDED.volume,
  oi = COALESCE(EXCLUDED.oi, marketdata.candles.oi),
  fetched_at = EXCLUDED.fetched_at
"""

_UPSERT_IV = """
INSERT INTO marketdata.iv_history
  (underlying, trade_date, open, high, low, close, iv, iv_rank, iv_percentile,
   source, fetched_at)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (underlying, trade_date) DO UPDATE SET
  open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low,
  close = EXCLUDED.close, iv = EXCLUDED.iv, iv_rank = EXCLUDED.iv_rank,
  iv_percentile = EXCLUDED.iv_percentile, fetched_at = EXCLUDED.fetched_at
"""

_UPSERT_FUND = """
INSERT INTO marketdata.fundamentals
  (symbol, statement, period_end, granularity, metric, value, is_percent,
   source, fetched_at)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (symbol, statement, period_end, metric) DO UPDATE SET
  value = EXCLUDED.value, is_percent = EXCLUDED.is_percent,
  granularity = EXCLUDED.granularity, fetched_at = EXCLUDED.fetched_at
"""


# --------------------------------------------------------------------------- #
# Worker (one persistent connection per process)                              #
# --------------------------------------------------------------------------- #

_CONN = None
_DSN = ""
_LOAD_MODE = "upsert"  # 'upsert' (incremental-safe) | 'replace' (fast virgin backfill)


def _init_worker(dsn: str, load_mode: str = "upsert"):
    global _DSN, _LOAD_MODE
    _DSN = dsn
    _LOAD_MODE = load_mode


def _conn():
    global _CONN
    import psycopg
    if _CONN is None or _CONN.closed:
        _CONN = psycopg.connect(_DSN)
        with _CONN.cursor() as cur:
            cur.execute("SET synchronous_commit = off")
            cur.execute(_STAGE_DDL)
        _CONN.commit()
    return _CONN


_COPY_DIRECT = (
    "COPY marketdata.candles (exchange, tradingsymbol, interval, bucket, "
    "open, high, low, close, volume, oi, source, fetched_at) FROM STDIN"
)
_COPY_STAGE = (
    "COPY _candle_stage (exchange, tradingsymbol, interval, bucket, "
    "open, high, low, close, volume, oi, source, fetched_at) FROM STDIN"
)


def _load_candles(instrument: dict, rows: list[tuple]):
    conn = _conn()
    with conn.cursor() as cur:
        cur.execute(_UPSERT_INSTRUMENT, (
            instrument["exchange"], instrument["tradingsymbol"],
            instrument["instrument_type"], instrument["underlying_exchange"],
            instrument["underlying_tradingsymbol"], instrument["expiry"],
            instrument["strike"],
        ))
        if _LOAD_MODE == "replace":
            # Fast path: clear this instrument's bucket range (a no-op on virgin data,
            # and idempotent for crash-resume), then COPY straight into the hypertable.
            # No per-row ON CONFLICT probe -> ~100x the upsert throughput.
            exch, tsym, interval = rows[0][0], rows[0][1], rows[0][2]
            buckets = [r[3] for r in rows]
            cur.execute(
                "DELETE FROM marketdata.candles WHERE exchange=%s AND tradingsymbol=%s "
                "AND interval=%s AND bucket BETWEEN %s AND %s",
                (exch, tsym, interval, min(buckets), max(buckets)))
            with cur.copy(_COPY_DIRECT) as cp:
                for r in rows:
                    cp.write_row(r)
        else:
            cur.execute("TRUNCATE _candle_stage")
            with cur.copy(_COPY_STAGE) as cp:
                for r in rows:
                    cp.write_row(r)
            cur.execute(_STAGE_FLUSH)
    conn.commit()


def _load_simple(sql: str, rows: list[tuple]):
    conn = _conn()
    with conn.cursor() as cur:
        cur.executemany(sql, rows)
    conn.commit()


def _interval_for(path: Path) -> str:
    parts = [p.lower() for p in path.parts]
    if "day" in parts:
        return "1d"
    return "1m"


def _worker_fn(task: tuple) -> tuple:
    """task = (path_str, kind). Returns (path_str, status, rows, msg)."""
    path_str, kind = task
    path = Path(path_str)
    try:
        if kind == "OPTION":
            instr = parse_option(path.stem)
            rows = parse_candle_rows(path, instr, "1m")
            if rows:
                _load_candles(instr, rows)
        elif kind == "SPOT":
            instr = parse_index(SPOT_MAP[path.stem.lower()])
            rows = parse_candle_rows(path, instr, "1m")
            if rows:
                _load_candles(instr, rows)
        elif kind == "EQUITY":
            instr = parse_equity(path.stem)
            rows = parse_candle_rows(path, instr, _interval_for(path))
            if rows:
                _load_candles(instr, rows)
        elif kind == "IV":
            rows = parse_iv_rows(path)
            if rows:
                _load_simple(_UPSERT_IV, rows)
        elif kind == "FUNDAMENTAL":
            rows = parse_fundamental_rows(path)
            if rows:
                _load_simple(_UPSERT_FUND, rows)
        else:
            return path_str, "failed", 0, f"unknown kind: {kind}"
        return path_str, "loaded", len(rows), f"{len(rows)} rows"
    except Exception as exc:  # noqa: BLE001 - report, don't crash the pool
        return path_str, "failed", 0, str(exc)


def _worker_dry(task: tuple) -> tuple:
    """Dry-run: parse only, no DB."""
    path_str, kind = task
    path = Path(path_str)
    try:
        if kind == "OPTION":
            rows = parse_candle_rows(path, parse_option(path.stem), "1m")
        elif kind == "SPOT":
            rows = parse_candle_rows(path, parse_index(SPOT_MAP[path.stem.lower()]), "1m")
        elif kind == "EQUITY":
            rows = parse_candle_rows(path, parse_equity(path.stem), _interval_for(path))
        elif kind == "IV":
            rows = parse_iv_rows(path)
        elif kind == "FUNDAMENTAL":
            rows = parse_fundamental_rows(path)
        else:
            return path_str, "failed", 0, f"unknown kind: {kind}"
        return path_str, "loaded", len(rows), f"dry {len(rows)} rows"
    except Exception as exc:  # noqa: BLE001
        return path_str, "failed", 0, str(exc)


# --------------------------------------------------------------------------- #
# Manifest (SQLite, single writer in main process)                            #
# --------------------------------------------------------------------------- #

_MANIFEST_DDL = """
CREATE TABLE IF NOT EXISTS manifest (
  path      TEXT PRIMARY KEY,
  size      INTEGER NOT NULL,
  mtime_ns  INTEGER NOT NULL,
  sha256    TEXT,
  rows      INTEGER,
  status    TEXT NOT NULL,
  loaded_at TEXT NOT NULL
)
"""


def open_manifest(path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute(_MANIFEST_DDL)
    conn.commit()
    return conn


def load_fingerprints(conn: sqlite3.Connection) -> dict:
    cur = conn.execute("SELECT path, size, mtime_ns, sha256 FROM manifest")
    return {p: (sz, mt, sha) for p, sz, mt, sha in cur.fetchall()}


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def classify_changes(root: Path, prior: dict, verify_checksum: bool, log) -> tuple:
    """
    Walk root, classify each file against the manifest.
    Returns (pending, touches, counts, deleted):
      pending = [(path_str, kind, size, mtime_ns, sha_or_None)]  -> load these
      touches = [(path_str, mtime_ns)]  -> mtime moved but content identical; update mtime only
    """
    pending, touches = [], []
    counts = {"new": 0, "modified": 0, "unchanged": 0, "skipped_empty": 0, "unknown": 0}
    seen = set()

    for path in root.rglob("*.csv"):
        kind = classify_path(path)
        if kind == "UNKNOWN":
            counts["unknown"] += 1
            continue
        st = path.stat()
        if kind in ("OPTION", "SPOT", "EQUITY") and st.st_size <= EMPTY_FILE_BYTES:
            counts["skipped_empty"] += 1
            continue
        key = str(path)
        seen.add(key)
        prev = prior.get(key)  # (size, mtime_ns, sha256) or None
        if prev is None:
            counts["new"] += 1
            pending.append((key, kind, st.st_size, st.st_mtime_ns,
                            _sha256(path) if verify_checksum else None))
        elif prev[0] != st.st_size:
            counts["modified"] += 1
            pending.append((key, kind, st.st_size, st.st_mtime_ns,
                            _sha256(path) if verify_checksum else None))
        elif prev[1] != st.st_mtime_ns:
            # mtime moved, size same. With --verify-checksum, hash-confirm before reloading
            # (Google Drive re-sync rewrites mtime without changing content).
            if verify_checksum and prev[2] and prev[2] == _sha256(path):
                touches.append((key, st.st_mtime_ns))
                counts["unchanged"] += 1
            else:
                counts["modified"] += 1
                pending.append((key, kind, st.st_size, st.st_mtime_ns,
                                _sha256(path) if verify_checksum else None))
        else:
            counts["unchanged"] += 1

    deleted = [p for p in prior if p not in seen]
    counts["deleted"] = len(deleted)
    return pending, touches, counts, deleted


# --------------------------------------------------------------------------- #
# Main                                                                         #
# --------------------------------------------------------------------------- #

def main():
    ap = argparse.ArgumentParser(description="Historical CSV -> TimescaleDB ingest")
    ap.add_argument("--root", required=True, help="Local root to walk (copy off Drive first)")
    ap.add_argument("--dsn", default="postgresql://artha:artha@127.0.0.1:5432/artha",
                    help="Use 127.0.0.1, NOT localhost — localhost resolves to IPv6 ::1 "
                         "first and libpq stalls ~130s on the SYN timeout before IPv4 fallback")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--manifest", default="historical_manifest.sqlite")
    ap.add_argument("--load-mode", choices=["upsert", "replace"], default="upsert",
                    help="upsert = incremental-safe ON CONFLICT (default); "
                         "replace = fast bucket-range DELETE + direct COPY (virgin backfill)")
    ap.add_argument("--verify-checksum", action="store_true",
                    help="When mtime moved but size is unchanged, confirm with sha256")
    ap.add_argument("--dry-run", action="store_true", help="Parse only; no DB, no manifest")
    ap.add_argument("--mark-deleted", action="store_true",
                    help="Mark manifest rows whose file vanished as status='deleted'")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    log = logging.getLogger("ingest")

    root = Path(args.root)
    if not root.is_dir():
        log.error("root not a directory: %s", root)
        sys.exit(1)

    from tqdm import tqdm

    manifest = None if args.dry_run else open_manifest(Path(args.manifest))
    prior = load_fingerprints(manifest) if manifest else {}

    log.info("scanning %s ...", root)
    pending, touches, counts, deleted = classify_changes(root, prior, args.verify_checksum, log)
    log.info("new=%d modified=%d unchanged=%d skipped_empty=%d unknown=%d deleted=%d",
             counts["new"], counts["modified"], counts["unchanged"],
             counts["skipped_empty"], counts["unknown"], counts["deleted"])

    if manifest and touches:
        manifest.executemany(
            "UPDATE manifest SET mtime_ns=? WHERE path=?",
            [(mt, p) for p, mt in touches])
        manifest.commit()

    if manifest and args.mark_deleted and deleted:
        now = datetime.now(UTC).isoformat()
        manifest.executemany(
            "UPDATE manifest SET status='deleted', loaded_at=? WHERE path=?",
            [(now, p) for p in deleted])
        manifest.commit()

    if not pending:
        log.info("Nothing to load.")
        return

    tasks = [(p, kind) for (p, kind, _sz, _mt, _sha) in pending]
    meta = {p: (sz, mt, sha) for (p, _k, sz, mt, sha) in pending}
    worker = _worker_dry if args.dry_run else _worker_fn

    errors, ok = [], 0
    with multiprocessing.Pool(processes=args.workers, initializer=_init_worker,
                              initargs=(args.dsn, args.load_mode)) as pool:
        for path_str, status, nrows, msg in tqdm(
                pool.imap_unordered(worker, tasks), total=len(tasks), unit="file"):
            if status == "loaded":
                ok += 1
                if args.verbose:
                    log.debug("OK %s — %s", path_str, msg)
            else:
                errors.append((path_str, msg))
                log.warning("ERR %s — %s", path_str, msg)

            if manifest:
                sz, mt, sha = meta[path_str]
                manifest.execute(
                    "INSERT INTO manifest (path, size, mtime_ns, sha256, rows, status, loaded_at) "
                    "VALUES (?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET "
                    "size=excluded.size, mtime_ns=excluded.mtime_ns, sha256=excluded.sha256, "
                    "rows=excluded.rows, status=excluded.status, loaded_at=excluded.loaded_at",
                    (path_str, sz, mt, sha, nrows, status, datetime.now(UTC).isoformat()))
                if (ok + len(errors)) % 500 == 0:
                    manifest.commit()

    if manifest:
        manifest.commit()
        manifest.close()
    log.info("Done. loaded=%d errors=%d", ok, len(errors))
    if errors:
        log.warning("First 10 errors:")
        for k, m in errors[:10]:
            log.warning("  %s: %s", k, m)
        sys.exit(1)


if __name__ == "__main__":
    main()
