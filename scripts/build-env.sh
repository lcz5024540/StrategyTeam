#!/usr/bin/env bash
# ============================================================
# 多环境编译脚本 — 统一入口
# 用法:
#   ./scripts/build-env.sh local    # 本地环境
#   ./scripts/build-env.sh test     # 测试环境 (49.51.250.174)
#   ./scripts/build-env.sh prod     # 生产环境
#
# 产物:
#   dist/<ENV>/backend/app.jar               — Job 后端可执行 JAR (策略/任务/内部数据)
#   dist/<ENV>/web/app.jar                   — Web 服务可执行 JAR (前端对接 API 入口)
#   dist/<ENV>/frontend/dist/                — 前端静态资源
#   dist/<ENV>/smart-wallet-<ENV>.tar.gz     — 完整部署包 (backend+web+frontend+bin+runtime+deploy)
# ============================================================
set -euo pipefail

# ── 参数校验 ──────────────────────────────────────────────────
ENV="${1:-}"
if [[ -z "$ENV" ]] || [[ ! "$ENV" =~ ^(local|test|prod)$ ]]; then
  echo "用法: $0 <local|test|prod>"
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/coin-strategy-frontend"
RELEASE_DIR="$ROOT_DIR/dist/$ENV"

echo "============================================================"
echo " 构建环境: $ENV"
echo " 项目根目录: $ROOT_DIR"
echo " 输出目录: $RELEASE_DIR"
echo "============================================================"

# ── 清理旧产物 ────────────────────────────────────────────────
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR/backend" "$RELEASE_DIR/web" "$RELEASE_DIR/frontend" "$RELEASE_DIR/bin" "$RELEASE_DIR/runtime" "$RELEASE_DIR/deploy"

# ============================================================
# 1. 后端 — Maven 编译 (job + web 一并打包)
# ============================================================
echo ""
echo "[1/3] 编译后端 JAR (job + web, profile=$ENV) ..."
(cd "$ROOT_DIR" && mvn -pl coin-strategy-job,coin-strategy-web -am package -DskipTests -Dspring.profiles.active="$ENV")

# Job JAR
JOB_JAR="$(find "$ROOT_DIR/coin-strategy-job/target" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*.jar' | head -n 1)"
if [ -z "$JOB_JAR" ]; then
  echo "[ERROR] 未找到 Job JAR 包 (coin-strategy-job/target/*.jar)" >&2
  exit 1
fi
cp "$JOB_JAR" "$RELEASE_DIR/backend/app.jar"
echo "[OK] Job JAR -> $RELEASE_DIR/backend/app.jar"

# Web JAR
WEB_JAR="$(find "$ROOT_DIR/coin-strategy-web/target" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*.jar' | head -n 1)"
if [ -z "$WEB_JAR" ]; then
  echo "[ERROR] 未找到 Web JAR 包 (coin-strategy-web/target/*.jar)" >&2
  exit 1
fi
cp "$WEB_JAR" "$RELEASE_DIR/web/app.jar"
echo "[OK] Web JAR -> $RELEASE_DIR/web/app.jar"

# ============================================================
# 2. 前端 — Vite 编译 + 环境文件
# ============================================================
echo ""
echo "[2/3] 编译前端 dist (mode=$ENV) ..."

# 安装依赖
if [ -f "$FRONTEND_DIR/package-lock.json" ]; then
  (cd "$FRONTEND_DIR" && npm ci)
else
  (cd "$FRONTEND_DIR" && npm install)
fi

# Vite 构建: --mode 参数决定加载哪个 .env 文件
# local  -> .env.local
# test   -> .env.test
# prod   -> .env.production (Vite 特殊处理: "prod" 映射不到文件，需要用 "production")
if [ "$ENV" = "prod" ]; then
  VITE_MODE="production"
else
  VITE_MODE="$ENV"
fi

(cd "$FRONTEND_DIR" && npx vite build --mode "$VITE_MODE")

if [ ! -d "$FRONTEND_DIR/dist" ]; then
  echo "[ERROR] 前端 dist 目录未生成" >&2
  exit 1
