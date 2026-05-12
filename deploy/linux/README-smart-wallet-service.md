# Smart Wallet Linux 制品部署说明

这份方案按你现在的要求重新收口了：

- 服务器不拉代码
- 服务器不做 Maven / Node 构建
- 服务器只接收上传的 `job/web jar` 和前端 `dist`
- 前端和后端在同一台服务器
- `web` 监听 `127.0.0.1:8080`
- `job` 监听 `127.0.0.1:8081`
- 外部流量统一走 `nginx -> web -> job(内转发)`

当前默认机器信息：

- 服务器 IP：`49.51.250.174`
- 服务 profile：`prod`
- web 本地端口：`8080`
- job 本地端口：`8081`

## 1. 部署目录

推荐统一运行目录：

```bash
/opt/coinstrategy/smart-wallet
```

目录结构建议如下：

```bash
/opt/coinstrategy/smart-wallet/
├── backend/app.jar
├── web/app.jar
├── frontend/dist/
├── bin/
│   ├── start-smart-wallet-backend.sh
│   ├── install-smart-wallet-artifacts.sh
│   └── install-smart-wallet-linux-service.sh
├── runtime/
│   ├── polymarket_clob_client.py
│   └── polymarket_http.py
└── logs/
```

说明：

- `backend/app.jar` 是 job 服务 jar
- `web/app.jar` 是 web 服务 jar
- `frontend/dist/` 是你本地打包后上传的前端静态文件
- `runtime/` 只在 Polymarket LIVE Python bridge 需要时使用
- 服务器上不需要 Git 仓库源码

## 2. 一次性安装内容

服务器只需要预装这些运行时：

- `JDK 17`
- `nginx`
- `python3`
- `pip3`

如果启用 Polymarket LIVE，还要安装：

```bash
pip3 install py-clob-client
```

注意：`Maven`、`Node.js`、`npm` 不再是服务器必须项，它们只需要在你本地或 CI 构建机上存在。

## 3. 配置原则

主原则是：

- `application-prod.yml` 放“默认配置”
- `/etc/coinstrategy/smart-wallet-job.env` 放 job 敏感信息和运行配置
- `/etc/coinstrategy/smart-wallet-web.env` 放 web 入口配置
- 如果是测试包直接解压运行，也可以先直接维护 `deploy/linux/env/smart-wallet-job.env` 和 `deploy/linux/env/smart-wallet-web.env`

你不应该在服务器上改源码里的 `application-prod.yml`，而是通过环境变量覆盖。

示例环境文件：

- [`deploy/linux/env/smart-wallet-job.env`](env/smart-wallet-job.env)
- [`deploy/linux/env/smart-wallet-web.env`](env/smart-wallet-web.env)

建议重点维护这些变量：

- job:
  - `SERVER_ADDRESS=127.0.0.1`
  - `SERVER_PORT=8081`
  - `SPRING_DATASOURCE_URL`
  - `SPRING_DATASOURCE_USERNAME`
  - `SPRING_DATASOURCE_PASSWORD`
  - `POLYMARKET_*`
  - `SMART_WALLET_EXECUTION_WALLET_HELPER_SCRIPT_PATH`
- web:
  - `SERVER_ADDRESS=127.0.0.1`
  - `SERVER_PORT=8080`
  - `DASHBOARD_JOB_BASE_URL=http://127.0.0.1:8081`

## 4. 安全边界

这套方案默认的安全边界是：

1. `web` 只监听 `127.0.0.1:8080`
2. `job` 只监听 `127.0.0.1:8081`
2. 外网只开放 `80/443`
3. `nginx` 反代 `/strategy/api/` 到本机 `127.0.0.1:8080`
4. `web` 再通过内网地址调用 `job`
5. `systemd` 当前可以直接使用 `ubuntu` 用户运行；后续如果要隔离，再单独切换服务用户
6. 环境变量文件优先放在 `/etc/coinstrategy/smart-wallet-job.env` 和 `/etc/coinstrategy/smart-wallet-web.env`
7. 服务加了基础 `systemd` 加固项：`NoNewPrivileges`、`ProtectSystem`、`PrivateTmp`

如果 MySQL 也在这台机器上，建议把数据库地址也改成：

```bash
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/coin_strategy?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
```

不要继续走公网 IP `49.51.250.174:3306`。

## 5. 本地构建上传包

在本地仓库执行：

```bash
bash scripts/build-smart-wallet-release.sh
```

这个脚本只适合本地或 CI，用来生成上传包，不是给服务器构建用的。

产物会在：

```bash
dist/smart-wallet-release/
```

