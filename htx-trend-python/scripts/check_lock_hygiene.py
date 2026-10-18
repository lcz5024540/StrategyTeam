#!/usr/bin/env python3
"""Fail if requirements.lock contains known Ubuntu/system-only packages."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

SYSTEM_ONLY_PACKAGES = frozenset(
    {
        "dbus-python",
        "pygobject",
        "python-apt",
        "python-debian",
        "launchpadlib",
        "lazr.restfulclient",
        "lazr.uri",
        "wadllib",
        "systemd-python",
        "apt",
        "apt-xapian-index",
        "commandnotfound",
        "distupgrade",
        "languageselector",
        "ufw",
        "unattended-upgrades",
        "software-properties",
    }
)


def lock_requirement_names(text: str) -> set[str]:
    names: set[str] = set()
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("--") or line.startswith("\\"):
            continue
        token = line.split()[0]
        name = token.split("[", 1)[0]
        for sep in ("===", "==", "~=", "!=", "<=", ">=", "<", ">"):
            if sep in name:
                name = name.split(sep, 1)[0]
                break
        name = name.strip().lower().replace("_", "-")
        if name:
            names.add(name)
    return names


def check_lock(path: Path) -> list[str]:
    if not path.is_file():
        raise FileNotFoundError(f"lock file not found: {path}")
    names = lock_requirement_names(path.read_text())
    return sorted(names & SYSTEM_ONLY_PACKAGES)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "lock_file",
        nargs="?",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "requirements.lock",
    )
    args = parser.parse_args(argv)
    leaked = check_lock(args.lock_file)
    if leaked:
        print(
            f"ERROR: {args.lock_file} contains system-only packages: {leaked}",
            file=sys.stderr,
        )
        return 1
    print(f"OK: {args.lock_file} has no known system-only packages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