fi
cp -R "$FRONTEND_DIR/dist" "$RELEASE_DIR/frontend/"
echo "[OK] 前端 dist -> $RELEASE_DIR/frontend/dist"

# ============================================================
# 3. 复制部署辅助文件
# ============================================================
echo ""
echo "[3/3] 复制部署辅助文件..."

# 安装/服务注册脚本
if [ -f "$ROOT_DIR/scripts/install-smart-wallet-artifacts.sh" ]; then
  cp "$ROOT_DIR/scripts/install-smart-wallet-artifacts.sh" "$RELEASE_DIR/bin/"
fi
if [ -f "$ROOT_DIR/scripts/install-smart-wallet-linux-service.sh" ]; then
  cp "$ROOT_DIR/scripts/install-smart-wallet-linux-service.sh" "$RELEASE_DIR/bin/"
fi

# Python 运行时
for py_file in polymarket_clob_client.py polymarket_http.py; do
  if [ -f "$ROOT_DIR/scripts/$py_file" ]; then
    cp "$ROOT_DIR/scripts/$py_file" "$RELEASE_DIR/runtime/"
  fi
done

# 部署配置 (nginx / systemd / env)
if [ -d "$ROOT_DIR/deploy/linux" ]; then
  cp -R "$ROOT_DIR/deploy/linux" "$RELEASE_DIR/deploy/"
fi

# ============================================================
# 4. 生成环境专属快捷脚本 (调用通用管理脚本)
# ============================================================

# 复制通用管理脚本 — Job
cp "$ROOT_DIR/scripts/start-smart-wallet-backend.sh" "$RELEASE_DIR/bin/"
cp "$ROOT_DIR/deploy/linux/bin/restart-smart-wallet-backend.sh" "$RELEASE_DIR/bin/"
chmod +x "$RELEASE_DIR/bin/start-smart-wallet-backend.sh"
chmod +x "$RELEASE_DIR/bin/restart-smart-wallet-backend.sh"

# 复制通用管理脚本 — Web (若 scripts/ 下存在则一并打入)
if [ -f "$ROOT_DIR/scripts/start-smart-wallet-web.sh" ]; then
  cp "$ROOT_DIR/scripts/start-smart-wallet-web.sh" "$RELEASE_DIR/bin/"
  chmod +x "$RELEASE_DIR/bin/start-smart-wallet-web.sh"
fi
if [ -f "$ROOT_DIR/deploy/linux/bin/restart-smart-wallet-web.sh" ]; then
  cp "$ROOT_DIR/deploy/linux/bin/restart-smart-wallet-web.sh" "$RELEASE_DIR/bin/"
  chmod +x "$RELEASE_DIR/bin/restart-smart-wallet-web.sh"
fi

# 生成环境快捷脚本 — Job 后端
cat > "$RELEASE_DIR/bin/start-backend-${ENV}.sh" << SCRIPT_EOF
#!/usr/bin/env bash
# ============================================================
# 快捷脚本 — ${ENV} 环境 Job 后端服务管理
# 用法:
#   ./start-backend-${ENV}.sh          — 后台启动
#   ./start-backend-${ENV}.sh stop     — 停止
#   ./start-backend-${ENV}.sh restart  — 重启
#   ./start-backend-${ENV}.sh status   — 查看状态
#   ./start-backend-${ENV}.sh log      — 实时查看日志
# ============================================================
set -euo pipefail

SCRIPT_DIR="\$(cd "\$(dirname "\$0")" && pwd)"
APP_HOME="\$(cd "\$SCRIPT_DIR/.." && pwd)"

# 环境配置
export SPRING_PROFILES_ACTIVE="${ENV}"
export SMART_WALLET_ENV_FILE="\${SMART_WALLET_ENV_FILE:-\$APP_HOME/deploy/linux/env/smart-wallet-job.env}"
export LOG_DIR="\${LOG_DIR:-/opt/coinstrategy/smart-wallet/logs}"

# 委托给通用管理脚本
exec "\$SCRIPT_DIR/start-smart-wallet-backend.sh" "\${1:-start}"
SCRIPT_EOF

