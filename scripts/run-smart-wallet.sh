#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/scripts/polymarket-env.sh"
FRONTEND_DIR="$ROOT_DIR/coin-strategy-frontend"

REQUESTED_BACKEND_PORT="${SMART_WALLET_BACKEND_PORT:-8081}"
REQUESTED_FRONTEND_PORT="${SMART_WALLET_FRONTEND_PORT:-5173}"

port_in_use() {
  local port="$1"
  lsof -n -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
}

find_available_port() {
  local start="$1"
  local max_try="${2:-50}"
  local port="$start"
  local tries=0
  while [ "$tries" -lt "$max_try" ]; do
    if ! port_in_use "$port"; then
      echo "$port"
      return 0
    fi
    port=$((port + 1))
    tries=$((tries + 1))
  done
  return 1
}

if [ -f "$ENV_FILE" ]; then
  source "$ENV_FILE"
fi

cd "$ROOT_DIR"
if [ -f "$HOME/.zshrc" ]; then
  source "$HOME/.zshrc"
fi

BACKEND_PORT="$(find_available_port "$REQUESTED_BACKEND_PORT")" || {
  echo "[run-smart-wallet] cannot find available backend port from $REQUESTED_BACKEND_PORT" >&2
  exit 1
}
if [ "$BACKEND_PORT" != "$REQUESTED_BACKEND_PORT" ]; then
  echo "[run-smart-wallet] backend port $REQUESTED_BACKEND_PORT is busy, fallback to $BACKEND_PORT"
fi

frontend_start="$REQUESTED_FRONTEND_PORT"
if [ "$frontend_start" -eq "$BACKEND_PORT" ]; then
  frontend_start=$((BACKEND_PORT + 1))
fi
FRONTEND_PORT="$(find_available_port "$frontend_start")" || {
  echo "[run-smart-wallet] cannot find available frontend port from $frontend_start" >&2
  exit 1
}
if [ "$FRONTEND_PORT" != "$REQUESTED_FRONTEND_PORT" ]; then
  echo "[run-smart-wallet] frontend port $REQUESTED_FRONTEND_PORT is busy, fallback to $FRONTEND_PORT"
fi

APP_URL="http://127.0.0.1:$BACKEND_PORT"
FRONTEND_URL="http://127.0.0.1:$FRONTEND_PORT"
API_CONTEXT_PATH="/strategy/api"
BACKEND_HEALTH_URL="$APP_URL$API_CONTEXT_PATH/smart-wallet/health"
BACKEND_SWAGGER_URL="$APP_URL$API_CONTEXT_PATH/swagger-ui.html"

echo "[run-smart-wallet] building coin-strategy-job..."
mvn -pl coin-strategy-job -am package -DskipTests

APP_JAR="$(find "$ROOT_DIR/coin-strategy-job/target" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*.jar' | head -n 1)"
if [ -z "$APP_JAR" ]; then
  echo "[run-smart-wallet] packaged application jar was not produced." >&2
  exit 1
fi

echo "[run-smart-wallet] starting backend on $APP_URL ..."
java -Dserver.port="$BACKEND_PORT" -jar "$APP_JAR" &
APP_PID=$!
FRONTEND_PID=""

cleanup() {
  if [ -n "$FRONTEND_PID" ] && kill -0 "$FRONTEND_PID" >/dev/null 2>&1; then
    kill "$FRONTEND_PID" >/dev/null 2>&1 || true
  fi
  if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" >/dev/null 2>&1; then
    kill "$APP_PID" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT INT TERM

for _ in {1..90}; do
  if curl -s "$BACKEND_HEALTH_URL" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
    echo "[run-smart-wallet] backend exited before becoming healthy." >&2
    wait "$APP_PID"
    exit 1
  fi
  sleep 1
done

if ! curl -s "$BACKEND_HEALTH_URL" >/dev/null 2>&1; then
  echo "[run-smart-wallet] backend health check timeout: $BACKEND_HEALTH_URL" >&2
  exit 1
fi

if [ ! -d "$FRONTEND_DIR" ]; then
  echo "[run-smart-wallet] frontend directory not found: $FRONTEND_DIR" >&2
  exit 1
fi

if [ ! -x "$FRONTEND_DIR/node_modules/.bin/vite" ]; then
  echo "[run-smart-wallet] installing frontend dependencies..."
  (cd "$FRONTEND_DIR" && npm install)
fi

echo "[run-smart-wallet] starting frontend on $FRONTEND_URL ..."
(
  cd "$FRONTEND_DIR"
  VITE_BACKEND_TARGET="$APP_URL" npm run dev -- --host 127.0.0.1 --port "$FRONTEND_PORT" --strictPort
) &
FRONTEND_PID=$!

for _ in {1..60}; do
  if curl -s "$FRONTEND_URL" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$FRONTEND_PID" >/dev/null 2>&1; then
    echo "[run-smart-wallet] frontend exited before becoming ready." >&2
    wait "$FRONTEND_PID"
    exit 1
  fi
  sleep 1
done

if ! curl -s "$FRONTEND_URL" >/dev/null 2>&1; then
  echo "[run-smart-wallet] frontend startup timeout: $FRONTEND_URL" >&2
  exit 1
fi

echo ""
echo "[run-smart-wallet] services are ready."
echo "[run-smart-wallet] backend health:   $BACKEND_HEALTH_URL"
echo "[run-smart-wallet] backend swagger:  $BACKEND_SWAGGER_URL"
echo "[run-smart-wallet] frontend dashboard: $FRONTEND_URL/dashboard"
echo "[run-smart-wallet] frontend app:       $FRONTEND_URL"
echo ""

wait "$APP_PID"
