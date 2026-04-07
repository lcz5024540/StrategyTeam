#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/scripts/polymarket-env.sh"
APP_URL="http://127.0.0.1:8081"

if [ -f "$ENV_FILE" ]; then
  source "$ENV_FILE"
fi

cd "$ROOT_DIR"
source ~/.zshrc

echo "[run-smart-wallet] building coin-strategy-job..."
mvn -pl coin-strategy-job -am package -DskipTests

CP="coin-strategy-job/target/classes:coin-strategy-core/target/classes:coin-strategy-common/common-api/target/classes:coin-strategy-common/common-dao/target/classes:coin-strategy-common/common-client/target/classes:coin-strategy-common/common-base/target/classes:coin-strategy-common/common-model/target/classes:$(find "$ROOT_DIR/.m2/repository" -name '*.jar' \
  ! -path '*/org/springframework/boot/*/3.*/*.jar' \
  ! -path '*/org/apache/tomcat/embed/*/10.*/*.jar' \
  ! -path '*/org/springframework/spring-*/6.*/*.jar' \
  ! -path '*/jakarta/servlet/*/*.jar' \
  ! -name '*undertow*' \
  ! -name 'spring-boot-starter-undertow-*.jar' | paste -sd ':' -)"

echo "[run-smart-wallet] starting application..."
java -cp "$CP" com.metaverse.coin.strategy.job.CoinStrategyJobApplication &
APP_PID=$!

cleanup() {
  if kill -0 "$APP_PID" >/dev/null 2>&1; then
    kill "$APP_PID" >/dev/null 2>&1 || true
  fi
}

trap cleanup INT TERM

for _ in {1..60}; do
  if curl -s "$APP_URL/smart-wallet/health" >/dev/null 2>&1; then
    echo ""
    echo "[run-smart-wallet] service is ready."
    echo "[run-smart-wallet] health:   $APP_URL/smart-wallet/health"
    echo "[run-smart-wallet] swagger:  $APP_URL/swagger-ui.html"
    echo "[run-smart-wallet] dashboard overview: $APP_URL/dashboard/index.html"
    echo "[run-smart-wallet] dashboard detail:   $APP_URL/dashboard/detail.html?strategy=POLYMARKET_COPY&days=30"
    echo "[run-smart-wallet] dashboard charts:   $APP_URL/dashboard/charts.html?strategy=POLYMARKET_COPY&days=30"
    echo ""
    break
  fi
  if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
    echo "[run-smart-wallet] application exited before becoming healthy." >&2
    wait "$APP_PID"
    exit 1
  fi
  sleep 1
done

wait "$APP_PID"
