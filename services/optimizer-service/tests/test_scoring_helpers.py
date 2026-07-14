"""Unit tests for the pure §6 scoring helper functions (parsing / coverage / numeric coercion)."""

from __future__ import annotations

from app import scoring


def test_parse_fraction() -> None:
    assert scoring._parse_fraction("42/45") == 42 / 45
    assert scoring._parse_fraction("1.5/3") == 0.5
    assert scoring._parse_fraction("3/0") is None  # zero denominator
    assert scoring._parse_fraction("abc") is None  # no "/"
    assert scoring._parse_fraction("5") is None  # no "/"
    assert scoring._parse_fraction("x/2") is None  # unparseable numerator
    assert scoring._parse_fraction(None) is None
    assert scoring._parse_fraction(123) is None  # not a str


def test_oi_below_floor() -> None:
    # floor is 0.80
    assert scoring._oi_below_floor("30/45") is True  # 0.666 < 0.80
    assert scoring._oi_below_floor("40/45") is False  # 0.888 > 0.80
    assert scoring._oi_below_floor("36/45") is False  # exactly 0.80, not strictly below
    assert scoring._oi_below_floor(None) is False
    assert scoring._oi_below_floor("3/0") is False  # unparseable -> not below


def test_num() -> None:
    assert scoring._num(3) == 3.0
    assert scoring._num("2.5") == 2.5
    assert scoring._num(None) is None
    assert scoring._num("x") is None
    assert scoring._num([]) is None  # TypeError path


def test_ratio_and_neg() -> None:
    assert scoring._ratio(10, 2) == 5.0
    assert scoring._ratio(1, 0) is None  # zero denominator
    assert scoring._ratio("x", 2) is None
    assert scoring._neg(3) == -3.0
    assert scoring._neg(None) is None


def test_round_passthrough() -> None:
    assert scoring._round(1.23456) == 1.2346
    assert scoring._round(5) == 5
    assert scoring._round("keep") == "keep"  # non-numeric passes through


def test_sort_key_orders_ints_before_others() -> None:
    assert scoring._sort_key(5) == (0, 5)
    assert scoring._sort_key("abc") == (1, "abc")
    assert scoring._sort_key(1.5) == (1, "1.5")  # float is not int -> stringified bucket


def test_dedupe_preserves_first_seen_order() -> None:
    assert scoring._dedupe(["a", "b", "a", "c", "b"]) == ["a", "b", "c"]
    assert scoring._dedupe([]) == []
    assert scoring._dedupe(["x"]) == ["x"]
