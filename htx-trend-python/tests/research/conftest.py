"""Research-test fixtures."""

from __future__ import annotations

import pytest


@pytest.fixture(autouse=True)
def _skip_runtime_integrity_unless_marked(monkeypatch, request):
    if request.node.get_closest_marker("require_runtime_integrity"):
        return
    monkeypatch.setattr(
        "htx_trend.research.runner.require_runtime_integrity",
        lambda *args, **kwargs: None,
    )
