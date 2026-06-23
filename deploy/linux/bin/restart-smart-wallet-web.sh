#!/usr/bin/env bash
# ============================================================
# Web 服务 Linux 远程重启脚本 (与 restart-smart-wallet-backend.sh 对称)
# - 自动识别 systemd / 直接脚本两种部署模式
# - 默认端口 8080
# - systemd 服务名默认 smart-wallet-web
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

detect_default_app_home() {
  if [ -f "$SCRIPT_DIR/start-smart-wallet-web.sh" ]; then
    cd "$SCRIPT_DIR/.." && pwd
    return 0
  fi

  if [ -f "$SCRIPT_DIR/../../../bin/start-smart-wallet-web.sh" ]; then
    cd "$SCRIPT_DIR/../../.." && pwd
    return 0
  fi

  echo "/opt/coinstrategy/smart-wallet"
}

APP_HOME="${SMART_WALLET_APP_HOME:-$(detect_default_app_home)}"
APP_USER="${SMART_WALLET_APP_USER:-${SUDO_USER:-ubuntu}}"
APP_GROUP="${SMART_WALLET_APP_GROUP:-}"
SERVICE_NAME="${SMART_WALLET_WEB_SERVICE_NAME:-smart-wallet-web}"
START_MODE="${SMART_WALLET_START_MODE:-auto}"
WEB_PORT="${SMART_WALLET_WEB_PORT:-8080}"
LOG_DIR="${LOG_DIR:-/opt/coinstrategy/smart-wallet/logs}"

resolve_default_env_file() {
  local system_env="/etc/coinstrategy/smart-wallet-web.env"
  local bundled_env="$APP_HOME/deploy/linux/env/smart-wallet-web.env"

  if [ -f "$system_env" ]; then
    echo "$system_env"
    return 0
  fi

  if [ -f "$bundled_env" ]; then
    echo "$bundled_env"
    return 0
  fi

  echo "$system_env"
}

resolve_default_shared_env_file() {
  local system_env="/etc/coinstrategy/smart-wallet-job.env"
  local bundled_env="$APP_HOME/deploy/linux/env/smart-wallet-job.env"

  if [ -f "$system_env" ]; then
    echo "$system_env"
    return 0
  fi

  if [ -f "$bundled_env" ]; then
    echo "$bundled_env"
    return 0
  fi

  echo ""
}

ENV_FILE="${SMART_WALLET_ENV_FILE:-$(resolve_default_env_file)}"
SHARED_ENV_FILE="${SMART_WALLET_SHARED_ENV_FILE:-$(resolve_default_shared_env_file)}"
START_SCRIPT="${SMART_WALLET_WEB_START_SCRIPT:-$APP_HOME/bin/start-smart-wallet-web.sh}"

has_sudo() {
  command -v sudo >/dev/null 2>&1
}

run_root() {
  if has_sudo; then
    sudo "$@"
  else
    "$@"
  fi
}

resolve_app_group() {
  if [ -n "$APP_GROUP" ]; then
    echo "$APP_GROUP"
    return 0
  fi

  if id -u "$APP_USER" >/dev/null 2>&1; then
    id -gn "$APP_USER"
    return 0
  fi

  echo "$APP_USER"
}

run_app_user() {
  if id -u "$APP_USER" >/dev/null 2>&1; then
    if has_sudo; then
      sudo -u "$APP_USER" "$@"
    else
      "$@"
    fi
  else
    echo "[linux-web-restart] warning: app user not found: $APP_USER, will start with the current sudo/root context."
    run_root "$@"
  fi
}

