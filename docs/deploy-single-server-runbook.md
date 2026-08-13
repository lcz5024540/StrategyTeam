---
title: "CoinStrategy 单机部署 Runbook（新服务器一台机全装）"
type: runbook
status: active
tags: [deploy, runbook, single-server, htx, smart-wallet]
created: 2026-08-13
updated: 2026-08-13
---

# CoinStrategy 单机部署 Runbook

> 目标：**把 MySQL + HTX 做市 + job + web + 前端全部装在一台新服务器上**，照本文顺序执行即可跑起来。
> 原两机拓扑（web 在 43.139.186.27 / job+DB+HTX 在 49.51.250.174）在本方案里合并成一台，所有服务走 `127.0.0.1` 互联，只对外开放 22(SSH) 和 80(nginx)。
> ⚠️ HTX 是实盘真金交易，`HTX_TRADE_ENABLED` / `HTX_MM_ENABLED` 默认关，验证无误后再打开。

---

## 0. 一图速览（本机端口 / 目录 / 库）

| 服务 | 进程 | 端口 | 监听 | 可执行文件 | systemd 单元 |
|------|------|------|------|-----------|--------------|
| MySQL | mysqld | 3306 | 本机 | 系统安装 | `mysql` |
| HTX 现货做市 | java | 8082 | 127.0.0.1 | `/opt/coinstrategy/htx-trade/app.jar` | `coin-strategy-htx.service` |
| job（主业务/@Scheduled/Polymarket） | java | 8081 | 127.0.0.1 | `/opt/coinstrategy/smart-wallet/backend/app.jar` | `smart-wallet-backend.service` |
| web（前端 API 入口） | java | 8080 | 127.0.0.1 | `/opt/coinstrategy/smart-wallet/web/app.jar` | `smart-wallet-web.service` |
| 前端（SPA 静态页） | nginx | 80 | 0.0.0.0（对外） | `/opt/coinstrategy/smart-wallet/frontend/dist/` | `nginx` |

**两个数据库（同一个 MySQL 实例内）：**

| 库名 | 归属 | 建库方式 |
|------|------|---------|
| `coin_strategy` | web + job 共用 | **手工建**：导入 `sql/database.sql` 等（URL 里没有 auto-create） |
| `htx_trade_strategy` | HTX 独占 | **自动建**：HTX 首启用 `createDatabaseIfNotExist=true` + `schema.sql` 自动建库建表，无需手工 |

**DB 账号：** `root`（本机 `127.0.0.1`）。密码见 §3 与 §7（HTX 用明文、web/job 用 RSA 密文）。

---

## 1. 系统前置依赖（一次性）

以 Ubuntu 为例，`root` 或 `sudo`：

```bash
# JDK 17（必须 17，Lombok 注解处理在 JDK 25 会失败；运行也用 17）
sudo apt update
sudo apt install -y openjdk-17-jdk
java -version   # 确认输出含 "17."

# MySQL 8
sudo apt install -y mysql-server

# nginx（前端 + 反代 web）
sudo apt install -y nginx

# python3（job 若启用 Polymarket LIVE 需要；不启用也建议装上）
sudo apt install -y python3 python3-pip
# 仅当要跑 Polymarket 实盘：pip3 install py-clob-client
```

> 服务器**不拉代码、不装 Maven / Node**。jar 和前端 `dist` 都在本地/CI 构建好再上传（见 §4）。

---

## 2. 部署目录规划

```bash
sudo mkdir -p /opt/coinstrategy/htx-trade/{env,logs}
sudo mkdir -p /opt/coinstrategy/smart-wallet/{backend,web,frontend,bin,runtime,logs}
sudo mkdir -p /etc/coinstrategy/{shared,test}
# 运行用户（systemd 用它跑，示例 ubuntu；按你机器实际用户改）
sudo chown -R ubuntu:ubuntu /opt/coinstrategy
```

目录约定：

