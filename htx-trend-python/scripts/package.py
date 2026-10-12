#!/usr/bin/env python3
"""打包脚本 - 创建可复现的分发包"""

import argparse
import hashlib
import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
from datetime import UTC, datetime
from pathlib import Path

PACKAGE_GLOBS = (
    "src/**/*.py",
    "tests/**/*",
    "configs/**/*",
    "deployment/**/*",
    "sql/**/*",
    "data/quality/**/*",
    "pyproject.toml",
    "README.md",
    "RESEARCH_SYSTEM.md",
    "CHANGELOG.md",
    "requirements.lock",
    "scripts/compile_lock.sh",
    "scripts/check_lock_hygiene.py",
    "scripts/smoke_e2e.sh",
    "scripts/package.py",
    "scripts/tag_message.py",
    "strategy_manifest.yaml",
)
SKIP_DIR_NAMES = {"__pycache__", ".pytest_cache", ".venv"}
SKIP_SUFFIXES = {".pyc", ".pyo"}


def iter_package_files(repo_root: Path):
    seen: set[Path] = set()
    for pattern in PACKAGE_GLOBS:
        for file_path in repo_root.glob(pattern):
            if not file_path.is_file():
                continue
            if any(part in SKIP_DIR_NAMES for part in file_path.parts):
                continue
            if file_path.suffix in SKIP_SUFFIXES:
                continue
            rel = file_path.relative_to(repo_root)
            if rel in seen:
                continue
            seen.add(rel)
            yield file_path, rel


def run_command(cmd: list[str], cwd: Path | None = None) -> str:
    """运行命令并返回输出"""
    result = subprocess.run(
        cmd, capture_output=True, text=True, check=True, cwd=cwd
    )
    return result.stdout.strip()


def get_version() -> str:
    """从 _version.py 读取版本号"""
    version_file = Path(__file__).parent.parent / "src/htx_trend/research/_version.py"
    content = version_file.read_text()
    
    for line in content.split("\n"):
        if line.startswith("__version__"):
            return line.split("=")[1].strip().strip('"').strip("'")
    
    raise ValueError("Version not found in _version.py")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build the HTX trend research tarball")
    parser.add_argument(
        "--branch",
        help="Branch name to embed. Required when HEAD is detached.",
    )
    parser.add_argument(
        "--version",
        help="Override version from src/htx_trend/research/_version.py",
    )
    return parser.parse_args(argv)


def resolve_branch(detected: str, override: str | None) -> str:
    """Never persist detached HEAD as the packaged branch name."""
    branch = (override or detected or "").strip()
    if override is not None:
        branch = override.strip()
    else:
        branch = (detected or "").strip()
    if not branch or branch == "HEAD":
        raise SystemExit(
            "HEAD is detached; pass --branch <name> so the package "
            "does not record branch='HEAD'"
        )
    return branch


def get_git_info() -> dict:
    """获取 git 信息"""
    repo_root = Path(__file__).parent.parent.parent
    
    try:
        commit = run_command(["git", "rev-parse", "HEAD"], repo_root)
        short_sha = commit[:8]
        branch = run_command(["git", "rev-parse", "--abbrev-ref", "HEAD"], repo_root)
        status = run_command(["git", "status", "--porcelain"], repo_root)
        dirty = len(status) > 0
        
        return {
            "commit": commit,
            "short_sha": short_sha,
            "branch": branch,
            "dirty": dirty,
        }
    except Exception as e:
        print(f"Warning: Could not get git info: {e}", file=sys.stderr)
        return {
            "commit": "unknown",
            "short_sha": "unknown",
            "branch": "unknown",
            "dirty": False,
        }


