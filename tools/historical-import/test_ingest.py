"""
Unit tests for the pure parsing logic in ingest.py (no DB required).

Run:  cd tools/historical-import && python -m pytest test_ingest.py -q
"""
from datetime import date, datetime, timezone
from pathlib import Path

import ingest as ig

UTC = timezone.utc


# --------------------------------------------------------------------------- #
# classify_path                                                               #
# --------------------------------------------------------------------------- #

def test_classify_option_source_a():
    p = Path("opt/nifty/2014/2014-01-30/NIFTY_6200_CE_30_JAN_14.csv")
    assert ig.classify_path(p) == "OPTION"


def test_classify_option_source_b():
    p = Path("data/options/stocks/NHPC/2025-10-28/NHPC_85_PE_28_OCT_25.csv")
    assert ig.classify_path(p) == "OPTION"


def test_classify_spot():
    assert ig.classify_path(Path("opt/2014-2024/nifty_spot.csv")) == "SPOT"
    assert ig.classify_path(Path("opt/2014-2024/banknifty_spot.csv")) == "SPOT"


def test_classify_equity():
    assert ig.classify_path(Path("data/equity/day/0031_BAJAJFINSV.csv")) == "EQUITY"
    assert ig.classify_path(Path("data/equity/minute/0975_VIDHIING.csv")) == "EQUITY"


def test_classify_iv():
    assert ig.classify_path(Path("data/implied volatility/SBIN.csv")) == "IV"


def test_classify_fundamental():
    p = Path("data/fundamentals/ASTRAL/balance_sheet.csv")
    assert ig.classify_path(p) == "FUNDAMENTAL"


def test_classify_unknown():
    assert ig.classify_path(Path("data/fundamentals/ASTRAL/unknown_file.csv")) == "UNKNOWN"


# --------------------------------------------------------------------------- #
# parse_option / parse_equity                                                 #
# --------------------------------------------------------------------------- #

def test_parse_option_nifty_ce():
    i = ig.parse_option("NIFTY_6200_CE_30_JAN_14")
    assert i["exchange"] == "NFO"
    assert i["tradingsymbol"] == "NIFTY14JAN6200CE"
    assert i["instrument_type"] == "CE"
    assert i["expiry"] == date(2014, 1, 30)
    assert i["strike"] == 6200


def test_parse_option_sensex_routes_bfo():
    i = ig.parse_option("SENSEX_73200_PE_06_DEC_24")
    assert i["exchange"] == "BFO"
    assert i["underlying_exchange"] == "BSE"
    assert i["tradingsymbol"] == "SENSEX24DEC73200PE"


def test_classify_option_hyphen_symbol():
    p = Path("data/options/stocks/BAJAJ-AUTO/2025-04-24/BAJAJ-AUTO_8300_CE_24_APR_25.csv")
    assert ig.classify_path(p) == "OPTION"


def test_parse_option_hyphen_symbol():
    i = ig.parse_option("BAJAJ-AUTO_8300_CE_24_APR_25")
    assert i["underlying_tradingsymbol"] == "BAJAJ-AUTO"
    assert i["tradingsymbol"] == "BAJAJ-AUTO25APR8300CE"
    assert i["strike"] == 8300


def test_parse_option_future():
    i = ig.parse_option("NIFTY_24500_FUT_26_DEC_24")
    assert i["instrument_type"] == "FUT"
    assert i["tradingsymbol"] == "NIFTY24DECFUT"
    assert i["strike"] is None


def test_parse_equity_strips_numeric_id():
    i = ig.parse_equity("0031_BAJAJFINSV")
    assert i["tradingsymbol"] == "BAJAJFINSV"
    assert i["instrument_type"] == "EQ"
    assert i["exchange"] == "NSE"


# --------------------------------------------------------------------------- #
# date format detection                                                       #
# --------------------------------------------------------------------------- #

