#!/usr/bin/env bash
# Generate ENC(...) values locally without starting the backend service.
#
# Examples:
#   APP_RSA_PRIVATE_KEY_PATH=/path/to/app-private.pem bash scripts/encrypt-secret.sh 'plain-password'
#   APP_RSA_PRIVATE_KEY_PATH=/path/to/app-private.pem bash scripts/encrypt-secret.sh
#   printf '%s' 'plain-password' | APP_RSA_PRIVATE_KEY_PATH=/path/to/app-private.pem bash scripts/encrypt-secret.sh --stdin
#   APP_RSA_PRIVATE_KEY_PATH=/path/to/app-private.pem bash scripts/encrypt-secret.sh --decrypt 'ENC(...)'
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MAIN_CLASS="com.metaverse.coin.strategy.job.config.SecretEncryptTool"
SRC_DIR="$ROOT_DIR/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/config"
BUILD_DIR="$ROOT_DIR/coin-strategy-job/target/secret-tool-classes"

mkdir -p "$BUILD_DIR"
javac -encoding UTF-8 -d "$BUILD_DIR" \
  "$SRC_DIR/SecretCrypto.java" \
  "$SRC_DIR/SecretEncryptTool.java"

java -cp "$BUILD_DIR" "$MAIN_CLASS" "$@"
