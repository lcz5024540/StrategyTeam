#!/usr/bin/env python3
"""Seal a ProductLiveWaiver (path B) from a verified LIVE encode package.

Does not emit formal G2 ``synthetic:false`` admission or REAL_PAPER evidence.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from htx_trend.live_approval import LiveRiskPolicy  # noqa: E402
from htx_trend.product_live_waiver import (  # noqa: E402
    LIVE_INSTANCE_KEY,
    ProductLiveWaiver,
    ProductLiveWaiverBody,
    UserRulingRef,
)

import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("release_package", ROOT / "release/package.py")
_package = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_package)


def _stamp_sha256(path: Path) -> str:
    return sha256(path.read_bytes()).hexdigest()


def seal_waiver(
    archive: Path,
    *,
    account_id: int,
    approved_by: str,
    rollback_point: str,
    product_stamp: Path,
    issued_at: str | None = None,
    expires_at: str | None = None,
    user_ruling: dict | None = None,
) -> ProductLiveWaiver:
    receipt = _package.verify_live(archive)
    bindings = {**receipt["bindings"], "package_sha256": receipt["package_sha256"]}
    issued = datetime.fromisoformat((issued_at or datetime.now(UTC).isoformat()).replace("Z", "+00:00"))
    if issued.tzinfo is None:
        raise ValueError("issued_at requires timezone")
    expires = datetime.fromisoformat(
        (expires_at or (issued + timedelta(days=45)).isoformat()).replace("Z", "+00:00")
    )
    ruling = user_ruling or {
        "channel": "BrainBot",
        "ruled_at_cst": "2026-10-31 19:18 Asia/Shanghai",
        "summary": (
            "Path B: product-written LIVE waiver with LIVE-15m aligned to PAPER-15m "
            "(dbfee173 + confirm-v1) entry/exit logic; not a deployment stamp."
        ),
    }
    body = ProductLiveWaiverBody(
        package_sha256=bindings["package_sha256"],
        source_commit=bindings["source_commit"],
        strategy_version=bindings["strategy_version"],
        timeframe_profile=bindings["timeframe_profile"],
        instance_key=bindings.get("instance_key", LIVE_INSTANCE_KEY),
        config_fingerprint=bindings["config_fingerprint"],
        strategy_spec_hash=bindings["strategy_spec_hash"],
        account_id=account_id,
        risk=LiveRiskPolicy(),
        exit_version="HTX_TREND_EXIT_V4",
        waived_gates=("PAPER_EXECUTION_14D", "SIGMA_D_ORDERPX"),
        user_ruling_ref=UserRulingRef(**ruling),
        product_stamp_sha256=_stamp_sha256(product_stamp),
        issued_at=issued.astimezone(UTC).isoformat(),
        expires_at=expires.astimezone(UTC).isoformat(),
        approved_by=approved_by,
        rollback_point=rollback_point,
    ).sealed()
    return ProductLiveWaiver(synthetic=False, waiver=body)


def main() -> None:
    parser = argparse.ArgumentParser(description="Seal ProductLiveWaiver (htx-product-live-waiver-g2)")
    sub = parser.add_subparsers(dest="command", required=True)

    seal = sub.add_parser("seal", help="seal waiver from a verified LIVE tarball")
    seal.add_argument("archive", type=Path)
    seal.add_argument("--output", type=Path, required=True)
    seal.add_argument("--account-id", type=int, default=7)
    seal.add_argument("--approved-by", required=True)
    seal.add_argument("--rollback-point", required=True)
    seal.add_argument("--product-stamp", type=Path, required=True)
    seal.add_argument("--issued-at")
    seal.add_argument("--expires-at")
    seal.add_argument("--user-ruling-json", type=Path)

    validate = sub.add_parser("validate", help="validate an on-disk waiver JSON")
    validate.add_argument("--artifact", type=Path, required=True)

    args = parser.parse_args()
    if args.command == "seal":
        ruling = json.loads(args.user_ruling_json.read_text()) if args.user_ruling_json else None
        waiver = seal_waiver(
            args.archive,
            account_id=args.account_id,
            approved_by=args.approved_by,
            rollback_point=args.rollback_point,
            product_stamp=args.product_stamp,
            issued_at=args.issued_at,
            expires_at=args.expires_at,
            user_ruling=ruling,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        document = waiver.to_document()
        args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        ProductLiveWaiver.load(args.output)
        print(json.dumps({"status": "SEALED", "path": str(args.output.resolve()),
                          "waiver_hash": waiver.waiver.waiver_hash}, indent=2))
        return
    ProductLiveWaiver.load(args.artifact)
    print(json.dumps({"status": "VALID", "path": str(args.artifact.resolve())}, indent=2))


if __name__ == "__main__":
    main()
