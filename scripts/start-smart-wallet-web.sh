#!/usr/bin/env bash
# ============================================================
# CoinStrategy Web 服务管理脚本
# 用法:
#   ./start-smart-wallet-web.sh start   — 后台启动
#   ./start-smart-wallet-web.sh stop    — 优雅停止
#   ./start-smart-wallet-web.sh restart — 重启
#   ./start-smart-wallet-web.sh status  — 查看状态
#   ./start-smart-wallet-web.sh log     — 实时查看日志
#
# 环境变量:
#   SPRING_PROFILES_ACTIVE       — 环境 (local/test/prod), 默认 prod
#   SMART_WALLET_ENV_FILE        — Web 专属 env 文件路径
#   SMART_WALLET_SHARED_ENV_FILE — 与 Job 共享的 env 文件 (DB/密钥等)
#   SMART_WALLET_WEB_PORT        — 端口覆盖 (默认 8080)
#   SMART_WALLET_WEB_APP_JAR     — Web JAR 路径覆盖
#   JAVA_OPTS                    — JVM 参数 (默认 -Xms256m -Xmx512m -XX:+UseG1GC)
#   LOG_DIR                      — 日志目录, 默认 /opt/coinstrategy/smart-wallet/logs
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

SYSTEM_ENV_FILE="/etc/coinstrategy/smart-wallet-web.env"
SYSTEM_SHARED_ENV_FILE="/etc/coinstrategy/smart-wallet-job.env"
LEGACY_SYSTEM_ENV_FILE="/etc/coinstrategy/smart-wallet.env"
BUNDLED_ENV_FILE="$APP_HOME/deploy/linux/env/smart-wallet-web.env"
BUNDLED_SHARED_ENV_FILE="$APP_HOME/deploy/linux/env/smart-wallet-job.env"
LEGACY_BUNDLED_ENV_FILE="$APP_HOME/deploy/linux/env/smart-wallet.env"

DEFAULT_APP_JAR="$APP_HOME/web/app.jar"
DEFAULT_RUNTIME_DIR="$APP_HOME/runtime"

resolve_default_env_file() {
  if [ -f "$SYSTEM_ENV_FILE" ]; then
    echo "$SYSTEM_ENV_FILE"
    return 0
  fi

  if [ -f "$BUNDLED_ENV_FILE" ]; then
    echo "$BUNDLED_ENV_FILE"
    return 0
  fi

  if [ -f "$LEGACY_SYSTEM_ENV_FILE" ]; then
    echo "$LEGACY_SYSTEM_ENV_FILE"
    return 0
  fi

  if [ -f "$LEGACY_BUNDLED_ENV_FILE" ]; then
    echo "$LEGACY_BUNDLED_ENV_FILE"
    return 0
  fi

  echo "$SYSTEM_ENV_FILE"
}

resolve_default_shared_env_file() {
  if [ -f "$SYSTEM_SHARED_ENV_FILE" ]; then
    echo "$SYSTEM_SHARED_ENV_FILE"
    return 0
  fi

  if [ -f "$BUNDLED_SHARED_ENV_FILE" ]; then
    echo "$BUNDLED_SHARED_ENV_FILE"
    return 0
  fi

  echo ""
}

# ── 配置 ──────────────────────────────────────────────────────
ENV_FILE="${SMART_WALLET_ENV_FILE:-$(resolve_default_env_file)}"
SHARED_ENV_FILE="${SMART_WALLET_SHARED_ENV_FILE:-$(resolve_default_shared_env_file)}"
SPRING_PROFILE="${SPRING_PROFILES_ACTIVE:-prod}"
WEB_PORT="${SMART_WALLET_WEB_PORT:-}"
APP_JAR="${SMART_WALLET_WEB_APP_JAR:-$DEFAULT_APP_JAR}"
JAVA_OPTS_VALUE="${JAVA_OPTS:--Xms256m -Xmx512m -XX:+UseG1GC}"
LOG_DIR="${LOG_DIR:-/opt/coinstrategy/smart-wallet/logs}"

PID_FILE="$APP_HOME/web/app.pid"

