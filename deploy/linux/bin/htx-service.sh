#!/usr/bin/env bash
# ============================================================
# htx-service.sh — HTX(火币) 现货执行服务 管理脚本 (self-contained)
#
# 部署机: 49.51.250.174 (与 MySQL 同机, DB 走 env/htx.env)
# 目录约定 (APP_HOME = 本脚本父目录的上一级):
#   APP_HOME/app.jar            可执行 fat jar
#   APP_HOME/env/htx.env        环境变量 (含 AK/SK/DB, 700 权限, 不入 git)
#   APP_HOME/logs/              日志 (LOG_DIR); 业务日志由 logback 写 coin-strategy-htx.log
#   APP_HOME/logs/stdout.log    进程 stdout/stderr
#   APP_HOME/app.pid            PID 文件
#
# 用法:
#   ./htx-service.sh start     后台启动
#   ./htx-service.sh stop      停止
#   ./htx-service.sh restart   重启
#   ./htx-service.sh status    查看状态 (进程 + 端口 + 风控上限)
#   ./htx-service.sh log       tail 业务日志
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

JAR="$APP_HOME/app.jar"
ENV_FILE="${HTX_ENV_FILE:-$APP_HOME/env/htx.env}"
export LOG_DIR="${LOG_DIR:-$APP_HOME/logs}"
PID_FILE="$APP_HOME/app.pid"
STDOUT_LOG="$LOG_DIR/stdout.log"

mkdir -p "$LOG_DIR"

load_env() {
  if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
  else
    echo "[WARN] env 文件不存在: $ENV_FILE (将依赖 application.yml 默认值)" >&2
  fi
}

is_running() {
  [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

start() {
  if is_running; then
    echo "[htx] 已在运行 (pid=$(cat "$PID_FILE"))"; return 0
  fi
  [[ -f "$JAR" ]] || { echo "[ERROR] 找不到 $JAR" >&2; exit 1; }
  load_env
  echo "[htx] 启动中 ... (HTX_TRADE_ENABLED=${HTX_TRADE_ENABLED:-未设置} 单笔上限=${HTX_MAX_SINGLE_QUOTE_NOTIONAL:-app默认50} 日累计上限=${HTX_MAX_DAILY_QUOTE_NOTIONAL:-app默认100000})"
  # shellcheck disable=SC2086
  nohup java ${JAVA_OPTS:-} -jar "$JAR" >>"$STDOUT_LOG" 2>&1 &
  echo $! > "$PID_FILE"
  sleep 2
  if is_running; then
    echo "[htx] 已启动 pid=$(cat "$PID_FILE"); 日志: $STDOUT_LOG 与 $LOG_DIR/coin-strategy-htx.log"
  else
    echo "[ERROR] 启动失败, 查看 $STDOUT_LOG" >&2; tail -n 40 "$STDOUT_LOG" >&2 || true; exit 1
  fi
}

stop() {
  if is_running; then
    local pid; pid="$(cat "$PID_FILE")"
    echo "[htx] 停止 pid=$pid ..."; kill "$pid"
    for _ in $(seq 1 20); do is_running || break; sleep 0.5; done
    is_running && { echo "[htx] 强制 kill -9"; kill -9 "$pid" 2>/dev/null || true; }
    rm -f "$PID_FILE"; echo "[htx] 已停止"
  else
    echo "[htx] 未在运行"; rm -f "$PID_FILE" 2>/dev/null || true
  fi
}

status() {
  if is_running; then
    echo "[htx] RUNNING pid=$(cat "$PID_FILE")"
  else
    echo "[htx] STOPPED"
  fi
  (ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null) | grep -E ':8082' || echo "  8082 未监听"
}

log() { tail -n "${2:-100}" -f "$LOG_DIR/coin-strategy-htx.log"; }

case "${1:-start}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; start ;;
  status)  status ;;
  log)     log "$@" ;;
  *) echo "用法: $0 {start|stop|restart|status|log}"; exit 1 ;;
esac
