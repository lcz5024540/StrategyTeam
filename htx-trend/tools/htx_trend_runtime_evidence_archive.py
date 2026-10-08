#!/usr/bin/env python3
"""Export immutable runtime evidence from MySQL into compressed JSONL archives.

This tool is intentionally outside the live Java service.  It only sends SELECT
statements to MySQL, writes a content-checked JSONL.zst archive and never removes
or changes database rows.  Source the trading host's existing htx.env before use;
credentials are passed only to the mysql child process and are never printed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


DEFAULT_DATABASE = "htx_trade_strategy"
DATABASE_ENV = ("HTX_DB_HOST", "HTX_DB_PORT", "HTX_DB_USERNAME", "HTX_DB_PASSWORD")
ISO_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$")


@dataclass(frozen=True)
class ArchiveTable:
    name: str
    timestamp_column: str
    columns: tuple[str, ...]

    def query(self, before: str) -> str:
        pairs = ", ".join(f"'{column}', {column}" for column in self.columns)
        return (f"SELECT JSON_OBJECT({pairs}) FROM {self.name} "
                f"WHERE {self.timestamp_column} < '{before}' ORDER BY id")


ARCHIVE_TABLES = {
    item.name: item for item in (
        ArchiveTable("htx_trend_runtime_kline_candle", "captured_at", (
            "id", "candle_hash", "contract_code", "period", "bar_time", "open_price", "high_price", "low_price",
            "close_price", "amount", "volume", "turnover", "trade_count", "source_kind", "captured_at")),
        ArchiveTable("htx_trend_runtime_kline_snapshot", "captured_at", (
            "id", "snapshot_key", "run_id", "factor_snapshot_id", "position_id", "capture_stage", "contract_code",
            "period", "target_bar_time", "window_start_bar_time", "window_end_bar_time", "expected_window_size",
            "actual_window_size", "complete_window", "window_sha256", "source_kind", "source_reference", "data_integrity",
            "captured_at")),
        ArchiveTable("htx_trend_position_mark_snapshot", "created_at", (
            "id", "snapshot_key", "position_id", "run_id", "account_id", "account_label", "contract_code", "side",
            "primary_model", "timeframe_profile", "bar_time", "reference_price", "unrealized_net_pnl", "mfe_net_pnl",
            "mae_net_pnl", "giveback_rate", "protection_price", "trend_state", "exit_phase", "suggested_action",
            "execution_mode", "evidence_json", "created_at")),
    )
}


def parse_before(value: str) -> str:
    if not ISO_TIMESTAMP.fullmatch(value):
        raise ValueError("--before must be an ISO-8601 timestamp with a timezone")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]


def selected_tables(values: Iterable[str]) -> list[ArchiveTable]:
    names = list(values)
    if not names:
        return list(ARCHIVE_TABLES.values())
    unknown = sorted(set(names).difference(ARCHIVE_TABLES))
    if unknown:
        raise ValueError("unknown archive table: " + ", ".join(unknown))
    return [ARCHIVE_TABLES[name] for name in names]


def mysql_command(table: ArchiveTable, before: str, database: str, environ: dict[str, str]) -> tuple[list[str], dict[str, str]]:
    missing = [name for name in DATABASE_ENV if not environ.get(name)]
    if missing:
        raise ValueError("missing database environment: " + ", ".join(missing))
    command = ["mysql", "--batch", "--skip-column-names", "--raw", "--host", environ["HTX_DB_HOST"],
               "--port", environ["HTX_DB_PORT"], "--user", environ["HTX_DB_USERNAME"], database,
               "--execute", table.query(before)]
    child_environ = dict(environ)
    child_environ["MYSQL_PWD"] = environ["HTX_DB_PASSWORD"]
    return command, child_environ


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def export_table(table: ArchiveTable, before: str, database: str, output: Path, compression_level: int,
                 environ: dict[str, str] | None = None) -> dict:
    environment = dict(os.environ if environ is None else environ)
    command, child_environ = mysql_command(table, before, database, environment)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix=output.name + ".", suffix=".partial", dir=output.parent, delete=False) as temp:
        partial = Path(temp.name)
    output_handle = partial.open("wb")
    mysql = None
    zstd = None
    count = 0
    try:
        mysql = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=child_environ)
        zstd = subprocess.Popen(["zstd", "--quiet", "--force", f"-{compression_level}", "--stdout"],
                                stdin=subprocess.PIPE, stdout=output_handle, stderr=subprocess.PIPE, text=True)
        assert mysql.stdout is not None and zstd.stdin is not None
        for line in mysql.stdout:
            json.loads(line)
            zstd.stdin.write(line)
            count += 1
        zstd.stdin.close()
        mysql_code = mysql.wait()
        zstd_code = zstd.wait()
        mysql_error = (mysql.stderr.read() if mysql.stderr else "").strip()
        zstd_error = (zstd.stderr.read() if zstd.stderr else "").strip()
        if mysql_code != 0:
            raise RuntimeError("archive query failed: " + mysql_error)
        if zstd_code != 0:
            raise RuntimeError("archive compression failed: " + zstd_error)
        partial.replace(output)
    except Exception:
        if mysql is not None and mysql.poll() is None:
            mysql.kill()
        if zstd is not None and zstd.poll() is None:
            zstd.kill()
        partial.unlink(missing_ok=True)
        raise
    finally:
        for handle in (mysql.stdout if mysql else None, mysql.stderr if mysql else None,
                       zstd.stdin if zstd else None, zstd.stderr if zstd else None, output_handle):
            if handle is not None and not handle.closed:
                handle.close()
    return {"table": table.name, "before": before, "file": output.name, "rows": count,
            "sha256": sha256(output), "bytes": output.stat().st_size, "compression": "zstd-jsonl"}


def write_manifest(output_dir: Path, before: str, entries: list[dict]) -> Path:
    payload = {"schema": "htx-trend-runtime-evidence-archive-v1", "createdAt": datetime.now(timezone.utc).isoformat(),
               "beforeUtc": before, "entries": entries,
               "archiveChecksum": hashlib.sha256(json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()).hexdigest()}
    target = output_dir / "archive-manifest.json"
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="empty target directory for this archive batch")
    parser.add_argument("--before", required=True, help="exclusive ISO-8601 cutoff, for example 2026-07-01T00:00:00+08:00")
    parser.add_argument("--database", default=DEFAULT_DATABASE)
    parser.add_argument("--table", action="append", choices=sorted(ARCHIVE_TABLES), help="repeat to export selected tables")
    parser.add_argument("--zstd-level", type=int, default=6, choices=range(1, 20))
    parser.add_argument("--dry-run", action="store_true", help="validate inputs and print the read-only export plan")
    args = parser.parse_args()
    before = parse_before(args.before)
    tables = selected_tables(args.table or [])
    if args.out.exists() and any(args.out.iterdir()):
        raise ValueError("--out must be an empty directory to prevent replacing an existing archive")
    plan = {"before": before, "database": args.database, "tables": [table.name for table in tables],
            "out": str(args.out), "mode": "READ_ONLY_EXPORT"}
    if args.dry_run:
        print(json.dumps(plan, ensure_ascii=False))
        return
    args.out.mkdir(parents=True, exist_ok=False)
    entries = [export_table(table, before, args.database, args.out / f"{table.name}.jsonl.zst", args.zstd_level)
               for table in tables]
    manifest = write_manifest(args.out, before, entries)
    print(json.dumps({**plan, "manifest": str(manifest), "entries": entries}, ensure_ascii=False))


if __name__ == "__main__":
    main()
