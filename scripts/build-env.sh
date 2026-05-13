#!/usr/bin/env bash
# ============================================================
# 多环境编译脚本 — 统一入口
# 用法:
#   ./scripts/build-env.sh local    # 本地环境
#   ./scripts/build-env.sh test     # 测试环境
#   ./scripts/build-env.sh prod     # 生产环境 (暂未上线)
#
# 目标拓扑 (test):
#   web + 前端 → 43.139.186.27  (本机 DB 127.0.0.1, devuser)
#   job        → 49.51.250.174  (远程 DB 43.139.186.27, devuser)
#   web 与 job 互不调用，各自独立连同一个 DB
#
# 产物 (按部署机拆成两个 tar，便于分发到不同服务器):
#   dist/<ENV>/backend/app.jar             — Job 后端可执行 JAR (策略/任务)
#   dist/<ENV>/web/app.jar                 — Web 服务可执行 JAR (前端 API 入口)
#   dist/<ENV>/frontend/dist/              — 前端静态资源
#   dist/<ENV>/smart-wallet-<ENV>-web.tar.gz   — web + 前端 + nginx + env (推 43.139.186.27)
#   dist/<ENV>/smart-wallet-<ENV>-job.tar.gz   — backend + runtime + env       (推 49.51.250.174)
#   dist/<ENV>/smart-wallet-<ENV>.tar.gz       — 整包 (兼容旧单机部署流程)
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
# 排除:
#   original-*.jar   Spring Boot 2.x repackage 前的原始 jar
#   *-sources.jar    maven-source-plugin 生成的源码包
#   *-javadoc.jar    maven-javadoc-plugin 生成的文档包
JOB_JAR="$(find "$ROOT_DIR/coin-strategy-job/target" -maxdepth 1 -type f -name '*.jar' \
  ! -name 'original-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -n 1)"
if [ -z "$JOB_JAR" ]; then
  echo "[ERROR] 未找到 Job JAR 包 (coin-strategy-job/target/*.jar)" >&2
  exit 1
fi
# 校验是 Spring Boot fat jar (含 Main-Class), 而不是普通 classes jar
if ! unzip -p "$JOB_JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q '^Main-Class:'; then
  echo "[ERROR] Job JAR 不是可执行 fat jar (缺 Main-Class): $JOB_JAR" >&2
  echo "        请检查 coin-strategy-job/pom.xml 的 spring-boot-maven-plugin 是否声明了 repackage execution" >&2
  exit 1
fi
cp "$JOB_JAR" "$RELEASE_DIR/backend/app.jar"
echo "[OK] Job JAR -> $RELEASE_DIR/backend/app.jar"

# Web JAR
WEB_JAR="$(find "$ROOT_DIR/coin-strategy-web/target" -maxdepth 1 -type f -name '*.jar' \
  ! -name 'original-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -n 1)"
if [ -z "$WEB_JAR" ]; then
  echo "[ERROR] 未找到 Web JAR 包 (coin-strategy-web/target/*.jar)" >&2
  exit 1
fi
if ! unzip -p "$WEB_JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q '^Main-Class:'; then
  echo "[ERROR] Web JAR 不是可执行 fat jar (缺 Main-Class): $WEB_JAR" >&2
  echo "        请检查 coin-strategy-web/pom.xml 的 spring-boot-maven-plugin 是否声明了 repackage execution" >&2
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
# 注意: env 目录按机器拆分 (shared/test/prod)，全量复制后由部署脚本按机器挑选
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

# 一键重启 web + job (同机部署场景)
if [ -f "$ROOT_DIR/deploy/linux/bin/restart-smart-wallet-all.sh" ]; then
  cp "$ROOT_DIR/deploy/linux/bin/restart-smart-wallet-all.sh" "$RELEASE_DIR/bin/"
  chmod +x "$RELEASE_DIR/bin/restart-smart-wallet-all.sh"
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
# 5. 打包发布包 — 按机器拆成 web / job 两份，并保留整包做 fallback
# ============================================================
echo ""
echo "打包发布包..."

