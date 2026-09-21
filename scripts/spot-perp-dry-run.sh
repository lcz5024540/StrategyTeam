#!/usr/bin/env bash
# Public-data dry-run / backtest for the spot-long perp-short funding harvest.
# Does not start Spring, does not read API keys, does not place orders.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ -z "${JAVA_HOME:-}" ]]; then
  if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
fi
COMMAND="${1:-once}"
shift || true
mvn -pl coin-strategy-core -am -DskipTests install
exec mvn -f coin-strategy-core/pom.xml exec:java -Dexec.args="${COMMAND} $*"
