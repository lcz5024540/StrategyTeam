#!/usr/bin/env python3
"""One generator + one command for the quality-flag gate suite.

Takes path arguments so it reproduces outside /opt/cursor/artifacts:

  python3 reports/v0.1.0-ops/generate_quality_flag_gate.py \\
    --tarball /path/to/htx-trend-python-0.1.0-rc.13-0e54ec24.tar.gz \\
    --work-dir /tmp/htx-rc13-clean \\
    --out-dir reports/v0.1.0-ops

Uses a clean extract of the official rc.13 tarball and an external venv.
Freeze/run CLI cwd is --work-dir (never the package tree).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
import venv
from pathlib import Path

import pandas as pd

PACKAGE_SHA256 = "f0e7b49c646e03d3d5af4a61fbb566e3cb7c143dc9ae8a45282ef3c92d267f30"
BAD_TICKS_SHA256 = "09e1ed52105909f8c9810a94ecc0cfdfb667bc2f5072ca0419c108965a197a4b"
FUNDING_GAPS_SHA256 = "08b52442ca4a60cf2d95a9e047b8f602a3d753a73a2832c86b1f81276fdbd221"
CONTENT_HASH = "42fcfaa58461ad23e7beadb2aff16f2f9088e1ab0d5d3a059f30e017203c75c8"
MANIFEST_CHECK = "306a17eb4e2b4e86d9be2a2380f3f11a7840c9fd4ad681c0e68384496815c45c"
HARD_CODED_SYMBOLS = ["BTC-USDT", "ETH-USDT"]

# Fixture grid (not official 2020-01-01..2026-10-12). Official values stay in the runbook.
CUTOFF = "2022-05-01T00:00:00Z"
VAL = "2021-01-01T00:00:00Z"
TEST = "2022-01-01T00:00:00Z"
WINDOW = 30
GRID_START = "2020-09-01T00:00:00Z"
LAST_OPEN = "2022-04-30T20:00:00Z"
EXPECTED_ROWS = 3642
EXPECTED_EXTERNAL = 366
EXPECTED_NC = 61
EXPECTED_HTX_ROWS = 3276
EXPECTED_BN_ROWS = 366
HTX_START = "2020-11-01T00:00:00Z"
HTX_END = "2022-04-30T20:00:00Z"
BN_START = "2020-09-01T00:00:00Z"
BN_END = "2020-10-31T20:00:00Z"
YAML_TICKS = {
    "BTC-USDT": [
        "2021-02-19T12:00:00Z",
        "2021-02-19T16:00:00Z",
        "2021-02-19T20:00:00Z",
        "2021-04-27T08:00:00Z",
        "2021-09-16T08:00:00Z",
        "2022-04-27T04:00:00Z",
    ],
    "ETH-USDT": [
        "2021-02-19T16:00:00Z",
        "2021-02-19T20:00:00Z",
        "2021-04-27T08:00:00Z",
        "2021-09-16T08:00:00Z",
        "2022-04-27T04:00:00Z",
    ],
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Reproduce the quality-flag gate suite.")
    parser.add_argument("--tarball", required=True, type=Path)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument(
        "--script",
        type=Path,
        default=here / "check_frozen_quality_flags.py",
    )
    parser.add_argument(
        "--kline-gaps",
        type=Path,
        default=here / "kline_gaps.yaml",
    )
    return parser.parse_args(argv)


def extract_package(tarball: Path, pkg: Path) -> Path:
    if not tarball.is_file():
        raise SystemExit(f"missing official tarball: {tarball}")
    digest = sha256_file(tarball)
    if digest != PACKAGE_SHA256:
        raise SystemExit(f"tarball sha256 {digest} != {PACKAGE_SHA256}")
    if pkg.exists():
        shutil.rmtree(pkg)
    pkg.mkdir(parents=True)
    with tarfile.open(tarball, "r:gz") as handle:
        handle.extractall(pkg)
    if not (pkg / "src" / "htx_trend" / "research" / "cli.py").is_file():
        raise SystemExit(f"extract missing cli.py under {pkg}")
    if not (pkg / "PACKAGE_MANIFEST").is_file():
        raise SystemExit(f"extract missing PACKAGE_MANIFEST under {pkg}")
    return pkg


def make_venv(root: Path) -> Path:
    py = root / "bin" / "python"
    if not py.is_file():
        venv.EnvBuilder(with_pip=True, clear=True).create(root)
    probe = subprocess.run(
        [str(py), "-I", "-c", "import pandas, numpy, pyarrow, yaml, httpx"],
        capture_output=True,
        text=True,
        check=False,
    )
    if probe.returncode != 0:
        subprocess.run([str(py), "-m", "pip", "install", "-q", "--upgrade", "pip"], check=True)
        subprocess.run(
            [
                str(py),
                "-m",
                "pip",
                "install",
                "--ignore-installed",
                "--no-user",
                "pandas>=2.0,<3",
                "numpy>=1.24,<2",
                "pyarrow>=14.0,<18",
                "pyyaml>=6.0,<7",
                "httpx>=0.27,<1",
            ],
            check=True,
        )
        subprocess.run(
            [str(py), "-I", "-c", "import pandas, numpy, pyarrow, yaml, httpx"],
            check=True,
        )
    return py


def assert_state(state: Path, cwd: Path) -> None:
    if not state.is_dir():
        raise SystemExit(f"STATE does not exist: {state}")
    if state.resolve() == cwd.resolve():
        raise SystemExit(
            f"STATE {state.resolve()} must not be the current directory {cwd.resolve()}"
        )
    if "src/htx_trend" in str(cwd.resolve()) or (cwd / "PACKAGE_MANIFEST").is_file():
        raise SystemExit(f"cwd {cwd} looks like the package tree; refuse")


def klines(times, source: str) -> pd.DataFrame:
    close = 100 + pd.Series(range(len(times)), dtype=float)
    return pd.DataFrame(
        {
            "bar_time": times,
            "open": close,
            "high": close + 1,
            "low": close - 1,
            "close": close,
            "volume": 10.0,
            "amount": 100.0,
            "source": source,
            "first_seen_at": pd.Timestamp("2020-09-01T00:00:00Z"),
        }
    )


def write_collect_raw(workspace: Path) -> dict[str, dict]:
    raw = workspace / "data" / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    bn_times = pd.date_range("2020-09-01", "2020-10-31 20:00", freq="4h", tz="UTC")
    htx_times = pd.date_range("2020-11-01", "2022-04-30 20:00", freq="4h", tz="UTC")
    inventory = {}
    for symbol in HARD_CODED_SYMBOLS:
        htx_path = raw / f"{symbol}_4hour_htx.parquet"
        bn_path = raw / f"{symbol.replace('-', '')}_4h_binance.parquet"
        klines(htx_times, "htx").to_parquet(htx_path, index=False)
        klines(bn_times, "binance").to_parquet(bn_path, index=False)
        for path in (htx_path, bn_path):
            frame = pd.read_parquet(path)
            times = pd.to_datetime(frame["bar_time"], utc=True)
            inventory[path.name] = {
                "sha256": sha256_file(path),
                "rows": int(len(frame)),
                "min_bar_time": times.min().strftime("%Y-%m-%dT%H:%M:%SZ"),
                "max_bar_time": times.max().strftime("%Y-%m-%dT%H:%M:%SZ"),
            }
    return inventory


def research_env(pkg: Path) -> dict[str, str]:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(pkg / "src")
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return env


def freeze_cmd(pkg: Path, state: Path) -> list[str]:
    return [
        "--workspace",
        str(state),
        "freeze",
        "--contracts",
        "BTC-USDT,ETH-USDT",
        "--timeframe",
        "4hour",
        "--train-ratio",
        "0.6",
        "--val-ratio",
        "0.2",
        "--backfill-binance",
        "--overlap-threshold",
        "0.003",
        "--cutoff",
        CUTOFF,
        "--validation-start",
        VAL,
        "--test-start",
        TEST,
        "--non-comparable-window",
        str(WINDOW),
        "--stitch-config",
        str(pkg / "data" / "quality" / "stitch_points.yaml"),
        "--stitch-point",
        "BTC-USDT=2020-11-01T00:00:00Z",
        "--stitch-point",
        "ETH-USDT=2020-11-01T00:00:00Z",
        "--bad-ticks",
        str(pkg / "data" / "quality" / "bad_ticks.yaml"),
        "--funding-gaps",
        str(pkg / "data" / "quality" / "funding_gaps.yaml"),
    ]


def run_cli(
    pkg: Path,
    py: Path,
    argv: list[str],
    log: Path,
    *,
    cwd: Path,
) -> subprocess.CompletedProcess:
    cmd = [str(py), "-m", "htx_trend.research", *argv]
    proc = subprocess.run(
        cmd,
        cwd=str(cwd),
        env=research_env(pkg),
        capture_output=True,
        text=True,
        check=False,
    )
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(
        "cmd: "
        + " ".join(cmd)
        + f"\ncwd: {cwd}\nreturncode: {proc.returncode}\n"
        + "--- stdout ---\n"
        + proc.stdout
        + "--- stderr ---\n"
        + proc.stderr
    )
    return proc


def run_freeze(pkg: Path, py: Path, state: Path, log: Path, cwd: Path) -> str:
    proc = run_cli(pkg, py, freeze_cmd(pkg, state), log, cwd=cwd)
    if proc.returncode != 0:
        raise SystemExit(f"freeze failed:\n{log.read_text()}")
    dirs = [
        path
        for path in sorted((state / "data" / "datasets").glob("4hour_*"))
        if path.is_dir()
    ]
    if not dirs:
        raise SystemExit(f"freeze wrote no dataset under {state}")
    return dirs[-1].name


def gate_cmd(
    *,
    script: Path,
    pkg: Path,
    workspace: Path,
    dataset_id: str,
    evidence: Path,
    kline_gaps: Path,
    tarball: Path,
) -> list[str]:
    args = [
        str(script),
        "--dataset-dir",
        str(workspace / "data" / "datasets" / dataset_id),
        "--raw-dir",
        str(workspace / "data" / "raw"),
        "--contracts",
        "BTC-USDT,ETH-USDT",
        "--timeframe",
        "4hour",
        "--cutoff",
        CUTOFF,
        "--window-bars",
        str(WINDOW),
        "--stitch-config",
        str(pkg / "data" / "quality" / "stitch_points.yaml"),
        "--stitch-point",
        "BTC-USDT=2020-11-01T00:00:00Z",
        "--stitch-point",
        "ETH-USDT=2020-11-01T00:00:00Z",
        "--backfill-binance",
        "--bad-ticks",
        str(pkg / "data" / "quality" / "bad_ticks.yaml"),
        "--expected-bad-ticks-sha256",
        BAD_TICKS_SHA256,
        "--kline-gaps",
        str(kline_gaps),
        "--package-tar",
        str(tarball),
        "--package-sha256",
        PACKAGE_SHA256,
        "--evidence",
        str(evidence),
    ]
    for symbol in HARD_CODED_SYMBOLS:
        args.extend(
            [
                "--grid-start",
                f"{symbol}={GRID_START}",
                "--expected-rows",
                f"{symbol}={EXPECTED_ROWS}",
                "--expected-external",
                f"{symbol}={EXPECTED_EXTERNAL}",
                "--expected-non-comparable",
                f"{symbol}={EXPECTED_NC}",
                "--expected-last-open",
                f"{symbol}={LAST_OPEN}",
                "--expected-htx-rows",
                f"{symbol}={EXPECTED_HTX_ROWS}",
                "--expected-binance-rows",
                f"{symbol}={EXPECTED_BN_ROWS}",
                "--expected-htx-start",
                f"{symbol}={HTX_START}",
                "--expected-htx-end",
                f"{symbol}={HTX_END}",
                "--expected-binance-start",
                f"{symbol}={BN_START}",
                "--expected-binance-end",
                f"{symbol}={BN_END}",
                "--expected-bad-ticks",
                f"{symbol}={len(YAML_TICKS[symbol])}",
            ]
        )
    return args


def run_gate(py: Path, cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(
        [str(py), "-I", "-B", *cmd],
        capture_output=True,
        text=True,
        check=False,
    )


def clone_ws(src_ws: Path, dataset_id: str, cases_dir: Path, nonce: int) -> Path:
    dest = cases_dir / f"case-{dataset_id}-{nonce}"
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    write_collect_raw(dest)
    target = dest / "data" / "datasets" / dataset_id
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src_ws / "data" / "datasets" / dataset_id, target)
    return dest


def expect_fail(proc, evidence: Path, needles: list[str], label: str, script: Path) -> dict:
    print(f"=== {label} ===")
    print(proc.stderr)
    if proc.returncode == 0:
        raise SystemExit(f"{label}: expected non-zero")
    payload = json.loads(evidence.read_text())
    blob = "\n".join(payload.get("errors") or [])
    missing = [needle for needle in needles if needle not in blob]
    if missing:
        raise SystemExit(f"{label}: missing {missing!r} in {payload.get('errors')}")
    if payload.get("script_sha256") != sha256_file(script):
        raise SystemExit(f"{label}: evidence missing script sha")
    if payload.get("package_sha256_verified") is not True:
        raise SystemExit(f"{label}: package tar hash was not verified")
    print(f"{label}: FAIL as expected")
    return payload


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    script = args.script.resolve()
    tarball = args.tarball.resolve()
    work = args.work_dir.resolve()
    out = args.out_dir.resolve()
    kline_gaps = args.kline_gaps.resolve()
    if not script.is_file():
        raise SystemExit(f"gate script not found: {script}")
    if not kline_gaps.is_file():
        raise SystemExit(f"kline-gaps template not found: {kline_gaps}")

    gate_dir = out / "gate_test"
    evid = out / "runbook_cli_evidence"
    if gate_dir.exists():
        shutil.rmtree(gate_dir)
    gate_dir.mkdir(parents=True)
    evid.mkdir(parents=True, exist_ok=True)
    work.mkdir(parents=True, exist_ok=True)
    cases_dir = work / "cases"
    cases_dir.mkdir(parents=True, exist_ok=True)

    cwd = Path.cwd().resolve()
    print("extract official tarball + external venv")
    pkg = extract_package(tarball, work / "pkg")
    py = make_venv(work / "venv")
    write_json(
        evid / "clean_env.json",
        {
            "tarball": str(tarball),
            "package_sha256": PACKAGE_SHA256,
            "package_tar_sha256": sha256_file(tarball),
            "pkg": str(pkg),
            "venv_python": str(py),
            "script_sha256": sha256_file(script),
            "cwd": str(cwd),
        },
    )

    state = work / "state"
    if state.exists():
        shutil.rmtree(state)
    state.mkdir(parents=True)
    assert_state(state, cwd)
    inventory = write_collect_raw(state)
    write_json(evid / "raw_inventory.json", inventory)

    print("freeze via extracted rc.13 CLI (cwd = work-dir, not package)")
    dataset_id = run_freeze(pkg, py, state, evid / "freeze.txt", cwd=work)
    print("dataset_id", dataset_id)

    freeze_args = {
        "workspace_position": "BEFORE_subcommand",
        "cwd": str(work),
        "cwd_is_package": False,
        "cutoff": CUTOFF,
        "validation_start": VAL,
        "test_start": TEST,
        "train_ratio": 0.6,
        "val_ratio": 0.2,
        "train_val_ratio_ignored_when_starts_present": 1,
        "non_comparable_window": WINDOW,
        "contracts": "BTC-USDT,ETH-USDT",
        "hard_coded_symbols": HARD_CODED_SYMBOLS,
        "bad_ticks": str(pkg / "data" / "quality" / "bad_ticks.yaml"),
        "bad_ticks_sha256": BAD_TICKS_SHA256,
        "funding_gaps": str(pkg / "data" / "quality" / "funding_gaps.yaml"),
        "funding_gaps_sha256": FUNDING_GAPS_SHA256,
        "kline_gaps": str(kline_gaps),
        "kline_gaps_sha256": sha256_file(kline_gaps),
    }
    write_json(evid / "freeze_args.json", freeze_args)
    (evid / "freeze_args.txt").write_text(
        "\n".join(f"{key}={value}" for key, value in freeze_args.items()) + "\n"
    )

    preflight = {
        "cwd": str(cwd),
        "state": str(state.resolve()),
        "state_exists": state.is_dir(),
        "state_is_not_cwd": state.resolve() != cwd.resolve(),
        "pkg": str(pkg.resolve()),
        "package_sha256": PACKAGE_SHA256,
        "package_tar_sha256": sha256_file(tarball),
        "script_sha256": sha256_file(script),
    }
    write_json(evid / "preflight_state.json", preflight)

    write_json(
        evid / "quality_file_sha256.json",
        {
            "bad_ticks.yaml": sha256_file(pkg / "data" / "quality" / "bad_ticks.yaml"),
            "expected_bad_ticks.yaml": BAD_TICKS_SHA256,
            "funding_gaps.yaml": sha256_file(pkg / "data" / "quality" / "funding_gaps.yaml"),
            "expected_funding_gaps.yaml": FUNDING_GAPS_SHA256,
            "kline_gaps.yaml": sha256_file(kline_gaps),
            "ok": True,
        },
    )

    run_cli(pkg, py, ["--help"], evid / "cli_help.txt", cwd=work)
    freeze_help = run_cli(pkg, py, ["freeze", "--help"], evid / "freeze_help.txt", cwd=work)
    if freeze_help.returncode != 0:
        raise SystemExit("freeze --help failed")
    if "--workspace" in freeze_help.stdout:
        raise SystemExit("freeze --help unexpectedly lists --workspace")

    wrong = run_cli(
        pkg,
        py,
        ["freeze", "--workspace", str(state), *freeze_cmd(pkg, state)[3:]],
        evid / "workspace_after_subcommand.txt",
        cwd=work,
    )
    if wrong.returncode == 0 or "unrecognized arguments: --workspace" not in (
        wrong.stderr + wrong.stdout
    ):
        raise SystemExit("expected argparse rejection of --workspace after freeze")

    run_cli(pkg, py, ["run", "--help"], evid / "run_help.txt", cwd=work)
    manifest = run_cli(
        pkg,
        py,
        [
            "--workspace",
            str(state),
            "strategy-manifest",
            "--config",
            str(pkg / "configs" / "runs" / "R0001.yaml"),
            "--check",
        ],
        evid / "strategy_manifest_check.txt",
        cwd=work,
    )
    if manifest.returncode != 0:
        raise SystemExit("strategy-manifest --check failed")
    r0001 = (pkg / "configs" / "runs" / "R0001.yaml").read_text()
    if MANIFEST_CHECK not in r0001:
        raise SystemExit("packaged R0001 strategy_manifest_sha256 drifted")

    run_todo = run_cli(
        pkg,
        py,
        [
            "--workspace",
            str(state),
            "run",
            str(pkg / "configs" / "runs" / "R0001.yaml"),
            "--funding-gaps",
            str(pkg / "data" / "quality" / "funding_gaps.yaml"),
        ],
        evid / "run_todo_placeholders.txt",
        cwd=work,
    )
    if run_todo.returncode == 0:
        raise SystemExit("run with TODO_FREEZE placeholders must fail")

    # Symbols vs hard-coded expected list (never compare a variable to itself).
    present = []
    missing = []
    raw = state / "data" / "raw"
    for symbol in HARD_CODED_SYMBOLS:
        htx = raw / f"{symbol}_4hour_htx.parquet"
        bn = raw / f"{symbol.replace('-', '')}_4h_binance.parquet"
        if htx.is_file() and bn.is_file():
            present.append(symbol)
        else:
            missing.append(symbol)
    if present != HARD_CODED_SYMBOLS or missing:
        raise SystemExit(f"symbols pre-freeze check failed: {present} missing={missing}")
    write_json(
        evid / "symbols_pre_freeze.json",
        {
            "hard_coded_expected": HARD_CODED_SYMBOLS,
            "present": present,
            "missing": missing,
        },
    )

    frozen_manifest = json.loads(
        (state / "data" / "datasets" / dataset_id / "manifest.json").read_text()
    )
    write_json(evid / "frozen_manifest.json", frozen_manifest)
    if sorted(frozen_manifest.get("symbols") or []) != sorted(HARD_CODED_SYMBOLS):
        raise SystemExit(f"frozen manifest symbols {frozen_manifest.get('symbols')}")
    if frozen_manifest.get("funding_gaps_hash") != f"sha256:{FUNDING_GAPS_SHA256}":
        raise SystemExit(f"funding_gaps_hash {frozen_manifest.get('funding_gaps_hash')}")
    if frozen_manifest.get("bad_ticks_hash") != f"sha256:{BAD_TICKS_SHA256}":
        raise SystemExit(f"bad_ticks_hash {frozen_manifest.get('bad_ticks_hash')}")

    write_json(
        evid / "summary.json",
        {
            "preflight": preflight,
            "wrong_workspace_order_rejected": True,
            "strategy_manifest_check_returncode": manifest.returncode,
            "run_todo_returncode": run_todo.returncode,
            "dataset_id": dataset_id,
            "funding_gaps_hash": frozen_manifest.get("funding_gaps_hash"),
            "bad_ticks_hash": frozen_manifest.get("bad_ticks_hash"),
            "package_content_hash": CONTENT_HASH,
            "freeze_cwd": str(work),
        },
    )

    nonce = 0

    def next_ws() -> Path:
        nonlocal nonce
        nonce += 1
        return clone_ws(state, dataset_id, cases_dir, nonce)

    def gcmd(workspace: Path, evidence: Path) -> list[str]:
        return gate_cmd(
            script=script,
            pkg=pkg,
            workspace=workspace,
            dataset_id=dataset_id,
            evidence=evidence,
            kline_gaps=kline_gaps,
            tarball=tarball,
        )

    good_ev = gate_dir / "good_quality_flags.json"
    good = run_gate(py, gcmd(state, good_ev))
    print("=== GOOD ===")
    print(good.stdout)
    print(good.stderr)
    if good.returncode != 0:
        print(good_ev.read_text() if good_ev.exists() else "no evidence")
        return 1
    payload = json.loads(good_ev.read_text())
    if payload.get("package_sha256_verified") is not True:
        raise SystemExit("good evidence: package tar was not hashed")
    if payload.get("script_sha256") != sha256_file(script):
        raise SystemExit("good evidence missing script sha")
    for symbol in HARD_CODED_SYMBOLS:
        row = payload["frozen"][symbol]
        actual = {name: len(row["actual_true"][name]) for name in row["actual_true"]}
        print(symbol, actual, "rows", row["rows"])
        assert actual["non_comparable"] == 61, row
        assert actual["bad_tick"] == len(YAML_TICKS[symbol]), row
        assert actual["incomplete"] == 0, row
        assert actual["external"] == EXPECTED_EXTERNAL, row
        assert row["rows"] == EXPECTED_ROWS, row
        assert row["frozen_duplicate_bar_times"] == []
        assert row["last_bar_closed_at_or_before_cutoff"] is True
        for name, diff in row["symmetric_diff"].items():
            assert diff["only_actual"] == [] and diff["only_expected"] == [], diff
    print("good fixture: PASS")

    # 1. count 61 -> 58
    count_ws = next_ws()
    path = count_ws / "data" / "datasets" / dataset_id / "BTC-USDT.parquet"
    frame = pd.read_parquet(path)
    idx = frame.index[frame["non_comparable"] == True]  # noqa: E712
    frame.loc[idx[:3], "non_comparable"] = False
    frame.to_parquet(path, index=False)
    count_ev = gate_dir / "broken_count_mismatch.json"
    expect_fail(
        run_gate(py, gcmd(count_ws, count_ev)),
        count_ev,
        ["non_comparable True-set mismatch"],
        "BROKEN count 61->58",
        script,
    )

    # 2. same count, swapped bar
    swap_ws = next_ws()
    path = swap_ws / "data" / "datasets" / dataset_id / "BTC-USDT.parquet"
    frame = pd.read_parquet(path)
    true_idx = frame.index[frame["non_comparable"] == True]  # noqa: E712
    false_idx = frame.index[frame["non_comparable"] == False]  # noqa: E712
    frame.loc[true_idx[0], "non_comparable"] = False
    frame.loc[false_idx[-1], "non_comparable"] = True
    assert int(frame["non_comparable"].sum()) == 61
    frame.to_parquet(path, index=False)
    swap_ev = gate_dir / "broken_swapped_bar.json"
    expect_fail(
        run_gate(py, gcmd(swap_ws, swap_ev)),
        swap_ev,
        ["non_comparable True-set mismatch"],
        "BROKEN same count, one bar swapped",
        script,
    )

    # 3. unclosed last bar
    open_ws = next_ws()
    path = open_ws / "data" / "datasets" / dataset_id / "BTC-USDT.parquet"
    frame = pd.read_parquet(path)
    extra = frame.iloc[[-1]].copy()
    extra["bar_time"] = pd.Timestamp(CUTOFF)
    extra["incomplete"] = False
    extra["non_comparable"] = False
    extra["bad_tick"] = False
    extra["external"] = False
    frame = pd.concat([frame, extra], ignore_index=True)
    frame.to_parquet(path, index=False)
    open_ev = gate_dir / "broken_unclosed_last_bar.json"
    expect_fail(
        run_gate(py, gcmd(open_ws, open_ev)),
        open_ev,
        ["last bar not closed at/before cutoff"],
        "BROKEN unclosed last bar",
        script,
    )

    # 4. string-typed flags
    str_ws = next_ws()
    path = str_ws / "data" / "datasets" / dataset_id / "BTC-USDT.parquet"
    frame = pd.read_parquet(path)
    for name in ("incomplete", "non_comparable", "bad_tick", "external"):
        frame[name] = frame[name].map({True: "True", False: "False"}).astype(object)
    frame.to_parquet(path, index=False)
    str_ev = gate_dir / "broken_string_typed_flags.json"
    expect_fail(
        run_gate(py, gcmd(str_ws, str_ev)),
        str_ev,
        ["must be strict bool dtype"],
        "BROKEN string-typed flags",
        script,
    )

    # 5. delete whole rows
    del_ws = next_ws()
    path = del_ws / "data" / "datasets" / dataset_id / "BTC-USDT.parquet"
    frame = pd.read_parquet(path)
    times = pd.to_datetime(frame["bar_time"], utc=True)
    drop = times.dt.strftime("%Y-%m-%dT%H:%M:%SZ").isin(
        ["2021-02-19T12:00:00Z", "2021-02-19T16:00:00Z", "2021-02-19T20:00:00Z"]
    )
    if int(drop.sum()) != 3:
        raise SystemExit(f"expected to drop 3 BTC 2021-02-19 bars, got {int(drop.sum())}")
    frame.loc[~drop].to_parquet(path, index=False)
    del_ev = gate_dir / "broken_deleted_rows.json"
    expect_fail(
        run_gate(py, gcmd(del_ws, del_ev)),
        del_ev,
        ["deleted/missing whole row"],
        "BROKEN delete whole row",
        script,
    )

    # 6. missing raw file
    miss_ws = next_ws()
    (miss_ws / "data" / "raw" / "BTC-USDT_4hour_htx.parquet").unlink()
    miss_ev = gate_dir / "broken_missing_raw.json"
    expect_fail(
        run_gate(py, gcmd(miss_ws, miss_ev)),
        miss_ev,
        ["required raw file missing"],
        "BROKEN missing raw file",
        script,
    )

    # 7. duplicated bar (frozen)
    dup_ws = next_ws()
    path = dup_ws / "data" / "datasets" / dataset_id / "BTC-USDT.parquet"
    frame = pd.read_parquet(path)
    frame = pd.concat([frame, frame.iloc[[0]]], ignore_index=True)
    frame.to_parquet(path, index=False)
    dup_ev = gate_dir / "broken_duplicated_bar.json"
    expect_fail(
        run_gate(py, gcmd(dup_ws, dup_ev)),
        dup_ev,
        ["duplicate bar_time"],
        "BROKEN duplicated bar",
        script,
    )

    # 8. duplicated bar on raw (separate from frozen)
    rawdup_ws = next_ws()
    raw_path = rawdup_ws / "data" / "raw" / "BTC-USDT_4hour_htx.parquet"
    raw_frame = pd.read_parquet(raw_path)
    raw_frame = pd.concat([raw_frame, raw_frame.iloc[[0]]], ignore_index=True)
    raw_frame.to_parquet(raw_path, index=False)
    rawdup_ev = gate_dir / "broken_duplicated_raw_bar.json"
    expect_fail(
        run_gate(py, gcmd(rawdup_ws, rawdup_ev)),
        rawdup_ev,
        ["duplicate bar_time"],
        "BROKEN duplicated raw bar",
        script,
    )

    summary = {
        "command": (
            "python3 reports/v0.1.0-ops/generate_quality_flag_gate.py "
            "--tarball <rc.13.tar.gz> --work-dir <dir> --out-dir reports/v0.1.0-ops"
        ),
        "package_sha256": PACKAGE_SHA256,
        "package_tar_sha256": sha256_file(tarball),
        "script_sha256": sha256_file(script),
        "dataset_id": dataset_id,
        "good_returncode": good.returncode,
        "good_counts": {
            symbol: {
                name: len(payload["frozen"][symbol]["actual_true"][name])
                for name in payload["frozen"][symbol]["actual_true"]
            }
            for symbol in HARD_CODED_SYMBOLS
        },
        "good_rows": {
            symbol: payload["frozen"][symbol]["rows"] for symbol in HARD_CODED_SYMBOLS
        },
        "object_dtype_counterexample": (
            "skipped: parquet roundtrip canonicalizes Python object/bool to bool; "
            "string-typed flags is the dtype case"
        ),
        "counterexamples": {
            "count_61_to_58": {"returncode": 1, "evidence": str(count_ev)},
            "same_count_swapped_bar": {"returncode": 1, "evidence": str(swap_ev)},
            "unclosed_last_bar": {"returncode": 1, "evidence": str(open_ev)},
            "string_typed_flags": {"returncode": 1, "evidence": str(str_ev)},
            "delete_whole_row": {"returncode": 1, "evidence": str(del_ev)},
            "missing_raw": {"returncode": 1, "evidence": str(miss_ev)},
            "duplicated_bar_frozen": {"returncode": 1, "evidence": str(dup_ev)},
            "duplicated_bar_raw": {"returncode": 1, "evidence": str(rawdup_ev)},
        },
        "runbook_cli_evidence": str(evid),
        "clean_env": {"pkg": str(pkg), "venv_python": str(py), "state": str(state)},
    }
    write_json(gate_dir / "summary.json", summary)
    print("ALL GATE TESTS PASSED")
    print(json.dumps(summary["counterexamples"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
