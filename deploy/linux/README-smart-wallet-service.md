# Smart Wallet Linux 制品部署说明

> 新拓扑（test 环境）— prod 暂时取消。详见 `docs/deploy-topology-refactor.md`。

服务器原则:
- 服务器不拉代码
- 服务器不做 Maven / Node 构建
- 服务器只接收上传的 `job / web jar` 和前端 `dist`
- **web 与 job 跨机部署，互不依赖 HTTP，各自连同一个 MySQL**

## 1. 拓扑

| 机器 | 部署服务 | DB 主机 (该机视角) | DB 用户 | 端口 |
|------|----------|---------|---------|------|
| `43.139.186.27` | web + 前端 + MySQL | `127.0.0.1` | `devuser` | nginx 80（对外）、web 8080（loopback） |
| `49.51.250.174` | job | `43.139.186.27`（远程） | `devuser` | 8081（**loopback only**） |

外部流量入口：`http://43.139.186.27/` → nginx → `127.0.0.1:8080` (web) → 直查 MySQL。

job 完全不对外，前端永远不会触达 job。

## 2. 部署目录

两台机器都用同一个根目录：

```bash
/opt/coinstrategy/smart-wallet/
├── backend/app.jar              # 仅 job 机器有
├── web/app.jar                  # 仅 web 机器有
├── frontend/dist/               # 仅 web 机器有
├── bin/
│   ├── start-smart-wallet-backend.sh
│   ├── start-smart-wallet-web.sh
│   ├── start-backend-test.sh
│   ├── start-web-test.sh
│   ├── install-smart-wallet-artifacts.sh
│   └── install-smart-wallet-linux-service.sh
├── runtime/                     # 仅 job 机器需要 (Polymarket Python bridge)
│   ├── polymarket_clob_client.py
│   └── polymarket_http.py
├── deploy/linux/env/            # env 文件 (按机器挑用)
│   ├── shared/secrets.env       # 两台机器都用
│   └── test/
│       ├── smart-wallet-web.env # 43.139.186.27 用
│       └── smart-wallet-job.env # 49.51.250.174 用
└── logs/
```

## 3. 一次性安装

两台机器都需要：

- `JDK 17`
- `python3 / pip3`（job 机器若启用 Polymarket LIVE 还要 `pip3 install py-clob-client`）

仅 web 机器（43.139.186.27）额外需要：
- `nginx`
- `MySQL`（如果 DB 和 web 部署在同一台）

`Maven` / `Node.js` / `npm` 仅在本地或 CI 构建机需要。

## 4. 配置原则

主原则：
- `application-test.yml`（job + web 各一份）放默认配置
- env 文件按机器拆分：
  - `deploy/linux/env/shared/secrets.env` — 跨环境共享：RSA 私钥路径、`DB_PASSWORD` 密文、Polymarket 密钥
  - `deploy/linux/env/test/smart-wallet-web.env` — web 机器专属：`DB_HOST=127.0.0.1`、`DB_USERNAME=devuser`、JVM
  - `deploy/linux/env/test/smart-wallet-job.env` — job 机器专属：`DB_HOST=43.139.186.27`、`DB_USERNAME=devuser`、JVM
- 启动脚本会先 source `shared/secrets.env`，再 source 机器专属 env。

服务器上 **不应改源码里的 `application-test.yml`**，全部通过 env 文件覆盖。

可选：把 env 放到 `/etc/coinstrategy/<profile>/` 下，由 systemd 直接引用，避免 jar 升级时被覆盖。

## 5. 安全边界

1. **web 仅监听 `127.0.0.1:8080`** — 外网无法直接访问
2. **job 仅监听 `127.0.0.1:8081`** — 任何外部 IP（含 web 机器）都打不到 job
3. **web 与 job 之间没有 HTTP 调用**（新架构核心变化）
4. **外网只开放 `43.139.186.27:80`**（nginx）和必要的 SSH
5. **MySQL `3306` 仅放行**：`127.0.0.1`（web 本机）和 `49.51.250.174`（job 跨机访问）
6. systemd 服务用 `ubuntu` 用户运行；加固项：`NoNewPrivileges`、`ProtectSystem`、`PrivateTmp`

