"""Read-only, standard-library capture prefix export; safe to run via SSH stdout."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

KEEP = {"TRADE", "BBO", "FRAME", "FUNDING", "GAP", "SOURCE_ERROR"}


def canonical(value):
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode()


def export_prefix(root, manifest, output):
    counts, exported = Counter(), Counter()
    seen = set()
    with gzip.GzipFile(fileobj=output, mode="wb", compresslevel=1, mtime=0) as stream:

        def emit(value):
            stream.write(canonical(value) + b"\n")

        emit(
            {
                "type": "HEADER",
                "manifest_sha256": manifest["manifest_sha256"],
                "first_ms": manifest["first_ms"],
                "last_ms": manifest["last_ms"],
                "contracts": manifest["capture"]["contracts"],
                "prefix_segments": len(manifest["segments"]),
                "scope": "PUBLIC_OBSERVATIONS_ONLY_NOT_EXECUTABLE",
                "excluded_kinds": ["DEPTH", "CONTRACT_METADATA"],
            }
        )
        for ref in manifest["segments"]:
            sha = ref["hash"]
            if not re.fullmatch("[0-9a-f]{64}", sha) or sha in seen:
                raise ValueError("invalid/duplicate segment identity")
            seen.add(sha)
            packed = (Path(root) / "segments" / (sha + ".json.gz")).read_bytes()
            raw = gzip.decompress(packed)
            if len(packed) != ref["bytes"] or hashlib.sha256(raw).hexdigest() != sha:
                raise ValueError("segment integrity failure")
            value = json.loads(raw)
            if len(value["events"]) != ref["count"]:
                raise ValueError("segment count failure")
            for index, event in enumerate(value["events"]):
                counts[event["kind"]] += 1
                if event["kind"] not in KEEP:
                    continue
                exported[event["kind"]] += 1
                emit(
                    {
                        "type": "EVENT",
                        "observation": event,
                        "source_ref": {
                            "segment": sha,
                            "index": index,
                            "event_hash": hashlib.sha256(canonical(event)).hexdigest(),
                        },
                    }
                )
        emit(
            {
                "type": "COMPLETE",
                "segments_verified": len(seen),
                "source_counts": dict(counts),
                "exported_counts": dict(exported),
            }
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True)
    args = parser.parse_args()
    export_prefix(args.root, json.load(sys.stdin), sys.stdout.buffer)


if __name__ == "__main__":
    main()