```text
/opt/coinstrategy/
├── app-private.pem                 # RSA 私钥（解密 web/job 的 ENC 密文），640
├── htx-trade/
│   ├── app.jar                     # HTX 可执行 fat jar
│   ├── env/htx.env                 # HTX 环境变量（含 AK/SK + DB 明文密码），600
│   └── logs/                       # stdout.log + coin-strategy-htx.log
└── smart-wallet/
    ├── backend/app.jar             # job
    ├── web/app.jar                 # web
    ├── frontend/dist/              # 前端静态资源（nginx root）
    ├── runtime/                    # Polymarket python 桥（可选）
    └── logs/

/etc/coinstrategy/                  # env 放这里，避免 jar 升级被覆盖
├── shared/secrets.env              # RSA 路径 + DB_PASSWORD 密文 + Polymarket 密钥（web/job 共用）
└── test/
    ├── smart-wallet-job.env        # job 专属：DB_HOST/DB_USERNAME/JVM
    └── smart-wallet-web.env        # web 专属：DB_HOST/DB_USERNAME/JVM
```

---

## 3. MySQL 配置

### 3.1 设置 root 密码 + 监听本机

```bash
sudo mysql_secure_installation      # 设 root 密码（记为 <MYSQL_ROOT_PWD>）、去匿名用户

# 单机部署：只需本机访问，保持默认 bind-address = 127.0.0.1 即可（更安全）
# 确认：
grep -n "bind-address" /etc/mysql/mysql.conf.d/mysqld.cnf   # 期望 127.0.0.1
```

### 3.2 授权（单机只放本机来源）

```bash
sudo mysql <<'SQL'
ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '<MYSQL_ROOT_PWD>';
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '<MYSQL_ROOT_PWD>';
GRANT ALL PRIVILEGES ON coin_strategy.*        TO 'root'@'127.0.0.1';
GRANT ALL PRIVILEGES ON htx_trade_strategy.*   TO 'root'@'127.0.0.1';
GRANT ALL PRIVILEGES ON coin_strategy.*        TO 'root'@'localhost';
GRANT ALL PRIVILEGES ON htx_trade_strategy.*   TO 'root'@'localhost';
FLUSH PRIVILEGES;
SQL
```

### 3.3 建 `coin_strategy` 库并导入 schema（web/job 用）

从项目 `sql/` 目录把脚本上传到服务器（例如 `/tmp/sql/`），**严格按下面顺序导入**。

> ⚠️ **顺序敏感（2026-08-13 核对）**：`database.sql` 只 `CREATE DATABASE`、**不建任何表**；`arbitrage_schema.sql` 才是所有 `smart_wallet_*` 表的建表脚本，**必须先于**后面那批 `*.sql`（它们全是 `ALTER TABLE ... ADD COLUMN` 幂等加列，表不存在会直接报错）。这些脚本**没有 `USE` 语句**，因此命令行必须带 `coin_strategy` 库名参数（见下）。所有建表/加列都是 `IF NOT EXISTS` / 存储过程幂等，重复导入安全。

```bash
# ① 建库（只建库，无表）
mysql -u root -p < /tmp/sql/database.sql

# ② 基础 schema（必须最先，建全部 smart_wallet_* / weather_* / pm_* / strategy_* 表）
mysql -u root -p coin_strategy < /tmp/sql/polymarket/arbitrage_schema.sql

# ③ 其他独立基础表（与 ② 无依赖，可放这里）
mysql -u root -p coin_strategy < /tmp/sql/strategy_engine_runtime.sql          # strategy_engine_* 3 表
mysql -u root -p coin_strategy < /tmp/sql/funding_rate_arbitrage.sql           # single_exchange_* 8 表（资金费率套利，不用可跳）

# ④ 增量 ALTER（依赖 ②，按文件日期顺序；幂等加列/加索引）
mysql -u root -p coin_strategy < /tmp/sql/polymarket/2026-05-10__smart_wallet_baseline.sql
mysql -u root -p coin_strategy < /tmp/sql/polymarket/2026-05-12__p0_remediation.sql
mysql -u root -p coin_strategy < /tmp/sql/polymarket/2026-05-16__copy_trading_w1_w2.sql   # 跟单 W1/W2，启用跟单再导

# ⑤ 种子数据（依赖 ② 的表；配置项 + 黑名单）
mysql -u root -p coin_strategy < /tmp/sql/polymarket/strategy_runtime_config_seed_current.sql
mysql -u root -p coin_strategy < /tmp/sql/polymarket/smart_wallet_manual_blocklist_seed_20260510.sql

# ⑥ 【草案 DRAFT，谨慎】仅当启用对应功能且已配套服务侧迁移时再导：
#   2026-05-17__p1_tracker_persistence_draft.sql          （GTC/MAKER_ONLY 超时持久化）
#   2026-05-17__f-p1-12_uma_dispute_audit_columns_draft.sql（UMA 争议审计列）
#   这两个后缀 _draft 标注「未在任一环境执行」，不启用相关功能就别导。
```

