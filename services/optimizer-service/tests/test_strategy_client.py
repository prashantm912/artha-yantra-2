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


# --- EVO E4 slice 2: sibling-clone materialization (create / publish / detail / list) ------------


@respx.mock
def test_create_posts_the_clone_and_serializes_config():
    route = respx.post("http://sig/api/v1/strategies").mock(
        return_value=httpx.Response(201, json={"id": "clone-1", "version": "1.0.0",
                                               "status": "draft", "checksum": "c"})
    )
    client = StrategyClient("http://sig")
    out = client.create(
        name="Clone", description="cloned_from base",
        tags=["manas-arora", "evo"], config={"id": "base--evo-g1-abc"}, created_by="evo:camp-1",
    )
    assert out["id"] == "clone-1"
    sent = json.loads(route.calls.last.request.content)
    assert sent["name"] == "Clone"
    assert sent["tags"] == ["manas-arora", "evo"]
    assert sent["createdBy"] == "evo:camp-1"
    # config is serialized to the JSON string the registry parser accepts
    assert json.loads(sent["config"]) == {"id": "base--evo-g1-abc"}


@respx.mock
def test_create_omits_created_by_when_absent():
    route = respx.post("http://sig/api/v1/strategies").mock(
        return_value=httpx.Response(201, json={"id": "clone-1", "status": "draft"})
    )
    client = StrategyClient("http://sig")
    client.create(name="Clone", description=None, tags=[], config={"id": "x"})
    assert "createdBy" not in json.loads(route.calls.last.request.content)


@respx.mock
def test_create_raises_on_409_conflict():
    respx.post("http://sig/api/v1/strategies").mock(
        return_value=httpx.Response(409, json={"code": "CONFLICT_SLUG_EXISTS"})
    )
    client = StrategyClient("http://sig")
    try:
        client.create(name="Clone", description=None, tags=[], config={"id": "x"})
        raise AssertionError("expected HTTPStatusError")
    except httpx.HTTPStatusError as exc:
        assert exc.response.status_code == 409


@respx.mock
def test_publish_posts_to_the_publish_route():
    route = respx.post("http://sig/api/v1/strategies/clone-1/publish").mock(
        return_value=httpx.Response(200, json={"id": "clone-1", "version": "1.0.0",
                                               "status": "published"})
    )
    client = StrategyClient("http://sig")
    out = client.publish("clone-1", notes="evo paper clone publish")
    assert out["status"] == "published"
    assert json.loads(route.calls.last.request.content)["notes"] == "evo paper clone publish"


@respx.mock
def test_detail_returns_version_id():
    respx.get("http://sig/api/v1/strategies/clone-1").mock(
        return_value=httpx.Response(200, json={"id": "clone-1", "versionId": "ver-9",
                                               "version": "1.0.0", "status": "published"})
    )
    client = StrategyClient("http://sig")
    out = client.detail("clone-1")
    assert out["versionId"] == "ver-9"


@respx.mock
def test_list_strategies_unwraps_items_and_passes_filters():
    route = respx.get("http://sig/api/v1/strategies").mock(
        return_value=httpx.Response(200, json={"items": [{"id": "a", "tags": ["manas-arora",
                                                                              "evo"]}]})
    )
    client = StrategyClient("http://sig")
    out = client.list_strategies(tag="evo", status="published")
    assert out == [{"id": "a", "tags": ["manas-arora", "evo"]}]
    assert route.calls.last.request.url.params["tag"] == "evo"
    assert route.calls.last.request.url.params["status"] == "published"
