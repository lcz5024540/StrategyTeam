#!/usr/bin/env bash
# ============================================================
# CoinStrategy 后端服务管理脚本
# 用法:
#   ./start-smart-wallet-backend.sh start   — 后台启动
#   ./start-smart-wallet-backend.sh stop    — 优雅停止
#   ./start-smart-wallet-backend.sh restart — 重启
#   ./start-smart-wallet-backend.sh status  — 查看状态
#   ./start-smart-wallet-backend.sh log     — 实时查看日志
#
# 环境变量:
#   SPRING_PROFILES_ACTIVE  — 环境 (local/test/prod), 默认 prod
#   SMART_WALLET_ENV_FILE   — 环境变量文件路径
#   SMART_WALLET_BACKEND_PORT — 端口覆盖
#   JAVA_OPTS               — JVM 参数
#   LOG_DIR                 — 日志目录, 默认 /opt/coinstrategy/smart-wallet/logs
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
SYSTEM_ENV_FILE="/etc/coinstrategy/smart-wallet-job.env"
LEGACY_SYSTEM_ENV_FILE="/etc/coinstrategy/smart-wallet.env"
BUNDLED_ENV_FILE="$APP_HOME/deploy/linux/env/smart-wallet-job.env"
LEGACY_BUNDLED_ENV_FILE="$APP_HOME/deploy/linux/env/smart-wallet.env"
DEFAULT_APP_JAR="$APP_HOME/backend/app.jar"
DEFAULT_RUNTIME_DIR="$APP_HOME/runtime"

resolve_default_env_file() {
  if [ -f "$SYSTEM_ENV_FILE" ]; then
    echo "$SYSTEM_ENV_FILE"
    return 0
  fi

  if [ -f "$LEGACY_SYSTEM_ENV_FILE" ]; then
    echo "$LEGACY_SYSTEM_ENV_FILE"
    return 0
  fi

  if [ -f "$BUNDLED_ENV_FILE" ]; then
    echo "$BUNDLED_ENV_FILE"
    return 0
  fi

  if [ -f "$LEGACY_BUNDLED_ENV_FILE" ]; then
    echo "$LEGACY_BUNDLED_ENV_FILE"
    return 0
  fi

  echo "$SYSTEM_ENV_FILE"
}

# ── 配置 ──────────────────────────────────────────────────────
ENV_FILE="${SMART_WALLET_ENV_FILE:-$(resolve_default_env_file)}"
SPRING_PROFILE="${SPRING_PROFILES_ACTIVE:-prod}"
BACKEND_PORT="${SMART_WALLET_BACKEND_PORT:-}"
APP_JAR="${SMART_WALLET_APP_JAR:-$DEFAULT_APP_JAR}"
JAVA_OPTS_VALUE="${JAVA_OPTS:--Xms512m -Xmx1024m -XX:+UseG1GC}"
HELPER_SCRIPT_PATH="${SMART_WALLET_EXECUTION_WALLET_HELPER_SCRIPT_PATH:-}"
LOG_DIR="${LOG_DIR:-/opt/coinstrategy/smart-wallet/logs}"

PID_FILE="$APP_HOME/backend/app.pid"

# ── 加载环境文件 ──────────────────────────────────────────────
load_env() {
  if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
    # 重新读取可能被 env 文件覆盖的变量
    SPRING_PROFILE="${SPRING_PROFILES_ACTIVE:-$SPRING_PROFILE}"
    LOG_DIR="${LOG_DIR:-/opt/coinstrategy/smart-wallet/logs}"
  fi

  if [ -z "$HELPER_SCRIPT_PATH" ] && [ -f "$DEFAULT_RUNTIME_DIR/polymarket_clob_client.py" ]; then
    export SMART_WALLET_EXECUTION_WALLET_HELPER_SCRIPT_PATH="$DEFAULT_RUNTIME_DIR/polymarket_clob_client.py"
  fi
}

# ── 获取运行中的 PID ─────────────────────────────────────────
get_pid() {
  if [ -f "$PID_FILE" ]; then
    local pid
    pid=$(cat "$PID_FILE")
    if kill -0 "$pid" 2>/dev/null; then
      echo "$pid"
      return 0
    fi
    # PID 文件存在但进程不存在，清理
    rm -f "$PID_FILE"
  fi
  return 1
}