# ── 加载环境文件 ──────────────────────────────────────────────
load_env() {
  # 1) 先加载共享 env (Job/Web 公共：数据库、RSA、密钥等)
  if [ -n "$SHARED_ENV_FILE" ] && [ -f "$SHARED_ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$SHARED_ENV_FILE"
    set +a
  fi

  # 2) 再加载 Web 专属 env (端口/JVM/对接 Job 的 base url)
  if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
  fi

  # 重新读取可能被 env 文件覆盖的变量
  SPRING_PROFILE="${SPRING_PROFILES_ACTIVE:-$SPRING_PROFILE}"
  LOG_DIR="${LOG_DIR:-/opt/coinstrategy/smart-wallet/logs}"

  # 端口优先级：显式传参 > env 文件 SMART_WALLET_WEB_PORT > env 文件 SERVER_PORT
  if [ -z "$WEB_PORT" ]; then
    WEB_PORT="${SMART_WALLET_WEB_PORT:-${SERVER_PORT:-}}"
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
    rm -f "$PID_FILE"
  fi
  return 1
}

# ── 启动 ──────────────────────────────────────────────────────
do_start() {
  load_env

  if pid=$(get_pid); then
    echo "[web] 服务已在运行中 (PID=$pid)"
    return 0
  fi

  if [ ! -f "$APP_JAR" ]; then
    echo "[web] 错误: JAR 文件不存在: $APP_JAR" >&2
    exit 1
  fi

  mkdir -p "$LOG_DIR" "$APP_HOME/logs" "$APP_HOME/web"

  # 构建 Java 启动命令
  # shellcheck disable=SC2206
  JAVA_OPTS_ARRAY=($JAVA_OPTS_VALUE)
  JAVA_CMD=(java "${JAVA_OPTS_ARRAY[@]}")
  JAVA_CMD+=(-Dspring.profiles.active="$SPRING_PROFILE")
  JAVA_CMD+=(-Dlog.dir="$LOG_DIR")

  if [ -n "$WEB_PORT" ]; then
    JAVA_CMD+=(-Dserver.port="$WEB_PORT")
  fi

  JAVA_CMD+=(-jar "$APP_JAR")

  # 启动信息
  echo "[web] ============================================"
  echo "[web] 启动 CoinStrategy Web 服务 (前端 API 入口)"
  echo "[web] 环境:       $SPRING_PROFILE"
  echo "[web] JAR:        $APP_JAR"
  echo "[web] 共享 env:   ${SHARED_ENV_FILE:-<未配置>}"
  echo "[web] Web env:    $ENV_FILE"
  echo "[web] 日志目录:   $LOG_DIR"
  echo "[web] 日志文件:   $LOG_DIR/web-current.log (按天滚动为 web-yyyy-MM-dd.log)"
  echo "[web] 端口:       ${WEB_PORT:-<配置文件默认 8080>}"
  echo "[web] PID 文件:   $PID_FILE"
  echo "[web] ============================================"

  STARTUP_LOG="$LOG_DIR/web-startup.log"
  nohup "${JAVA_CMD[@]}" > "$STARTUP_LOG" 2>&1 &
  local pid=$!
  echo "$pid" > "$PID_FILE"

  echo "[web] 服务已在后台启动 (PID=$pid)"
  echo "[web] 启动日志: tail -f $STARTUP_LOG"
  echo "[web] 应用日志: tail -f $LOG_DIR/web-current.log"

  sleep 3
  if kill -0 "$pid" 2>/dev/null; then
    echo "[web] 服务启动成功 ✓"
  else
    echo "[web] 警告: 进程可能已退出，请检查日志: $STARTUP_LOG" >&2
    rm -f "$PID_FILE"
    exit 1
  fi
}

# ── 停止 ──────────────────────────────────────────────────────
do_stop() {
  if ! pid=$(get_pid); then
    echo "[web] 服务未在运行"
    return 0
  fi

  echo "[web] 正在停止服务 (PID=$pid) ..."
  kill "$pid" 2>/dev/null || true

  local count=0
  while [ "$count" -lt 30 ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "[web] 服务已停止 ✓"
      rm -f "$PID_FILE"
      return 0
    fi
    sleep 1
    count=$((count + 1))
  done

  echo "[web] 优雅关闭超时，强制终止..."
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  echo "[web] 服务已强制停止"
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
    echo "[web] 服务运行中 (PID=$pid)"
    ps -p "$pid" -o pid,user,%cpu,%mem,etime,command --no-headers 2>/dev/null || true
  else
    echo "[web] 服务未运行"
  fi
}

# ── 实时日志 ──────────────────────────────────────────────────
do_log() {
  local log_file="$LOG_DIR/web-current.log"
  if [ -f "$log_file" ]; then
    echo "[web] 实时查看日志 (Ctrl+C 退出): $log_file"
    tail -f "$log_file"
  else
    echo "[web] 日志文件不存在: $log_file"
    echo "[web] 服务可能尚未启动，可改看启动日志: $LOG_DIR/web-startup.log"
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
