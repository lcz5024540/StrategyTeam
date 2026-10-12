#!/usr/bin/env python3
"""Print a git tag message summary for v0.1.0. Does not create tags."""

from __future__ import annotations

from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
MANIFEST_PATH = ROOT / "strategy_manifest.yaml"


def main() -> int:
    payload = yaml.safe_load(MANIFEST_PATH.read_text()) or {}
    print("v0.1.0")
    print()
    print(f"strategy_manifest_sha256: {payload.get('manifest_sha256')}")
    print(f"package_version: {payload.get('package_version')}")
    print(f"git_commit: {payload.get('git_commit')}")
    print(f"timeframe: {payload.get('timeframe')}")
    symbols = payload.get("symbols") or []
    print(f"symbols: {', '.join(str(item) for item in symbols)}")
    print(
        "risk: "
        f"initial_equity={payload.get('initial_equity')} "
        f"risk_per_trade={payload.get('risk_per_trade')} "
        f"max_total_risk={payload.get('max_total_risk')} "
        f"max_positions={payload.get('max_positions')}"
    )
    print()
    print("enabled conditions:")
    any_enabled = False
    for row in payload.get("conditions") or []:
        if not row.get("enabled"):
            continue
        any_enabled = True
        params = row.get("params") or {}
        encoded = ", ".join(f"{key}={value!r}" for key, value in params.items())
        print(f"  {row.get('id')} {row.get('name')}: {encoded}")
    if not any_enabled:
        print("  (none)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
