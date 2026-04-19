# Smart Wallet Linux 服务部署说明

这套部署方案是基于当前仓库里的 [`scripts/run-smart-wallet.sh`](../../scripts/run-smart-wallet.sh) 整理出来的 Linux 生产版方案。

当前按你的服务器实际情况，默认约定如下：

- 后端主配置文件：[`coin-strategy-job/src/main/resources/application-prod.yml`](../../coin-strategy-job/src/main/resources/application-prod.yml)
- 服务器 IP：`49.51.250.174`
- 后端服务端口：`8081`

核心差异：

- 本地脚本：动态找端口、临时前后台一起拉起、前端使用 `vite dev`
- 服务器方案：后端使用 `systemd` 常驻，前端使用 `vite build` 产物 + `nginx` 托管

## 1. 推荐目录

建议服务器上的项目目录保持为固定路径，例如：

```bash
/opt/coinstrategy/CoinStrategy
```

如果你后续想做版本切换，也可以扩展为：

```bash
/opt/coinstrategy/releases/20260419
/opt/coinstrategy/current -> /opt/coinstrategy/releases/20260419
```

本文档默认 `APP_HOME=/opt/coinstrategy/CoinStrategy`。

## 2. 依赖准备

至少准备这些运行环境：

- `JDK 17`
- `Maven 3.9+`
- `Node.js 18+`
- `npm`
- `nginx`
- `MySQL 8.x`
- `python3` 和 `pip3`

如果你要启用 Polymarket LIVE 下单，还需要安装 Python SDK：

```bash
pip3 install py-clob-client
```

## 3. 配置文件约定

服务器启动时，主配置文件使用：

```bash
coin-strategy-job/src/main/resources/application-prod.yml
```

目前仓库里已经对齐为：

- `server.port=8081`
- `server.address=0.0.0.0`
- MySQL 地址：`49.51.250.174:3306`

也就是说，后端默认通过 `--spring.profiles.active=prod` 读取这份配置启动。

## 4. 可选环境变量文件

Linux 服务启动不再依赖 `~/.zshrc`，统一改成独立环境文件：

```bash
/etc/coinstrategy/smart-wallet.env
```

模板文件在：

- [`deploy/linux/env/smart-wallet.env.example`](./env/smart-wallet.env.example)

这个文件会被 [`scripts/start-smart-wallet-backend.sh`](../../scripts/start-smart-wallet-backend.sh) 直接 `source`，所以请使用 `export KEY=value` 语法。

这份文件现在更适合放“敏感信息”或“少量覆盖项”，不是主配置入口。

最少建议确认这些配置：

- `SMART_WALLET_BACKEND_PORT`
- `SPRING_PROFILES_ACTIVE`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `POLYMARKET_PYTHON_EXECUTABLE`
- `POLYMARKET_EXECUTION_WALLET_ADDRESS`
- `POLYMARKET_EXECUTION_PRIVATE_KEY`
- `POLYMARKET_EXECUTION_FUNDER_ADDRESS`
- `POLYMARKET_CLOB_API_KEY`
- `POLYMARKET_CLOB_API_SECRET`
- `POLYMARKET_CLOB_API_PASSPHRASE`

## 5. 构建发布产物

如果你打算直接在服务器构建，可以在项目根目录执行：

```bash
bash scripts/build-smart-wallet-release.sh
```

这个脚本会做几件事：

1. 执行 `mvn -pl coin-strategy-job -am package -DskipTests`
2. 执行前端 `npm ci` 和 `npm run build`
3. 生成发布目录 `dist/smart-wallet-release`

生成后你会拿到：

- `dist/smart-wallet-release/backend/coin-strategy-job.jar`
- `dist/smart-wallet-release/frontend/dist`
- `dist/smart-wallet-release/scripts`
- `dist/smart-wallet-release/deploy/linux`

