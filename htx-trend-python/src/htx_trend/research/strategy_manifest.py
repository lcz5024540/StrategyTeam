"""Machine-readable strategy condition manifest (C01–C09)."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

import yaml

from ._version import __version__

PACKAGE_ROOT = Path(__file__).resolve().parents[3]
CONDITION_IDS = tuple(f"C0{i}" for i in range(1, 10))

CONDITION_SPECS: dict[str, dict[str, Any]] = {
    "C01": {
        "name": "Donchian Ensemble",
        "implemented": True,
        "code_path": "src/htx_trend/research/conditions/c01_donchian.py",
        "param_keys": ("periods", "threshold"),
    },
    "C02": {
        "name": "ATR Stop",
        "implemented": True,
        "code_path": "src/htx_trend/research/conditions/c02_atr_stop.py",
        "param_keys": ("atr_period", "atr_multiplier", "tick_size", "stop_slippage_ticks"),
    },
    "C03": {
        "name": "Trailing stop / reverse-channel exit",
        "implemented": False,
        "code_path": None,
        "param_keys": (),
    },
    "C04": {
        "name": "EMA 20/60/120 alignment filter",
        "implemented": False,
        "code_path": None,
        "param_keys": (),
    },
    "C05": {
        "name": "Volatility-target sizing",
        "implemented": False,
        "code_path": None,
        "param_keys": (),
    },
    "C06": {
        "name": "Regime gate",
        "implemented": False,
        "code_path": None,
        "param_keys": (),
    },
    "C07": {
        "name": "Time exit",
        "implemented": False,
        "code_path": None,
        "param_keys": (),
    },
    "C08": {
        "name": "Funding/OI crowding de-risk",
        "implemented": False,
        "code_path": None,
        "param_keys": (),
    },
    "C09": {
        "name": "Event-window filter",
        "implemented": False,
        "code_path": None,
        "param_keys": (),
    },
}


def load_run_config(path: Path | str) -> dict[str, Any]:
    payload = yaml.safe_load(Path(path).read_text())
    if not isinstance(payload, dict):
        raise ValueError(f"run config is not a mapping: {path}")
    return payload


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _condition_params(spec: dict[str, Any], raw: dict[str, Any], *, enabled: bool) -> dict[str, Any]:
    keys = spec.get("param_keys") or ()
    if spec["implemented"] and keys:
        if enabled:
            missing = [key for key in keys if raw.get(key) is None]
            if missing:
                raise ValueError(
                    f"{spec.get('id', '')} strategy manifest missing required params: {missing}"
                )
            return {key: raw[key] for key in keys}
        return {key: raw[key] for key in keys if key in raw and raw[key] is not None}
    return {
        key: value
        for key, value in raw.items()
        if key != "enabled"
    }


def _condition_row(cond_id: str, config: dict[str, Any]) -> dict[str, Any]:
    spec = dict(CONDITION_SPECS[cond_id])
    spec["id"] = cond_id
    conditions = config.get("conditions") or {}
    raw = conditions.get(cond_id) or {}
    if not isinstance(raw, dict):
        raise ValueError(f"{cond_id} config must be a mapping")
    enabled = bool(raw.get("enabled", False))
    code_path = spec["code_path"]
    code_sha256 = None
    if spec["implemented"]:
        if not code_path:
            raise ValueError(f"{cond_id} is implemented but has no code_path")
        module_path = PACKAGE_ROOT / code_path
        if not module_path.is_file():
            raise ValueError(f"{cond_id} condition module missing: {code_path}")
        code_sha256 = _file_sha256(module_path)
    return {
        "id": cond_id,
        "name": spec["name"],
        "implemented": bool(spec["implemented"]),
        "enabled": enabled,
        "params": _condition_params(spec, raw, enabled=enabled),
        "code_path": code_path,
        "code_sha256": code_sha256,
    }


def canonical_manifest_payload(payload: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in payload.items() if key != "manifest_sha256"}


def manifest_sha256(payload: dict[str, Any]) -> str:
    canonical = canonical_manifest_payload(payload)
    blob = json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(blob).hexdigest()


def build_strategy_manifest(
    config: dict[str, Any],
    *,
    git_commit: str | None = None,
    package_version: str | None = None,
) -> dict[str, Any]:
    if git_commit is None:
        from .version_info import get_version_info

        git_commit = get_version_info().commit
    payload: dict[str, Any] = {
        "package_version": package_version or __version__,
        "git_commit": git_commit,
        "timeframe": config.get("timeframe"),
        "symbols": list(config.get("symbols") or []),
        "initial_equity": config.get("initial_equity"),
        "risk_per_trade": config.get("risk_per_trade"),
        "max_total_risk": config.get("max_total_risk"),
        "max_positions": config.get("max_positions"),
        "conditions": [_condition_row(cond_id, config) for cond_id in CONDITION_IDS],
    }
    payload["manifest_sha256"] = manifest_sha256(payload)
    return payload


def write_strategy_manifest(path: Path | str, payload: dict[str, Any]) -> None:
    Path(path).write_text(
        yaml.safe_dump(
            payload,
            sort_keys=False,
            allow_unicode=True,
            default_flow_style=False,
        ),
        encoding="utf-8",
    )


def check_committed_manifest(path: Path | str, config_path: Path | str) -> dict[str, Any]:
    committed_path = Path(path)
    if not committed_path.is_file():
        raise SystemExit(f"strategy_manifest.yaml is stale vs code/config: missing {committed_path}")
    committed = yaml.safe_load(committed_path.read_text())
    if not isinstance(committed, dict):
        raise SystemExit("strategy_manifest.yaml is stale vs code/config: not a mapping")
    rebuilt = build_strategy_manifest(
        load_run_config(config_path),
        git_commit=committed.get("git_commit"),
        package_version=None,
    )
    if rebuilt != committed:
        raise SystemExit("strategy_manifest.yaml is stale vs code/config")
    return rebuilt


def enabled_condition_summary(payload: dict[str, Any]) -> str:
    lines = []
    for row in payload.get("conditions") or []:
        if not row.get("enabled"):
            continue
        params = row.get("params") or {}
        encoded = ", ".join(f"{key}={value!r}" for key, value in params.items())
        lines.append(f"{row['id']} {row.get('name')}: {encoded}".rstrip())
    return "\n".join(lines)
