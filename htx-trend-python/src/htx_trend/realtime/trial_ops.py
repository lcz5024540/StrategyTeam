"""Read trial status or explicitly activate/stop under the account writer lock."""

import argparse
import json
import time
from decimal import Decimal as D
from pathlib import Path

from ..admission import canonical, seal
from ..mysql_store import MySqlSettings, connect
from ..writer_lock import TrendWriterLock
from .trial import Limits, MySqlTrialStore, Trial
from .trial_admission import validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command", choices=("status", "activate", "stop", "seal-management")
    )
    parser.add_argument("--trial-id")
    parser.add_argument("--approval", type=Path)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--reason", default="OPERATOR_STOP")
    args = parser.parse_args()
    if args.command == "seal-management":
        value = json.loads(args.input.read_text())
        if (
            value.get("permission") != "MANAGEMENT"
            or value.get("candidate") is not None
        ):
            raise ValueError("this command only seals management authority")
        data = seal(value)
        # Validate a temporary sibling so evidence paths remain relative and no invalid output is issued.
        import tempfile

        with tempfile.NamedTemporaryFile(dir=args.output.parent, delete=False) as f:
            temp = Path(f.name)
            f.write(canonical(data).encode())
        try:
            validate(temp, data)
            with args.output.open("x") as f:
                f.write(canonical(data))
        finally:
            temp.unlink()
        return
    db = connect(MySqlSettings.from_env())
    lock = None
    try:
        store = MySqlTrialStore(db)
        saved = store.load(args.trial_id)
        if saved is None:
            raise ValueError("trial has not been prepared by the bound runtime")
        trial = Trial(
            store,
            args.trial_id,
            saved["binding"],
            Limits(
                **{
                    **saved["limits"],
                    "per_trade": D(saved["limits"]["per_trade"]),
                    "loss": D(saved["limits"]["loss"]),
                }
            ),
        )
        if args.command != "status":
            lock = TrendWriterLock(connect(MySqlSettings.from_env()))
            lock.acquire()
            if args.command == "activate":
                from .factory import parameters

                configured_parameters = parameters()
                if (
                    saved["binding"]["parameters_hash"]
                    != configured_parameters.fingerprint
                ):
                    raise ValueError(
                        "activation parameters do not match the prepared trial"
                    )
                validate(
                    args.approval,
                    saved["binding"],
                    entry=True,
                    candidate=configured_parameters.candidate,
                )
                trial.activate(int(time.time() * 1000))
            else:
                trial.stop(args.reason)
        import os
        import shutil

        status = trial.status(int(time.time() * 1000))
        capture = os.environ.get("HTX_RT_CAPTURE_DIR")
        status["disk_free_bytes"] = shutil.disk_usage(capture).free if capture else None
        status["loss_limit"] = str(trial.limits.loss)
        print(json.dumps(status, default=str, ensure_ascii=False))
    finally:
        if lock is not None:
            lock.close()
        db.close()


if __name__ == "__main__":
    main()