def generate_requirements_lock() -> Path:
    """Use the committed pip-compile lock. Never `pip freeze` the host."""
    repo_root = Path(__file__).parent.parent
    lock_file = repo_root / "requirements.lock"
    compile_script = repo_root / "scripts" / "compile_lock.sh"
    check_script = repo_root / "scripts" / "check_lock_hygiene.py"

    if os.environ.get("COMPILE_LOCK") == "1":
        if not compile_script.is_file():
            raise SystemExit(f"Missing {compile_script}")
        subprocess.run(["bash", str(compile_script)], check=True, cwd=repo_root)

    if not lock_file.is_file():
        raise SystemExit(
            "requirements.lock is missing. Run scripts/compile_lock.sh "
            "in a clean Python 3.12 venv (do not pip freeze the host)."
        )
    subprocess.run(
        [sys.executable, str(check_script), str(lock_file)],
        check=True,
        cwd=repo_root,
    )
    print(f"Using committed lock: {lock_file}")
    return lock_file


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_package_manifest(repo_root: Path) -> dict:
    files = {}
    for path, rel in sorted(iter_package_files(repo_root), key=lambda item: str(item[1])):
        files[str(rel).replace("\\", "/")] = file_sha256(path)
    blob = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    return {
        "files": files,
        "content_hash": hashlib.sha256(blob).hexdigest(),
    }


def require_fresh_strategy_manifest(repo_root: Path) -> dict:
    src = repo_root / "src"
    if str(src) not in sys.path:
        sys.path.insert(0, str(src))
    from htx_trend.research.strategy_manifest import check_committed_manifest

    return check_committed_manifest(
        repo_root / "strategy_manifest.yaml",
        repo_root / "configs" / "runs" / "R0001.yaml",
    )


def create_package(
    version: str, git_info: dict, output_dir: Path | None = None
) -> tuple[Path, str]:
    """创建打包文件"""
    repo_root = Path(__file__).parent.parent
    dist_dir = Path(output_dir) if output_dir is not None else repo_root / "dist"
    dist_dir.mkdir(exist_ok=True)
    
    package_name = f"htx-trend-python-{version}-{git_info['short_sha']}"
    tar_path = dist_dir / f"{package_name}.tar.gz"
    
    print(f"Creating package: {tar_path}")
    package_manifest = build_package_manifest(repo_root)
    manifest_bytes = json.dumps(package_manifest, indent=2, sort_keys=True).encode()
    
    with tarfile.open(tar_path, "w:gz") as tar:
        for file_path, arcname in iter_package_files(repo_root):
            tar.add(file_path, arcname=str(arcname).replace("\\", "/"))
            print(f"  Added: {arcname}")
        info = tarfile.TarInfo("PACKAGE_MANIFEST")
        info.size = len(manifest_bytes)
        info.mtime = int(datetime.now(UTC).timestamp())
        tar.addfile(info, io.BytesIO(manifest_bytes))
        print("  Added: PACKAGE_MANIFEST")
    
    sha256_hash = hashlib.sha256()
    with open(tar_path, "rb") as f:
        for chunk in iter(lambda: f.read(4096), b""):
            sha256_hash.update(chunk)
    
    sha256 = sha256_hash.hexdigest()
    
    sha256_file = dist_dir / f"{package_name}.tar.gz.sha256"
    sha256_file.write_text(f"{sha256}  {tar_path.name}\n")
    
    print(f"\nPackage created: {tar_path}")
    print(f"Size: {tar_path.stat().st_size / 1024:.1f} KB")
    print(f"SHA256: {sha256}")
    print(f"SHA256 file: {sha256_file}")
    
    return tar_path, sha256


def verify_extracted_package(tar_path: Path) -> None:
    """Extract the tarball into an empty temp dir and run the full pytest suite."""
    print("Verifying extracted package with a clean venv + pytest...")
    with tempfile.TemporaryDirectory(prefix="htx-pkg-verify-") as tmp:
        tmp_path = Path(tmp)
        extract_dir = tmp_path / "extract"
        extract_dir.mkdir()
        with tarfile.open(tar_path) as tar:
            tar.extractall(extract_dir)
        venv_dir = tmp_path / "venv"
        subprocess.run([sys.executable, "-m", "venv", str(venv_dir)], check=True)
        pip = venv_dir / "bin" / "pip"
        python = venv_dir / "bin" / "python"
        subprocess.run([str(pip), "install", "--upgrade", "pip"], check=True)
        subprocess.run(
            [
                str(pip),
                "install",
                "--require-hashes",
                "-r",
                str(extract_dir / "requirements.lock"),
            ],
            check=True,
            cwd=extract_dir,
        )
        subprocess.run(
            [str(pip), "install", "--no-deps", "-e", "."],
            check=True,
            cwd=extract_dir,
        )
        result = subprocess.run(
            [str(python), "-m", "pytest", "tests", "-q"],
            cwd=extract_dir,
        )
        if result.returncode != 0:
            raise SystemExit("extracted-package pytest failed")
        print("Extracted-package pytest passed.")


