#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_HOME="${SMART_WALLET_APP_HOME:-/opt/coinstrategy/smart-wallet}"
APP_USER="${SMART_WALLET_APP_USER:-${SUDO_USER:-ubuntu}}"
APP_GROUP="${SMART_WALLET_APP_GROUP:-$APP_USER}"
JOB_SERVICE_NAME="${SMART_WALLET_JOB_SERVICE_NAME:-smart-wallet-backend}"
WEB_SERVICE_NAME="${SMART_WALLET_WEB_SERVICE_NAME:-smart-wallet-web}"
BACKEND_PORT="${SMART_WALLET_BACKEND_PORT:-8081}"
WEB_PORT="${SMART_WALLET_WEB_PORT:-8080}"
SERVICE_ENV="${SMART_WALLET_SERVICE_ENV:-prod}"
SERVER_NAME="${SMART_WALLET_SERVER_NAME:-49.51.250.174}"
INSTALL_NGINX="${SMART_WALLET_INSTALL_NGINX:-true}"
FRONTEND_DIST_DIR="${SMART_WALLET_FRONTEND_DIST_DIR:-$APP_HOME/frontend/dist}"
ENV_DIR="${SMART_WALLET_ENV_DIR:-/etc/coinstrategy}"
JOB_ENV_FILE="${SMART_WALLET_JOB_ENV_FILE:-$ENV_DIR/smart-wallet-job.env}"
WEB_ENV_FILE="${SMART_WALLET_WEB_ENV_FILE:-$ENV_DIR/smart-wallet-web.env}"
JOB_SYSTEMD_FILE="/etc/systemd/system/${JOB_SERVICE_NAME}.service"
WEB_SYSTEMD_FILE="/etc/systemd/system/${WEB_SERVICE_NAME}.service"
NGINX_CONF="${SMART_WALLET_NGINX_CONF:-/etc/nginx/conf.d/smart-wallet.conf}"

JOB_SYSTEMD_TEMPLATE="$ROOT_DIR/deploy/linux/systemd/smart-wallet-backend.service.template"
WEB_SYSTEMD_TEMPLATE="$ROOT_DIR/deploy/linux/systemd/smart-wallet-web.service.template"
NGINX_TEMPLATE="$ROOT_DIR/deploy/linux/nginx/smart-wallet.conf.template"
JOB_ENV_TEMPLATE="$ROOT_DIR/deploy/linux/env/smart-wallet-job.env"
WEB_ENV_TEMPLATE="$ROOT_DIR/deploy/linux/env/smart-wallet-web.env"
RESTART_HELPER_SOURCE="$ROOT_DIR/deploy/linux/bin/restart-smart-wallet-backend.sh"

if [ "$(id -u)" -ne 0 ]; then
  echo "[install-smart-wallet-linux-service] please run as root or with sudo." >&2
  exit 1
fi

if [ ! -f "$JOB_SYSTEMD_TEMPLATE" ]; then
  echo "[install-smart-wallet-linux-service] job systemd template not found: $JOB_SYSTEMD_TEMPLATE" >&2
  exit 1
fi

if [ ! -f "$WEB_SYSTEMD_TEMPLATE" ]; then
  echo "[install-smart-wallet-linux-service] web systemd template not found: $WEB_SYSTEMD_TEMPLATE" >&2
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

if [ ! -f "$JOB_ENV_FILE" ]; then
  cp "$JOB_ENV_TEMPLATE" "$JOB_ENV_FILE"
  echo "[install-smart-wallet-linux-service] created job env file: $JOB_ENV_FILE"
fi

if [ ! -f "$WEB_ENV_FILE" ]; then
  cp "$WEB_ENV_TEMPLATE" "$WEB_ENV_FILE"
  echo "[install-smart-wallet-linux-service] created web env file: $WEB_ENV_FILE"
fi

chown "root:$APP_GROUP" "$JOB_ENV_FILE" "$WEB_ENV_FILE"
chmod 640 "$JOB_ENV_FILE" "$WEB_ENV_FILE"

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

