#!/usr/bin/env bash
# ============================================================
# 一键重启 web + job 服务（同机部署场景）
# 顺序: 先 backend(job, 8081) → 健康检查 → web(8080) → 健康检查 → 双 tail
#
# 用法:
#   sudo ./restart-smart-wallet-all.sh                # 全流程, 最后 tail 两个日志
#   sudo ./restart-smart-wallet-all.sh --no-tail      # 重启完成立即返回
#   sudo ./restart-smart-wallet-all.sh --skip-backend # 只重启 web
#   sudo ./restart-smart-wallet-all.sh --skip-web     # 只重启 backend
#
# 注意: 单独的 restart-smart-wallet-{backend,web}.sh 也支持 --no-tail 参数,
#       这里内部就是通过那个参数串行调度的。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_SCRIPT="$SCRIPT_DIR/restart-smart-wallet-backend.sh"
WEB_SCRIPT="$SCRIPT_DIR/restart-smart-wallet-web.sh"

LOG_DIR="${LOG_DIR:-/opt/coinstrategy/smart-wallet/logs}"
BACKEND_PORT="${SMART_WALLET_BACKEND_PORT:-8081}"
WEB_PORT="${SMART_WALLET_WEB_PORT:-8080}"
CONTEXT_PATH="${SMART_WALLET_CONTEXT_PATH:-/strategy/api}"
HEALTH_PATH="${SMART_WALLET_HEALTH_PATH:-/smart-wallet/health}"
HEALTH_TIMEOUT="${SMART_WALLET_HEALTH_TIMEOUT:-60}"   # 秒

# 参数解析
skip_tail=0
skip_backend=0
skip_web=0
for arg in "$@"; do
  case "$arg" in
    --no-tail) skip_tail=1 ;;
    --skip-backend) skip_backend=1 ;;
    --skip-web) skip_web=1 ;;
    -h|--help)
      sed -n '2,16p' "$0"
      exit 0
      ;;
    *)
      echo "[restart-all] 未知参数: $arg" >&2
      exit 2
      ;;
  esac
done

if [ ! -x "$BACKEND_SCRIPT" ]; then
  echo "[restart-all] 找不到 $BACKEND_SCRIPT" >&2
  exit 1
fi
if [ ! -x "$WEB_SCRIPT" ]; then
  echo "[restart-all] 找不到 $WEB_SCRIPT" >&2
  exit 1
fi

wait_health() {
  local name=$1
  local port=$2
  local url="http://127.0.0.1:${port}${CONTEXT_PATH}${HEALTH_PATH}"
  echo "[restart-all] 等待 ${name} 健康检查: ${url} (最长 ${HEALTH_TIMEOUT}s)"
  local i=0
  while [ "$i" -lt "$HEALTH_TIMEOUT" ]; do
    if curl -fs --max-time 2 "$url" >/dev/null 2>&1; then
      echo "[restart-all] ${name} ✓ 已就绪 (耗时 ${i}s)"
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "[restart-all] ⚠ ${name} 在 ${HEALTH_TIMEOUT}s 内未就绪, 可能仍在启动, 请手动查看日志" >&2
  return 1
}

echo "[restart-all] ============================================"
echo "[restart-all] 一键重启 smart-wallet 全套服务"
echo "[restart-all] skip-backend=${skip_backend}  skip-web=${skip_web}  no-tail=${skip_tail}"
echo "[restart-all] backend port=${BACKEND_PORT}  web port=${WEB_PORT}"
echo "[restart-all] log dir=${LOG_DIR}"
echo "[restart-all] ============================================"

# 1) 重启 backend(job)
if [ "$skip_backend" = "0" ]; then
  echo "[restart-all] >>> 第一步: 重启 backend (job, ${BACKEND_PORT})"
  "$BACKEND_SCRIPT" --no-tail
  wait_health "backend" "$BACKEND_PORT" || true
else
  echo "[restart-all] >>> 跳过 backend 重启"
fi

# 2) 重启 web
if [ "$skip_web" = "0" ]; then
  echo "[restart-all] >>> 第二步: 重启 web (${WEB_PORT})"
  "$WEB_SCRIPT" --no-tail
  wait_health "web" "$WEB_PORT" || true
else
  echo "[restart-all] >>> 跳过 web 重启"
fi

echo "[restart-all] ============================================"
echo "[restart-all] 重启完成"
echo "[restart-all] ============================================"

# 3) 双 tail
if [ "$skip_tail" = "1" ]; then
  echo "[restart-all] --no-tail 已指定, 退出"
  exit 0
fi

JOB_LOG="$LOG_DIR/job-current.log"
WEB_LOG="$LOG_DIR/web-current.log"
JOB_FALLBACK="$LOG_DIR/job-startup.log"
WEB_FALLBACK="$LOG_DIR/web-startup.log"

tail_files=()
[ -f "$JOB_LOG" ] && tail_files+=("$JOB_LOG") || { [ -f "$JOB_FALLBACK" ] && tail_files+=("$JOB_FALLBACK"); }
[ -f "$WEB_LOG" ] && tail_files+=("$WEB_LOG") || { [ -f "$WEB_FALLBACK" ] && tail_files+=("$WEB_FALLBACK"); }

if [ "${#tail_files[@]}" -eq 0 ]; then
  echo "[restart-all] 未找到任何日志文件, 不 tail"
  exit 0
fi

echo "[restart-all] 同时 tail: ${tail_files[*]}"
echo "[restart-all] 按 Ctrl+C 退出 tail"
# tail -F 自动处理 logback 文件切换 + 文件不存在场景
if command -v sudo >/dev/null 2>&1; then
  exec sudo tail -F -n 200 "${tail_files[@]}"
else
  exec tail -F -n 200 "${tail_files[@]}"
fi
