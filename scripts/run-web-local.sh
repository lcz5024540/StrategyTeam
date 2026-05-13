#!/usr/bin/env bash
# ============================================================
# 本地启动 web 服务 (前台运行, 日志直接打到终端便于调试)
#
# 用法:
#   ./scripts/run-web-local.sh                            # 标准本地启动
#   SMART_WALLET_WEB_PORT=8088 ./scripts/run-web-local.sh # 换端口
#   SKIP_BUILD=1 ./scripts/run-web-local.sh               # 跳过 mvn build, 复用已有 jar
#   DB_PASSWORD=明文 ./scripts/run-web-local.sh           # 临时用明文密码 (覆盖 ENC)
#
# 默认约定:
#   profile     = local
#   DB          = jdbc:mysql://49.51.250.174:3306/coin_strategy   (本地直接连远程 DB)
#   DB user     = root  (从 shared/secrets.env 取, 与 test/prod 一致)
#   DB password = 取自 deploy/linux/env/shared/secrets.env (ENC 密文, 一字不改)
#   RSA 私钥    = scripts/keys/app-private-local.pem (用于解密 ENC)
#   web port    = 8080
#   API root    = http://127.0.0.1:8080/strategy/api/
#
# 注意:
#   - 远程 MySQL (49.51.250.174:3306) 必须允许你本地 IP 访问;
#     若失败请检查: bind-address / GRANT ... @'你的IP' / 防火墙
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="${LOG_DIR:-$ROOT_DIR/logs}"
WEB_PORT="${SMART_WALLET_WEB_PORT:-8080}"
SHARED_ENV_FILE="$ROOT_DIR/deploy/linux/env/shared/secrets.env"
LOCAL_RSA_KEY="$ROOT_DIR/scripts/keys/app-private.pem"

# ── 1. 先备份调用方传入的 env vars (caller 永远优先于 shared/secrets.env) ──
__CALLER_DB_PASSWORD_SET="${DB_PASSWORD+set}"
__CALLER_DB_PASSWORD_VAL="${DB_PASSWORD:-}"
__CALLER_DB_USERNAME_SET="${DB_USERNAME+set}"
__CALLER_DB_USERNAME_VAL="${DB_USERNAME:-}"
__CALLER_DB_HOST_SET="${DB_HOST+set}"
__CALLER_DB_HOST_VAL="${DB_HOST:-}"

# ── 2. Source shared/secrets.env (拿到与 test/prod 同一份 ENC 密文) ─────────
if [ -f "$SHARED_ENV_FILE" ]; then
  echo "[run-web-local] source shared 密钥: $SHARED_ENV_FILE"
  set -a
  # shellcheck disable=SC1090
  source "$SHARED_ENV_FILE"
  set +a
else
  echo "[run-web-local] ⚠ 找不到 $SHARED_ENV_FILE, 将走 application-local.yml 默认值 (空密码)" >&2
fi

# ── 3. 还原 caller 传入的覆盖值 (caller > shared) ──────────────────────────
if [ -n "$__CALLER_DB_PASSWORD_SET" ]; then
  export DB_PASSWORD="$__CALLER_DB_PASSWORD_VAL"
  echo "[run-web-local] caller 显式传入 DB_PASSWORD, 覆盖 shared/secrets.env 的 ENC 密文"
fi
[ -n "$__CALLER_DB_USERNAME_SET" ] && export DB_USERNAME="$__CALLER_DB_USERNAME_VAL"
[ -n "$__CALLER_DB_HOST_SET" ] && export DB_HOST="$__CALLER_DB_HOST_VAL"

# ── 4. 本地特定覆盖 ─────────────────────────────────────────────────────────
# RSA 私钥永远用本地路径 (shared/secrets.env 里默认是服务器路径 /opt/coinstrategy/app-private-local.pem)
export APP_RSA_PRIVATE_KEY_PATH="${APP_RSA_PRIVATE_KEY_PATH_LOCAL:-$LOCAL_RSA_KEY}"
# DB 默认直连远程 49.51.250.174; DB_USERNAME / DB_PASSWORD 沿用 shared/secrets.env 提供的值
export DB_HOST="${DB_HOST:-49.51.250.174}"
# DB_USERNAME 若 shared/secrets.env 没设, 兜底 root; caller 传入会覆盖
[ -z "${DB_USERNAME:-}" ] && export DB_USERNAME="root"

