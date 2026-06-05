# Smart-Wallet job 新 jar 未生效——分步排查与修复手册

> 输入：上一次定时任务（2026-05-31 00:xx）的结论——`settlement_run` 560 仍走旧分支
> `early_settlement_blocked_market_not_ended`，证明 **新 jar 未真正运行**（不是代码逻辑问题）。
> 本次任务：把"按图索骥"的诊断/修复拆成可分步执行的 SOP，按顺序跑、按分支决策。

---

## 0. 本地端事实（已自动核验，无需再跑）

| 项 | 值 |
|---|---|
| 本地构建产物 | `coin-strategy-job/target/coin-strategy-job-0.0.1-SNAPSHOT.jar` |
| 发布包内 backend jar | `dist/test/backend/app.jar` |
| 两者 md5 | `941aaeb4c82f9e8b7e4481bba45543e0`（**完全一致**） |
| jar 大小 | 66,596,241 B |
| 构建时间 | 2026-05-30 15:55 |
| `oracle_terminal_all_signals_set` 字符串在 backend jar 内出现 | **1 次** ✅ |
| 同 class 内可见的新方法 | `isResolved`、`getWinningTokenId` ✅ |

**结论：本地构建已包含新逻辑，问题只可能发生在 "本地包 → 服务器进程" 这一段。**

---

## 1. 部署链路 / 落点速查（基于现有脚本）

| 项 | 路径 / 名称 |
|---|---|
| 远端机 | `ubuntu@49.51.250.174`（job 机；web/前端在 43.139.186.27） |
| 远端 jar | `/opt/coinstrategy/smart-wallet/backend/app.jar`（**普通文件，install 拷贝覆盖，无软链**） |
| PID 文件 | `/opt/coinstrategy/smart-wallet/backend/app.pid` |
| systemd 服务 | `smart-wallet-backend.service` |
| ExecStart | `java $JAVA_OPTS -Dspring.profiles.active=test -jar /opt/coinstrategy/smart-wallet/backend/app.jar` |
| 应用日志 | `/opt/coinstrategy/smart-wallet/logs/job-current.log`（logback 按天滚动） |
| JVM 启动 stderr | `/opt/coinstrategy/smart-wallet/logs/job-startup.log`（每次重启截断） |
| systemd 日志 | `journalctl -u smart-wallet-backend` |
| 部署脚本 | `scripts/install-smart-wallet-artifacts.sh`（仅复制 jar） |
| 安装/装服务脚本 | `scripts/install-smart-wallet-linux-service.sh`（建立 systemd） |
| 本地启动脚本 | `scripts/start-smart-wallet-backend.sh`（nohup 模式，systemd 一般不用走） |

**注意（修正上一任务的假设）：** app.jar 是 `install -m 755` 拷贝出来的普通文件，
不存在软链——所以 `readlink app.jar` 没意义；应该用 `md5sum` 直接比对。

---

## 2. 分步排查（每一步独立可跑，按分支决策；不要 5 步一次性跑完）

> 远端登录入口统一：`ssh ubuntu@49.51.250.174`
> 期望比对的"本地新 jar md5"：`941aaeb4c82f9e8b7e4481bba45543e0`

### Step 1 — 远端 jar 是否就是本地新 jar？

```bash
ssh ubuntu@49.51.250.174 'bash -lc "
ls -la /opt/coinstrategy/smart-wallet/backend/app.jar
md5sum /opt/coinstrategy/smart-wallet/backend/app.jar
stat -c \"%y\" /opt/coinstrategy/smart-wallet/backend/app.jar
"'
```

判定：
- md5 = `941aaeb4c82f9e8b7e4481bba45543e0` ✅ → 进 Step 2
- md5 ≠ 上述值 ❌ → **根因 A：包没真上去 / 没盖到 app.jar。** 直接跳 §3.A，跑完再回到 Step 2

### Step 2 — 正在跑的 java 进程是新进程吗？