def test_parse_split_date_iso():
    assert ig._parse_split_date("2019-07-09") == datetime(2019, 7, 9)


def test_parse_split_date_dmy():
    assert ig._parse_split_date("28-12-2020") == datetime(2020, 12, 28)


def test_detect_schema_split_no_oi():
    s = ig.detect_candle_schema(["date", "time", "open", "high", "low", "close", "volume"])
    assert s["mode"] == "split"
    assert s["has_oi"] is False
    assert s["oi"] is None


def test_detect_schema_split_with_oi():
    s = ig.detect_candle_schema(["date", "time", "open", "high", "low", "close", "volume", "oi"])
    assert s["mode"] == "split"
    assert s["has_oi"] is True


def test_detect_schema_iso_timestamp():
    s = ig.detect_candle_schema(["timestamp", "open", "high", "low", "close", "volume", "oi"])
    assert s["mode"] == "iso"
    assert s["has_oi"] is True


def test_detect_schema_iso_date_col():
    # nifty_spot / equity: single ISO 'Date' column, no 'time'
    s = ig.detect_candle_schema(["Date", "Open", "High", "Low", "Close", "Volume"])
    assert s["mode"] == "iso"
    assert s["has_oi"] is False


# --------------------------------------------------------------------------- #
# candle row parsing (writes temp CSVs)                                        #
# --------------------------------------------------------------------------- #

def _write(tmp_path, name, text):
    p = tmp_path / name
    p.write_text(text, encoding="utf-8")
    return p


def test_parse_candle_split_dmy_with_oi(tmp_path):
    p = _write(tmp_path, "NHPC_85_PE_28_OCT_25.csv",
               "date,time,open,high,low,close,volume,oi\n"
               "28-12-2020,09:15,3.0,3.0,2.25,2.3,1450,19975.0\n")
    instr = ig.parse_option("NHPC_85_PE_28_OCT_25")
    rows = ig.parse_candle_rows(p, instr, "1m")
    assert len(rows) == 1
    r = rows[0]
    # 09:15 IST -> 03:45 UTC
    assert r[3] == datetime(2020, 12, 28, 3, 45, tzinfo=UTC)
    assert r[9] == 19975  # oi


def test_parse_candle_split_iso_no_oi(tmp_path):
    p = _write(tmp_path, "NIFTY_6200_CE_30_JAN_14.csv",
               "date,time,open,high,low,close,volume\n"
               "2019-07-09,09:17,123.45,134.25,123.2,134.25,450\n")
    instr = ig.parse_option("NIFTY_6200_CE_30_JAN_14")
    rows = ig.parse_candle_rows(p, instr, "1m")
    assert len(rows) == 1
    assert rows[0][9] is None  # oi absent -> NULL
    assert rows[0][8] == 450   # volume


def test_parse_candle_iso_timestamp_with_offset(tmp_path):
    p = _write(tmp_path, "NHPC_85_PE_28_OCT_25.csv",
               "timestamp,open,high,low,close,volume,oi\n"
               "2025-09-12T12:57:00+05:30,3.7,3.7,3.7,3.7,12800,38400\n")
    instr = ig.parse_option("NHPC_85_PE_28_OCT_25")
    rows = ig.parse_candle_rows(p, instr, "1m")
    assert len(rows) == 1
    # 12:57 IST -> 07:27 UTC
    assert rows[0][3] == datetime(2025, 9, 12, 7, 27, tzinfo=UTC)
    assert rows[0][9] == 38400


