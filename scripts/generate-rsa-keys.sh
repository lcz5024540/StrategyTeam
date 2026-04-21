#!/usr/bin/env bash
# ============================================================
# RSA-2048 密钥对生成脚本
#
# 用途：为应用层非对称加密（SecretDecryptor）生成密钥对。
#   - 私钥（PKCS#8 PEM）：用于应用启动时解密 ENC(...) 值
#   - 公钥（PEM）：仅作备份，应用直接从私钥派生公钥
#
# 使用方法：
#   bash scripts/generate-rsa-keys.sh [输出目录]
#   # 默认输出目录：scripts/keys/
#
# 生成后：
#   1. 将私钥文件路径配置到 APP_RSA_PRIVATE_KEY_PATH 环境变量
#   2. 本地：放在 scripts/keys/（已在 .gitignore 中）
#   3. 生产：scp 到服务器 /etc/coinstrategy/app-private.pem，chmod 600
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEY_DIR="${1:-$SCRIPT_DIR/keys}"
PRIVATE_KEY="$KEY_DIR/app-private.pem"
PUBLIC_KEY="$KEY_DIR/app-public.pem"

if ! command -v openssl &>/dev/null; then
    echo "ERROR: openssl not found. Install openssl first." >&2
    exit 1
fi

if [[ -f "$PRIVATE_KEY" ]]; then
    echo "ERROR: Private key already exists at: $PRIVATE_KEY" >&2
    echo "       Delete it first if you want to regenerate." >&2
    exit 1
fi

mkdir -p "$KEY_DIR"

echo "Generating RSA-2048 key pair..."

# genpkey 直接输出 PKCS#8 格式，无需二次转换，Java KeyFactory 可直接加载
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE_KEY" 2>/dev/null
openssl rsa -in "$PRIVATE_KEY" -pubout -out "$PUBLIC_KEY" 2>/dev/null

chmod 600 "$PRIVATE_KEY"
chmod 644 "$PUBLIC_KEY"

echo ""
echo "Keys generated successfully:"
echo "  Private key : $PRIVATE_KEY  (chmod 600)"
echo "  Public key  : $PUBLIC_KEY   (chmod 644)"
echo ""
echo "──────────────────────────────────────────────────────"
echo "本地开发：在 scripts/local-dev.env 中添加："
echo "  export APP_RSA_PRIVATE_KEY_PATH=\"$PRIVATE_KEY\""
echo ""
echo "生产部署：将私钥上传到服务器："
echo "  scp $PRIVATE_KEY user@server:/etc/coinstrategy/app-private.pem"
echo "  ssh user@server 'chmod 600 /etc/coinstrategy/app-private.pem'"
echo "  # 然后在 env 文件中设置："
echo "  # export APP_RSA_PRIVATE_KEY_PATH=/etc/coinstrategy/app-private.pem"
echo ""
echo "加密配置值："
echo "  1. 启动应用（先用明文），调用加密接口："
echo "     POST /strategy/api/smart-wallet/admin/encrypt-secret"
echo "     Body: {\"value\": \"你的明文密码\"}"
echo "  2. 将返回的 ENC(...) 值写入 env 文件对应变量"
echo "──────────────────────────────────────────────────────"