> `htx_trade_strategy` **不用在这里建**——HTX 服务首次启动会自动建库建表（见 §0）。

---

## 4. 本地/CI 构建产物（不在服务器上构建）

在你的开发机（装了 JDK 17 + Node + Maven）项目根目录执行：

```bash
export JAVA_HOME=/path/to/jdk-17         # 必须 JDK 17

# ① HTX 包
./scripts/build-htx.sh
#   产出：dist/htx/app.jar、dist/htx/htx-trade.tar.gz（含 app.jar + bin + env 模板）

# ② web + job + 前端包（test profile）
./scripts/build-test.sh
#   产出：
#   dist/test/smart-wallet-test-job.tar.gz   (backend/app.jar + runtime + bin + deploy)
#   dist/test/smart-wallet-test-web.tar.gz   (web/app.jar + frontend/dist + bin + deploy)
#   dist/test/smart-wallet-test.tar.gz       (整包，含全部，单机部署用这个最省事)
```

单机部署直接用**整包** `smart-wallet-test.tar.gz` + `htx-trade.tar.gz` 两个即可。

---

## 5. 上传与解包

```bash
NEW=<新服务器IP>

# HTX
scp dist/htx/htx-trade.tar.gz ubuntu@$NEW:/tmp/
ssh ubuntu@$NEW 'sudo tar -xzf /tmp/htx-trade.tar.gz -C /opt/coinstrategy/htx-trade && sudo chown -R ubuntu:ubuntu /opt/coinstrategy/htx-trade'

# smart-wallet 整包（backend + web + frontend + bin + runtime + deploy）
scp dist/test/smart-wallet-test.tar.gz ubuntu@$NEW:/tmp/
ssh ubuntu@$NEW 'sudo tar -xzf /tmp/smart-wallet-test.tar.gz -C /opt/coinstrategy/smart-wallet && sudo chown -R ubuntu:ubuntu /opt/coinstrategy/smart-wallet'
```

---

## 6. RSA 私钥（web/job 解密 DB 密码用；HTX 不需要）

### 6.0 前置动作 0：先离线备份旧机真私钥（搬机第一件事，别拖）

> ⛔ **这一步优先级高于本文档任何其他步骤。趁旧机 `49.51.250.174` 还在，立刻把真私钥拷出来单独存档。**
> **丢的是钥匙、不是密文**：密文 `DB_PASSWORD` / `POLYMARKET_EXECUTION_PRIVATE_KEY` 在本机 `deploy/linux/env/shared/secrets.env`（gitignored，文件在）有完整备份；缺的是**解密它的私钥**。仓库 `scripts/keys/app-private.pem` 是**另一把**（自加解密回路正常，但解现网密文报 `BadPaddingException`）。加密现网密文的真私钥**只存在于旧机** `/opt/coinstrategy/app-private.pem`，**没有其他备份**。旧机一旦下线且这把丢了，现网密文将**永久无法解密**。

