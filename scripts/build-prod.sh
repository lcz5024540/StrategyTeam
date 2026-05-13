#!/usr/bin/env bash
# 快捷脚本 — 构建生产环境（暂未上线）
# Prod 环境暂时取消。当前所有部署都走 test profile。
# 恢复 prod 时需要先补 deploy/linux/env/prod/ 下的 web/job env 文件。
set -euo pipefail
echo "[build-prod] WARNING: prod 环境暂未上线，请确认 deploy/linux/env/prod/ 下的 env 文件已配置好" >&2
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/build-env.sh" prod
