---
title: "部署拓扑重构 — 落地清单"
type: design
status: active
tags: [design, deploy]
created: 2026-05-17
updated: 2026-05-17
---

# 部署拓扑重构 — 落地清单

> 状态: 已落地 (test 环境). prod 暂时取消.

## 1. 目标拓扑 (test, 当前唯一活跃环境)

| 机器 | 部署的服务 | DB 主机（对该服务而言） | DB 用户 | 网络监听 |
|------|-----------|------|---------|---------|
| `43.139.186.27` | web + 前端 | `49.51.250.174` (远程) | `root` | web 8080 (loopback), nginx 80 (对外) |
| `49.51.250.174` | job + MySQL | `127.0.0.1` 或 `49.51.250.174` (本机) | `root` | job 8081 (**loopback only, 对外完全不可达**), MySQL 3306 |

核心架构变化:
- **web 与 job 互不调用** — 各自独立连同一个 MySQL 实例，没有 HTTP 依赖。
- **job 永远不对外** — 只监听 127.0.0.1，外网/内网都访问不到 8081。
- **DB 单实例统一连接** — MySQL 跑在 `49.51.250.174`，root 用户对 `127.0.0.1`（job 本机）和 `43.139.186.27`（web 跨机）两个源都开放授权；test 环境全部统一连这一个 DB。

prod 环境: 暂时取消，配置文件保留为模板 (yml 的 prod profile + `deploy/linux/env/prod/.gitkeep`)，恢复 prod 时再补。

---

## 2. 已完成的改动

### 2.1 Java 代码 — 切断 web → job 的 HTTP 调用

清空（保留 package 声明，移除所有 Spring bean 注解和实现）:
- `coin-strategy-web/src/main/java/.../web/config/DashboardProperties.java`
- `coin-strategy-web/src/main/java/.../web/service/JobApiForwardService.java`
- `coin-strategy-web/src/main/java/.../web/controller/JobApiProxyController.java`
- `coin-strategy-web/src/main/java/.../web/controller/smartwallet/SmartWalletReadProxyController.java`
- `coin-strategy-web/src/main/java/.../web/controller/smartwallet/SmartWalletWriteProxyController.java`

`CoinStrategyWebApplication.java` 已从 `@EnableConfigurationProperties` 移除 `DashboardProperties.class`。

> **影响**: 任何之前靠 fall-through proxy 透传到 job 的 `/strategy/api/binance/**` 和 `/strategy/api/smart-wallet/**` 路径，现在如果 web 端没有具体 controller，会直接 404。部署后需要 smoke test 前端所有接口。详见各 stub 文件顶部注释。

### 2.2 application yml — 按 profile 区分 DB 主机/用户

`coin-strategy-web/src/main/resources/`
- `application-test.yml`: 移除 `dashboard:` 块；`DB_HOST` 默认 `49.51.250.174`（统一远程 DB），`DB_USERNAME` 默认 `root`
- `application-prod.yml`: 移除 `dashboard:` 块；prod 暂未上线，加注释提示
- `application-local.yml`: 移除 `dashboard:` 块；`DB_HOST` 默认 `127.0.0.1`，`DB_USERNAME` 默认 `root`（脚本里可 export `DB_HOST=49.51.250.174` 临时连远程）

`coin-strategy-job/src/main/resources/`
- `application-test.yml`: `DB_HOST` 默认 `49.51.250.174`（与 web test 统一），`DB_USERNAME` 默认 `root`，`server.address` 保持 `127.0.0.1`（loopback）
- `application-local.yml`: `DB_HOST` 默认 `127.0.0.1`（之前写死, 现已改为 `${DB_HOST:127.0.0.1}` 支持覆盖）
- `application-prod.yml`: 暂未上线，加注释提示

### 2.3 env 文件 — 按环境拆分

新目录树:
```
deploy/linux/env/
├── shared/
│   └── secrets.env                  # 跨环境共享: RSA 路径 / DB_PASSWORD 密文 / Polymarket 密钥 / Binance
├── test/
│   ├── smart-wallet-web.env         # 43.139.186.27 web 专属: DB_HOST=49.51.250.174, DB_USERNAME=root, JVM 384m
│   └── smart-wallet-job.env         # 49.51.250.174 job 专属: DB_HOST=49.51.250.174, DB_USERNAME=root, JVM 1024m
├── prod/
│   └── .gitkeep                     # 暂未上线
├── smart-wallet.env                 # [DEPRECATED] 旧版合并文件，留 fallback
├── smart-wallet-job.env             # [DEPRECATED] 顶层 job env, 自动 source test/smart-wallet-job.env
└── smart-wallet-web.env             # [DEPRECATED] 顶层 web env, 自动 source test/smart-wallet-web.env
```

加载顺序（由启动脚本控制）: `shared/secrets.env` 先 source → `<profile>/smart-wallet-{web,job}.env` 后 source 覆盖 host/user/port/JVM。

### 2.4 启动脚本 — 支持新 env 路径

- `scripts/start-smart-wallet-backend.sh`:
  - 默认 profile 从 `prod` 改为 `test`
  - env 查找顺序加入 `${SPRING_PROFILE}/smart-wallet-job.env` 和 `shared/secrets.env`
  - 加载时先 source shared 再 source profile-specific
- `scripts/start-smart-wallet-web.sh`:
  - 同上调整
  - SHARED env 文件从"复用 job env"改成专门的 `shared/secrets.env`

### 2.5 nginx 配置