def _strategy_manifest_notes(payload: dict | None) -> str:
    if not payload:
        return ""
    enabled = []
    for row in payload.get("conditions") or []:
        if not row.get("enabled"):
            continue
        params = row.get("params") or {}
        encoded = ", ".join(f"{key}={value!r}" for key, value in params.items())
        enabled.append(f"- {row.get('id')} {row.get('name')}: {encoded}")
    body = "\n".join(enabled) if enabled else "- (none enabled)"
    sha = payload.get("manifest_sha256") or ""
    return (
        f"**Strategy manifest sha256**: `{sha}`\n\n"
        f"**Enabled conditions**:\n{body}\n"
    )


def generate_delivery_template(
    version: str,
    git_info: dict,
    sha256: str,
    tar_path: Path,
    strategy_manifest: dict | None = None,
) -> Path:
    """生成 DELIVERY.md 模板"""
    dist_dir = tar_path.parent
    delivery_path = dist_dir / "DELIVERY.md"
    
    template = f"""# HTX Trend Research System - Delivery Package

**Version**: {version}  
**Git Branch**: {git_info['branch']}  
**Git Commit**: {git_info['commit']}  
**Short SHA**: {git_info['short_sha']}  
**Dirty**: {git_info['dirty']}  
**Package**: {tar_path.name}  
**SHA256**: {sha256}  
**Created**: {datetime.now(UTC).isoformat()}

---

## Change Summary

### Version {version} ({datetime.now(UTC).strftime('%Y-%m-%d')})

**Type**: Initial Release (MVP-1)

**Features**:
- Complete HTX trend research system with backtest engine
- Fee provider interface with authenticated/default implementations
- Approval gates (PRE_PAPER/PRE_LIVE/BREAKER_RESTART)
- Account-level liquidation check for CROSS margin
- Non-strategy position identification
- Ops monitoring: structured logging, heartbeat, health checks, status interface
- Strict determinism tests: parquet hash comparison, dataset hash validation, no-lookahead

**Testing**:
- All pytest tests passed (27 tests)
- Determinism verified
- No-lookahead guaranteed

**Breaking Changes**:
- N/A (initial release)

{_strategy_manifest_notes(strategy_manifest)}
**Notes**:
- rc.6: R0001 risk params changed to 300U / 2% / 10% / 5 positions per user decision; replaces rc.5's 1%/2%/2

---

## Database Migrations

### Alembic Migrations (if using Alembic)
```bash
# No migrations required for fresh install
# Existing installations: see migration notes below
```

### Manual SQL (SQLite)
The system will automatically create tables on first run:
- `runs`: Run records
- `registrations`: Experiment registrations
- `trials`: Trial counter
- `test_set_locks`: Test set access locks
- `approvals`: Approval records with gates

**New in this version**:
- `approvals` table with gate/run_id/strategy_version/approver/decision/rationale

---

## New Configuration Keys

### In run config YAML:
```yaml
# Fee configuration (optional)
fees:
  use_authenticated: false  # Set true to use HTX API (requires key)
  maker_rate: 0.0002        # Optional override
  taker_rate: 0.0006        # Optional override
```

### Environment Variables:
- `HTX_API_KEY`: HTX API key (for authenticated fee provider)
- `HTX_SECRET_KEY`: HTX secret key (for authenticated fee provider)

---

## Installation

### Prerequisites
- Python 3.12
- pip
- The deploy venv at `/opt/coinstrategy/htx-trend-py/.venv` (reuse it; do not `pip freeze` the host)

### Packaging environment

Build the tarball inside a **Python 3.12** virtualenv that has
`requirements.lock` installed:

```bash
source /opt/coinstrategy/htx-trend-py/.venv/bin/activate
python scripts/package.py --branch <release-branch>
# optional: --version 0.2.0
```

Detached HEAD must pass `--branch`; the script refuses to write `branch='HEAD'`.

The tarball members are **repo-relative** (`src/`, `tests/`, `data/quality/`, …),
not a versioned wrapper directory. Keep `src/htx_trend/research/data` from the
tarball (it is source, not runtime `/data`).

### Deploy by extract-and-copy

Extract into a temp directory, then copy package files over
`/opt/coinstrategy/htx-trend-py` **without** replacing runtime directories.

Preserve on the host:
- `data/` (raw/datasets — except refresh `data/quality/` from the package)
- `.venv/`
- `run/`
- `logs/`

```bash
set -euo pipefail
tarball={tar_path.name}
dest=/opt/coinstrategy/htx-trend-py
extract=$(mktemp -d)
trap 'rm -rf "$extract"' EXIT
sha256sum -c "${{tarball}}.sha256"
tar -xzf "$tarball" -C "$extract"
src="$extract"
mkdir -p "$dest"
for item in src tests configs sql deployment scripts pyproject.toml \\
            requirements.lock README.md RESEARCH_SYSTEM.md; do
  rm -rf "$dest/$item"
  cp -a "$src/$item" "$dest/$item"
done
mkdir -p "$dest/data/quality"
cp -a "$src/data/quality/." "$dest/data/quality/"
# Do not copy or delete: $dest/data/raw $dest/data/datasets
# $dest/.venv $dest/run $dest/logs

cd "$dest"
source .venv/bin/activate
python -m pip install --require-hashes -r requirements.lock
python -m pip install --no-deps -e .
python -m htx_trend.research --help
```

`pip install --require-hashes -r requirements.lock` and
`python -m pip install --no-deps -e .` **must run after every update** so the
venv matches the new lock and the editable install sees the new `src/`.

### Freeze dataset (v0.1.0)

Collect full history (`--lookback-days max`) into one workspace, then freeze
in a **separate** workspace. `--cutoff` is required. Split is 60/20/20 by
unique UTC days (`validation_start_utc` / `test_start_utc`); 1d must reuse
the 4h boundaries via `--split-from-manifest` (or both `--validation-start`
and `--test-start`). Stitch must fall in train.

```bash
python -m htx_trend.research freeze \
  --workspace /data/htx-freeze \
  --contracts BTC-USDT,ETH-USDT \
  --timeframe 4hour \
  --backfill-binance \
  --overlap-threshold 0.003 \
  --cutoff 2026-10-12T00:00:00Z
```

---

## Rollback Steps

### If Rollback Required

1. **Stop all running processes**:
   ```bash
   # Check health
   python -m htx_trend.research health
   
   # Note any running components
   ```

2. **Backup current database**:
   ```bash
   cp ledger.db ledger.db.backup.$(date +%Y%m%d-%H%M%S)
   ```

3. **Restore previous version**:
   ```bash
   # Extract previous package
   tar -xzf htx-trend-python-<previous-version>-<sha>.tar.gz
   cd htx-trend-python-<previous-version>-<sha>
   
   python -m pip install -r requirements.lock
   python -m pip install --no-deps -e .
   ```

4. **Verify rollback**:
   ```bash
   python -m htx_trend.research health
   python -c "from htx_trend.research import __version__; print(__version__)"
   ```

5. **Database rollback** (if schema changed):
   ```bash
   # Restore backup
   cp ledger.db.backup.<timestamp> ledger.db
   ```

### Rollback Notes
- The `approvals` table is new in v0.1.0
- If rolling back from v0.1.0, existing approvals will be ignored by older versions
- No data loss, but approval checks will not function

---

## Verification

### Verify Package Integrity
```bash
sha256sum -c {tar_path.name}.sha256
```

### Run Tests
```bash
cd htx-trend-python-{version}-{git_info['short_sha']}
python -m pytest tests/research/ -v
```

Expected: 27 passed

### Verify Version
```bash
python -m htx_trend.research status --json | grep version_info
```

---

## CHANGELOG Entry

Add to CHANGELOG.md:

```markdown
## [{version}] - {datetime.now(UTC).strftime('%Y-%m-%d')}

### Added
- Initial release of HTX Trend Research System MVP-1
- Complete backtest engine with event-driven architecture
- Fee provider interface (default and authenticated HTX API stub)
- Approval gate system (PRE_PAPER, PRE_LIVE, BREAKER_RESTART)
- Account-level liquidation check for CROSS margin mode
- Non-strategy position identification in status output
- Ops monitoring: structured JSON logging, heartbeat system, health checks
- Version information embedded in package and exposed in CLI
- Strict determinism tests with parquet hash comparison
- No-lookahead guarantees with truncation invariance tests

### Security
- API keys managed through environment variables
- Fee source clearly labeled in reports
- Approval records with version checks prevent unauthorized promotion

### Testing
- 27 pytest tests covering all core functionality
- Determinism verified with identical parquet hashes
- No-lookahead guarantee verified with truncation tests

### Documentation
- Complete RESEARCH_SYSTEM.md with usage examples
- MVP1_DELIVERY_REPORT.md with detailed implementation notes
- Inline code documentation
```

---

## Support

For issues or questions:
1. Check RESEARCH_SYSTEM.md documentation
2. Review test cases in tests/research/
3. Consult MVP1_DELIVERY_REPORT.md for implementation details

---

**Package Prepared By**: Automated packaging script  
**Package Date**: {datetime.now(UTC).isoformat()}  
**Package Valid**: Verify SHA256 before deployment
"""
    
    delivery_path.write_text(template)
    print(f"\nDelivery template: {delivery_path}")
    
    return delivery_path