# ── 3. 预检 RSA 私钥 ────────────────────────────────────────────────────────
if [[ "${DB_PASSWORD:-}" == ENC* ]]; then
  if [ ! -f "$APP_RSA_PRIVATE_KEY_PATH" ]; then
    echo "[run-web-local] ✗ RSA 私钥不存在: $APP_RSA_PRIVATE_KEY_PATH" >&2
    echo "[run-web-local]   DB_PASSWORD 是 ENC(...) 加密格式, 需要私钥才能解开" >&2
    echo "[run-web-local]   解决:" >&2
    echo "[run-web-local]     bash scripts/generate-rsa-keys.sh" >&2
    echo "[run-web-local]   或临时改用明文:" >&2
    echo "[run-web-local]     DB_PASSWORD='你的明文密码' $0" >&2
    exit 1
  fi
  echo "[run-web-local] ✓ RSA 私钥: $APP_RSA_PRIVATE_KEY_PATH"

  # 可选的预解密自检 — 只是友好提醒, 失败不阻塞启动
  # 需要 javac (跟 mvn build 一样的 JDK 环境); 任何中间环节出问题就跳过
  if [ -x "$ROOT_DIR/scripts/encrypt-secret.sh" ] && command -v javac >/dev/null 2>&1; then
    _DECRYPT_OUT="$(APP_RSA_PRIVATE_KEY_PATH="$APP_RSA_PRIVATE_KEY_PATH" \
      bash "$ROOT_DIR/scripts/encrypt-secret.sh" --decrypt "$DB_PASSWORD" 2>&1)" && _DECRYPT_RC=0 || _DECRYPT_RC=$?
    if [ "$_DECRYPT_RC" = "0" ]; then
      echo "[run-web-local] ✓ ENC 解密验证通过 (本地 keypair 与密文匹配)"
    else
      # 真的解不开才警告; 但不退出, 让 Spring 自己最终裁决
      echo "[run-web-local] ⚠ 本地 encrypt-secret.sh 试解 ENC 未通过 (exit=$_DECRYPT_RC). 不阻塞启动, 由 Spring 决定." >&2
      echo "[run-web-local]   工具输出:" >&2
      echo "$_DECRYPT_OUT" | sed 's/^/[run-web-local]     /' >&2
    fi
    unset _DECRYPT_OUT _DECRYPT_RC
  fi
fi

# ── JVM (本地不卡内存, 给足) ─────────────────────────────────────────────────
JAVA_OPTS_VALUE="${JAVA_OPTS:--Xms256m -Xmx768m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError}"

# ── 4. 检查远程 DB 端口是否可达 ─────────────────────────────────────────────
echo "[run-web-local] 检查 $DB_HOST:3306 网络可达性..."
if command -v nc >/dev/null 2>&1; then
  if nc -z -w 5 "$DB_HOST" 3306 2>/dev/null; then
    echo "[run-web-local] ✓ $DB_HOST:3306 TCP 可达"
  else
    echo "[run-web-local] ✗ 无法连接 $DB_HOST:3306 (5s 超时)" >&2
    echo "[run-web-local]   可能原因:" >&2
    echo "[run-web-local]   1) MySQL 没监听公网网卡 (bind-address = 0.0.0.0)" >&2
    echo "[run-web-local]   2) 服务器防火墙没放行 3306 给你本地 IP" >&2
    echo "[run-web-local]   3) 你本地网络不通 ($DB_HOST)" >&2
    echo "[run-web-local]   (仍然继续启动, 让 Spring Boot 报具体错)" >&2
  fi
else
  echo "[run-web-local] (nc 未安装, 跳过端口检查; mac 默认有 nc)"
fi

# ── 5. 端口占用检查 ─────────────────────────────────────────────────────────
if lsof -nP -iTCP:"$WEB_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "[run-web-local] ✗ 端口 $WEB_PORT 已被占用:" >&2
  lsof -nP -iTCP:"$WEB_PORT" -sTCP:LISTEN >&2
  echo "[run-web-local] 解决: kill 占用进程, 或换端口 SMART_WALLET_WEB_PORT=8088 $0" >&2
  exit 1
fi

# ── 6. 编译 (除非 SKIP_BUILD=1) ──────────────────────────────────────────────
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "[run-web-local] 编译 coin-strategy-web (mvn package, 跳过 test) ..."
  (cd "$ROOT_DIR" && mvn -pl coin-strategy-web -am package -DskipTests -q)
fi

WEB_JAR="$(find "$ROOT_DIR/coin-strategy-web/target" -maxdepth 1 -type f -name '*.jar' \
  ! -name 'original-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | head -n 1)"

if [ -z "$WEB_JAR" ] || [ ! -f "$WEB_JAR" ]; then
  echo "[run-web-local] ✗ 找不到 web jar, 请先执行: mvn -pl coin-strategy-web -am package -DskipTests" >&2
  exit 1
