#!/usr/bin/env bash
# 2026-05-19 ray@cowork: 部署 WeeklyTopLeaderAutoAllowlistJob + AutoAllowlistConfig + WEEK leaderboard query.
#
# 用法 (在你 Mac 上执行):
#   cd /Users/jenniferni/jenifer/ray/CoinStrategy
#   bash polymarket/deploy-auto-allowlist-job-2026-05-19.sh
#
# Sandbox 没法直连 49.51.250.174, 所以由你本机 SSH/SCP 推上去.

set -euo pipefail

PEM=/Users/jenniferni/jenifer/ray/ChainStrategyPrime.pem
HOST=ubuntu@49.51.250.174
PROJECT_ROOT=$(cd "$(dirname "$0")/.." && pwd)

REMOTE_BUILD=/home/ubuntu/build/CoinStrategy
REMOTE_APP_DIR=/opt/coinstrategy/smart-wallet/backend
REMOTE_OVERRIDE_YML=/opt/coinstrategy/smart-wallet/config/application-test.yml

CHANGED_FILES=(
  "coin-strategy-common/common-service/src/main/java/com/metaverse/coin/strategy/common/service/smartwallet/config/SmartWalletProperties.java"
  "coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/WeeklyTopLeaderAutoAllowlistJob.java"
  "coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletSignalRiskService.java"
  "coin-strategy-job/src/main/resources/application-test.yml"
)

echo "==> [1/6] 推送变更代码到 $HOST:$REMOTE_BUILD"
for f in "${CHANGED_FILES[@]}"; do
  echo "    $f"
  scp -i "$PEM" -o StrictHostKeyChecking=no "$PROJECT_ROOT/$f" "$HOST:$REMOTE_BUILD/$f"
done

echo "==> [2/6] 远程 mvn compile job 模块 (依赖 common-service)"
ssh -i "$PEM" -o StrictHostKeyChecking=no "$HOST" bash <<'REMOTE_BUILD_SH'
set -euo pipefail
cd /home/ubuntu/build/CoinStrategy
# 先装 common-service 的新版本到本地仓库, 再 package job
mvn -pl coin-strategy-common/common-service -am -DskipTests install 2>&1 | tail -10
mvn -pl coin-strategy-job -am -DskipTests package 2>&1 | tail -15
ls -la coin-strategy-job/target/*.jar
REMOTE_BUILD_SH

echo "==> [3/6] 备份当前 jar"
TS=$(date +%s)
ssh -i "$PEM" -o StrictHostKeyChecking=no "$HOST" "sudo cp $REMOTE_APP_DIR/app.jar $REMOTE_APP_DIR/app.jar.bak-${TS} && ls -la $REMOTE_APP_DIR/app.jar*"

echo "==> [4/6] 部署新 jar (sudo cp)"
ssh -i "$PEM" -o StrictHostKeyChecking=no "$HOST" "sudo cp $REMOTE_BUILD/coin-strategy-job/target/coin-strategy-job-*.jar $REMOTE_APP_DIR/app.jar && sudo chown ubuntu:ubuntu $REMOTE_APP_DIR/app.jar"

echo "==> [5/6] 更新 override yml (auto-allowlist + WEEK leaderboard)"
ssh -i "$PEM" -o StrictHostKeyChecking=no "$HOST" bash <<REMOTE_YML_SH
set -euo pipefail
OVR=$REMOTE_OVERRIDE_YML
if grep -q "auto-allowlist:" "\$OVR"; then
  echo "auto-allowlist already in override yml, skip append"
else
  cat >> "\$OVR" <<'YML'

# 2026-05-19 ray@cowork: 每日 06:00 (Asia/Shanghai) 自动从 smart_wallet_profile 选
# 本周活跃 + 高 PnL + 高胜率 leader 入 manual_allowlist (PAPER, 30d 过期)
  auto-allowlist:
    enabled: true
    cron: "0 0 6 * * *"
    zone: "Asia/Shanghai"
    top-n: 10
    min-lifetime-trades: 30
    min-recent-7d-trades: 1
    active-within-days: 7
    min-pnl-usdc: 10000
    min-win-rate: 0.55
    max-drawdown: 0.30
    stake-multiplier: 0.50
    default-mode: "PAPER"
    expiration-days: 30
    overwrite-existing: false
YML
fi

# WEEK leaderboard query
if grep -qE 'time-period:\s*"?WEEK"?' "\$OVR"; then
  echo "WEEK leaderboard already in override yml, skip"
else
  python3 - <<'PY'
import re, sys
p = "$REMOTE_OVERRIDE_YML"
src = open(p).read()
# 在 polymarket.leaderboard-queries 块末尾追加 WEEK 项
m = re.search(r'(\s+polymarket:\n(?:.*\n)*?\s+leaderboard-queries:\n(?:\s+-.*\n(?:\s+\w.*\n)*)+)', src)
if m:
    block = m.group(1)
    if 'WEEK' not in block:
        indent = '      '
        addition = f'{indent}- category: "OVERALL"\n{indent}  time-period: "WEEK"\n{indent}  order-by: "PNL"\n{indent}  limit: 100\n'
        src = src[:m.end()] + addition + src[m.end():]
        open(p,'w').write(src)
        print('added WEEK leaderboard query')
    else:
        print('WEEK already present')
else:
    print('warn: polymarket.leaderboard-queries block not found in', p)
PY
fi
echo "--- override yml head ---"
head -120 "\$OVR"
REMOTE_YML_SH

echo "==> [6/6] 重启服务 + tail log 验证"
ssh -i "$PEM" -o StrictHostKeyChecking=no "$HOST" bash <<'REMOTE_RESTART_SH'
set -euo pipefail
sudo systemctl restart smart-wallet-backend.service || sudo /opt/coinstrategy/smart-wallet/bin/run-smart-wallet.sh restart || true
sleep 12
ps -ef | grep -E 'java.*app.jar' | grep -v grep | head -3
echo "--- last 60 lines of log ---"
LOG=$(ls -t /opt/coinstrategy/smart-wallet/logs/*.log 2>/dev/null | head -1)
[ -n "$LOG" ] && tail -60 "$LOG"
echo "--- search for WeeklyTopLeaderAutoAllowlistJob bootstrap ---"
[ -n "$LOG" ] && grep -i 'WeeklyTopLeaderAutoAllowlist\|AutoAllowlist\|auto-allowlist' "$LOG" | head -10
REMOTE_RESTART_SH

echo "==> done. 验证步骤:"
echo "    1. tail -f /opt/coinstrategy/smart-wallet/logs/<latest>.log | grep WeeklyTopLeader"
echo "    2. 等明早 06:00 (Asia/Shanghai) 第一次 cron 触发, 或者手动 curl:"
echo "       (如果有 actuator) 或直接看 log 'tick done' 行"
echo "    3. SQL 验证 allowlist 是否扩充:"
echo "       SELECT wallet_address, status, allowed_execution_modes, stake_multiplier, expires_at, reason, created_by"
echo "       FROM smart_wallet_manual_allowlist WHERE created_by='WeeklyTopLeaderAutoAllowlistJob';"