其中最关键的是：

- `backend/app.jar`
- `web/app.jar`
- `frontend/dist/`
- `bin/`
- `deploy/linux/`

也会额外生成一个压缩包：

```bash
dist/smart-wallet-release/smart-wallet-release.tar.gz
```

## 6. 服务器首次安装

首次安装时，把这些内容上传到服务器任意临时目录，例如：

```bash
/tmp/smart-wallet-release/
```

然后执行一次安装脚本：

```bash
sudo SMART_WALLET_APP_HOME=/opt/coinstrategy/smart-wallet \
SMART_WALLET_APP_USER=ubuntu \
SMART_WALLET_SERVER_NAME=49.51.250.174 \
bash /tmp/smart-wallet-release/bin/install-smart-wallet-linux-service.sh
```

这个脚本只做“一次性骨架安装”：

- 默认使用 `ubuntu` 作为运行用户（如果你显式指定了 `SMART_WALLET_APP_USER`，则按指定值处理）
- 创建 `/opt/coinstrategy/smart-wallet` 目录结构
- 创建 `/etc/coinstrategy/smart-wallet-job.env`
- 创建 `/etc/coinstrategy/smart-wallet-web.env`
- 安装 `systemd` 双服务
- 安装 `nginx` 配置

## 7. 每次发布更新

之后每次发版，只需要重新上传新版本的：

- `backend/app.jar`
- `web/app.jar`
- `frontend/dist/`

然后执行：

```bash
sudo SMART_WALLET_APP_HOME=/opt/coinstrategy/smart-wallet \
SMART_WALLET_APP_USER=ubuntu \
bash /tmp/smart-wallet-release/bin/install-smart-wallet-artifacts.sh
```

这个脚本只会：

- 替换 `backend/app.jar`
- 替换 `web/app.jar`
- 替换 `frontend/dist/`
- 同步 `bin/start-smart-wallet-backend.sh`
- 同步 `bin/restart-smart-wallet-backend.sh`
- 如果存在则同步 `runtime/*.py`

然后重启服务：

```bash
sudo systemctl restart smart-wallet-backend
sudo systemctl restart smart-wallet-web
sudo systemctl reload nginx
```

如果你想在 Linux 上手工兜底处理“8081 端口被占用、先杀进程再启动并追日志”这一套动作，也可以直接用新脚本。

如果你是把 `smart-wallet-test.tar.gz` 直接解压到 `/opt/coinstrategy/smart-wallet`，推荐优先执行：

```bash
sudo /opt/coinstrategy/smart-wallet/bin/restart-smart-wallet-backend.sh
```

如果你还没把脚本同步到根目录 `bin/`，也可以直接执行解压包里的原始路径：

```bash
sudo /opt/coinstrategy/smart-wallet/deploy/linux/bin/restart-smart-wallet-backend.sh
```

这个脚本会自动做这些事：

- 用 `sudo` 检查 `8081` 端口
- 用 `sudo kill` 清理占用端口的进程
- 如果检测到 `systemd` 服务 `smart-wallet-backend`，会先 `sudo systemctl stop/start`
- 如果没有 `systemd` 服务，才会用 `sudo -u ubuntu` 调用 `start-smart-wallet-backend.sh`
- 用 `sudo tail -n 500 -f` 持续查看日志

## 8. 启动与验证

检查 job：

```bash
sudo systemctl status smart-wallet-backend
journalctl -u smart-wallet-backend -f
```

检查 web：

```bash
sudo systemctl status smart-wallet-web
journalctl -u smart-wallet-web -f
```

检查 web 本机接口：

```bash
curl http://127.0.0.1:8080/strategy/api/smart-wallet/health
```

检查 job 本机接口：

```bash
curl http://127.0.0.1:8081/strategy/api/smart-wallet/health
```

检查外部入口：

```bash
curl http://49.51.250.174/strategy/api/smart-wallet/health
```

如果启动时报 `Failed to load RSA private key from: /opt/coinstrategy/app-private.pem`，优先检查私钥文件是否存在、格式是否为 PKCS#8 PEM、以及 `ubuntu` 运行用户是否可读：

```bash
sudo ls -l /opt/coinstrategy/app-private.pem
sudo -u ubuntu test -r /opt/coinstrategy/app-private.pem && echo readable
sudo head -n 1 /opt/coinstrategy/app-private.pem
sudo openssl pkey -in /opt/coinstrategy/app-private.pem -noout
```

私钥推荐用 root 拥有、`ubuntu` 组可读的方式安装：