```bash
# ① 从旧机拷出真私钥，落到你本地一个安全目录（勿进 git）
scp ubuntu@49.51.250.174:/opt/coinstrategy/app-private.pem ./app-private.REAL.pem
chmod 600 ./app-private.REAL.pem

# ② 立刻验证这把能解现网密文（从 secrets.env 抽完整密文，勿手抄省略号！）
#    打印出明文=对；BadPadding=拿错钥匙，别继续搬
DBENC=$(grep -Eo 'ENC\([^)]*\)' deploy/linux/env/shared/secrets.env | head -1)
APP_RSA_PRIVATE_KEY_PATH=./app-private.REAL.pem \
  bash scripts/encrypt-secret.sh --decrypt "$DBENC"

# ③ 再做一份异地/离线冷备（U盘、密码管理器附件等，勿只留一处）
```

> 验证通过后，后续按**路线 A 或 B**（见下）二选一继续。**验证不通过（BadPadding）说明旧机这把也不是加密用的那把 → 现网密文已无法救，只能走路线 A 全部重设 + 重新拿 Polymarket 私钥明文。**

---

web/job 的 `DB_PASSWORD` 在 `secrets.env` 里是 `ENC(...)` 密文，运行时用 RSA 私钥解密。

> **⚠️ 关键坑（2026-08-13 验证）：真私钥没有本地代码库备份。**
> 仓库里 `scripts/keys/app-private.pem` 是**另一把**（自加解密回路正常，但解现网 `secrets.env` 的密文报 `BadPaddingException`，密钥对不上）。
> **加密现网 `secrets.env` 用的真私钥，只存在于旧服务器 `/opt/coinstrategy/app-private.pem`。** 搬机第一步必须从旧机把它拷出来并备份，别用仓库里那把：
> ```bash
> scp ubuntu@49.51.250.174:/opt/coinstrategy/app-private.pem ./app-private.REAL.pem
> # 验证能解现网密文（打印出明文=对；BadPadding=拿错钥匙）
> APP_RSA_PRIVATE_KEY_PATH=./app-private.REAL.pem bash scripts/encrypt-secret.sh --decrypt 'ENC(jpJMtbz9...moQ==)'
> ```
> 若旧机也丢了这把私钥，则现网 `DB_PASSWORD` / `POLYMARKET_EXECUTION_PRIVATE_KEY` 密文将**永久无法解密**，只能全部改用新密钥重设（路线 A）+ 重新拿到 Polymarket 私钥明文。

**两条路二选一：**

**路线 A（推荐，新机设新密码）：** 在开发机用新 MySQL 密码重新加密，得到新密文替换 `secrets.env`。

```bash
# 开发机：生成私钥（若还没有）
bash scripts/generate-rsa-keys.sh          # 产出 scripts/keys/app-private-local.pem
# 用私钥加密你的新 MySQL 密码
APP_RSA_PRIVATE_KEY_PATH=scripts/keys/app-private-local.pem bash scripts/encrypt-secret.sh
#   输入 <MYSQL_ROOT_PWD>，把输出的 ENC(...) 串填进 §7 的 secrets.env DB_PASSWORD
# 上传私钥到服务器
scp scripts/keys/app-private-local.pem ubuntu@$NEW:/tmp/app-private.pem
ssh ubuntu@$NEW 'sudo install -o ubuntu -g ubuntu -m 640 /tmp/app-private.pem /opt/coinstrategy/app-private.pem'
```

**路线 B（沿用旧密文）：** 把**旧机的真私钥**（见上方警告，`scp` 自 `49.51.250.174:/opt/coinstrategy/app-private.pem`，**不是**仓库 `scripts/keys/` 那把）拷到新机 `/opt/coinstrategy/app-private.pem`，并把新机 MySQL root 密码设成**和旧机一样的明文**（否则旧密文解出来对不上）。旧明文可用真私钥 `--decrypt` 解出。

> `secrets.env` 里 `APP_RSA_PRIVATE_KEY_PATH` 要指向 `/opt/coinstrategy/app-private.pem`。

---

## 7. env 配置文件（照抄改值）

### 7.1 HTX：`/opt/coinstrategy/htx-trade/env/htx.env`（含明文，权限 600）

