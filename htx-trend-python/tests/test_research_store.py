from pathlib import Path

import pytest

from htx_trend.research.store import FileEvidenceStore, IntegrityError


def test_content_addressed_store_is_idempotent_and_detects_tampering(tmp_path: Path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        first = store.put("raw", {"value": 1})
        assert store.put("raw", {"value": 1}) == first
        child = store.put("normalized", {"value": 2}, refs=(first,))
        assert store.verify(child) == {"root": child, "objects": 2, "integrity": "VERIFIED"}
        store._path(first).write_text("{}")
        with pytest.raises(IntegrityError, match="hash mismatch"):
            store.verify(child)


def test_checkpoint_and_outbox_survive_restart_and_retry(tmp_path: Path) -> None:
    store = FileEvidenceStore(tmp_path)
    key = store.put("manifest", {"pages": []})
    store.checkpoint("collector:BTC-USDT:15min", {"end": 900})
    attempts = 0

    def flaky_sink(_key, _value):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise ConnectionError("offline")

    assert store.deliver(flaky_sink) == {"attempted": 1, "delivered": 0}
    store.close()
    with FileEvidenceStore(tmp_path) as reopened:
        assert reopened.checkpoint("collector:BTC-USDT:15min") == {"end": 900}
        assert reopened.deliver(flaky_sink) == {"attempted": 1, "delivered": 1}
        assert reopened.get(key)["kind"] == "manifest"