load_env() {
  if [ -n "$SHARED_ENV_FILE" ] && [ -f "$SHARED_ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$SHARED_ENV_FILE"
    set +a
  fi

  if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
  fi

  WEB_PORT="${SMART_WALLET_WEB_PORT:-${SERVER_PORT:-$WEB_PORT}}"
  LOG_DIR="${LOG_DIR:-/opt/coinstrategy/smart-wallet/logs}"
}

ensure_sudo_ready() {
  if [ "$(id -u)" -ne 0 ] && ! has_sudo; then
    echo "[linux-web-restart] error: sudo is required but not installed." >&2
    exit 1
  fi

  if has_sudo; then
    sudo -v
  fi
}

validate_port() {
  case "$WEB_PORT" in
    ''|*[!0-9]*)
      echo "[linux-web-restart] error: invalid web port: $WEB_PORT" >&2
      exit 1
      ;;
  esac
}

validate_start_mode() {
  case "$START_MODE" in
    auto|systemd|script)
      ;;
    *)
      echo "[linux-web-restart] error: invalid start mode: $START_MODE" >&2
      exit 1
      ;;
  esac
}

systemd_service_exists() {
  if ! command -v systemctl >/dev/null 2>&1; then
    return 1
  fi

  run_root systemctl cat "$SERVICE_NAME" >/dev/null 2>&1
}

find_port_pids() {
  local output=""

  if command -v lsof >/dev/null 2>&1; then
    output="$(run_root lsof -tiTCP:"$WEB_PORT" -sTCP:LISTEN 2>/dev/null || true)"
  elif command -v ss >/dev/null 2>&1; then
    output="$(run_root ss -ltnp "sport = :$WEB_PORT" 2>/dev/null | awk -F'pid=' 'NR > 1 && NF > 1 {split($2, a, ","); print a[1]}' || true)"
  else
    echo "[linux-web-restart] error: neither lsof nor ss is installed, cannot inspect port $WEB_PORT." >&2
    exit 1
  fi

  printf '%s\n' "$output" | sed '/^$/d' | sort -u
}

print_pid_info() {
  local pid="$1"
  run_root ps -p "$pid" -o pid,user,command --no-headers 2>/dev/null || true
}

ensure_start_script_permissions() {
  if [ ! -f "$START_SCRIPT" ]; then
    echo "[linux-web-restart] error: start script not found: $START_SCRIPT" >&2
    exit 1
  fi

  local start_dir
  start_dir="$(dirname "$START_SCRIPT")"

  if ! run_app_user test -r "$START_SCRIPT"; then
    run_root chmod 755 "$START_SCRIPT"
  fi

  if ! run_app_user test -x "$START_SCRIPT"; then
    run_root chmod 755 "$START_SCRIPT"
  fi

  if ! run_app_user test -x "$start_dir"; then
    run_root chmod 755 "$start_dir"
  fi
}

ensure_runtime_permissions() {
  local app_group
  app_group="$(resolve_app_group)"

  echo "[linux-web-restart] ensuring runtime paths are writable by $APP_USER:$app_group"

  run_root mkdir -p \
    "$APP_HOME" \
    "$APP_HOME/bin" \
    "$APP_HOME/web" \
    "$APP_HOME/logs" \
    "$LOG_DIR"

  if id -u "$APP_USER" >/dev/null 2>&1; then
    run_root chown "$APP_USER:$app_group" "$APP_HOME" "$APP_HOME/bin" "$APP_HOME/web" "$APP_HOME/logs" "$LOG_DIR"
    run_root chown -R "$APP_USER:$app_group" "$APP_HOME/web" "$APP_HOME/logs" "$LOG_DIR"
  fi

  run_root chmod 755 "$APP_HOME" "$APP_HOME/bin" "$APP_HOME/web" "$APP_HOME/logs" "$LOG_DIR"
  run_root chmod -R u+rwX "$APP_HOME/web" "$APP_HOME/logs" "$LOG_DIR"
}

