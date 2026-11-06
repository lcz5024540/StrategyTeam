"""Short read-only public WS smoke test; no accounts, service edits or COS PUTs."""

import json
import tempfile
import threading
import time
from pathlib import Path

from htx_trend.realtime.public_capture import Capture, iter_records


def main():
    root = Path(tempfile.mkdtemp(prefix="cos-ws-diagnostic-smoke-")).resolve() / "capture"
    capture = Capture(root, ["ETH-USDT"], seconds=40, min_free_bytes=0, max_bytes=16 * 1024**2)
    worker = threading.Thread(target=capture.stream_loop, daemon=True)
    started = time.monotonic()
    worker.start()
    try:
        while worker.is_alive() and time.monotonic() - started < 40:
            capture.write_batch(capture.drain_batch())
            snapshot = capture.status()["ws_diagnostics"]
            if (snapshot["htx_heartbeat"]["last_pong_sent_ms"] is not None
                    and snapshot["recovered_channels"] == 2
                    and time.monotonic() - started > 23):
                break
        snapshot = capture.status()["ws_diagnostics"]
    finally:
        capture.stop.set()
        if capture.socket:
            capture.socket.close()
        worker.join(12)
        capture.close_catchup()
        while not capture.events.empty():
            capture.write_batch(capture.drain_batch())
        capture.recorder.flush()
        records = list(iter_records(root))
        capture.client.close()
        capture.lock.close()
    result = dict(
        root=str(root), elapsed_s=round(time.monotonic() - started, 3),
        thread_stopped=not worker.is_alive(), counts=dict(capture.counts),
        snapshot=snapshot, recovery_events=[e for e, _ in records if e["kind"] == "WS_CHANNEL_RECOVERED"],
        diagnostic_errors=capture.diagnostic_errors, failure=capture.failure,
        segments_verified_events=len(records),
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    assert not worker.is_alive()
    assert len(result["recovery_events"]) == 2
    assert snapshot["htx_heartbeat"]["last_pong_sent_ms"] is not None
    assert result["diagnostic_errors"] == 0


if __name__ == "__main__":
    main()
