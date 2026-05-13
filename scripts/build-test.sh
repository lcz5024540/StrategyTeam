#!/usr/bin/env bash
# 快捷脚本 — 构建测试环境
# 拓扑:
#   web + 前端 → 43.139.186.27 (本机 MySQL, devuser)
#   job        → 49.51.250.174 (远程连 43.139.186.27 的 MySQL, devuser)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/build-env.sh" test