```bash
# HTX API 凭证（火币现货子账号）
export HTX_ACCESS_KEY="<你的HTX_AK>"
export HTX_SECRET_KEY="<你的HTX_SK>"
# 现货账户 id：首启可留空，调 GET http://127.0.0.1:8082/htx/api/htx/accounts 拿到后回填
export HTX_SPOT_ACCOUNT_ID="73741966"

# 安全开关（先全关，验证后再逐个打开）
export HTX_TRADE_ENABLED="false"
export HTX_MM_ENABLED="false"
export HTX_MM_SYMBOLS="xautusdt"

# profile + DB（单机本机直连；HTX 用明文密码，不走 RSA）
export SPRING_PROFILES_ACTIVE="test"
export HTX_DB_HOST="127.0.0.1"
export HTX_DB_PORT="3306"
export HTX_DB_USERNAME="root"
export HTX_DB_PASSWORD="<MYSQL_ROOT_PWD>"

# JVM（可选）
export JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
```

```bash
sudo chmod 600 /opt/coinstrategy/htx-trade/env/htx.env
```

### 7.2 web/job 共享：`/etc/coinstrategy/shared/secrets.env`（权限 640）

```bash
export APP_RSA_PRIVATE_KEY_PATH="/opt/coinstrategy/app-private.pem"
# DB_PASSWORD：§6 加密得到的密文（或沿用旧机密文）
export DB_PASSWORD="ENC(...你的密文...)"

# Binance（如用到，否则留空）
export BINANCE_API_KEY=""
export BINANCE_SECRET_KEY=""

# Polymarket（仅 job 需要；不跑 Polymarket 可留占位）
export POLYMARKET_EXECUTION_WALLET_ADDRESS="..."
export POLYMARKET_EXECUTION_PRIVATE_KEY="ENC(...)"
export POLYMARKET_CLOB_API_KEY="..."
export POLYMARKET_CLOB_API_SECRET="..."
export POLYMARKET_CLOB_API_PASSPHRASE="..."
export POLYMARKET_PYTHON_EXECUTABLE="/usr/bin/python3"
export SMART_WALLET_EXECUTION_WALLET_HELPER_SCRIPT_PATH="/opt/coinstrategy/smart-wallet/runtime/polymarket_clob_client.py"
```

> 原始密钥值见项目 `deploy/linux/env/shared/secrets.env`（gitignored，不入库）。搬机时从旧机 `secrets.env` 拷贝这些非 DB 的密钥值。

### 7.3 job 专属：`/etc/coinstrategy/test/smart-wallet-job.env`（640）

```bash
export SPRING_PROFILES_ACTIVE="test"
export SERVER_ADDRESS="127.0.0.1"
export SERVER_PORT="8081"
export DB_HOST="127.0.0.1"          # 单机改成本机
export DB_USERNAME="root"
# DB_PASSWORD 来自 shared/secrets.env
export JAVA_OPTS="-Xms512m -Xmx1024m -Xss512k -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError"
```

### 7.4 web 专属：`/etc/coinstrategy/test/smart-wallet-web.env`（640）

```bash
export SPRING_PROFILES_ACTIVE="test"
export SERVER_ADDRESS="127.0.0.1"
export SERVER_PORT="8080"
export DB_HOST="127.0.0.1"          # 单机改成本机
export DB_USERNAME="root"
# DB_PASSWORD 来自 shared/secrets.env
export JAVA_OPTS="-Xms128m -Xmx384m -Xss512k -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError"
```

```bash
sudo chown root:ubuntu /etc/coinstrategy/shared/secrets.env /etc/coinstrategy/test/*.env
sudo chmod 640 /etc/coinstrategy/shared/secrets.env /etc/coinstrategy/test/*.env
```

---

## 8. systemd 服务安装

### 8.1 HTX：`/etc/systemd/system/coin-strategy-htx.service`

