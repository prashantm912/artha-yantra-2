"""§D.8 spec-derived consumer contract: the strategy-signal client reads a version
config (respx stub — no live service)."""

import json

import httpx
import respx

from app.strategy_client import StrategyClient


@respx.mock
def test_version_config_unwraps_the_config():
    respx.get("http://sig/api/v1/strategies/s1/versions/1.0.0").mock(
        return_value=httpx.Response(200, json={"config": {"indicators": []}, "checksum": "c"})
    )
    client = StrategyClient("http://sig")
    assert client.version_config("s1", "1.0.0") == {"indicators": []}


@respx.mock
def test_create_draft_posts_a_minor_bump():
    route = respx.put("http://sig/api/v1/strategies/s1").mock(
        return_value=httpx.Response(200, json={"version": "1.1.0", "status": "draft"})
    )
    client = StrategyClient("http://sig")
    out = client.create_draft("s1", {"indicators": []}, notes="from sweep")
    assert out["status"] == "draft"
    assert route.called
    # no actor passed → the createdBy key is omitted (registry defaults to 'owner')
    assert "createdBy" not in json.loads(route.calls.last.request.content)


@respx.mock
def test_create_draft_stamps_the_actor_when_given():
    """Audit T3: a promote sends `created_by=optimizer:{sweepId}` in the PUT body so the version's
    `created_by` column carries machine-readable provenance."""
    route = respx.put("http://sig/api/v1/strategies/s1").mock(
        return_value=httpx.Response(200, json={"version": "1.1.0", "status": "draft"})
    )
    client = StrategyClient("http://sig")
    client.create_draft("s1", {"indicators": []}, notes="n", created_by="optimizer:sweep-9")
    assert json.loads(route.calls.last.request.content)["createdBy"] == "optimizer:sweep-9"