## 6. MySQL 授权（在 43.139.186.27 上执行）

```sql
-- web 本机访问
CREATE USER IF NOT EXISTS 'devuser'@'127.0.0.1' IDENTIFIED BY '<明文密码>';
GRANT ALL ON coin_strategy.* TO 'devuser'@'127.0.0.1';

-- job 跨机访问
CREATE USER IF NOT EXISTS 'devuser'@'49.51.250.174' IDENTIFIED BY '<明文密码>';
GRANT ALL ON coin_strategy.* TO 'devuser'@'49.51.250.174';

FLUSH PRIVILEGES;
```

`/etc/mysql/mysql.conf.d/mysqld.cnf`:
```
bind-address = 0.0.0.0
```

## 7. 防火墙

```bash
# 43.139.186.27 (web/前端/DB 宿主)
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow from 49.51.250.174 to any port 3306 proto tcp
sudo ufw deny 3306/tcp
sudo ufw deny 8080/tcp
sudo ufw enable

# 49.51.250.174 (job 宿主)
sudo ufw allow 22/tcp
sudo ufw deny 8081/tcp
sudo ufw enable
```

## 8. RSA 私钥分发

```bash
# 本地一次性生成
bash scripts/generate-rsa-keys.sh

# 上传到两台机器
for host in 43.139.186.27 49.51.250.174; do
  scp scripts/keys/app-private-local.pem ubuntu@$host:/tmp/app-private-local.pem
  ssh ubuntu@$host 'sudo install -o root -g ubuntu -m 640 /tmp/app-private-local.pem /opt/coinstrategy/app-private-local.pem'
done
```

## 9. 部署流程（test 环境）

```bash
# 本地构建
bash scripts/build-test.sh

# 推 web 包到 43.139.186.27
scp dist/test/smart-wallet-test-web.tar.gz ubuntu@43.139.186.27:/tmp/
ssh ubuntu@43.139.186.27 <<'EOF'
  sudo mkdir -p /opt/coinstrategy/smart-wallet
  sudo tar -xzf /tmp/smart-wallet-test-web.tar.gz -C /opt/coinstrategy/smart-wallet
  sudo chown -R ubuntu:ubuntu /opt/coinstrategy/smart-wallet
  cd /opt/coinstrategy/smart-wallet
  bin/start-web-test.sh
  sudo cp deploy/linux/nginx/smart-wallet-test.conf /etc/nginx/conf.d/smart-wallet.conf
  sudo nginx -t && sudo systemctl reload nginx
EOF

# 推 job 包到 49.51.250.174
scp dist/test/smart-wallet-test-job.tar.gz ubuntu@49.51.250.174:/tmp/
ssh ubuntu@49.51.250.174 <<'EOF'
  sudo mkdir -p /opt/coinstrategy/smart-wallet
  sudo tar -xzf /tmp/smart-wallet-test-job.tar.gz -C /opt/coinstrategy/smart-wallet
  sudo chown -R ubuntu:ubuntu /opt/coinstrategy/smart-wallet
  cd /opt/coinstrategy/smart-wallet
  bin/start-backend-test.sh
EOF
```

## 10. 验证

```bash
# 从外网访问 web（应通）
curl http://43.139.186.27/strategy/api/smart-wallet/health

# 从外网访问 job（应失败 / connection refused / 超时）
curl --max-time 3 http://49.51.250.174:8081/strategy/api/smart-wallet/health

# 在 job 机器本地访问 job（应通）
ssh ubuntu@49.51.250.174 'curl http://127.0.0.1:8081/strategy/api/smart-wallet/health'

# 在 job 机器上验证它能连到远程 DB
ssh ubuntu@49.51.250.174 'mysql -h 43.139.186.27 -u devuser -p coin_strategy -e "SELECT 1"'

# 前端首页
open http://43.139.186.27/
```

## 11. 已知影响

新架构下 **web 不再透传请求给 job**。前端任何依赖 `JobApiProxyController` / `SmartWalletReadProxyController` / `SmartWalletWriteProxyController` fall-through 的接口会 404。需要在 web 端补本地 controller（直查 DB），具体哪些接口请按 smoke test 结果逐一处理。
