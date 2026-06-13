"""§D.7 Redis transport: the TrialDispatcher XADDs trial jobs and drains results
via cg-optuna (fakeredis — no live Redis)."""

import fakeredis

from app.streams import RESULTS_STREAM, TRIALS_STREAM, TrialDispatcher


def _dispatcher():
    return TrialDispatcher(fakeredis.FakeRedis(), consumer="t1")


def test_dispatch_enqueues_a_trial_job():
    dispatcher = _dispatcher()
    dispatcher.dispatch("trial-1")
    entries = dispatcher._redis.xrange(TRIALS_STREAM)  # noqa: SLF001 - test introspection
    assert entries
    assert entries[0][1][b"jobId"] == b"trial-1"


def test_read_results_returns_and_acks():
    dispatcher = _dispatcher()
    dispatcher.ensure_group()
    dispatcher._redis.xadd(  # noqa: SLF001
        RESULTS_STREAM, {"trialId": "trial-1", "metrics": "{\"sharpe\": 1.2}"}
    )

    results = dispatcher.read_results(max_count=10, block_ms=50)
    assert len(results) == 1
    assert results[0]["trialId"] == "trial-1"
    assert results[0]["metrics"] == '{"sharpe": 1.2}'

    # acked → not redelivered
    assert dispatcher.read_results(max_count=10, block_ms=50) == []


def test_ensure_group_is_idempotent():
    dispatcher = _dispatcher()
    dispatcher.ensure_group()
    dispatcher.ensure_group()  # BUSYGROUP swallowed
