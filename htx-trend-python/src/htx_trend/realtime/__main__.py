"""Public capture and offline tools; no trade credentials or trading client."""

import argparse
import json
import time
from pathlib import Path
from .feed import PublicStream
from .storage import SegmentRecorder


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    capture = sub.add_parser("capture")
    capture.add_argument("--contracts", default="ETH-USDT")
    capture.add_argument("--seconds", type=float, default=30)
    capture.add_argument("--output", type=Path, required=True)
    capture.add_argument("--max-bytes", type=int, default=2 * 1024**3)
    verify = sub.add_parser("verify")
    verify.add_argument("--input", type=Path, required=True)
    audit = sub.add_parser("audit-capture")
    audit.add_argument("--input", type=Path, required=True)
    audit.add_argument("--output", type=Path, required=True)
    replay = sub.add_parser("replay")
    replay.add_argument("--input", type=Path, required=True)
    replay.add_argument("--sha256", required=True)
    replay.add_argument("--evidence-root", type=Path)
    replay.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command in ("audit-capture", "replay"):
        from .replay import audit_capture, load, replay as run_replay, write_report

        try:
            result = (
                audit_capture(args.input)
                if args.command == "audit-capture"
                else run_replay(
                    load(args.input, args.sha256), evidence_root=args.evidence_root
                )
            )
        except (ValueError, KeyError) as error:
            result = {
                "status": "NOT_ELIGIBLE",
                "blockers": [
                    "INPUT_REJECTED:" + type(error).__name__ + ":" + str(error)
                ],
                "production_changes": False,
            }
            write_report(result, args.output)
            raise SystemExit(2)
        write_report(result, args.output)
        print(
            json.dumps(
                {
                    "status": result["status"],
                    "blockers": result["blockers"],
                    "output": str(args.output),
                }
            )
        )
        return
    if args.command == "verify":
        if not args.input.is_dir():
            raise SystemExit("capture directory does not exist")
        recorder = SegmentRecorder(args.input)
        print(
            json.dumps(
                {"events_verified": len(recorder.verify()), "bytes": recorder.used}
            )
        )
        return
    stream = PublicStream(tuple(args.contracts.split(",")))
    recorder = SegmentRecorder(args.output, max_bytes=args.max_bytes)
    counts = {}
    epochs = set()
    deadline = time.monotonic() + args.seconds
    last_flush = time.monotonic()
    stream.start()
    try:
        while time.monotonic() < deadline and not recorder.failure:
            for event in stream.drain():
                counts[event.kind] = counts.get(event.kind, 0) + 1
                epochs.add(event.epoch)
                recorder.append(event)
            if time.monotonic() - last_flush >= 10:
                recorder.flush()
                last_flush = time.monotonic()
            time.sleep(0.1)
    finally:
        stream.close()
        recorder.flush()
    result = {
        "schema": "realtime-public-capture-v1",
        "counts": counts,
        "epochs": len(epochs),
        "failure": recorder.failure or stream.failure,
        "bytes": recorder.used,
        "observed_both_channels": all(counts.get(k, 0) > 0 for k in ("TRADE", "BBO")),
        "events_verified": len(recorder.verify()),
        "trading": False,
    }
    print(json.dumps(result))
    if not result["observed_both_channels"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
