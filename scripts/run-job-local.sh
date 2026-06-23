#!/usr/bin/env bash
# ============================================================
# 本地启动 job 服务 (前台运行, 日志直接打到终端便于调试)
#
# 用法:
#   ./scripts/run-job-local.sh                            # 标准本地启动
#   DB_PASSWORD=明文 ./scripts/run-job-local.sh           # 临时用明文密码 (覆盖 ENC)
#   SMART_WALLET_BACKEND_PORT=8181 ./scripts/run-job-local.sh   # 换端口
#   SKIP_BUILD=1 ./scripts/run-job-local.sh               # 跳过 mvn build, 复用已有 jar
#   DB_HOST=127.0.0.1 ./scripts/run-job-local.sh          # 临时换连本机 DB
#
# 默认约定 (与 run-web-local.sh 完全平行, 仅端口/模块/日志文件名不同):
#   profile     = local
#   DB          = jdbc:mysql://43.139.186.27:3306/coin_strategy  (远程 dev DB, 与 web local 一致)
#   DB user     = devuser  (local 专属账号, 跟 test 的 root 隔离)
#   DB password = 取自 deploy/linux/env/shared/secrets.env (ENC 密文, 跟 test 同一份)
#   RSA 私钥    = scripts/keys/app-private.pem (用于解密 ENC)
#   job port    = 8081  (默认仅监听 0.0.0.0 — 跟 Spring 默认一致, 部署到服务器才走 loopback)
#   API root    = http://127.0.0.1:8081/strategy/api/
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="${LOG_DIR:-$ROOT_DIR/logs}"
JOB_PORT="${SMART_WALLET_BACKEND_PORT:-8081}"
SHARED_ENV_FILE="$ROOT_DIR/deploy/linux/env/shared/secrets.env"
LOCAL_RSA_KEY="$ROOT_DIR/scripts/keys/app-private.pem"

# ── 1. 先备份调用方传入的 env vars (caller 永远优先于 shared/secrets.env) ──
__CALLER_DB_PASSWORD_SET="${DB_PASSWORD+set}"
__CALLER_DB_PASSWORD_VAL="${DB_PASSWORD:-}"
__CALLER_DB_USERNAME_SET="${DB_USERNAME+set}"
__CALLER_DB_USERNAME_VAL="${DB_USERNAME:-}"
__CALLER_DB_HOST_SET="${DB_HOST+set}"
__CALLER_DB_HOST_VAL="${DB_HOST:-}"

# ── 2. Source shared/secrets.env (拿到 ENC 密文 + 各 API 密钥) ──────────────
if [ -f "$SHARED_ENV_FILE" ]; then
  echo "[run-job-local] source shared 密钥: $SHARED_ENV_FILE"
  set -a
  # shellcheck disable=SC1090
  source "$SHARED_ENV_FILE"
  set +a
else
  echo "[run-job-local] ⚠ 找不到 $SHARED_ENV_FILE, 将走 application-local.yml 默认值" >&2
fi

# ── 3. 还原 caller 传入的覆盖值 (caller > shared) ──────────────────────────
if [ -n "$__CALLER_DB_PASSWORD_SET" ]; then
  export DB_PASSWORD="$__CALLER_DB_PASSWORD_VAL"
  echo "[run-job-local] caller 显式传入 DB_PASSWORD, 覆盖 shared/secrets.env 的 ENC 密文"
fi
[ -n "$__CALLER_DB_USERNAME_SET" ] && export DB_USERNAME="$__CALLER_DB_USERNAME_VAL"
[ -n "$__CALLER_DB_HOST_SET" ] && export DB_HOST="$__CALLER_DB_HOST_VAL"

# ── 4. 本地特定覆盖 (与 run-web-local.sh 一致) ────────────────────────────
# RSA 私钥永远用本地路径 (shared/secrets.env 里默认是服务器路径 /opt/coinstrategy/app-private.pem)
export APP_RSA_PRIVATE_KEY_PATH="${APP_RSA_PRIVATE_KEY_PATH_LOCAL:-$LOCAL_RSA_KEY}"
# local 默认连远程 dev DB 43.139.186.27 (跟 run-web-local.sh 完全一致, 共用同一份 DB)
export DB_HOST="${DB_HOST:-43.139.186.27}"
# DB_USERNAME 在 local 强制覆盖为 devuser (跟 test 的 root 隔离)
export DB_USERNAME="${DB_USERNAME:-devuser}"
# Polymarket / Binance / RSA 等其他密钥统一从 shared/secrets.env 取, 脚本不再额外注入

