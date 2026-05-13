#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/coin-strategy-frontend"
RELEASE_DIR="${SMART_WALLET_RELEASE_DIR:-$ROOT_DIR/dist/smart-wallet-release}"
VITE_API_BASE_URL_VALUE="${VITE_API_BASE_URL:-}"

mkdir -p "$RELEASE_DIR"

echo "[build-smart-wallet-release] building job/web jars locally..."
(cd "$ROOT_DIR" && mvn -pl coin-strategy-job,coin-strategy-web -am package -DskipTests)

JOB_JAR="$(find "$ROOT_DIR/coin-strategy-job/target" -maxdepth 1 -type f -name '*.jar' \
  ! -name 'original-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -n 1)"
WEB_JAR="$(find "$ROOT_DIR/coin-strategy-web/target" -maxdepth 1 -type f -name '*.jar' \
  ! -name 'original-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -n 1)"
if [ -z "$JOB_JAR" ]; then
  echo "[build-smart-wallet-release] packaged job jar not found." >&2
  exit 1
fi
if [ -z "$WEB_JAR" ]; then
  echo "[build-smart-wallet-release] packaged web jar not found." >&2
  exit 1
fi
# 校验是 Spring Boot fat jar (含 Main-Class)
for jar in "$JOB_JAR" "$WEB_JAR"; do
  if ! unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null | grep -q '^Main-Class:'; then
    echo "[build-smart-wallet-release] $jar 不是可执行 fat jar (缺 Main-Class)" >&2
    echo "  请确认对应模块的 spring-boot-maven-plugin 声明了 repackage execution" >&2
    exit 1
  fi
done

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

rm -rf "$RELEASE_DIR/backend" "$RELEASE_DIR/web" "$RELEASE_DIR/frontend" "$RELEASE_DIR/bin" "$RELEASE_DIR/runtime" "$RELEASE_DIR/deploy"
mkdir -p "$RELEASE_DIR/backend" "$RELEASE_DIR/web" "$RELEASE_DIR/frontend" "$RELEASE_DIR/bin" "$RELEASE_DIR/runtime" "$RELEASE_DIR/deploy"

cp "$JOB_JAR" "$RELEASE_DIR/backend/app.jar"
cp "$WEB_JAR" "$RELEASE_DIR/web/app.jar"
cp -R "$FRONTEND_DIR/dist" "$RELEASE_DIR/frontend/"
cp "$ROOT_DIR/scripts/start-smart-wallet-backend.sh" "$RELEASE_DIR/bin/"
cp "$ROOT_DIR/deploy/linux/bin/restart-smart-wallet-backend.sh" "$RELEASE_DIR/bin/"
cp "$ROOT_DIR/scripts/install-smart-wallet-artifacts.sh" "$ROOT_DIR/scripts/install-smart-wallet-linux-service.sh" "$RELEASE_DIR/bin/"
cp "$ROOT_DIR/scripts/polymarket_clob_client.py" "$ROOT_DIR/scripts/polymarket_http.py" "$RELEASE_DIR/runtime/"
cp -R "$ROOT_DIR/deploy/linux" "$RELEASE_DIR/deploy/"

(
  cd "$RELEASE_DIR"
  tar -czf smart-wallet-release.tar.gz backend web frontend bin runtime deploy
)

echo "[build-smart-wallet-release] upload bundle ready: $RELEASE_DIR"
echo "[build-smart-wallet-release] upload these to server:"
echo "  - $RELEASE_DIR/backend/app.jar"
echo "  - $RELEASE_DIR/web/app.jar"
echo "  - $RELEASE_DIR/frontend/dist"
echo "  - $RELEASE_DIR/bin/"
echo "  - $RELEASE_DIR/deploy/linux/"
echo "[build-smart-wallet-release] bundled archive:"
echo "  - $RELEASE_DIR/smart-wallet-release.tar.gz"
