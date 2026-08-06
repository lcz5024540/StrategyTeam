#!/usr/bin/env bash
# ============================================================
# build-htx.sh — 构建 coin-strategy-htx 可执行 fat jar + 部署产物
#
# ⚠️ 必须 JDK 17 (common-model Lombok 注解处理在 JDK 25 会失败)。
#
# 产物: dist/htx/
#   app.jar                          可执行 fat jar (Spring Boot)
#   bin/htx-service.sh               服务管理脚本
#   env/htx.env.example              env 模板 (真实 env 在服务器, 不入包)
#   htx-trade.tar.gz                 上述打包, scp 到 /opt/coinstrategy/htx-trade/
#
# 用法: JDK17 环境下  ./scripts/build-htx.sh
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT_DIR/dist/htx"

# ── JDK 17 校验 ──
JV="$(java -version 2>&1 | head -1)"
if ! echo "$JV" | grep -qE '"17\.'; then
  echo "[ERROR] 需要 JDK 17，当前: $JV" >&2
  echo "  export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home" >&2
  exit 1
fi

echo "[1/3] Maven 打包 coin-strategy-htx ..."
(cd "$ROOT_DIR" && mvn -pl coin-strategy-htx -am package -DskipTests -Dspring.profiles.active=test)

JAR="$(find "$ROOT_DIR/coin-strategy-htx/target" -maxdepth 1 -type f -name '*.jar' \
  ! -name 'original-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -n 1)"
[[ -n "$JAR" ]] || { echo "[ERROR] 未找到 htx jar" >&2; exit 1; }

echo "[2/3] 组织产物到 $OUT ..."
rm -rf "$OUT"; mkdir -p "$OUT/bin" "$OUT/env"
cp "$JAR" "$OUT/app.jar"
cp "$ROOT_DIR/deploy/linux/bin/htx-service.sh" "$OUT/bin/"
chmod +x "$OUT/bin/htx-service.sh"
[[ -f "$ROOT_DIR/deploy/linux/env/htx.env.example" ]] && cp "$ROOT_DIR/deploy/linux/env/htx.env.example" "$OUT/env/"

echo "[3/3] 打包 tar ..."
(cd "$OUT" && tar -czf htx-trade.tar.gz app.jar bin env)

echo "完成: $OUT/htx-trade.tar.gz"
ls -lh "$OUT/htx-trade.tar.gz" "$OUT/app.jar"
