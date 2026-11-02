"""Write EXP-SPRT Phase-0 fixture examples. Does not submit orders."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from .evidence import DEFAULT_EVIDENCE_ROOT
from .samples import write_phase0_examples


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Write EXP-SPRT Phase-0 exploration examples")
    parser.add_argument("--evidence-root", default=str(DEFAULT_EVIDENCE_ROOT))
    args = parser.parse_args(argv)
    manifest = write_phase0_examples(Path(args.evidence_root))
    print(json.dumps({"evidence_root": args.evidence_root, "plan_ids": manifest["plan_ids"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
