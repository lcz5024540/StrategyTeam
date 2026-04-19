#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/coin-strategy-frontend"
RELEASE_DIR="${SMART_WALLET_RELEASE_DIR:-$ROOT_DIR/dist/smart-wallet-release}"
VITE_API_BASE_URL_VALUE="${VITE_API_BASE_URL:-}"

mkdir -p "$RELEASE_DIR"

echo "[build-smart-wallet-release] building backend jar locally..."
(cd "$ROOT_DIR" && mvn -pl coin-strategy-job -am package -DskipTests)

APP_JAR="$(find "$ROOT_DIR/coin-strategy-job/target" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*.jar' | head -n 1)"
if [ -z "$APP_JAR" ]; then
  echo "[build-smart-wallet-release] packaged backend jar not found." >&2
  exit 1
fi

echo "[build-smart-wallet-release] building frontend dist locally..."
if [ -f "$FRONTEND_DIR/package-lock.json" ]; then
  (cd "$FRONTEND_DIR" && npm ci)
else
  (cd "$FRONTEND_DIR" && npm install)
fi
(cd "$FRONTEND_DIR" && VITE_API_BASE_URL="$VITE_API_BASE_URL_VALUE" npm run build)

if [ ! -d "$FRONTEND_DIR/dist" ]; then
  echo "[build-smart-wallet-release] frontend dist directory not found." >&2
  exit 1
fi

rm -rf "$RELEASE_DIR/backend" "$RELEASE_DIR/frontend" "$RELEASE_DIR/bin" "$RELEASE_DIR/runtime" "$RELEASE_DIR/deploy"
mkdir -p "$RELEASE_DIR/backend" "$RELEASE_DIR/frontend" "$RELEASE_DIR/bin" "$RELEASE_DIR/runtime" "$RELEASE_DIR/deploy"

cp "$APP_JAR" "$RELEASE_DIR/backend/app.jar"
cp -R "$FRONTEND_DIR/dist" "$RELEASE_DIR/frontend/"
cp "$ROOT_DIR/scripts/start-smart-wallet-backend.sh" "$RELEASE_DIR/bin/"
cp "$ROOT_DIR/scripts/install-smart-wallet-artifacts.sh" "$ROOT_DIR/scripts/install-smart-wallet-linux-service.sh" "$RELEASE_DIR/bin/"
cp "$ROOT_DIR/scripts/polymarket_clob_client.py" "$ROOT_DIR/scripts/polymarket_http.py" "$RELEASE_DIR/runtime/"
cp -R "$ROOT_DIR/deploy/linux" "$RELEASE_DIR/deploy/"

(
  cd "$RELEASE_DIR"
  tar -czf smart-wallet-release.tar.gz backend frontend bin runtime deploy
)

echo "[build-smart-wallet-release] upload bundle ready: $RELEASE_DIR"
echo "[build-smart-wallet-release] upload these to server:"
echo "  - $RELEASE_DIR/backend/app.jar"
echo "  - $RELEASE_DIR/frontend/dist"
echo "  - $RELEASE_DIR/bin/"
echo "  - $RELEASE_DIR/deploy/linux/"
echo "[build-smart-wallet-release] bundled archive:"
echo "  - $RELEASE_DIR/smart-wallet-release.tar.gz"
