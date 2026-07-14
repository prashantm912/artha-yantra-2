"""Tests for the fail-soft ntfy client (topic gating, URL normalization, swallowed failures)."""

from __future__ import annotations

import httpx
import respx

from app.notify import NtfyClient


def test_url_default_and_trailing_slash_stripped() -> None:
    assert NtfyClient("https://x.example/", "t")._url == "https://x.example"
    assert NtfyClient("", "t")._url == "https://ntfy.sh"
    assert NtfyClient("https://ntfy.sh", "")._topic == ""


@respx.mock
def test_blank_topic_makes_no_request() -> None:
    NtfyClient("https://ntfy.sh", "").send("Title", "body")
    assert respx.calls.call_count == 0


@respx.mock
def test_send_posts_to_topic_with_headers_and_body() -> None:
    route = respx.post("https://ntfy.sh/mytopic").mock(return_value=httpx.Response(200))
    NtfyClient("https://ntfy.sh", "mytopic").send("Hi", "hello", priority="high")
    assert route.called
    req = route.calls[0].request
    assert req.headers["Title"] == "Hi"
    assert req.headers["Priority"] == "high"
    assert req.content == b"hello"


@respx.mock
def test_send_swallows_transport_failure() -> None:
    respx.post("https://ntfy.sh/t").mock(side_effect=httpx.ConnectError("boom"))
    # A transport failure must never propagate out of send().
    NtfyClient("https://ntfy.sh", "t").send("Hi", "hello")
