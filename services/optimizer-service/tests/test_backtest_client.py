"""§D.5 consumer contract: the backtest client reads a run's fold array (respx stub — no live
service)."""

import httpx
import respx

from app.backtest_client import BacktestClient


@respx.mock
def test_folds_returns_the_fold_array():
    respx.get("http://bt/api/v1/backtests/run-9/folds").mock(
        return_value=httpx.Response(200, json=[{"fold": 0, "oosMetrics": {"sharpe": "0.7"}}])
    )
    client = BacktestClient("http://bt")
    folds = client.folds("run-9")
    assert folds[0]["fold"] == 0


@respx.mock
def test_guard_summary_assembles_from_folds_and_results():
    respx.get("http://bt/api/v1/backtests/run-9/folds").mock(
        return_value=httpx.Response(
            200, json=[{"fold": 0, "regimeOos": {"BULL": {"sharpe": "0.7", "tradeCount": 4}}}]
        )
    )
    respx.get("http://bt/api/v1/backtests/run-9/results").mock(
        return_value=httpx.Response(200, json={"dataHash": "abc", "metrics": {"foldsExcluded": 2}})
    )
    summary = BacktestClient("http://bt").guard_summary("run-9")
    assert summary == {
        "dataHash": "abc",
        "foldsExcluded": 2,
        "regimeOos": [{"BULL": {"sharpe": "0.7", "tradeCount": 4}}],
    }


@respx.mock
def test_guard_summary_is_none_for_full_window_run():
    respx.get("http://bt/api/v1/backtests/run-fw/folds").mock(
        return_value=httpx.Response(200, json=[])
    )
    assert BacktestClient("http://bt").guard_summary("run-fw") is None