```ini
[Unit]
Description=CoinStrategy HTX Spot Market Maker
After=network.target mysql.service

[Service]
Type=simple
User=ubuntu
Group=ubuntu
WorkingDirectory=/opt/coinstrategy/htx-trade
EnvironmentFile=/opt/coinstrategy/htx-trade/env/htx.env
Environment=LOG_DIR=/opt/coinstrategy/htx-trade/logs
ExecStart=/usr/bin/java ${JAVA_OPTS} -jar /opt/coinstrategy/htx-trade/app.jar
SuccessExitStatus=143
Restart=always
RestartSec=5
LimitNOFILE=65535
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now coin-strategy-htx.service
sudo systemctl status coin-strategy-htx.service
```

### 8.2 job + web：用项目自带安装脚本

项目 `scripts/install-smart-wallet-linux-service.sh` 会渲染 job/web 的 systemd 单元。单机只装服务、nginx 单独装（见 §9），所以传 `SMART_WALLET_INSTALL_NGINX=false`：

```bash
cd /opt/coinstrategy/smart-wallet     # 解包后 deploy/ 在这里
sudo SMART_WALLET_SERVICE_ENV=test \
     SMART_WALLET_APP_USER=ubuntu \
     SMART_WALLET_ENV_DIR=/etc/coinstrategy \
     SMART_WALLET_INSTALL_NGINX=false \
     bash deploy/linux/../scripts/install-smart-wallet-linux-service.sh 2>/dev/null \
  || sudo SMART_WALLET_SERVICE_ENV=test SMART_WALLET_APP_USER=ubuntu \
          SMART_WALLET_ENV_DIR=/etc/coinstrategy SMART_WALLET_INSTALL_NGINX=false \
          bash /tmp/scripts/install-smart-wallet-linux-service.sh
```

> 脚本会生成 `smart-wallet-backend.service`（job）与 `smart-wallet-web.service`（web），`enable` 它们。若整包里没带 `scripts/`，把开发机的 `scripts/install-smart-wallet-linux-service.sh` 和 `deploy/linux/systemd/*.template` 一起 scp 到 `/tmp` 再跑。

启动：

```bash
sudo systemctl restart smart-wallet-backend.service smart-wallet-web.service
sudo systemctl status  smart-wallet-backend.service smart-wallet-web.service
```

> 若不想用脚本，可直接把 `deploy/linux/systemd/*.template` 里的 `__APP_HOME__`=`/opt/coinstrategy/smart-wallet`、`__APP_USER__/__APP_GROUP__`=`ubuntu`、`__ENV_FILE__`=对应 env、`__SHARED_ENV_FILE__`=`/etc/coinstrategy/shared/secrets.env`、`__ENV__`=`test` 手工替换后落 `/etc/systemd/system/`。

---

## 9. nginx（前端 + 反代 web）

`/etc/nginx/conf.d/smart-wallet.conf`（把项目 `deploy/linux/nginx/smart-wallet-test.conf` 拷来，改 `server_name` 为新机 IP）：

```nginx
server {
    listen 80;
    server_name <新服务器IP>;

    root /opt/coinstrategy/smart-wallet/frontend/dist;
    index index.html;
    server_tokens off;

    location /strategy/api/ {
        proxy_pass http://127.0.0.1:8080/strategy/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_connect_timeout 30s;
        proxy_read_timeout 120s;
        proxy_send_timeout 30s;
    }
    location ~ /\. { deny all; access_log off; log_not_found off; }
    location / { try_files $uri $uri/ /index.html; }
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 7d; add_header Cache-Control "public, immutable";
    }
    add_header X-Frame-Options SAMEORIGIN always;
    add_header X-Content-Type-Options nosniff always;
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

---

## 10. 防火墙（单机只对外开 22 + 80）

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw deny 8080/tcp     # web 仅本机 nginx 反代
sudo ufw deny 8081/tcp     # job 仅 loopback
sudo ufw deny 8082/tcp     # HTX 仅 loopback
sudo ufw deny 3306/tcp     # MySQL 仅本机
sudo ufw enable
```

---

## 11. 启动顺序与验证

