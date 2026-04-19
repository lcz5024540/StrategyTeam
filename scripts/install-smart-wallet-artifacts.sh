#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE_DIR="${SMART_WALLET_SOURCE_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
APP_HOME="${SMART_WALLET_APP_HOME:-/opt/coinstrategy/smart-wallet}"
APP_USER="${SMART_WALLET_APP_USER:-coinstrategy}"
APP_GROUP="${SMART_WALLET_APP_GROUP:-$APP_USER}"

SRC_BACKEND_JAR="${SMART_WALLET_SOURCE_JAR:-$SOURCE_DIR/backend/app.jar}"
SRC_FRONTEND_DIST="${SMART_WALLET_SOURCE_FRONTEND_DIST:-$SOURCE_DIR/frontend/dist}"
SRC_BIN_DIR="${SMART_WALLET_SOURCE_BIN_DIR:-$SOURCE_DIR/bin}"
SRC_RUNTIME_DIR="${SMART_WALLET_SOURCE_RUNTIME_DIR:-$SOURCE_DIR/runtime}"

DST_BACKEND_DIR="$APP_HOME/backend"
DST_FRONTEND_DIR="$APP_HOME/frontend"
DST_BIN_DIR="$APP_HOME/bin"
DST_RUNTIME_DIR="$APP_HOME/runtime"

if [ ! -f "$SRC_BACKEND_JAR" ]; then
  echo "[install-smart-wallet-artifacts] backend jar not found: $SRC_BACKEND_JAR" >&2
  exit 1
fi

if [ ! -d "$SRC_FRONTEND_DIST" ]; then
  echo "[install-smart-wallet-artifacts] frontend dist not found: $SRC_FRONTEND_DIST" >&2
  exit 1
fi

mkdir -p "$DST_BACKEND_DIR" "$DST_FRONTEND_DIR" "$DST_BIN_DIR" "$DST_RUNTIME_DIR" "$APP_HOME/logs"

install -m 755 "$SRC_BACKEND_JAR" "$DST_BACKEND_DIR/app.jar"
rm -rf "$DST_FRONTEND_DIR/dist"
cp -R "$SRC_FRONTEND_DIST" "$DST_FRONTEND_DIR/"

if [ -f "$SRC_BIN_DIR/start-smart-wallet-backend.sh" ]; then
  install -m 755 "$SRC_BIN_DIR/start-smart-wallet-backend.sh" "$DST_BIN_DIR/start-smart-wallet-backend.sh"
fi

if [ -d "$SRC_RUNTIME_DIR" ]; then
  if [ -f "$SRC_RUNTIME_DIR/polymarket_clob_client.py" ]; then
    install -m 755 "$SRC_RUNTIME_DIR/polymarket_clob_client.py" "$DST_RUNTIME_DIR/polymarket_clob_client.py"
  fi
  if [ -f "$SRC_RUNTIME_DIR/polymarket_http.py" ]; then
    install -m 755 "$SRC_RUNTIME_DIR/polymarket_http.py" "$DST_RUNTIME_DIR/polymarket_http.py"
  fi
fi

if id -u "$APP_USER" >/dev/null 2>&1; then
  chown -R "$APP_USER:$APP_GROUP" "$APP_HOME"
fi

echo "[install-smart-wallet-artifacts] backend installed: $DST_BACKEND_DIR/app.jar"
echo "[install-smart-wallet-artifacts] frontend installed: $DST_FRONTEND_DIR/dist"
echo "[install-smart-wallet-artifacts] bin installed:     $DST_BIN_DIR"
echo "[install-smart-wallet-artifacts] runtime installed: $DST_RUNTIME_DIR"