```bash
ssh ubuntu@49.51.250.174 'bash -lc "
echo -- PID 文件 --
cat /opt/coinstrategy/smart-wallet/backend/app.pid 2>/dev/null
echo -- 真正在跑的 app.jar 进程 --
ps -eo pid,lstart,etime,cmd | grep -E \"java.*backend/app.jar\" | grep -v grep
echo -- systemctl status --
systemctl status smart-wallet-backend --no-pager | head -20
"'
```

判定（`lstart` 应该晚于 jar 文件 mtime，即 2026-05-30 15:55 之后；如果 mtime 已被你覆盖更新过，
看的是"晚于覆盖时间"）：
- 进程存在且 `lstart` 晚于 jar mtime ✅ → 进 Step 3
- 进程存在但 `lstart` 早于 jar mtime ❌ → **根因 B：旧进程没被 kill。** 跳 §3.B
- 进程根本不存在 ❌ → **根因 C：进程没拉起来。** 跳 §3.C

### Step 3 — Spring Boot 是否启动成功？

```bash
ssh ubuntu@49.51.250.174 'bash -lc "
echo -- 启动 banner --
grep -E \"Started [A-Za-z]+ in [0-9.]+ seconds\" /opt/coinstrategy/smart-wallet/logs/job-current.log | tail -3
echo
echo -- 启动 stderr 文件（每次重启截断；非空通常意味启动炸了） --
ls -la /opt/coinstrategy/smart-wallet/logs/job-startup.log
tail -50 /opt/coinstrategy/smart-wallet/logs/job-startup.log
echo
echo -- systemd 最近 50 行 --
journalctl -u smart-wallet-backend -n 50 --no-pager
"'
```

判定：
- 看到最新的 `Started ... in N seconds`（时间戳与 Step 2 进程 lstart 接近）✅ → 进 Step 4
- 没看到 / `job-startup.log` 里有 stacktrace ❌ → **根因 C：启动失败。** 跳 §3.C

### Step 4 — 新代码是否真的被执行？

```bash
ssh ubuntu@49.51.250.174 'bash -lc "
echo -- 新短路标记 oracle_terminal_all_signals_set --
grep -c oracle_terminal_all_signals_set /opt/coinstrategy/smart-wallet/logs/job-current.log
echo -- 旧分支 early_settlement_blocked_market_not_ended --
grep -c early_settlement_blocked_market_not_ended /opt/coinstrategy/smart-wallet/logs/job-current.log
echo -- 最近一次相关日志上下文 --
grep -E \"oracle_terminal_all_signals_set|early_settlement_blocked_market_not_ended|Skipping settlement|Skipped premature\" /opt/coinstrategy/smart-wallet/logs/job-current.log | tail -20
"'
```

判定：
- `oracle_terminal_all_signals_set` 计数 > 0，且时间戳在 Step 2 进程 lstart 之后 ✅ → **修复完成。** 进 §4 业务校验
- 仍为 0 ❌ → 进 Step 5

### Step 5 — class 文件本身真的包含新方法吗？

```bash
ssh ubuntu@49.51.250.174 'bash -lc "
cd /tmp && rm -rf classcheck && mkdir classcheck && cd classcheck
unzip -p /opt/coinstrategy/smart-wallet/backend/app.jar \
  BOOT-INF/classes/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletAccountingService.class \
  | strings | grep -E \"oracle_terminal_all_signals_set|isResolved|getWinningTokenId\" | sort -u
"'
```

判定：
- 三条字符串都出现 ✅ → 那 Step 4 仍为 0 只可能是 **logback 配置没把 INFO 写进 job-current.log**，去看 `src/main/resources/logback-spring.xml` 对应 logger 等级；或确认 settlement 任务确实有触发（看 `settlement_run` 表有没有 Step 2 之后的新行）
- 字符串缺失 ❌ → **罕见，根因 D：md5 对得上但 class 内容异常**，跳 §3.D

---

## 3. 修复 SOP（按 Step 1–5 分支命中后执行；每节自带"验证步")

