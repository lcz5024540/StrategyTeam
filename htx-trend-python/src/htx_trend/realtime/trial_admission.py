"""Separate, sealed trial authority; management permission does not permit entry."""

import os
from datetime import UTC, datetime
from decimal import Decimal as D
from pathlib import Path

from ..admission import file_hash, read_sealed, window
from ..execution import ExecutionConfig
from ..research.store import digest
from . import VERSION
from .plans import EVENT_LOCAL, ORIGINAL, SBOX
from .trial import Limits

SCHEMA = "htx-realtime-trial-approval-v1"
PATH = "HTX_RT_TRIAL_APPROVAL_PATH"
CONFLICTS = (
    "HTX_TREND_PRODUCT_LIVE_WAIVER_PATH",
    "HTX_TREND_LIVE_APPROVAL_PATH",
    "HTX_TREND_LEGACY_LIVE_WAIVER_PATH",
    "HTX_TREND_STRATEGY_ADMISSION_PATH",
)
BINDINGS = (
    "trial_id",
    "instance_key",
    "account_id",
    "strategy_version",
    "source_commit",
    "package_sha256",
    "config_fingerprint",
    "parameters_hash",
    "exit_policy_hash",
    "contracts_hash",
    "execution_hash",
)


def configured():
    return bool(os.environ.get(PATH, "").strip())


def validate(path, current, *, entry=False, now=None, candidate=ORIGINAL):
    now = now or datetime.now(UTC)
    value = read_sealed(path, SCHEMA)
    if value["synthetic"] or value.get("formal_admission") != "NOT_ELIGIBLE":
        raise ValueError(
            "trial cannot claim formal admission or use synthetic authorization"
        )
    if value.get("permission") not in ("MANAGEMENT", "TRIAL"):
        raise ValueError("invalid trial permission")
    for name in BINDINGS:
        if not value.get(name) or value[name] != current.get(name):
            raise ValueError("trial approval binding mismatch: " + name)
    if value["strategy_version"] != VERSION or value.get("profile") != "INTRADAY_15M":
        raise ValueError("trial is realtime 15m only")
    for name, length in (
        ("source_commit", 40),
        ("package_sha256", 64),
        ("config_fingerprint", 64),
        ("parameters_hash", 64),
        ("exit_policy_hash", 64),
        ("contracts_hash", 64),
        ("execution_hash", 64),
    ):
        if len(value[name]) != length or any(
            c not in "0123456789abcdef" for c in value[name]
        ):
            raise ValueError("invalid trial digest: " + name)
    limits = Limits(
        **{
            **value["limits"],
            "per_trade": D(value["limits"]["per_trade"]),
            "loss": D(value["limits"]["loss"]),
        }
    )
    if value.get("waived_conditions") != ["FORMAL_PROFITABILITY", "PAPER_14_DAYS"]:
        raise ValueError("trial safety conditions cannot be waived")
    if not value.get("approved_by") or not value.get("confirmation_ref"):
        raise ValueError("trial confirmation missing")
    file_hash(
        Path(path).parent, value["confirmation_ref"], value["confirmation_sha256"]
    )
    if entry:
        window(value, now)
        if (
            value["permission"] != "TRIAL"
            or candidate not in (ORIGINAL, EVENT_LOCAL, SBOX)
            or value.get("candidate") != candidate
        ):
            raise ValueError("unique production candidate not confirmed")
        if not value.get("candidate_confirmation_ref"):
            raise ValueError("candidate confirmation missing")
        file_hash(
            Path(path).parent,
            value["candidate_confirmation_ref"],
            value["candidate_confirmation_sha256"],
        )
    return value, limits


def runtime_authority(instance, account, *, policy, contracts, parameters, entry=False):
    if any(os.environ.get(k, "").strip() for k in CONFLICTS):
        raise ValueError(
            "trial authority cannot coexist with formal/legacy authorities"
        )
    if (
        instance.strategy_name != VERSION
        or instance.execution_mode != "LIVE"
        or account.role != "PRIMARY"
        or len(contracts) != 52
        or len(set(contracts)) != 52
    ):
        raise ValueError(
            "trial requires primary LIVE realtime instance and frozen 52 contracts"
        )
    config = ExecutionConfig.from_env()
    if (
        config.max_risk_per_trade_rate > D(".005")
        or config.max_total_open_risk_rate > D(".015")
        or config.max_total_margin_rate > D(".10")
        or config.max_active_positions > 10
    ):
        raise ValueError("trial account limits exceed authorization")
    if (
        not 0
        < int(os.environ.get("HTX_RT_CAPTURE_MAX_BYTES", str(2 * 1024**3)))
        <= 2 * 1024**3
    ):
        raise ValueError("trial capture capacity exceeds 2GiB")
    path = Path(os.environ[PATH])
    # Trial ID is a disk identity, never generated on restart.
    raw = read_sealed(path, SCHEMA)
    from dataclasses import asdict

    from ..execution_service import EntryExecutionConfig

    binding = dict(
        trial_id=raw["trial_id"],
        instance_key=instance.instance_key,
        account_id=account.account_id,
        strategy_version=VERSION,
        source_commit=os.environ.get("HTX_PYTHON_SOURCE_COMMIT", ""),
        package_sha256=os.environ.get("HTX_PYTHON_PACKAGE_SHA256", ""),
        config_fingerprint=instance.config_fingerprint,
        parameters_hash=parameters.fingerprint,
        exit_policy_hash=policy.to_dict()["hash"],
        contracts_hash=digest(sorted(contracts)),
        execution_hash=digest(
            {
                "execution": asdict(config),
                "entry_execution": asdict(EntryExecutionConfig.from_env()),
            }
        ),
    )
    return validate(path, binding, entry=entry, candidate=parameters.candidate)