# ── 5. 预检 RSA 私钥 ────────────────────────────────────────────────────────
if [[ "${DB_PASSWORD:-}" == ENC* ]]; then
  if [ ! -f "$APP_RSA_PRIVATE_KEY_PATH" ]; then
    echo "[run-job-local] ✗ RSA 私钥不存在: $APP_RSA_PRIVATE_KEY_PATH" >&2
    echo "[run-job-local]   DB_PASSWORD 是 ENC(...), 需要私钥才能解开" >&2
    echo "[run-job-local]   解决:" >&2
    echo "[run-job-local]     bash scripts/generate-rsa-keys.sh" >&2
    echo "[run-job-local]   或临时改用明文:" >&2
    echo "[run-job-local]     DB_PASSWORD='你的明文密码' $0" >&2
    exit 1
  fi
  echo "[run-job-local] ✓ RSA 私钥: $APP_RSA_PRIVATE_KEY_PATH"

  # 可选的预解密自检 — 失败不阻塞启动
  if [ -x "$ROOT_DIR/scripts/encrypt-secret.sh" ] && command -v javac >/dev/null 2>&1; then
    _DECRYPT_OUT="$(APP_RSA_PRIVATE_KEY_PATH="$APP_RSA_PRIVATE_KEY_PATH" \
      bash "$ROOT_DIR/scripts/encrypt-secret.sh" --decrypt "$DB_PASSWORD" 2>&1)" && _DECRYPT_RC=0 || _DECRYPT_RC=$?
    if [ "$_DECRYPT_RC" = "0" ]; then
      echo "[run-job-local] ✓ ENC 解密验证通过"
    else
      echo "[run-job-local] ⚠ encrypt-secret.sh 试解 ENC 未通过 (exit=$_DECRYPT_RC). 不阻塞启动, 由 Spring 决定." >&2
      echo "$_DECRYPT_OUT" | sed 's/^/[run-job-local]     /' >&2
    fi
    unset _DECRYPT_OUT _DECRYPT_RC
  fi
fi

# ── JVM 参数 (job 是主业务进程, 多给点内存) ─────────────────────────────────
JAVA_OPTS_VALUE="${JAVA_OPTS:--Xms512m -Xmx1024m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError}"

# ── 6. 检查远程 DB 端口可达 ─────────────────────────────────────────────────
echo "[run-job-local] 检查 $DB_HOST:3306 网络可达性..."
if command -v nc >/dev/null 2>&1; then
  if nc -z -w 5 "$DB_HOST" 3306 2>/dev/null; then
    echo "[run-job-local] ✓ $DB_HOST:3306 TCP 可达"
  else
    echo "[run-job-local] ✗ 无法连接 $DB_HOST:3306 (5s 超时)" >&2
    echo "[run-job-local]   可能原因:" >&2
    echo "[run-job-local]   1) MySQL 没监听公网网卡 (bind-address = 0.0.0.0)" >&2
    echo "[run-job-local]   2) 服务器防火墙没放行 3306 给你本地 IP" >&2
    echo "[run-job-local]   3) 本地网络不通 ($DB_HOST)" >&2
    echo "[run-job-local]   (仍然继续启动, 让 Spring Boot 报具体错)" >&2
  fi
else
  echo "[run-job-local] (nc 未安装, 跳过端口检查)"
fi

# ── 7. 端口占用检查 ─────────────────────────────────────────────────────────
if lsof -nP -iTCP:"$JOB_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "[run-job-local] ✗ 端口 $JOB_PORT 已被占用:" >&2
  lsof -nP -iTCP:"$JOB_PORT" -sTCP:LISTEN >&2
  echo "[run-job-local] 解决: kill 占用进程, 或换端口 SMART_WALLET_BACKEND_PORT=8181 $0" >&2
  exit 1
fi

# ── 8. 编译 (除非 SKIP_BUILD=1) ──────────────────────────────────────────────
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "[run-job-local] 编译 coin-strategy-job (mvn package, 跳过 test) ..."
  (cd "$ROOT_DIR" && mvn -pl coin-strategy-job -am package -DskipTests -q)
fi

