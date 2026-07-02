"""TrialDispatcher per-sweep routing (audit P1-10b) against a stub Redis client: entries for
ANOTHER sweep are parked (not destroyed after the XACK) and delivered to their owner's next
read; ``sweepId``-less entries keep the legacy deliver-to-caller shape. No fakeredis dep —
the stub implements exactly the three calls the dispatcher makes."""

from __future__ import annotations

from typing import Any

from app.streams import RESULTS_GROUP, RESULTS_STREAM, TrialDispatcher


class StubRedis:
    """Serves queued xreadgroup batches and records acks/adds."""

    def __init__(self) -> None:
        self.batches: list[list[tuple[bytes, dict[bytes, bytes]]]] = []
        self.acked: list[Any] = []
        self.added: list[tuple[str, dict]] = []

    def xreadgroup(self, group, consumer, streams, count, block):
        assert group == RESULTS_GROUP and RESULTS_STREAM in streams
        if not self.batches:
            return []
        return [(RESULTS_STREAM.encode(), self.batches.pop(0))]

    def xack(self, stream, group, entry_id) -> None:
        self.acked.append(entry_id)

    def xadd(self, stream, fields) -> None:
        self.added.append((stream, fields))


def entry(entry_id: str, trial: str, sweep: str | None) -> tuple[bytes, dict[bytes, bytes]]:
    fields = {b"trialId": trial.encode(), b"metrics": b"{}"}
    if sweep is not None:
        fields[b"sweepId"] = sweep.encode()
    return entry_id.encode(), fields


def test_other_sweeps_results_are_parked_and_delivered_to_their_owner() -> None:
    stub = StubRedis()
    dispatcher = TrialDispatcher(stub)  # type: ignore[arg-type]
    # One batch interleaving sweep A's and sweep B's results.
    stub.batches.append([entry("1-1", "trial-a1", "sweep-a"), entry("1-2", "trial-b1", "sweep-b")])

    got_a = dispatcher.read_results(max_count=10, sweep_id="sweep-a")
    assert [r["trialId"] for r in got_a] == ["trial-a1"]
    assert len(stub.acked) == 2  # both entries acked — B's is parked, NOT destroyed

    got_b = dispatcher.read_results(max_count=10, sweep_id="sweep-b")
    assert [r["trialId"] for r in got_b] == ["trial-b1"]


def test_sweepless_entries_keep_the_legacy_deliver_to_caller_shape() -> None:
    stub = StubRedis()
    dispatcher = TrialDispatcher(stub)  # type: ignore[arg-type]
    stub.batches.append([entry("1-1", "trial-x", None)])

    got = dispatcher.read_results(max_count=10, sweep_id="sweep-a")
    assert [r["trialId"] for r in got] == ["trial-x"]


def test_no_sweep_id_reads_everything() -> None:
    stub = StubRedis()
    dispatcher = TrialDispatcher(stub)  # type: ignore[arg-type]
    stub.batches.append([entry("1-1", "t1", "sweep-a"), entry("1-2", "t2", "sweep-b")])

    got = dispatcher.read_results(max_count=10)
    assert [r["trialId"] for r in got] == ["t1", "t2"]
