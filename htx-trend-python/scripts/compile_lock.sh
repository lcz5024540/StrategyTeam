#!/usr/bin/env bash
# Compile requirements.lock from pyproject.toml in a fresh Python 3.12 venv.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PYTHON="${PYTHON:-python3.12}"
WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/htx-compile-lock.XXXXXX")"
cleanup() { rm -rf "$WORKDIR"; }
trap cleanup EXIT

if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "ERROR: $PYTHON not found (need Python 3.12)" >&2
  exit 1
fi

"$PYTHON" -m venv "$WORKDIR/venv"
# shellcheck disable=SC1091
source "$WORKDIR/venv/bin/activate"
python -m pip install --upgrade pip pip-tools
cd "$ROOT"
pip-compile \
  --generate-hashes \
  --extra test \
  --strip-extras \
  --output-file requirements.lock \
  --resolver=backtracking \
  pyproject.toml

echo "Wrote $ROOT/requirements.lock"
python "$ROOT/scripts/check_lock_hygiene.py" "$ROOT/requirements.lock"