JOB_JAR="$(find "$ROOT_DIR/coin-strategy-job/target" -maxdepth 1 -type f -name '*.jar' \
  ! -name 'original-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | head -n 1)"

if [ -z "$JOB_JAR" ] || [ ! -f "$JOB_JAR" ]; then
  echo "[run-job-local] ✗ 找不到 job jar, 请先执行: mvn -pl coin-strategy-job -am package -DskipTests" >&2
  exit 1
fi

if ! unzip -p "$JOB_JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q '^Main-Class:'; then
  echo "[run-job-local] ✗ $JOB_JAR 不是可执行 fat jar, 检查 spring-boot-maven-plugin 配置" >&2
  exit 1
fi

mkdir -p "$LOG_DIR"

# ── 9. 启动信息 ─────────────────────────────────────────────────────────────
if [[ "${DB_PASSWORD:-}" == ENC* ]]; then
  PWD_HINT="ENC(...) (将由 Spring 用 $APP_RSA_PRIVATE_KEY_PATH 解密)"
elif [ -n "${DB_PASSWORD:-}" ]; then
  PWD_HINT="(明文, 已设置)"
else
  PWD_HINT="(空)"
fi

JAVA_STDOUT_LOG="$LOG_DIR/job-stdout.log"
JAVA_STDERR_LOG="$LOG_DIR/job-stderr.log"
: > "$JAVA_STDOUT_LOG"
: > "$JAVA_STDERR_LOG"

echo ""
echo "[run-job-local] ============================================"
echo "[run-job-local] 启动 coin-strategy-job (前台运行, Ctrl+C 退出)"
echo "[run-job-local] profile:   local"
echo "[run-job-local] jar:       $JOB_JAR"
echo "[run-job-local] port:      $JOB_PORT (本地 loopback, 跟服务器一致不对外)"
echo "[run-job-local] DB:        jdbc:mysql://$DB_HOST:3306/coin_strategy as $DB_USERNAME"
echo "[run-job-local] DB pwd:    $PWD_HINT"
echo "[run-job-local] log dir:   $LOG_DIR"
echo "[run-job-local] log files: $LOG_DIR/job-current.log, $LOG_DIR/job-error-current.log"
echo "[run-job-local]   stdout 副本: $JAVA_STDOUT_LOG"
echo "[run-job-local]   stderr 副本: $JAVA_STDERR_LOG"
echo "[run-job-local] ────────────────────────────────────────────"
echo "[run-job-local] 访问入口 (仅本机):"
echo "[run-job-local]   health   http://127.0.0.1:$JOB_PORT/strategy/api/smart-wallet/health"
echo "[run-job-local]   swagger  http://127.0.0.1:$JOB_PORT/strategy/api/swagger-ui.html"
echo "[run-job-local] ============================================"
echo ""

# shellcheck disable=SC2206
JAVA_OPTS_ARRAY=($JAVA_OPTS_VALUE)

# 关掉 set -e, 让 java 非 0 退出能跑到 post-mortem
set +e
java "${JAVA_OPTS_ARRAY[@]}" \
  -Dspring.profiles.active=local \
  -Dserver.port="$JOB_PORT" \
  -Dlog.dir="$LOG_DIR" \
  -jar "$JOB_JAR" \
  > >(tee "$JAVA_STDOUT_LOG") 2> >(tee "$JAVA_STDERR_LOG" >&2)
RC=$?
set -e

echo ""
echo "[run-job-local] ────────────────────────────────────────────"
echo "[run-job-local] Java 进程已退出, exit code = $RC"
echo "[run-job-local] (130 是 Ctrl+C, 其它非 0 是真异常)"
echo ""
echo "[run-job-local] 退出后落盘大小:"
ls -la "$JAVA_STDOUT_LOG" "$JAVA_STDERR_LOG" "$LOG_DIR/job-current.log" "$LOG_DIR/job-error-current.log" 2>/dev/null
echo ""

if [ "$RC" -ne 0 ] && [ "$RC" -ne 130 ]; then
  for f in "$JAVA_STDERR_LOG" "$JAVA_STDOUT_LOG" "$LOG_DIR/job-error-current.log" "$LOG_DIR/job-current.log"; do
    if [ -f "$f" ] && [ -s "$f" ]; then
      echo "[run-job-local] ──── tail -80 $f ────"
      tail -80 "$f"
      echo ""
    fi
  done
fi
exit $RC
