#!/usr/bin/env bash
# Fresh-server smoke: venv, lock install, HTX+vision collect, freeze, dual SMOKE run, diff.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

WS="${SMOKE_WORKSPACE:-$ROOT/smoke_workspace}"
VENV="${SMOKE_VENV:-$ROOT/.venv-smoke}"
SUMMARY="${SMOKE_SUMMARY:-$WS/smoke_summary.txt}"
LOG="$WS/smoke.log"
LOOKBACK="${LOOKBACK_DAYS:-max}"
DAILY_CUT="${DAILY_CUT:-UTC00}"
OVERLAP_THRESHOLD="${OVERLAP_THRESHOLD:-0.003}"
SMOKE_BAR_WINDOW="${SMOKE_BAR_WINDOW:-200}"
export ARROW_NUM_THREADS="${ARROW_NUM_THREADS:-1}"
export OMP_NUM_THREADS="${OMP_NUM_THREADS:-1}"

EXIT_CODE=0
DATASET_ID=""
MANIFEST_PATH=""
MANIFEST_HASH=""
DATE_UTC="$(date -u +%Y%m%d)"

mkdir -p "$WS"

write_summary() {
  {
    echo "smoke_log=$LOG"
    echo "exit_code=${EXIT_CODE}"
    echo "lookback_days=$LOOKBACK"
    echo "smoke_bar_window=$SMOKE_BAR_WINDOW"
    echo "daily_cut=$DAILY_CUT"
    echo "manifest=$MANIFEST_PATH"
    echo "manifest_hash=$MANIFEST_HASH"
    echo "dataset_id=$DATASET_ID"
    echo "report_1=$WS/runs/SMOKE-${DATE_UTC}-01/report.md"
    echo "report_2=$WS/runs/SMOKE-${DATE_UTC}-02/report.md"
    if [[ -n "$DATASET_ID" && -f "$WS/data/datasets/$DATASET_ID/source_counts.json" ]]; then
      echo "source_counts_file=$WS/data/datasets/$DATASET_ID/source_counts.json"
      python - "$WS/data/datasets/$DATASET_ID/source_counts.json" <<'PY' || true
import json
import sys
from pathlib import Path
payload = json.loads(Path(sys.argv[1]).read_text())
print("source_counts=" + json.dumps(payload, sort_keys=True))
for symbol, counts in payload.items():
    if not isinstance(counts, dict):
        continue
    print(
        f"{symbol}: htx_bars={counts.get('htx_bars', 0)} "
        f"binance_bars={counts.get('binance_bars', 0)} "
        f"binance_backfill_bars={counts.get('binance_backfill_bars', 0)} "
        f"total_bars={counts.get('total_bars', 0)}"
    )
PY
    else
      echo "source_counts=missing"
    fi
    if [[ -n "$DATASET_ID" && -f "$WS/data/datasets/$DATASET_ID/funding_source_timeline.json" ]]; then
      echo "funding_source_timeline_file=$WS/data/datasets/$DATASET_ID/funding_source_timeline.json"
      python - "$WS/data/datasets/$DATASET_ID/funding_source_timeline.json" <<'PY' || true
import json
import sys
from pathlib import Path
payload = json.loads(Path(sys.argv[1]).read_text())
print("funding_source_timeline=" + json.dumps(payload, sort_keys=True))
for symbol, sources in payload.items():
    if not isinstance(sources, dict):
        continue
    for source, stats in sources.items():
        if not isinstance(stats, dict):
            continue
        print(
            f"{symbol} {source}: count={stats.get('count', 0)} "
            f"earliest={stats.get('earliest')} latest={stats.get('latest')}"
        )
PY
    else
      echo "funding_source_timeline=missing"
    fi
  } > "$SUMMARY"
}

on_exit() {
  EXIT_CODE=$?
  write_summary || true
  echo "== smoke e2e finished exit=$EXIT_CODE =="
  echo "summary=$SUMMARY"
  exit "$EXIT_CODE"
}
trap on_exit EXIT

exec > >(tee -a "$LOG") 2>&1

echo "== smoke e2e start $(date -u +%Y-%m-%dT%H:%M:%SZ) =="
echo "lookback_days=$LOOKBACK (explicit; no implicit 90-day default)"
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

python - <<PY
from pathlib import Path
import json
import yaml

root = Path(r"$ROOT")
ws = Path(r"$WS")
base = yaml.safe_load((root / "configs/runs/R0001.yaml").read_text())
base["dataset_id"] = "$DATASET_ID"
base["manifest_hash"] = "$MANIFEST_HASH"
base["run_type"] = "SMOKE"
base["split"] = "train"
base["smoke_bar_window"] = int("$SMOKE_BAR_WINDOW")
base["overlap_deviation"] = None
overlap = ws / "data" / "datasets" / "$DATASET_ID" / "source_counts.json"
if overlap.exists():
    print("source_counts=" + overlap.read_text().replace("\n", " "))
overlap_dev = ws / "data" / "datasets" / "$DATASET_ID" / "overlap_deviation.json"
if overlap_dev.exists():
    base["overlap_deviation"] = json.loads(overlap_dev.read_text())
for run_id in ["SMOKE-${DATE_UTC}-01", "SMOKE-${DATE_UTC}-02"]:
    cfg = dict(base)
    cfg["run_id"] = run_id
    (ws / f"{run_id}.yaml").write_text(yaml.dump(cfg, allow_unicode=True))
print("wrote smoke configs")
PY

python -m htx_trend.research.cli --workspace "$WS" run "$WS/SMOKE-${DATE_UTC}-01.yaml"
python -m htx_trend.research.cli --workspace "$WS" run "$WS/SMOKE-${DATE_UTC}-02.yaml"

python - <<PY
from pathlib import Path
from htx_trend.research.ops.stable_hash import hash_parquet

ws = Path(r"$WS")
a = ws / "runs" / "SMOKE-${DATE_UTC}-01"
b = ws / "runs" / "SMOKE-${DATE_UTC}-02"
dec_a = hash_parquet(a / "decisions.parquet")
dec_b = hash_parquet(b / "decisions.parquet")
fills_a = hash_parquet(a / "fills.parquet")
fills_b = hash_parquet(b / "fills.parquet")
print(f"decisions_hash_a={dec_a}")
print(f"decisions_hash_b={dec_b}")
print(f"fills_hash_a={fills_a}")
print(f"fills_hash_b={fills_b}")
print(f"decisions_hash_match={dec_a == dec_b}")
print(f"fills_hash_match={fills_a == fills_b}")
if not (dec_a == dec_b and fills_a == fills_b):
    raise SystemExit("SMOKE replay hashes differ")
PY

echo "== smoke e2e complete =="