### §3.A 包没真上去 / app.jar 未被新 jar 覆盖

1. 本机执行（构建机或本机解到 `dist/test/` 后）：

   ```bash
   cd /Users/jenniferni/jenifer/ray/CoinStrategy
   md5sum dist/test/backend/app.jar   # 期望 941aaeb4c82f9e8b7e4481bba45543e0
   scp dist/test/backend/app.jar ubuntu@49.51.250.174:/tmp/app-new.jar
   ```

2. 远端覆盖 + 重启（建议用 sudo + install 保持权限/属主一致，等同 install-smart-wallet-artifacts.sh）：

   ```bash
   ssh ubuntu@49.51.250.174 'bash -lc "
     sudo install -m 755 -o ubuntu -g ubuntu /tmp/app-new.jar /opt/coinstrategy/smart-wallet/backend/app.jar
     md5sum /opt/coinstrategy/smart-wallet/backend/app.jar
     sudo systemctl restart smart-wallet-backend
     sleep 5
     systemctl status smart-wallet-backend --no-pager | head -15
   "'
   ```

3. 验证：重新跑 Step 1 + Step 4。

4. 回滚（如启动失败需要）：

   ```bash
   ssh ubuntu@49.51.250.174 'bash -lc "
     sudo cp /opt/coinstrategy/smart-wallet/backend/app.jar /opt/coinstrategy/smart-wallet/backend/app.jar.bad-$(date +%s)
     # 假设之前备份过 app.jar.bak：
     [ -f /opt/coinstrategy/smart-wallet/backend/app.jar.bak ] && sudo install -m 755 /opt/coinstrategy/smart-wallet/backend/app.jar.bak /opt/coinstrategy/smart-wallet/backend/app.jar
     sudo systemctl restart smart-wallet-backend
   "'
   ```

   > 提醒：当前部署脚本不会自动备份旧 jar。建议把 §3.A 第 2 步前加一行
   > `sudo cp app.jar app.jar.bak-$(date +%s)`，养成习惯。

### §3.B 软链/jar 新但旧 PID 还在跑

```bash
ssh ubuntu@49.51.250.174 'bash -lc "
  # 优先走 systemd（会按 Restart=always 自动拉起）
  sudo systemctl restart smart-wallet-backend
  sleep 5
  ps -eo pid,lstart,cmd | grep -E \"java.*backend/app.jar\" | grep -v grep
"'
```

如果 systemd 不在管理（曾经用 nohup 启过、PID 不归 systemd 管），需要：

```bash
ssh ubuntu@49.51.250.174 'bash -lc "
  PID=$(cat /opt/coinstrategy/smart-wallet/backend/app.pid 2>/dev/null)
  [ -n \"$PID\" ] && sudo kill -15 \"$PID\" || true
  # 再强查一道：
  for p in $(pgrep -f \"java.*backend/app.jar\"); do sudo kill -15 $p; done
  sleep 8
  for p in $(pgrep -f \"java.*backend/app.jar\"); do sudo kill -9 $p; done
  sudo systemctl restart smart-wallet-backend
"'
```

验证：重新跑 Step 2 + Step 4。

### §3.C 新进程拉不起来

1. 看炸点：

   ```bash
   ssh ubuntu@49.51.250.174 'bash -lc "
     tail -200 /opt/coinstrategy/smart-wallet/logs/job-startup.log
     echo ----
     journalctl -u smart-wallet-backend -n 200 --no-pager
   "'
   ```

2. 常见三类：
   - **端口冲突（8081 被占）**：`sudo ss -lntp | grep 8081` → kill 占用进程或在 env 文件改端口。
   - **env 文件缺字段 / Spring profile 缺 bean**：照 stacktrace 改 `/etc/coinstrategy/test/smart-wallet-job.env`，然后 `systemctl restart`。
   - **ClassNotFound / NoSuchMethod**：意味着 jar 与依赖版本/profile 不匹配；检查 `pom.xml` 是否近期改了依赖，且 `dist/test/backend/app.jar` 用的是同一 commit 出包。

