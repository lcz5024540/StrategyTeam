#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEFAULT_ENV_FILE="/etc/coinstrategy/smart-wallet.env"
LOCAL_ENV_FILE="$ROOT_DIR/scripts/polymarket-env.sh"

ENV_FILE="${SMART_WALLET_ENV_FILE:-}"
SPRING_PROFILE="${SPRING_PROFILES_ACTIVE:-prod}"
BACKEND_PORT="${SMART_WALLET_BACKEND_PORT:-}"
APP_JAR="${SMART_WALLET_APP_JAR:-}"
JAVA_OPTS_VALUE="${JAVA_OPTS:--Xms512m -Xmx1024m -XX:+UseG1GC}"

if [ -z "$ENV_FILE" ]; then
  if [ -f "$DEFAULT_ENV_FILE" ]; then
    ENV_FILE="$DEFAULT_ENV_FILE"
  elif [ -f "$LOCAL_ENV_FILE" ]; then
    ENV_FILE="$LOCAL_ENV_FILE"
  fi
fi

if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

if [ -z "$APP_JAR" ]; then
  APP_JAR="$(find "$ROOT_DIR/coin-strategy-job/target" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*.jar' | head -n 1)"
fi

if [ -z "$APP_JAR" ] || [ ! -f "$APP_JAR" ]; then
  echo "[start-smart-wallet-backend] cannot find runnable jar under $ROOT_DIR/coin-strategy-job/target" >&2
  echo "[start-smart-wallet-backend] run scripts/build-smart-wallet-release.sh or mvn -pl coin-strategy-job -am package -DskipTests first." >&2
  exit 1
fi

mkdir -p "$ROOT_DIR/logs"
cd "$ROOT_DIR"

# shellcheck disable=SC2206
JAVA_OPTS_ARRAY=($JAVA_OPTS_VALUE)

echo "[start-smart-wallet-backend] root:          $ROOT_DIR"
echo "[start-smart-wallet-backend] env file:      ${ENV_FILE:-<none>}"
echo "[start-smart-wallet-backend] spring profile:$SPRING_PROFILE"
echo "[start-smart-wallet-backend] config file:    application-$SPRING_PROFILE.yml"
echo "[start-smart-wallet-backend] port override: ${BACKEND_PORT:-<use config file>}"
echo "[start-smart-wallet-backend] app jar:       $APP_JAR"

JAVA_CMD=(java "${JAVA_OPTS_ARRAY[@]}" -Dspring.profiles.active="$SPRING_PROFILE")
if [ -n "$BACKEND_PORT" ]; then
  JAVA_CMD+=(-Dserver.port="$BACKEND_PORT")
fi
JAVA_CMD+=(-jar "$APP_JAR")

exec "${JAVA_CMD[@]}"