如果你想让前端使用同域名反代，构建时保持 `VITE_API_BASE_URL` 为空即可；这也是当前脚本默认行为。

## 6. 后端启动方式

Linux 服务启动脚本：

- [`scripts/start-smart-wallet-backend.sh`](../../scripts/start-smart-wallet-backend.sh)

它会做这些事：

1. 优先加载 `/etc/coinstrategy/smart-wallet.env`
2. 找可运行的 `coin-strategy-job` jar
3. 以 `spring.profiles.active=prod` 启动，并读取 `application-prod.yml`
4. 将日志输出到 `journalctl`，同时应用日志继续落到项目目录下 `logs/`

本地手动验证可以这样跑：

```bash
SMART_WALLET_ENV_FILE=/etc/coinstrategy/smart-wallet.env \
bash scripts/start-smart-wallet-backend.sh
```

## 7. 安装 systemd 和 nginx

已经准备好的模板文件：

- systemd 模板：[`deploy/linux/systemd/smart-wallet-backend.service.template`](./systemd/smart-wallet-backend.service.template)
- nginx 模板：[`deploy/linux/nginx/smart-wallet.conf.template`](./nginx/smart-wallet.conf.template)

也提供了一键安装脚本：

- [`scripts/install-smart-wallet-linux-service.sh`](../../scripts/install-smart-wallet-linux-service.sh)

示例：

```bash
sudo SMART_WALLET_APP_HOME=/opt/coinstrategy/CoinStrategy \
SMART_WALLET_SERVER_NAME=49.51.250.174 \
bash scripts/install-smart-wallet-linux-service.sh
```

这个安装脚本会：

1. 创建 `coinstrategy` 系统用户（如果不存在）
2. 创建 `/etc/coinstrategy/smart-wallet.env`（如果不存在）
3. 生成 `/etc/systemd/system/smart-wallet-backend.service`
4. 生成 `/etc/nginx/conf.d/smart-wallet.conf`
5. 执行 `systemctl daemon-reload`
6. 执行 `systemctl enable smart-wallet-backend`
7. 如果系统已安装 `nginx`，执行 `nginx -t`

## 8. 启动与验证

启动后端：

```bash
sudo systemctl restart smart-wallet-backend
sudo systemctl status smart-wallet-backend
```

查看日志：

```bash
journalctl -u smart-wallet-backend -f
tail -f /opt/coinstrategy/CoinStrategy/logs/coin-strategy-job.log
```

重载 nginx：

```bash
sudo systemctl reload nginx
```

验证接口：

```bash
curl http://127.0.0.1:8081/strategy/api/smart-wallet/health
curl http://127.0.0.1/strategy/api/smart-wallet/health
```

Swagger：

```bash
http://49.51.250.174/strategy/api/swagger-ui.html
```

前端：

```bash
http://49.51.250.174/
http://49.51.250.174/dashboard
```

## 9. 推荐上线顺序

建议按下面顺序配置：

1. 先确认服务器上的 `application-prod.yml` 配置正确
2. 手工执行一次 `bash scripts/start-smart-wallet-backend.sh` 验证后端能起来
3. 执行 `npm run build` 或 `bash scripts/build-smart-wallet-release.sh`
4. 安装 `systemd` 和 `nginx`
5. 最后再开启 `auto-execution-enabled` 或 LIVE 模式

## 10. 注意事项

- 线上不要继续用 `vite dev`，那是开发态服务
- 你当前服务器是以 `application-prod.yml` 为主配置启动，所以数据库、端口等核心参数建议优先维护在这份文件里
- `/etc/coinstrategy/smart-wallet.env` 更适合放私钥、API Key、超时参数这类敏感或易变配置
- `helper-script-path` 现在是相对路径 `scripts/polymarket_clob_client.py`，所以 `systemd` 的 `WorkingDirectory` 必须指向项目根目录
- 如果服务器不是 `x86_64` 常规 Linux，请先确认 Java、Node、Python 版本都可用
