# Smart Wallet Linux 制品部署说明

这份方案按你现在的要求重新收口了：

- 服务器不拉代码
- 服务器不做 Maven / Node 构建
- 服务器只接收上传的 `jar` 和前端 `dist`
- 前端和后端在同一台服务器
- 后端只监听 `127.0.0.1`
- 外部流量统一走 `nginx`

当前默认机器信息：

- 服务器 IP：`49.51.250.174`
- 后端 profile：`prod`
- 后端本地端口：`8081`

## 1. 部署目录

推荐统一运行目录：

```bash
/opt/coinstrategy/smart-wallet
```

目录结构建议如下：

```bash
/opt/coinstrategy/smart-wallet/
├── backend/app.jar
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

- `backend/app.jar` 是你本地构建后上传的后端包
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
- `/etc/coinstrategy/smart-wallet.env` 放“敏感信息和服务器差异化配置”

你不应该在服务器上改源码里的 `application-prod.yml`，而是通过环境变量覆盖。

示例环境文件：

- [`deploy/linux/env/smart-wallet.env`](env/smart-wallet.env)

建议重点维护这些变量：

- `SERVER_ADDRESS=127.0.0.1`
- `SERVER_PORT=8081`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `JAVA_OPTS`
- `POLYMARKET_*`
- `SMART_WALLET_EXECUTION_WALLET_HELPER_SCRIPT_PATH`

## 4. 安全边界

这套方案默认的安全边界是：

1. Spring Boot 后端只监听 `127.0.0.1:8081`
2. 外网只开放 `80/443`
3. `nginx` 反代 `/strategy/api/` 到本机 `127.0.0.1:8081`
4. `systemd` 使用独立低权限用户 `coinstrategy`
5. 环境变量文件放在 `/etc/coinstrategy/smart-wallet.env`，权限为 `640`
6. 后端服务加了基础 `systemd` 加固项：`NoNewPrivileges`、`ProtectSystem`、`PrivateTmp`

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
SMART_WALLET_SERVER_NAME=49.51.250.174 \
bash /tmp/smart-wallet-release/bin/install-smart-wallet-linux-service.sh
```

这个脚本只做“一次性骨架安装”：

- 创建 `coinstrategy` 用户
- 创建 `/opt/coinstrategy/smart-wallet` 目录结构
- 创建 `/etc/coinstrategy/smart-wallet.env`
- 安装 `systemd`
- 安装 `nginx` 配置

## 7. 每次发布更新

之后每次发版，只需要重新上传新版本的：

- `backend/app.jar`
- `frontend/dist/`

然后执行：

```bash
sudo SMART_WALLET_APP_HOME=/opt/coinstrategy/smart-wallet \
bash /tmp/smart-wallet-release/bin/install-smart-wallet-artifacts.sh
```

这个脚本只会：

- 替换 `backend/app.jar`
- 替换 `frontend/dist/`
- 同步 `bin/start-smart-wallet-backend.sh`
- 如果存在则同步 `runtime/*.py`

然后重启服务：

```bash
sudo systemctl restart smart-wallet-backend
sudo systemctl reload nginx
```

## 8. 启动与验证

检查后端：

```bash
sudo systemctl status smart-wallet-backend
journalctl -u smart-wallet-backend -f
```

检查本机接口：

```bash
curl http://127.0.0.1:8081/strategy/api/smart-wallet/health
```

检查外部入口：

```bash
curl http://49.51.250.174/strategy/api/smart-wallet/health
```

如果启动时报 `Failed to load RSA private key from: /etc/coinstrategy/app-private.pem`，优先检查私钥文件是否存在、格式是否为 PKCS#8 PEM、以及 `coinstrategy` 服务用户是否可读：

```bash
sudo ls -l /etc/coinstrategy/app-private.pem
sudo -u coinstrategy test -r /etc/coinstrategy/app-private.pem && echo readable
sudo head -n 1 /etc/coinstrategy/app-private.pem
sudo openssl pkey -in /etc/coinstrategy/app-private.pem -noout
```

私钥推荐用 root 拥有、服务组可读的方式安装：

```bash
sudo install -o root -g coinstrategy -m 640 /tmp/app-private.pem /etc/coinstrategy/app-private.pem
```

访问地址：

- Swagger: `http://49.51.250.174/strategy/api/swagger-ui.html`
- 前端首页: `http://49.51.250.174/`
- 前端 dashboard: `http://49.51.250.174/dashboard`

## 9. 当前脚本职责

- [`scripts/build-smart-wallet-release.sh`](../../scripts/build-smart-wallet-release.sh)
  本地构建上传包
- [`scripts/install-smart-wallet-artifacts.sh`](../../scripts/install-smart-wallet-artifacts.sh)
  服务器更新 `jar` 和 `dist`
- [`scripts/install-smart-wallet-linux-service.sh`](../../scripts/install-smart-wallet-linux-service.sh)
  服务器首次安装服务骨架
- [`scripts/start-smart-wallet-backend.sh`](../../scripts/start-smart-wallet-backend.sh)
  手动管理后端服务时使用的启动脚本

## 10. 精简建议

如果你当前阶段只跑后端接口和前端展示，可以继续精简：

- 不启用 Polymarket LIVE，就不用上传 `runtime/*.py`
- 不改 profile，就不用在 env 里设置 `SPRING_PROFILES_ACTIVE`
- 不改单机端口，就不用在 env 里设置 `SERVER_PORT`

这样服务器侧真正需要长期维护的，通常只剩：

- 一个 `app.jar`
- 一个 `dist/`
- 一个 `/etc/coinstrategy/smart-wallet.env`

这就是比较安全、可控的制品部署方式。
