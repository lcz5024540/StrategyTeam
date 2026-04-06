#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/scripts/polymarket-env.sh"

if [ -f "$ENV_FILE" ]; then
  source "$ENV_FILE"
fi

cd "$ROOT_DIR"
source ~/.zshrc
mvn -pl coin-strategy-job -am package -DskipTests

CP="coin-strategy-job/target/classes:coin-strategy-core/target/classes:coin-strategy-common/common-api/target/classes:coin-strategy-common/common-dao/target/classes:coin-strategy-common/common-client/target/classes:coin-strategy-common/common-base/target/classes:coin-strategy-common/common-model/target/classes:$(find "$ROOT_DIR/.m2/repository" -name '*.jar' ! -name '*undertow*' ! -name 'spring-boot-starter-undertow-*.jar' | paste -sd ':' -)"
java -cp "$CP" com.metaverse.coin.strategy.job.CoinStrategyJobApplication
