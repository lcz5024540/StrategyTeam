#!/usr/bin/env bash
# 快捷脚本 — 构建本地环境
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/build-env.sh" local