def test_duplicate_bucket_deduped_last_wins(tmp_path):
    # source data sometimes repeats a minute; replace-mode direct COPY would hit the
    # candles PK without dedup. Keep last occurrence.
    p = _write(tmp_path, "NIFTY_7700_CE_29_DEC_16.csv",
               "date,time,open,high,low,close,volume\n"
               "2016-11-17,09:19,10,10,10,10,100\n"
               "2016-11-17,09:19,20,20,20,20,200\n"
               "2016-11-17,09:20,30,30,30,30,300\n")
    instr = ig.parse_option("NIFTY_7700_CE_29_DEC_16")
    rows = ig.parse_candle_rows(p, instr, "1m")
    assert len(rows) == 2  # 09:19 collapsed
    by = {r[3]: r for r in rows}
    nineteen = [r for r in rows if r[3] == datetime(2016, 11, 17, 3, 49, tzinfo=UTC)][0]
    assert nineteen[7] == 20  # last close wins
    assert nineteen[8] == 200


def test_oi_zero_becomes_null(tmp_path):
    p = _write(tmp_path, "X_1_CE_30_JAN_14.csv",
               "date,time,open,high,low,close,volume,oi\n"
               "30-01-2014,09:15,1,1,1,1,0,0\n")
    instr = ig.parse_option("X_1_CE_30_JAN_14")
    rows = ig.parse_candle_rows(p, instr, "1m")
    assert rows[0][9] is None


# --------------------------------------------------------------------------- #
# IV parsing                                                                  #
# --------------------------------------------------------------------------- #

def test_parse_iv_rows(tmp_path):
    p = _write(tmp_path, "JIOFIN.csv",
               "Date,Open,High,Low,Close,IV,Rank,IV Percentile\n"
               "2024-11-29,330.0,334.5,327.2,330.6,28.746,1,100.0\n")
    rows = ig.parse_iv_rows(p)
    assert len(rows) == 1
    r = rows[0]
    assert r[0] == "JIOFIN"
    assert r[1] == date(2024, 11, 29)
    assert float(r[6]) == 28.746
    assert float(r[8]) == 100.0


# --------------------------------------------------------------------------- #
# Fundamentals transpose                                                       #
# --------------------------------------------------------------------------- #

def test_parse_period_month_end():
    assert ig.parse_period("Mar 2023") == date(2023, 3, 31)
    assert ig.parse_period("Jun 2023") == date(2023, 6, 30)
    assert ig.parse_period("Dec 2024") == date(2024, 12, 31)
    assert ig.parse_period("garbage") is None


def test_clean_value():
    assert ig.clean_value('"1,361"') == (1361, False)
    v, pct = ig.clean_value("59.34%")
    assert float(v) == 59.34 and pct is True
    assert ig.clean_value("") == (None, False)


def test_clean_metric_strips_mojibake():
    assert ig.clean_metric("Sales\xa0+") == "Sales"
    assert ig.clean_metric("ROCE %") == "ROCE %"


def test_parse_fundamental_transpose(tmp_path):
    fund_dir = tmp_path / "ASTRAL"
    fund_dir.mkdir()
    p = fund_dir / "balance_sheet.csv"
    p.write_text(
        ",Mar 2023,Mar 2024\n"
        "Equity Capital,27,27\n"
        'Reserves,"2,652","3,103"\n',
        encoding="utf-8")
    rows = ig.parse_fundamental_rows(p)
    # 2 metrics x 2 periods = 4 rows
    assert len(rows) == 4
    by = {(r[4], r[2]): r[5] for r in rows}
    assert by[("Equity Capital", date(2023, 3, 31))] == 27
    assert by[("Reserves", date(2024, 3, 31))] == 3103
    assert all(r[0] == "ASTRAL" and r[1] == "balance_sheet" for r in rows)


def test_fundamental_skips_raw_pdf(tmp_path):
    fund_dir = tmp_path / "ASTRAL"
    fund_dir.mkdir()
    p = fund_dir / "quarterly_results.csv"
    p.write_text(
        ",Mar 2023\n"
        "Sales,1361\n"
        "Raw PDF,\n",
        encoding="utf-8")
    rows = ig.parse_fundamental_rows(p)
    metrics = {r[4] for r in rows}
    assert "Sales" in metrics
    assert "Raw PDF" not in metrics
