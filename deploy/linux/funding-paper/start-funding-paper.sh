#!/usr/bin/env bash
set -euo pipefail

APP_HOME="${FUNDING_APP_HOME:-/opt/coinstrategy/funding-paper}"
SHARED_ENV="${FUNDING_SHARED_ENV:-/opt/coinstrategy/smart-wallet/deploy/linux/env/shared/secrets.env}"
ENV_FILE="${FUNDING_ENV_FILE:-$APP_HOME/env/funding.env}"

[[ -f "$SHARED_ENV" ]] || { echo "missing shared secrets environment: $SHARED_ENV" >&2; exit 1; }
[[ -f "$ENV_FILE" ]] || { echo "missing funding environment: $ENV_FILE" >&2; exit 1; }
[[ -f "$APP_HOME/app.jar" ]] || { echo "missing application jar: $APP_HOME/app.jar" >&2; exit 1; }

set -a
# The shared file supplies DB_PASSWORD and the decryptor key. Funding-only component
# filtering keeps its unrelated strategy components from being registered.
source "$SHARED_ENV"
source "$ENV_FILE"
set +a

mkdir -p "$LOG_DIR" "$FUNDING_CAPTURE_OUTPUT_DIRECTORY"
exec java ${JAVA_OPTS:-} -jar "$APP_HOME/app.jar" \
  "--arbitrage.multi-exchange.capture-output-directory=$FUNDING_CAPTURE_OUTPUT_DIRECTORY"