WEB_START_SCRIPT_SOURCE=""
if [ -f "$ROOT_DIR/bin/start-smart-wallet-web.sh" ]; then
  WEB_START_SCRIPT_SOURCE="$ROOT_DIR/bin/start-smart-wallet-web.sh"
elif [ -f "$ROOT_DIR/scripts/start-smart-wallet-web.sh" ]; then
  WEB_START_SCRIPT_SOURCE="$ROOT_DIR/scripts/start-smart-wallet-web.sh"
fi

if [ -n "$WEB_START_SCRIPT_SOURCE" ]; then
  install -m 755 "$WEB_START_SCRIPT_SOURCE" "$APP_HOME/bin/start-smart-wallet-web.sh"
  chown "$APP_USER:$APP_GROUP" "$APP_HOME/bin/start-smart-wallet-web.sh"
fi

if [ -f "$RESTART_HELPER_SOURCE" ]; then
  install -m 755 "$RESTART_HELPER_SOURCE" "$APP_HOME/bin/restart-smart-wallet-backend.sh"
  chown "$APP_USER:$APP_GROUP" "$APP_HOME/bin/restart-smart-wallet-backend.sh"
fi

WEB_RESTART_HELPER_SOURCE="$ROOT_DIR/deploy/linux/bin/restart-smart-wallet-web.sh"
if [ -f "$WEB_RESTART_HELPER_SOURCE" ]; then
  install -m 755 "$WEB_RESTART_HELPER_SOURCE" "$APP_HOME/bin/restart-smart-wallet-web.sh"
  chown "$APP_USER:$APP_GROUP" "$APP_HOME/bin/restart-smart-wallet-web.sh"
fi

sed \
  -e "s|__APP_HOME__|$APP_HOME|g" \
  -e "s|__APP_USER__|$APP_USER|g" \
  -e "s|__APP_GROUP__|$APP_GROUP|g" \
  -e "s|__ENV_FILE__|$JOB_ENV_FILE|g" \
  -e "s|__ENV__|$SERVICE_ENV|g" \
  "$JOB_SYSTEMD_TEMPLATE" > "$JOB_SYSTEMD_FILE"

sed \
  -e "s|__APP_HOME__|$APP_HOME|g" \
  -e "s|__APP_USER__|$APP_USER|g" \
  -e "s|__APP_GROUP__|$APP_GROUP|g" \
  -e "s|__ENV_FILE__|$WEB_ENV_FILE|g" \
  -e "s|__SHARED_ENV_FILE__|$JOB_ENV_FILE|g" \
  -e "s|__ENV__|$SERVICE_ENV|g" \
  "$WEB_SYSTEMD_TEMPLATE" > "$WEB_SYSTEMD_FILE"

chmod 644 "$JOB_SYSTEMD_FILE" "$WEB_SYSTEMD_FILE"
systemctl daemon-reload
systemctl enable "$JOB_SERVICE_NAME" "$WEB_SERVICE_NAME"

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
    -e "s|__WEB_PORT__|$WEB_PORT|g" \
    "$NGINX_TEMPLATE" > "$NGINX_CONF"

  chmod 644 "$NGINX_CONF"
  if command -v nginx >/dev/null 2>&1; then
    nginx -t
  fi
fi

echo "[install-smart-wallet-linux-service] job service installed: $JOB_SYSTEMD_FILE"
echo "[install-smart-wallet-linux-service] web service installed: $WEB_SYSTEMD_FILE"
echo "[install-smart-wallet-linux-service] job env file:          $JOB_ENV_FILE"
echo "[install-smart-wallet-linux-service] web env file:          $WEB_ENV_FILE"
echo "[install-smart-wallet-linux-service] next step:"
echo "  1. edit $JOB_ENV_FILE and $WEB_ENV_FILE"
echo "  2. systemctl restart $JOB_SERVICE_NAME"
echo "  3. systemctl restart $WEB_SERVICE_NAME"
echo "  4. systemctl status $JOB_SERVICE_NAME"
echo "  5. systemctl status $WEB_SERVICE_NAME"
if [ "$INSTALL_NGINX" = "true" ]; then
  echo "  6. systemctl reload nginx"
fi
