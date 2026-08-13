#!/usr/bin/env bash
# verify-secrets-key.sh — 校验 RSA 私钥能否解开 secrets.env 里的全部 ENC(...) 密文
#
# 用途：搬机前验证从旧机拷来的 app-private.pem 是否为「真钥」（能解现网密文）。
#       解不了 → 别用它搬机（走 Runbook §6 路线 A 全部重设）。
#
# 用法：
#   bash scripts/verify-secrets-key.sh [私钥路径] [secrets.env路径] [--show]
# 默认：
#   私钥    = ./app-private.REAL.pem
#   secrets = deploy/linux/env/shared/secrets.env
#   --show  = 打印解出的明文全文（默认仅掩码，避免密码/私钥落终端历史）
#
# 退出码：0=全部解开；1=有失败项；2=文件缺失
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

KEY=""; SECRETS=""; SHOW="false"
for a in "$@"; do
  case "$a" in
    --show) SHOW="true" ;;
    *) if [ -z "$KEY" ]; then KEY="$a"; elif [ -z "$SECRETS" ]; then SECRETS="$a"; fi ;;
  esac
done
KEY="${KEY:-$REPO_ROOT/app-private.REAL.pem}"
SECRETS="${SECRETS:-$REPO_ROOT/deploy/linux/env/shared/secrets.env}"

[ -f "$KEY" ]     || { echo "✗ 私钥不存在: $KEY"; exit 2; }
[ -f "$SECRETS" ] || { echo "✗ secrets 不存在: $SECRETS"; exit 2; }

echo "私钥:    $KEY"
echo "secrets: $SECRETS"
echo "----------------------------------------"

ok=0; fail=0
# 只吃 export NAME="ENC(<base64>)" 行；正则限定 base64 字符集，
# 天然排除注释/模板里的字面占位符 ENC(...)（'.' 非 base64 字符）。
while IFS= read -r line; do
  name="${line%%=*}"; name="${name##*[[:space:]]}"   # 取等号前最后一段作为变量名
  enc="$(printf '%s' "$line" | grep -Eo 'ENC\([A-Za-z0-9+/=]+\)' | head -1)"
  [ -z "$enc" ] && continue
  if plain="$(APP_RSA_PRIVATE_KEY_PATH="$KEY" bash "$SCRIPT_DIR/encrypt-secret.sh" --decrypt "$enc" 2>/dev/null)"; then
    if [ "$SHOW" = "true" ]; then
      echo "✓ $name = $plain"
    else
      len=${#plain}
      echo "✓ $name  OK (len=$len, ${plain:0:2}…${plain: -2})"
    fi
    ok=$((ok+1))
  else
    echo "✗ $name  解密失败（BadPadding=钥匙不对 / base64错=密文残缺）"
    fail=$((fail+1))
  fi
done < <(grep -E '^[[:space:]]*(export[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*="ENC\(' "$SECRETS")

echo "----------------------------------------"
echo "结果: 成功 $ok 条，失败 $fail 条"
if [ "$fail" -eq 0 ] && [ "$ok" -gt 0 ]; then
  echo "✅ 这把私钥是真钥，可用于新机解密（拷到 /opt/coinstrategy/app-private.pem）。"
  exit 0
else
  echo "❌ 别用这把私钥搬机——见上方失败项，改走 Runbook §6 路线 A 全部重设。"
  exit 1
fi
