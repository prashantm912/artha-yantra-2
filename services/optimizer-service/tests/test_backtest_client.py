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