if [ "$ENV" != "local" ]; then
  # web 包: 推到 43.139.186.27 (web + 前端 同机)
  (
    cd "$RELEASE_DIR"
    tar -czf "smart-wallet-${ENV}-web.tar.gz" \
      web frontend \
      bin/start-smart-wallet-web.sh \
      bin/start-web-${ENV}.sh \
      bin/restart-smart-wallet-web.sh \
      bin/install-smart-wallet-artifacts.sh \
      bin/install-smart-wallet-linux-service.sh \
      deploy/linux/env/shared \
      deploy/linux/env/${ENV} \
      deploy/linux/nginx \
      deploy/linux/systemd/smart-wallet-web.service.template \
      2>/dev/null || tar -czf "smart-wallet-${ENV}-web.tar.gz" web frontend bin deploy
  )

  # job 包: 推到 49.51.250.174 (仅 job)
  (
    cd "$RELEASE_DIR"
    tar -czf "smart-wallet-${ENV}-job.tar.gz" \
      backend runtime \
      bin/start-smart-wallet-backend.sh \
      bin/start-backend-${ENV}.sh \
      bin/restart-smart-wallet-backend.sh \
      bin/install-smart-wallet-artifacts.sh \
      bin/install-smart-wallet-linux-service.sh \
      deploy/linux/env/shared \
      deploy/linux/env/${ENV} \
      deploy/linux/systemd/smart-wallet-backend.service.template \
      2>/dev/null || tar -czf "smart-wallet-${ENV}-job.tar.gz" backend runtime bin deploy
  )
fi

# 整包 (兼容旧的单机部署 / local)
(
  cd "$RELEASE_DIR"
  tar -czf "smart-wallet-${ENV}.tar.gz" backend web frontend bin runtime deploy
)

echo ""
echo "============================================================"
echo " 构建完成!"
echo " Job JAR:       $RELEASE_DIR/backend/app.jar"
echo " Web JAR:       $RELEASE_DIR/web/app.jar"
echo " 前端 dist:     $RELEASE_DIR/frontend/dist/"
echo " Job 启动脚本:  $RELEASE_DIR/bin/start-backend-${ENV}.sh"
echo " Web 启动脚本:  $RELEASE_DIR/bin/start-web-${ENV}.sh"
if [ "$ENV" != "local" ]; then
  echo " Web 部署包:    $RELEASE_DIR/smart-wallet-${ENV}-web.tar.gz   → 推 43.139.186.27"
  echo " Job 部署包:    $RELEASE_DIR/smart-wallet-${ENV}-job.tar.gz   → 推 49.51.250.174"
fi
echo " 整包 (兼容):   $RELEASE_DIR/smart-wallet-${ENV}.tar.gz"
echo "============================================================"
echo ""
echo "部署说明:"
if [ "$ENV" = "test" ]; then
  echo "  ── web/前端 (43.139.186.27) ──"
  echo "    1. scp smart-wallet-${ENV}-web.tar.gz 到 43.139.186.27"
  echo "    2. 解压到 /opt/coinstrategy/smart-wallet/"
  echo "    3. bin/start-web-${ENV}.sh        # Web 服务 (8080, 直查本机 DB devuser)"
  echo "    4. cp deploy/linux/nginx/smart-wallet-test.conf 到 /etc/nginx/conf.d/"
  echo "    5. nginx -t && systemctl reload nginx (server_name = 43.139.186.27)"
  echo ""
  echo "  ── job (49.51.250.174) ──"
  echo "    1. scp smart-wallet-${ENV}-job.tar.gz 到 49.51.250.174"
  echo "    2. 解压到 /opt/coinstrategy/smart-wallet/"
  echo "    3. bin/start-backend-${ENV}.sh    # Job 服务 (8081, loopback only, 远程连 43.139.186.27 DB)"
  echo ""
  echo "  ── DB / 防火墙 (43.139.186.27) ──"
  echo "    1. MySQL bind-address = 0.0.0.0 (或显式监听内网网卡)"
  echo "    2. GRANT ... ON coin_strategy.* TO 'devuser'@'49.51.250.174'  -- job 跨机访问"
  echo "    3. GRANT ... ON coin_strategy.* TO 'devuser'@'127.0.0.1'      -- web 本机访问"
  echo "    4. ufw allow from 49.51.250.174 to any port 3306 proto tcp"
elif [ "$ENV" = "prod" ]; then
  echo "  Prod 暂未上线。恢复 prod 时需要先在 deploy/linux/env/prod/ 下补 web/job env 文件，"
  echo "  并确认与 test 之间的端口/机器分配。"
fi