fi

# 校验是 Spring Boot fat jar
if ! unzip -p "$WEB_JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q '^Main-Class:'; then
  echo "[run-web-local] ✗ $WEB_JAR 不是可执行 fat jar, 检查 spring-boot-maven-plugin 配置" >&2
  exit 1
fi

mkdir -p "$LOG_DIR"

# ── 7. 启动信息 ─────────────────────────────────────────────────────────────
# 出于安全, 不打印 DB_PASSWORD; 只显示前缀 ENC( 或 (明文已设置)
if [[ "${DB_PASSWORD:-}" == ENC* ]]; then
  PWD_HINT="ENC(...) (将由 Spring 用 $APP_RSA_PRIVATE_KEY_PATH 解密)"
elif [ -n "${DB_PASSWORD:-}" ]; then
  PWD_HINT="(明文, 已设置)"
else
  PWD_HINT="(空)"
fi

echo ""
echo "[run-web-local] ============================================"
echo "[run-web-local] 启动 coin-strategy-web (前台运行, Ctrl+C 退出)"
echo "[run-web-local] profile:   local"
echo "[run-web-local] jar:       $WEB_JAR"
echo "[run-web-local] port:      $WEB_PORT"
echo "[run-web-local] DB:        jdbc:mysql://$DB_HOST:3306/coin_strategy as $DB_USERNAME"
echo "[run-web-local] DB pwd:    $PWD_HINT"
echo "[run-web-local] log dir:   $LOG_DIR"
echo "[run-web-local] log files: $LOG_DIR/web-current.log, $LOG_DIR/web-error-current.log"
echo "[run-web-local] ────────────────────────────────────────────"
echo "[run-web-local] 访问入口:"
echo "[run-web-local]   health   http://127.0.0.1:$WEB_PORT/strategy/api/smart-wallet/health"
echo "[run-web-local]   swagger  http://127.0.0.1:$WEB_PORT/strategy/api/swagger-ui.html"
echo "[run-web-local]   api-docs http://127.0.0.1:$WEB_PORT/strategy/api/v3/api-docs"
echo "[run-web-local] ============================================"
JAVA_STDOUT_LOG="$LOG_DIR/web-stdout.log"
JAVA_STDERR_LOG="$LOG_DIR/web-stderr.log"
: > "$JAVA_STDOUT_LOG"
: > "$JAVA_STDERR_LOG"

echo ""
echo "[run-web-local] Java 启动: 同时实时显示 + 落盘到日志 (logback FILE 不一定生效, 用 tee 兜底)"
echo "[run-web-local]   stdout 副本: $JAVA_STDOUT_LOG"
echo "[run-web-local]   stderr 副本: $JAVA_STDERR_LOG"
echo ""

# shellcheck disable=SC2206
JAVA_OPTS_ARRAY=($JAVA_OPTS_VALUE)

# 关掉 set -e, 这样 java 非 0 退出也不会让脚本直接死, 能跑到 post-mortem
set +e
# stdout 和 stderr 同时 tee 到屏幕 + 日志文件, 之后 java 进程哪怕秒退也有完整记录
java "${JAVA_OPTS_ARRAY[@]}" \
  -Dspring.profiles.active=local \
  -Dserver.port="$WEB_PORT" \
  -Dlog.dir="$LOG_DIR" \
  -jar "$WEB_JAR" \
  > >(tee "$JAVA_STDOUT_LOG") 2> >(tee "$JAVA_STDERR_LOG" >&2)
RC=$?
set -e

echo ""
echo "[run-web-local] ────────────────────────────────────────────"
echo "[run-web-local] Java 进程已退出, exit code = $RC"
echo "[run-web-local] (130 是 Ctrl+C, 其它非 0 是真异常)"
echo ""
echo "[run-web-local] 退出后落盘大小:"
ls -la "$JAVA_STDOUT_LOG" "$JAVA_STDERR_LOG" "$LOG_DIR/web-current.log" "$LOG_DIR/web-error-current.log" 2>/dev/null
echo ""

if [ "$RC" -ne 0 ] && [ "$RC" -ne 130 ]; then
  for f in "$JAVA_STDERR_LOG" "$JAVA_STDOUT_LOG" "$LOG_DIR/web-error-current.log" "$LOG_DIR/web-current.log"; do
    if [ -f "$f" ] && [ -s "$f" ]; then
      echo "[run-web-local] ──── tail -80 $f ────"
      tail -80 "$f"
      echo ""
    fi
  done
fi
exit $RC
