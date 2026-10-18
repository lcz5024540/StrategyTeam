from pathlib import Path

import pandas as pd

from htx_trend.research.reports.report_generator import ReportGenerator


def test_smoke_report_includes_overlap_deviation_distribution(tmp_path):
    gen = ReportGenerator("SMOKE-20261011-01", tmp_path)
    overlap = {
        "BTC-USDT": {
            "4hour": {
                "count": 10,
                "median": 0.001,
                "p95": 0.004,
                "max": 0.01,
                "bars_over_threshold": 2,
                "threshold": 0.003,
            }
        }
    }
    path = gen.generate_report(
        config={"run_id": "SMOKE-20261011-01", "run_type": "SMOKE"},
        fills=[],
        decisions=pd.DataFrame(),
        overlap_deviation=overlap,
        smoke=True,
    )
    text = path.read_text()
    assert "SMOKE" in text
    assert "重叠偏差分布" in text
    assert "BTC-USDT" in text
    assert "0.001" in text
    assert "bars_over_threshold" in text or "2" in text