- `deploy/linux/nginx/smart-wallet-test.conf`:
  - `server_name 49.51.250.174` → `43.139.186.27` (实际 web/前端宿主)
  - 注释说明"job 在另一台机器，nginx 不反代到 job"
- `deploy/linux/nginx/smart-wallet-prod.conf`: prod 暂未上线，保留占位

### 2.6 build 脚本

`scripts/build-env.sh`:
- 注释更新为新拓扑
- 打包逻辑: test/prod 环境额外生成 **两份** tar 包
  - `smart-wallet-test-web.tar.gz` — 推到 43.139.186.27
  - `smart-wallet-test-job.tar.gz` — 推到 49.51.250.174
  - `smart-wallet-test.tar.gz` 整包保留用于兼容
- 部署说明改成"双机部署" + DB 授权 / 防火墙 checklist

`scripts/build-test.sh`: 注释改为新拓扑说明
`scripts/build-prod.sh`: 加 WARNING，提示 prod 暂未上线

### 2.7 前端 .env

- `.env.test`: 注释更新（43.139.186.27 是 web/前端宿主）

---

## 3. 部署前必须配置的外部依赖

### MySQL (49.51.250.174 — DB 跟 job 同机)
```sql
-- root 本机访问 (job 跟 DB 同机)
GRANT ALL ON coin_strategy.* TO 'root'@'127.0.0.1' IDENTIFIED BY '<明文密码>';
-- root 跨机访问 (web from 43.139.186.27)
GRANT ALL ON coin_strategy.* TO 'root'@'43.139.186.27' IDENTIFIED BY '<明文密码>';
FLUSH PRIVILEGES;
```
`/etc/mysql/mysql.conf.d/mysqld.cnf` 里 `bind-address = 0.0.0.0` 或显式监听内网网卡。

### 防火墙
```bash
# 43.139.186.27 (web/前端 宿主)
sudo ufw allow 80/tcp                                          # nginx
sudo ufw deny 8080                                             # web 仅本机 nginx 反代

# 49.51.250.174 (job + DB 宿主)
sudo ufw allow from 43.139.186.27 to any port 3306 proto tcp   # web 跨机访问 MySQL
sudo ufw deny 3306                                             # MySQL 其余来源拒绝
sudo ufw deny 8081                                             # job loopback only
```

### RSA 私钥（解密 ENC 密文）
```bash
# 本地一次性生成
bash scripts/generate-rsa-keys.sh

# 上传到两台机器
for host in 43.139.186.27 49.51.250.174; do
  scp scripts/keys/app-private-local.pem user@$host:/tmp/app-private-local.pem
  ssh user@$host 'sudo install -o root -g ubuntu -m 640 /tmp/app-private-local.pem /opt/coinstrategy/app-private-local.pem'
done
```

---

## 4. 部署流程

```bash
# 1. 本地构建
bash scripts/build-test.sh

# 2. 推 web 包到 43.139.186.27
scp dist/test/smart-wallet-test-web.tar.gz user@43.139.186.27:/tmp/
ssh user@43.139.186.27 'cd /opt/coinstrategy/smart-wallet && tar -xzf /tmp/smart-wallet-test-web.tar.gz && bin/start-web-test.sh'

# 3. 推 job 包到 49.51.250.174
scp dist/test/smart-wallet-test-job.tar.gz user@49.51.250.174:/tmp/
ssh user@49.51.250.174 'cd /opt/coinstrategy/smart-wallet && tar -xzf /tmp/smart-wallet-test-job.tar.gz && bin/start-backend-test.sh'

# 4. 在 43.139.186.27 配置 nginx
ssh user@43.139.186.27 'sudo cp /opt/coinstrategy/smart-wallet/deploy/linux/nginx/smart-wallet-test.conf /etc/nginx/conf.d/smart-wallet.conf && sudo nginx -t && sudo systemctl reload nginx'

# 5. 验证
curl http://43.139.186.27/strategy/api/smart-wallet/health
ssh user@49.51.250.174 'curl http://127.0.0.1:8081/strategy/api/smart-wallet/health'
```

---

## 5. 验收 checklist

- [ ] web 在 43.139.186.27 启动成功，跨机连 49.51.250.174 的 MySQL 通
- [ ] job 在 49.51.250.174 启动成功，本机直连 MySQL 通
- [ ] `curl http://49.51.250.174:8081` 从外部访问失败（loopback only 验证）
- [ ] `curl http://43.139.186.27/strategy/api/smart-wallet/health` 返回 OK
- [ ] 前端页面打开正常，控制台无 404 风暴（如果有 404，说明对应路径以前靠 proxy 透传，需要在 web 端补 controller）
- [ ] job 的 @Scheduled 任务正常打印日志（说明 DB 读写通）

---

## 6. 已知 follow-up

1. **被删的 proxy controllers** 之前覆盖了大量 fall-through 路径，前端会有部分 GET/POST 接口直接 404。需要按 smoke test 结果，逐一在 web 端补本地 controller (直查 DB)。
2. **prod 环境恢复**: 补 `deploy/linux/env/prod/{web,job}.env`，决定 prod 机器分配（如果与 test 同机需要分配不同端口）。
3. **legacy env 文件** `deploy/linux/env/smart-wallet{,-job,-web}.env` 当前作为 fallback 保留。线上 systemd 单元改名 / 改路径后即可删除。

---

<!-- kb-backlink -->
> 本文归属：[架构与平台 知识地图](MOC-架构与平台.md) ｜ [知识库首页](知识库首页.md)