# ── 启动 ──────────────────────────────────────────────────────
do_start() {
  load_env

  if pid=$(get_pid); then
    echo "[backend] 服务已在运行中 (PID=$pid)"
    return 0
  fi

  if [ ! -f "$APP_JAR" ]; then
    echo "[backend] 错误: JAR 文件不存在: $APP_JAR" >&2
    exit 1
  fi

  mkdir -p "$LOG_DIR" "$APP_HOME/logs" "$DEFAULT_RUNTIME_DIR"

  # 构建 Java 启动命令
  # shellcheck disable=SC2206
  JAVA_OPTS_ARRAY=($JAVA_OPTS_VALUE)
  JAVA_CMD=(java "${JAVA_OPTS_ARRAY[@]}")
  JAVA_CMD+=(-Dspring.profiles.active="$SPRING_PROFILE")
  JAVA_CMD+=(-Dlog.dir="$LOG_DIR")

  if [ -n "$BACKEND_PORT" ]; then
    JAVA_CMD+=(-Dserver.port="$BACKEND_PORT")
  fi

  JAVA_CMD+=(-jar "$APP_JAR")

  # 启动信息
  echo "[backend] ============================================"
  echo "[backend] 启动 CoinStrategy 后端服务"
  echo "[backend] 环境:       $SPRING_PROFILE"
  echo "[backend] JAR:        $APP_JAR"
  echo "[backend] 日志目录:   $LOG_DIR"
  echo "[backend] 日志文件:   $LOG_DIR/job-current.log (按天滚动为 job-yyyy-MM-dd.log)"
  echo "[backend] 端口:       ${BACKEND_PORT:-<配置文件默认 8081>}"
  echo "[backend] PID 文件:   $PID_FILE"
  echo "[backend] ============================================"

  # 后台启动: stdout 丢弃, stderr 写到独立小文件 (应用日志由 logback 滚动管理)
  # 历史 STARTUP_LOG 改名为 job-startup.log, 现在只承接 JVM 启动报错 / 未捕获的 stderr
  STARTUP_LOG="$LOG_DIR/job-startup.log"
  : > "$STARTUP_LOG"   # 每次重启截断, 避免无限增长
  nohup "${JAVA_CMD[@]}" >/dev/null 2>>"$STARTUP_LOG" &
  local pid=$!
  echo "$pid" > "$PID_FILE"

  echo "[backend] 服务已在后台启动 (PID=$pid)"
  echo "[backend] 启动日志: tail -f $STARTUP_LOG"
  echo "[backend] 应用日志: tail -f $LOG_DIR/job-current.log"

  # 等待几秒检查进程是否存活
  sleep 3
  if kill -0 "$pid" 2>/dev/null; then
    echo "[backend] 服务启动成功 ✓"
  else
    echo "[backend] 警告: 进程可能已退出，请检查日志: $STARTUP_LOG" >&2
    rm -f "$PID_FILE"
    exit 1
  fi
}

# ── 停止 ──────────────────────────────────────────────────────
do_stop() {
  if ! pid=$(get_pid); then
    echo "[backend] 服务未在运行"
    return 0
  fi

  echo "[backend] 正在停止服务 (PID=$pid) ..."

  # 先发 SIGTERM 优雅关闭
  kill "$pid" 2>/dev/null || true

  # 等待最多 30 秒
  local count=0
  while [ "$count" -lt 30 ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "[backend] 服务已停止 ✓"
      rm -f "$PID_FILE"
      return 0
    fi
    sleep 1
    count=$((count + 1))
  done

  # 超时强制杀
  echo "[backend] 优雅关闭超时，强制终止..."
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  echo "[backend] 服务已强制停止"
}

# ── 重启 ──────────────────────────────────────────────────────
do_restart() {
  do_stop
  sleep 2
  do_start
}

# ── 状态 ──────────────────────────────────────────────────────
do_status() {
  if pid=$(get_pid); then
    echo "[backend] 服务运行中 (PID=$pid)"
    # 显示进程信息
    ps -p "$pid" -o pid,user,%cpu,%mem,etime,command --no-headers 2>/dev/null || true
  else
    echo "[backend] 服务未运行"
  fi
}

# ── 实时日志 ──────────────────────────────────────────────────
do_log() {
  local log_file="$LOG_DIR/job-current.log"
  if [ -f "$log_file" ]; then
    echo "[backend] 实时查看日志 (Ctrl+C 退出): $log_file"
    tail -f "$log_file"
  else
    echo "[backend] 日志文件不存在: $log_file"
    echo "[backend] 服务可能尚未启动"
  fi
}

# ── 主入口 ────────────────────────────────────────────────────
ACTION="${1:-start}"
case "$ACTION" in
  start)   do_start   ;;
  stop)    do_stop    ;;
  restart) do_restart ;;
  status)  do_status  ;;
  log)     do_log     ;;
  *)
    echo "用法: $0 {start|stop|restart|status|log}"
    exit 1
    ;;
esac