启动顺序：MySQL → HTX → job → web → nginx（systemd `enable` 后开机自起）。

```bash
# 1) HTX（应自动建 htx_trade_strategy 库）
sudo systemctl status coin-strategy-htx.service
curl http://127.0.0.1:8082/htx/api/htx/accounts        # 拿 spot account id（若 AK/SK 已配）
mysql -u root -p -e "SHOW DATABASES LIKE 'htx_trade_strategy';"

# 2) job（本机连 coin_strategy）
curl http://127.0.0.1:8081/strategy/api/smart-wallet/health

# 3) web
curl http://127.0.0.1:8080/strategy/api/smart-wallet/health

# 4) 外网入口（前端 + API）
curl http://<新服务器IP>/strategy/api/smart-wallet/health
# 浏览器打开 http://<新服务器IP>/ 看前端页面

# 5) 隔离性校验：8081/8082 外网应打不通
curl --max-time 3 http://<新服务器IP>:8081/ ; echo "  <- 应超时/拒绝"
```

**打开 HTX 实盘（确认无误后）：** 编辑 `htx.env` 把 `HTX_TRADE_ENABLED=true`、按需 `HTX_MM_ENABLED=true`，再 `sudo systemctl restart coin-strategy-htx.service`。

---

## 12. 日常运维

```bash
# 状态 / 重启
sudo systemctl status|restart coin-strategy-htx.service
sudo systemctl status|restart smart-wallet-backend.service
sudo systemctl status|restart smart-wallet-web.service

# 日志
journalctl -u coin-strategy-htx.service -f
tail -f /opt/coinstrategy/htx-trade/logs/coin-strategy-htx.log
tail -f /opt/coinstrategy/smart-wallet/logs/*.log

# 升级 jar（以 HTX 为例）：备份→替换→重启
cd /opt/coinstrategy/htx-trade
cp -p app.jar app.jar.bak-$(date +%s)
# scp 新 app.jar 覆盖后：
sudo systemctl restart coin-strategy-htx.service && systemctl is-active coin-strategy-htx.service
```

---

## 13. 已知坑（务必知悉）

1. **JDK 必须 17**：构建与运行都用 17，JDK 25 会因 Lombok 注解处理失败。
2. **`coin_strategy` 库和表都不会自动建**：`database.sql` 只建空库，**表全靠脚本**，且 **`polymarket/arbitrage_schema.sql` 必须先于那批 `ALTER` 脚本**（顺序见 §3.3，导错顺序会报「表不存在」）；`htx_trade_strategy` 会自动建，别手工插手。
3. **web 不再透传给 job**（新架构）：前端部分依赖旧 proxy 的接口可能 404，按 smoke test 逐个在 web 端补 controller。
4. **web/job 密码走 RSA 密文**，HTX 走明文——两套机制别混：改了 MySQL 密码要同步 (a) `htx.env` 明文 (b) `secrets.env` 重新加密的密文。
5. **磁盘要够 + binlog 治理**：HTX 实盘曾因宿主机磁盘被 MySQL binlog 撑满 → 全库 COMMIT 挂起 → 做市引擎单线程调度器被死连接顶死。新机建议：调低 `binlog_expire_logs_seconds`、加磁盘 85% 告警、根分区别太小。
6. **HTX env 权限 600、secrets.env 640**：含 AK/SK 与密码，别世界可读，别进 git。

---

## 附：与原两机拓扑的差异清单

| 项 | 原两机 | 本单机 |
|----|--------|--------|
| DB_HOST（web/job/htx） | `49.51.250.174` | `127.0.0.1` |
| MySQL 授权来源 | `127.0.0.1` + `43.139.186.27` | 仅 `127.0.0.1`/`localhost` |
| MySQL bind-address | `0.0.0.0` | `127.0.0.1`（更安全） |
| nginx server_name | `43.139.186.27` | `<新服务器IP>` |
| 防火墙 3306 | 放行 web 机 | 全拒（本机自访） |
| 对外端口 | web 机 80、job 机仅 SSH | 仅 22 + 80 |
