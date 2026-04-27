#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_HOME="${SMART_WALLET_APP_HOME:-/opt/coinstrategy/smart-wallet}"
APP_USER="${SMART_WALLET_APP_USER:-${SUDO_USER:-ubuntu}}"
APP_GROUP="${SMART_WALLET_APP_GROUP:-$APP_USER}"
SERVICE_NAME="${SMART_WALLET_SERVICE_NAME:-smart-wallet-backend}"
BACKEND_PORT="${SMART_WALLET_BACKEND_PORT:-8081}"
SERVER_NAME="${SMART_WALLET_SERVER_NAME:-49.51.250.174}"
INSTALL_NGINX="${SMART_WALLET_INSTALL_NGINX:-true}"
FRONTEND_DIST_DIR="${SMART_WALLET_FRONTEND_DIST_DIR:-$APP_HOME/frontend/dist}"
ENV_DIR="${SMART_WALLET_ENV_DIR:-/etc/coinstrategy}"
ENV_FILE="${SMART_WALLET_ENV_FILE:-$ENV_DIR/smart-wallet.env}"
SYSTEMD_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
NGINX_CONF="${SMART_WALLET_NGINX_CONF:-/etc/nginx/conf.d/smart-wallet.conf}"

SYSTEMD_TEMPLATE="$ROOT_DIR/deploy/linux/systemd/smart-wallet-backend.service.template"
NGINX_TEMPLATE="$ROOT_DIR/deploy/linux/nginx/smart-wallet.conf.template"
ENV_TEMPLATE="$ROOT_DIR/deploy/linux/env/smart-wallet.env"
RESTART_HELPER_SOURCE="$ROOT_DIR/deploy/linux/bin/restart-smart-wallet-backend.sh"

if [ "$(id -u)" -ne 0 ]; then
  echo "[install-smart-wallet-linux-service] please run as root or with sudo." >&2
  exit 1
fi

if [ ! -f "$SYSTEMD_TEMPLATE" ]; then
  echo "[install-smart-wallet-linux-service] systemd template not found: $SYSTEMD_TEMPLATE" >&2
  exit 1
fi

if ! id -u "$APP_USER" >/dev/null 2>&1; then
  useradd --system --create-home --shell /bin/bash --user-group "$APP_USER"
fi

if ! getent group "$APP_GROUP" >/dev/null 2>&1; then
  groupadd --system "$APP_GROUP"
fi

if [ "$APP_GROUP" != "$APP_USER" ]; then
  usermod -a -G "$APP_GROUP" "$APP_USER"
fi

mkdir -p "$ENV_DIR" "$APP_HOME/logs"
mkdir -p "$APP_HOME/bin" "$APP_HOME/backend" "$APP_HOME/frontend" "$APP_HOME/runtime"
chown -R "$APP_USER:$APP_GROUP" "$APP_HOME"

if [ ! -f "$ENV_FILE" ]; then
  cp "$ENV_TEMPLATE" "$ENV_FILE"
  echo "[install-smart-wallet-linux-service] created env file: $ENV_FILE"
fi

chown "root:$APP_GROUP" "$ENV_FILE"
chmod 640 "$ENV_FILE"

START_SCRIPT_SOURCE=""
if [ -f "$ROOT_DIR/bin/start-smart-wallet-backend.sh" ]; then
  START_SCRIPT_SOURCE="$ROOT_DIR/bin/start-smart-wallet-backend.sh"
elif [ -f "$ROOT_DIR/scripts/start-smart-wallet-backend.sh" ]; then
  START_SCRIPT_SOURCE="$ROOT_DIR/scripts/start-smart-wallet-backend.sh"
fi

if [ -n "$START_SCRIPT_SOURCE" ]; then
  install -m 755 "$START_SCRIPT_SOURCE" "$APP_HOME/bin/start-smart-wallet-backend.sh"
  chown "$APP_USER:$APP_GROUP" "$APP_HOME/bin/start-smart-wallet-backend.sh"
fi

if [ -f "$RESTART_HELPER_SOURCE" ]; then
  install -m 755 "$RESTART_HELPER_SOURCE" "$APP_HOME/bin/restart-smart-wallet-backend.sh"
  chown "$APP_USER:$APP_GROUP" "$APP_HOME/bin/restart-smart-wallet-backend.sh"
fi

sed \
  -e "s|__APP_HOME__|$APP_HOME|g" \
  -e "s|__APP_USER__|$APP_USER|g" \
  -e "s|__APP_GROUP__|$APP_GROUP|g" \
  -e "s|__ENV_FILE__|$ENV_FILE|g" \
  "$SYSTEMD_TEMPLATE" > "$SYSTEMD_FILE"

chmod 644 "$SYSTEMD_FILE"
systemctl daemon-reload
systemctl enable "$SERVICE_NAME"

if [ "$INSTALL_NGINX" = "true" ]; then
  if [ ! -f "$NGINX_TEMPLATE" ]; then
    echo "[install-smart-wallet-linux-service] nginx template not found: $NGINX_TEMPLATE" >&2
    exit 1
  fi

  if [ ! -d "$FRONTEND_DIST_DIR" ]; then
    echo "[install-smart-wallet-linux-service] frontend dist not found: $FRONTEND_DIST_DIR" >&2
    echo "[install-smart-wallet-linux-service] upload frontend dist first, then rerun this script or set SMART_WALLET_INSTALL_NGINX=false." >&2
    exit 1
  fi

  sed \
    -e "s|__SERVER_NAME__|$SERVER_NAME|g" \
    -e "s|__FRONTEND_DIST_DIR__|$FRONTEND_DIST_DIR|g" \
    -e "s|__BACKEND_PORT__|$BACKEND_PORT|g" \
    "$NGINX_TEMPLATE" > "$NGINX_CONF"

  chmod 644 "$NGINX_CONF"
  if command -v nginx >/dev/null 2>&1; then
    nginx -t
  fi
fi

echo "[install-smart-wallet-linux-service] service installed: $SYSTEMD_FILE"
echo "[install-smart-wallet-linux-service] env file:          $ENV_FILE"
echo "[install-smart-wallet-linux-service] next step:"
echo "  1. edit $ENV_FILE"
echo "  2. systemctl restart $SERVICE_NAME"
echo "  3. systemctl status $SERVICE_NAME"
if [ "$INSTALL_NGINX" = "true" ]; then
  echo "  4. systemctl reload nginx"
fi