```bash
sudo install -o root -g ubuntu -m 640 /tmp/app-private.pem /opt/coinstrategy/app-private.pem
```

访问地址：

- Swagger: `http://49.51.250.174/strategy/api/swagger-ui.html`
- 前端首页: `http://49.51.250.174/`
- 前端 dashboard: `http://49.51.250.174/dashboard`

## 9. Test 环境可执行钱包池验收

当前 `test` profile 已将 smart-wallet discovery 收敛为：

- 只使用 `OVERALL|ALL|PNL|100`
- `leaderboard-limit=100`
- `analyze-top-n=100`
- 不放宽执行风控，继续保持 `PAPER`

部署前先记录一次基线：

```bash
curl http://49.51.250.174/strategy/api/smart-wallet/overview
curl "http://49.51.250.174/strategy/api/smart-wallet/diagnostics/signal-health?lookbackHours=24"
curl http://49.51.250.174/strategy/api/smart-wallet/watchlist/candidates
curl http://49.51.250.174/strategy/api/smart-wallet/watchlist/executable
curl "http://49.51.250.174/strategy/api/smart-wallet/profiles/screener?scope=all&sortBy=replicabilityScore&sortDirection=desc"
```

部署后优先等待两个 discovery 周期再看结果。默认 discovery 周期来自：

```bash
smart-wallet.polymarket.discovery-fixed-delay-ms=3600000
```

如果不想等待两个定时周期，可以在部署后手工触发一次全链路恢复，它会顺带执行 `leaderboardRefresh` 和 `selectionRefresh`：

```bash
curl -X POST "http://49.51.250.174/strategy/api/smart-wallet/admin/recover-state?reason=test-wallet-pool-refresh"
```

建议固定按这个顺序复核：

```bash
curl http://49.51.250.174/strategy/api/smart-wallet/overview
curl http://49.51.250.174/strategy/api/smart-wallet/watchlist/candidates
curl http://49.51.250.174/strategy/api/smart-wallet/watchlist/executable
curl "http://49.51.250.174/strategy/api/smart-wallet/profiles/screener?scope=all&sortBy=replicabilityScore&sortDirection=desc"
curl http://49.51.250.174/strategy/api/smart-wallet/consensus-signals
curl "http://49.51.250.174/strategy/api/smart-wallet/follow-tasks/executable?limit=20"
curl "http://49.51.250.174/strategy/api/smart-wallet/orders?limit=20"
```

成功标准：

- `candidateWatchlist >= 2`
- `executableWatchlist >= 1`
- 至少有 `1` 个地址不是 `FAST_EXIT_OR_SKIP`
- `qualifiedWatchlistCount > 0`
- 4 小时窗口内出现新的 `FOLLOWED` PAPER 任务，或至少出现 `consensusSignalCount > 0`

如果两个周期后仍然全部是 `OBSERVE_ONLY / FAST_EXIT_OR_SKIP`，不要继续调 `MIN_ORDER`、`PRICE_RANGE`、`MARKET_TIMING`，先回到 discovery 扩源。

## 10. 当前脚本职责

- [`scripts/build-smart-wallet-release.sh`](../../scripts/build-smart-wallet-release.sh)
  本地构建上传包
- [`scripts/install-smart-wallet-artifacts.sh`](../../scripts/install-smart-wallet-artifacts.sh)
  服务器更新 `jar` 和 `dist`
- [`scripts/install-smart-wallet-linux-service.sh`](../../scripts/install-smart-wallet-linux-service.sh)
  服务器首次安装服务骨架
- [`scripts/start-smart-wallet-backend.sh`](../../scripts/start-smart-wallet-backend.sh)
  后端服务启动/停止/重启脚本
- [`deploy/linux/bin/restart-smart-wallet-backend.sh`](bin/restart-smart-wallet-backend.sh)
  Linux 手工兜底重启脚本，兼容解压包目录和安装目录，自动清理 `8081` 端口并追日志

## 11. 精简建议

如果你当前阶段只跑后端接口和前端展示，可以继续精简：

- 不启用 Polymarket LIVE，就不用上传 `runtime/*.py`
- 不改 profile，就不用在 env 里设置 `SPRING_PROFILES_ACTIVE`
- 不改单机端口，就不用在 env 里设置 `SERVER_PORT`

这样服务器侧真正需要长期维护的，通常只剩：

- 一个 job `app.jar`
- 一个 web `app.jar`
- 一个 `dist/`
- 两个环境文件：`/etc/coinstrategy/smart-wallet-job.env` 与 `/etc/coinstrategy/smart-wallet-web.env`

这就是比较安全、可控的制品部署方式。
