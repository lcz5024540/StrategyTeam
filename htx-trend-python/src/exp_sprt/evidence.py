"""JSON evidence writer for the Phase-0 shadow tree."""
from __future__ import annotations

from pathlib import Path
import json

DEFAULT_EVIDENCE_ROOT = Path("/workspace/evidence/exp-sprt-phase0-20261102")


class EvidenceStore:
    """Write plans, zone events, arm A rows and meta under a configurable root."""

    def __init__(self, root: Path | None = None) -> None:
        self.root = Path(root) if root is not None else DEFAULT_EVIDENCE_ROOT

    def write_plan(self, plan: dict) -> Path:
        return self._dump(self.root / "plans" / f"{plan['plan_id']}.json", plan)

    def write_arm_a(self, record: dict) -> Path:
        return self._dump(self.root / "arm_a" / f"{record['plan_id']}.json", record)

    def write_zone_event(self, event: dict) -> Path:
        name = (
            f"{event['plan_id']}-{event.get('primary_predicate', 'na')}-"
            f"{event.get('stage', 'event')}-{event.get('observed_at_ms', 'na')}.json"
        )
        return self._dump(self.root / "zone_events" / name, event)

    def write_meta(self, name: str, payload: dict) -> Path:
        return self._dump(self.root / "meta" / name, payload)

    def _dump(self, path: Path, payload: dict) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return path
