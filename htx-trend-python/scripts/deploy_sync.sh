#!/usr/bin/env bash
# Sync a packaged tree to the deploy host without wiping runtime data
# or skipping src/htx_trend/research/data/.
#
# Anchored excludes ('/data') only match the destination-root data/
# directory. Unanchored 'data' would also skip research/data/.
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 SRC HOST:DEST" >&2
  exit 2
fi

SRC="${1%/}"
DEST="$2"

rsync -a --delete \
  --exclude '/data' \
  --exclude '/logs' \
  --exclude '/run' \
  --exclude '/.venv' \
  --exclude '/dist' \
  --exclude '/.git' \
  --exclude '__pycache__/' \
  --exclude '*.pyc' \
  "${SRC}/" "${DEST}"
