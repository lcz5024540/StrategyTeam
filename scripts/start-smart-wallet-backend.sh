#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
DEFAULT_ENV_FILE="/etc/coinstrategy/smart-wallet.env"
DEFAULT_APP_JAR="$APP_HOME/backend/app.jar"
DEFAULT_RUNTIME_DIR="$APP_HOME/runtime"

ENV_FILE="${SMART_WALLET_ENV_FILE:-$DEFAULT_ENV_FILE}"
SPRING_PROFILE="${SPRING_PROFILES_ACTIVE:-prod}"
BACKEND_PORT="${SMART_WALLET_BACKEND_PORT:-}"
APP_JAR="${SMART_WALLET_APP_JAR:-$DEFAULT_APP_JAR}"
JAVA_OPTS_VALUE="${JAVA_OPTS:--Xms512m -Xmx1024m -XX:+UseG1GC}"
HELPER_SCRIPT_PATH="${SMART_WALLET_EXECUTION_WALLET_HELPER_SCRIPT_PATH:-}"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

if [ -z "$HELPER_SCRIPT_PATH" ] && [ -f "$DEFAULT_RUNTIME_DIR/polymarket_clob_client.py" ]; then
  export SMART_WALLET_EXECUTION_WALLET_HELPER_SCRIPT_PATH="$DEFAULT_RUNTIME_DIR/polymarket_clob_client.py"
  HELPER_SCRIPT_PATH="$SMART_WALLET_EXECUTION_WALLET_HELPER_SCRIPT_PATH"
fi

if [ ! -f "$APP_JAR" ]; then
  echo "[start-smart-wallet-backend] runnable jar not found: $APP_JAR" >&2
  exit 1
fi

mkdir -p "$APP_HOME/logs" "$DEFAULT_RUNTIME_DIR"
cd "$APP_HOME"

# shellcheck disable=SC2206
JAVA_OPTS_ARRAY=($JAVA_OPTS_VALUE)
JAVA_CMD=(java "${JAVA_OPTS_ARRAY[@]}" -Dspring.profiles.active="$SPRING_PROFILE")

if [ -n "$BACKEND_PORT" ]; then
  JAVA_CMD+=(-Dserver.port="$BACKEND_PORT")
fi

JAVA_CMD+=(-jar "$APP_JAR")

echo "[start-smart-wallet-backend] app home:      $APP_HOME"
echo "[start-smart-wallet-backend] env file:      ${ENV_FILE:-<none>}"
echo "[start-smart-wallet-backend] spring profile:$SPRING_PROFILE"
echo "[start-smart-wallet-backend] port override: ${BACKEND_PORT:-<use config/env>}"
echo "[start-smart-wallet-backend] app jar:       $APP_JAR"
echo "[start-smart-wallet-backend] helper script: ${HELPER_SCRIPT_PATH:-<not configured>}"

exec "${JAVA_CMD[@]}"
