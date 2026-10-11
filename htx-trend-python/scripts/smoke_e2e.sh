#!/usr/bin/env bash
# Fresh-server smoke: venv, lock install, HTX+vision collect, freeze, dual SMOKE run, diff.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

WS="${SMOKE_WORKSPACE:-$ROOT/smoke_workspace}"
VENV="${SMOKE_VENV:-$ROOT/.venv-smoke}"
SUMMARY="${SMOKE_SUMMARY:-$WS/smoke_summary.txt}"
LOG="$WS/smoke.log"
LOOKBACK="${LOOKBACK_DAYS:-90}"
DAILY_CUT="${DAILY_CUT:-UTC00}"
OVERLAP_THRESHOLD="${OVERLAP_THRESHOLD:-0.003}"

mkdir -p "$WS"
exec > >(tee -a "$LOG") 2>&1

echo "== smoke e2e start $(date -u +%Y-%m-%dT%H:%M:%SZ) =="
python3.12 -m venv "$VENV"
# shellcheck disable=SC1091
source "$VENV/bin/activate"
python -m pip install --upgrade pip
python "$ROOT/scripts/check_lock_hygiene.py" "$ROOT/requirements.lock"
python -m pip install -r "$ROOT/requirements.lock"
python -m pip install --no-deps -e "$ROOT"

python -m htx_trend.research.cli --workspace "$WS" collect \
  --contracts BTC-USDT,ETH-USDT \
  --lookback-days "$LOOKBACK" \
  --backfill-binance

python -m htx_trend.research.cli --workspace "$WS" freeze \
  --contracts BTC-USDT,ETH-USDT \
  --timeframe 4hour \
  --backfill-binance \
  --overlap-threshold "$OVERLAP_THRESHOLD"

python -m htx_trend.research.cli --workspace "$WS" freeze \
  --contracts BTC-USDT,ETH-USDT \
  --timeframe 1day \
  --backfill-binance \
  --overlap-threshold "$OVERLAP_THRESHOLD" \
  --daily-cut "$DAILY_CUT"

DATASET_DIR="$(find "$WS/data/datasets" -maxdepth 1 -type d -name '4hour_*' | head -n 1)"
if [[ -z "$DATASET_DIR" ]]; then
  echo "ERROR: frozen 4hour dataset not found" >&2
  exit 1
fi
DATASET_ID="$(basename "$DATASET_DIR")"
MANIFEST_PATH="$WS/data/datasets/manifests/${DATASET_ID}.json"
MANIFEST_HASH="$(python - <<PY
from pathlib import Path
from htx_trend.research.data.manifest import DatasetManifest
print(DatasetManifest.load(Path(r"$MANIFEST_PATH")).sha256())
PY
)"

DATE_UTC="$(date -u +%Y%m%d)"
python - <<PY
from pathlib import Path
import yaml

root = Path(r"$ROOT")
ws = Path(r"$WS")
base = yaml.safe_load((root / "configs/runs/R0001.yaml").read_text())
base["dataset_id"] = "$DATASET_ID"
base["manifest_hash"] = "$MANIFEST_HASH"
base["run_type"] = "SMOKE"
base["split"] = "train"
base["overlap_deviation"] = None
overlap = ws / "data" / "datasets" / "$DATASET_ID" / "overlap_deviation.json"
if overlap.exists():
    import json
    base["overlap_deviation"] = json.loads(overlap.read_text())
for idx, run_id in enumerate(["SMOKE-${DATE_UTC}-01", "SMOKE-${DATE_UTC}-02"], start=1):
    cfg = dict(base)
    cfg["run_id"] = run_id
    (ws / f"{run_id}.yaml").write_text(yaml.dump(cfg, allow_unicode=True))
print("wrote smoke configs")
PY

python -m htx_trend.research.cli --workspace "$WS" run "$WS/SMOKE-${DATE_UTC}-01.yaml"
python -m htx_trend.research.cli --workspace "$WS" run "$WS/SMOKE-${DATE_UTC}-02.yaml"

python - <<PY
import hashlib
from pathlib import Path
import pandas as pd

ws = Path(r"$WS")
a = ws / "runs" / "SMOKE-${DATE_UTC}-01"
b = ws / "runs" / "SMOKE-${DATE_UTC}-02"

def h(path: Path) -> str:
    if not path.exists():
        return "missing"
    df = pd.read_parquet(path)
    return hashlib.sha256(df.to_json(orient="records", date_format="iso").encode()).hexdigest()

dec_ok = h(a / "decisions.parquet") == h(b / "decisions.parquet")
fills_ok = h(a / "fills.parquet") == h(b / "fills.parquet")
print(f"decisions_hash_match={dec_ok}")
print(f"fills_hash_match={fills_ok}")
if not (dec_ok and fills_ok):
    raise SystemExit("SMOKE replay hashes differ")
PY

{
  echo "smoke_log=$LOG"
  echo "manifest=$MANIFEST_PATH"
  echo "manifest_hash=$MANIFEST_HASH"
  echo "dataset_id=$DATASET_ID"
  echo "report_1=$WS/runs/SMOKE-${DATE_UTC}-01/report.md"
  echo "report_2=$WS/runs/SMOKE-${DATE_UTC}-02/report.md"
  echo "daily_cut=$DAILY_CUT"
} > "$SUMMARY"

echo "== smoke e2e complete =="
echo "summary=$SUMMARY"