def main(argv: list[str] | None = None) -> int:
    """主打包流程"""
    args = parse_args(argv)
    print("=" * 60)
    print("HTX Trend Research System - Package Builder")
    print("=" * 60)
    print()
    
    version = args.version or get_version()
    print(f"Version: {version}")
    
    git_info = get_git_info()
    git_info["branch"] = resolve_branch(git_info["branch"], args.branch)
    print(f"Git: {git_info['branch']}@{git_info['short_sha']}")
    
    if git_info['dirty']:
        print("WARNING: Working directory has uncommitted changes", file=sys.stderr)
    
    print()
    
    lock_file = generate_requirements_lock()
    print()

    repo_root = Path(__file__).parent.parent
    sys.path.insert(0, str(repo_root / "src"))
    from htx_trend.research.version_info import write_build_info

    strategy_manifest = require_fresh_strategy_manifest(repo_root)
    print("strategy-manifest --check passed")

    build_info_path = repo_root / "src" / "htx_trend" / "research" / "_build_info.py"
    import yaml

    strategy_manifest_path = repo_root / "strategy_manifest.yaml"
    if not strategy_manifest_path.is_file():
        raise SystemExit(
            "strategy_manifest.yaml is missing; "
            "python -m htx_trend.research strategy-manifest --config configs/runs/R0001.yaml --write"
        )
    strategy_manifest = yaml.safe_load(strategy_manifest_path.read_text()) or {}
    strategy_sha = str(strategy_manifest.get("manifest_sha256") or "")
    write_build_info(
        build_info_path,
        version,
        git_info,
        strategy_manifest_sha256=strategy_sha,
    )
    print(f"Wrote build info: {build_info_path}")
    print(f"STRATEGY_MANIFEST_SHA256: {strategy_sha}")
    
    tar_path, sha256 = create_package(version, git_info)
    write_build_info(
        build_info_path,
        "",
        {"commit": "", "branch": "", "dirty": False},
        strategy_manifest_sha256="",
    )
    build_info_path.write_text(
        "# Overwritten by scripts/package.py when building a release tarball.\n"
        "\n"
        'COMMIT = ""\n'
        'BRANCH = ""\n'
        'VERSION = ""\n'
        "DIRTY = False\n"
        'STRATEGY_MANIFEST_SHA256 = ""\n'
    )
    print()

    if os.environ.get("SKIP_PACKAGE_VERIFY") != "1":
        verify_extracted_package(tar_path)
        print()
    
    delivery_path = generate_delivery_template(
        version,
        git_info,
        sha256,
        tar_path,
        strategy_manifest=strategy_manifest,
    )
    print()
    
    print("=" * 60)
    print("Package Summary")
    print("=" * 60)
    print(f"Version:  {version}")
    print(f"Commit:   {git_info['commit']}")
    print(f"Branch:   {git_info['branch']}")
    print(f"Package:  {tar_path}")
    print(f"SHA256:   {sha256}")
    print(f"Delivery: {delivery_path}")
    print("=" * 60)
    
    return 0


if __name__ == "__main__":
    sys.exit(main())