kill_port_processes() {
  local pids=""
  local remaining=""

  pids="$(find_port_pids)"
  if [ -z "$pids" ]; then
    echo "[linux-web-restart] port $WEB_PORT is free."
    return 0
  fi

  echo "[linux-web-restart] found process(es) listening on port $WEB_PORT:"
  while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    print_pid_info "$pid"
  done <<< "$pids"

  echo "[linux-web-restart] stopping process(es) with sudo kill..."
  while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    run_root kill "$pid" 2>/dev/null || true
  done <<< "$pids"

  sleep 3
  remaining="$(find_port_pids)"
  if [ -n "$remaining" ]; then
    echo "[linux-web-restart] port $WEB_PORT still busy, forcing kill -9..."
    while IFS= read -r pid; do
      [ -n "$pid" ] || continue
      run_root kill -9 "$pid" 2>/dev/null || true
    done <<< "$remaining"
    sleep 1
  fi

  remaining="$(find_port_pids)"
  if [ -n "$remaining" ]; then
    echo "[linux-web-restart] error: failed to release port $WEB_PORT." >&2
    exit 1
  fi

  echo "[linux-web-restart] port $WEB_PORT has been released."
}

start_web() {
  local cmd=()

  if [ "$START_MODE" = "systemd" ] || { [ "$START_MODE" = "auto" ] && systemd_service_exists; }; then
    if ! systemd_service_exists; then
      echo "[linux-web-restart] error: systemd service not found: $SERVICE_NAME" >&2
      exit 1
    fi

    echo "[linux-web-restart] starting web via sudo systemctl start $SERVICE_NAME"
    run_root systemctl start "$SERVICE_NAME"
    return 0
  fi

  ensure_start_script_permissions
  ensure_runtime_permissions

  echo "[linux-web-restart] starting web via sudo -u $APP_USER $START_SCRIPT"
  cmd=(env
    "SMART_WALLET_ENV_FILE=$ENV_FILE"
    "SMART_WALLET_WEB_PORT=$WEB_PORT"
    "LOG_DIR=$LOG_DIR"
  )
  if [ -n "$SHARED_ENV_FILE" ]; then
    cmd+=("SMART_WALLET_SHARED_ENV_FILE=$SHARED_ENV_FILE")
  fi
  if [ -n "${SPRING_PROFILES_ACTIVE:-}" ]; then
    cmd+=("SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE")
  fi
  cmd+=(bash "$START_SCRIPT" start)

  run_app_user "${cmd[@]}"
}

stop_systemd_service_if_needed() {
  if [ "$START_MODE" = "script" ]; then
    return 0
  fi

  if systemd_service_exists; then
    echo "[linux-web-restart] stopping systemd service first to avoid Restart=always races: $SERVICE_NAME"
    run_root systemctl stop "$SERVICE_NAME" || true
  fi
}

tail_logs() {
  local log_file="$LOG_DIR/web-current.log"

  if [ ! -f "$log_file" ]; then
    log_file="$LOG_DIR/web-startup.log"
  fi

  echo "[linux-web-restart] tailing log with sudo: $log_file"
  run_root tail -n 500 -f "$log_file"
}

main() {
  local skip_tail=0
  for arg in "$@"; do
    case "$arg" in
      --no-tail) skip_tail=1 ;;
    esac
  done

  load_env
  validate_port
  validate_start_mode
  ensure_sudo_ready

  echo "[linux-web-restart] app home:        $APP_HOME"
  echo "[linux-web-restart] app user:        $APP_USER"
  echo "[linux-web-restart] service name:    $SERVICE_NAME"
  echo "[linux-web-restart] start mode:      $START_MODE"
  echo "[linux-web-restart] shared env file: ${SHARED_ENV_FILE:-<未配置>}"
  echo "[linux-web-restart] web env file:    $ENV_FILE"
  echo "[linux-web-restart] web port:        $WEB_PORT"
  echo "[linux-web-restart] log dir:         $LOG_DIR"

  stop_systemd_service_if_needed
  kill_port_processes
  start_web
  sleep 2

  if [ "$skip_tail" = "1" ] || [ "${SMART_WALLET_NO_TAIL:-0}" = "1" ]; then
    echo "[linux-web-restart] web restart finished (skip tail)"
    return 0
  fi

  tail_logs
}

main "$@"