3. 验证：Step 2 + Step 3。

### §3.D class 字节正常但 grep 永远是 0

罕见。按概率排：
1. logback 把对应 logger 调成 WARN 以上——查 `coin-strategy-job/src/main/resources/logback-spring.xml`。
2. 调度根本没跑到这个 if 分支——确认 `settlement_run` 表在重启后是否有新行：

   ```sql
   SELECT id, started_at, resolved_count, unresolved_count, notes
   FROM settlement_run
   ORDER BY id DESC LIMIT 5;
   ```

3. 跑到了但走的是别的方法分支（少见）——加临时 DEBUG 日志一行重打包，灰度上去。

---

## 4. 业务侧最终校验（修复完一定要做）

```sql
-- 4.1 最新 5 次 settlement_run：resolved 应该 > 0，不再全是 0/10
SELECT id, started_at, resolved_count, unresolved_count, notes
FROM settlement_run
ORDER BY id DESC LIMIT 5;

-- 4.2 那 18 笔之前卡住的 market，现在是否已落账
-- （把 marketId 列表换成上一任务报告里的实际 18 个 id）
SELECT market_id, status, resolved_outcome, winning_token_id, settled_at
FROM paper_position
WHERE market_id IN ('<id1>', '<id2>', '...')
ORDER BY settled_at DESC NULLS LAST;
```

期望：
- `resolved_count` 在新 jar 上线后的下一次 `settlement_run` 应 ≥ 之前堆积的可结算数（≈ 18）。
- 18 笔 `paper_position` 的 `settled_at` 全部非空，`status` 进入终态。

---

## 5. 防回归（一次性投入，省下次撕扯）

1. **install-smart-wallet-artifacts.sh 加备份 + md5 输出**：

   ```bash
   # install -m 755 之前
   if [ -f "$DST_BACKEND_DIR/app.jar" ]; then
     cp "$DST_BACKEND_DIR/app.jar" "$DST_BACKEND_DIR/app.jar.bak-$(date +%Y%m%d%H%M%S)"
   fi
   install -m 755 "$SRC_BACKEND_JAR" "$DST_BACKEND_DIR/app.jar"
   echo "[install] new app.jar md5: $(md5sum "$DST_BACKEND_DIR/app.jar")"
   ```

2. **部署后强制重启**：脚本末尾自动 `systemctl restart smart-wallet-backend`，省得"以为重启了"。

3. **CI 把 build commit hash 写进 jar manifest**：

   ```xml
   <!-- pom.xml maven-jar-plugin manifestEntries -->
   <Implementation-Version>${git.commit.id.abbrev}</Implementation-Version>
   ```

   远端用 `unzip -p app.jar META-INF/MANIFEST.MF | grep Implementation-Version` 一眼就知道跑的是哪个 commit。

4. **应用启动 banner 打一行 commit hash**：Spring `@PostConstruct` 或自定义 `BannerPrinter` 输出
   `Built from <git.commit.id>`，与本地 commit 直接对账。

---

## 附：执行顺序速查

```
本地核验 ✅(已自动完成)
       │
       ▼
Step 1  jar md5 一致？  ── 否 ──▶ §3.A 重传+重启 ──▶ 回到 Step 1
       │ 是
       ▼
Step 2  PID 是新进程？  ── 否 ──▶ §3.B 重启 / kill 旧 PID ──▶ 回到 Step 2
       │ 是
       ▼
Step 3  启动 banner 有？── 否 ──▶ §3.C 看 stacktrace 修配置/端口/依赖 ──▶ 回到 Step 2
       │ 是
       ▼
Step 4  新代码日志计数>0？── 否 ──▶ Step 5（class 字节核验）── 否 ──▶ §3.D 调日志/调度
       │ 是
       ▼
§4 业务校验：settlement_run resolved_count > 0、18 笔 paper_position 落账
       │
       ▼
§5 加备份 + 强制重启 + commit hash → 下次不再出这种"以为发了"
```
