#!/usr/bin/env bash
# 快捷脚本 — 构建测试环境
# 拓扑:
#   web + 前端 → 43.139.186.27 (远程连 49.51.250.174 的 MySQL, root)
#   job + DB   → 49.51.250.174 (job 本机访问 MySQL, root)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/build-env.sh" test
