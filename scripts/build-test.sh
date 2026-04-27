#!/usr/bin/env bash
# 快捷脚本 — 构建测试环境 (49.51.250.174)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/build-env.sh" test