chmod +x "$RELEASE_DIR/bin/start-backend-${ENV}.sh"

# 生成环境快捷脚本 — Web 服务
cat > "$RELEASE_DIR/bin/start-web-${ENV}.sh" << SCRIPT_EOF
#!/usr/bin/env bash
# ============================================================
# 快捷脚本 — ${ENV} 环境 Web 服务管理
# 用法:
#   ./start-web-${ENV}.sh          — 后台启动
#   ./start-web-${ENV}.sh stop     — 停止
#   ./start-web-${ENV}.sh restart  — 重启
#   ./start-web-${ENV}.sh status   — 查看状态
#   ./start-web-${ENV}.sh log      — 实时查看日志
# ============================================================
set -euo pipefail

SCRIPT_DIR="\$(cd "\$(dirname "\$0")" && pwd)"
APP_HOME="\$(cd "\$SCRIPT_DIR/.." && pwd)"

# 环境配置
export SPRING_PROFILES_ACTIVE="${ENV}"
export SMART_WALLET_ENV_FILE="\${SMART_WALLET_ENV_FILE:-\$APP_HOME/deploy/linux/env/smart-wallet-web.env}"
# Web 服务依赖 Job 环境中的部分共享配置（如数据库/密钥），通过 SHARED 文件追加加载
export SMART_WALLET_SHARED_ENV_FILE="\${SMART_WALLET_SHARED_ENV_FILE:-\$APP_HOME/deploy/linux/env/smart-wallet-job.env}"
export LOG_DIR="\${LOG_DIR:-/opt/coinstrategy/smart-wallet/logs}"

# 委托给通用管理脚本
exec "\$SCRIPT_DIR/start-smart-wallet-web.sh" "\${1:-start}"
SCRIPT_EOF

chmod +x "$RELEASE_DIR/bin/start-web-${ENV}.sh"

# ============================================================
# 5. 打包发布包
# ============================================================
echo ""
echo "打包发布包..."
(
  cd "$RELEASE_DIR"
  tar -czf "smart-wallet-${ENV}.tar.gz" backend web frontend bin runtime deploy
)

echo ""
echo "============================================================"
echo " 构建完成!"
echo " 发布包:        $RELEASE_DIR/smart-wallet-${ENV}.tar.gz"
echo " Job JAR:       $RELEASE_DIR/backend/app.jar"
echo " Web JAR:       $RELEASE_DIR/web/app.jar"
echo " 前端 dist:     $RELEASE_DIR/frontend/dist/"
echo " Job 启动脚本:  $RELEASE_DIR/bin/start-backend-${ENV}.sh"
echo " Web 启动脚本:  $RELEASE_DIR/bin/start-web-${ENV}.sh"
echo "============================================================"
echo ""
echo "部署说明:"
if [ "$ENV" = "test" ]; then
  echo "  1. 将 smart-wallet-${ENV}.tar.gz 上传至测试服务器 49.51.250.174"
  echo "  2. 解压后依次执行:"
  echo "       bin/start-backend-${ENV}.sh    # 启动 Job 服务 (端口 8081)"
  echo "       bin/start-web-${ENV}.sh        # 启动 Web 服务 (端口 8080, 对接前端)"
  echo "  3. 将 frontend/dist 配置到 nginx 作为静态资源根目录"
  echo "  4. 使用 deploy/linux/nginx/ 下的 nginx 配置模板 (反代至 Web 8080)"
elif [ "$ENV" = "prod" ]; then
  echo "  1. 将 backend/ + web/ + bin/ + runtime/ 上传至后端服务器"
  echo "  2. 将 frontend/ + deploy/ 上传至前端服务器"
  echo "  3. 在后端服务器分别执行:"
  echo "       bin/start-backend-${ENV}.sh    # Job 服务 (8081)"
  echo "       bin/start-web-${ENV}.sh        # Web 服务 (8080)"
  echo "  4. 在前端服务器配置 nginx (参考 deploy/linux/nginx/)"
  echo "  5. nginx 中 proxy_pass 指向 Web 服务器 IP:8080"
fi
