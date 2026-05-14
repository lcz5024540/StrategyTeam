# Priority 5 实施：单机 + nginx 部署（贴现有部署体系）

> 你的部署体系已经支撑同机双服务（web + job），nginx 反代 web、不暴露 job。
> Priority 5 网络层硬化只需要 **3 项小改动 + 几条验收命令**，不动 build/deploy 脚本结构。

---

## 一、现状盘点（已就位的部分，**不需要改**）

| 项 | 现状文件 | 评价 |
|---|---|---|
| nginx 反代配置 | `deploy/linux/nginx/smart-wallet-prod.conf` | 已正确反代 127.0.0.1:8080，不直连 8081 |
| job 环境变量 | `deploy/linux/env/smart-wallet-job.env` | `SERVER_ADDRESS="127.0.0.1"` 已设置 |
| web 环境变量 | `deploy/linux/env/smart-wallet-web.env` | `SERVER_ADDRESS="127.0.0.1"` + `DASHBOARD_JOB_BASE_URL="http://127.0.0.1:8081"` 已设置 |
| 启动脚本传 SERVER_ADDRESS | `scripts/start-smart-wallet-{backend,web}.sh` | 通过 env export → Spring 自动映射 `server.address` |
| restart 脚本 | `deploy/linux/bin/restart-smart-wallet-{backend,web}.sh` | 独立端口/Service 区分，无冲突 |
| systemd templates | `deploy/linux/systemd/smart-wallet-{backend,web}.service.template` | 一台机器跑两个 service 互不影响 |
| nginx 日志轮转 | `deploy/linux/nginx/logrotate-smart-wallet.conf` | 已配 daily / rotate 30 |

→ **build-env.sh、restart 脚本、systemd template、nginx config、env 分离全都不动。**

---

## 二、需要的 3 项改动（本次已落地）

### 改动 1：`coin-strategy-job/src/main/resources/application-prod.yml`
原 `server.address: 0.0.0.0` → `127.0.0.1`

### 改动 2：`coin-strategy-job/src/main/resources/application.yml`
加默认 `server.address: ${JOB_SERVER_ADDRESS:127.0.0.1}`，避免新 profile 漏配置时回到 0.0.0.0。

### 改动 3：`deploy/linux/env/smart-wallet-prod.env`
`SERVER_ADDRESS="0.0.0.0"` → `127.0.0.1`（这个文件实际部署可能不被加载，但保留 0.0.0.0 是定时炸弹，统一改了）。

---

## 三、Java 日志轮转

业务日志走 logback 自己滚动（`coin-strategy-job/src/main/resources/logback-spring.xml` + web 同名文件），按天 + 保留 30 天 + 总大小上限 + 启动清理过期归档。启动脚本里 `nohup` 的 stdout 重定向到 `/dev/null`，stderr 写到小文件，因此**不需要** OS 层 logrotate。

如未来流量上去需要兜底，可参考 `copytruncate` 模式给 `*-stderr.log` 加 logrotate（Java 应用不响应 SIGHUP，必须 `copytruncate` 而非 `kill -USR1`）。

---

## 四、部署流程对照（你现有的流程，不变）

1. **打包**：
   ```bash
   ./scripts/build-test.sh    # 测试环境
   ./scripts/build-prod.sh    # 生产环境
   ```
   产物：`dist/<env>/smart-wallet-<env>.tar.gz`

2. **上传到服务器**：
   ```bash
   scp dist/prod/smart-wallet-prod.tar.gz user@server:/tmp/
   ```

3. **服务器解压**：
   ```bash
   cd /opt/coinstrategy/smart-wallet
   sudo tar -xzf /tmp/smart-wallet-prod.tar.gz --strip-components=0
   sudo chown -R coinstrategy:coinstrategy /opt/coinstrategy/smart-wallet
   ```

4. **首次安装 systemd service**（仅一次）：
   ```bash
   sudo /opt/coinstrategy/smart-wallet/bin/install-smart-wallet-linux-service.sh prod
   sudo systemctl enable smart-wallet-backend
   sudo systemctl enable smart-wallet-web
   ```

5. **启停**（用现有 restart 脚本，**不变**）：
   ```bash
   # 重启 job
   sudo /opt/coinstrategy/smart-wallet/deploy/linux/bin/restart-smart-wallet-backend.sh

   # 重启 web
   sudo /opt/coinstrategy/smart-wallet/deploy/linux/bin/restart-smart-wallet-web.sh
   ```

6. **首次配置 nginx**（仅一次）：
   ```bash
   sudo cp /opt/coinstrategy/smart-wallet/deploy/linux/nginx/smart-wallet-prod.conf \
           /etc/nginx/conf.d/smart-wallet.conf
   # 编辑 server_name 替换 __SERVER_IP__
   sudo sed -i 's/__SERVER_IP__/api.your-domain.com/g' /etc/nginx/conf.d/smart-wallet.conf
   sudo nginx -t && sudo systemctl reload nginx
   ```

7. **配置 nginx 日志 logrotate**（仅一次，可选；自用阶段不部署也行）：
   ```bash
   sudo cp /opt/coinstrategy/smart-wallet/deploy/linux/nginx/logrotate-smart-wallet.conf \
           /etc/logrotate.d/smart-wallet
   ```
   Java 业务日志走 logback 自滚动，不需要 OS logrotate。

---

## 五、本次 Priority 5 上线后必须做的验收

部署 + 重启后，从**服务器外部**测试：

```bash
# ✅ 期望成功（200）
curl -i http://api.your-domain.com/strategy/api/smart-wallet/health

# ❌ 期望失败（Connection refused 或 timeout）—— 关键验收点
curl -i --connect-timeout 5 http://your-server-ip:8081/strategy/api/smart-wallet/health
curl -i --connect-timeout 5 http://your-server-ip:8080/strategy/api/smart-wallet/health

# ❌ 期望失败（404）—— Priority 4 禁单生效
curl -i http://api.your-domain.com/strategy/api/smart-wallet/wallets -X POST
curl -i http://api.your-domain.com/strategy/api/smart-wallet/config/baseline-model -X POST -d '{}'
curl -i 'http://api.your-domain.com/strategy/api/smart-wallet/orders/diagnostics?ids=1'
```

在**服务器本机**（ssh 进去）：

```bash
# ✅ loopback 期望成功
curl http://127.0.0.1:8081/strategy/api/smart-wallet/health
curl http://127.0.0.1:8080/strategy/api/smart-wallet/health

# 监听地址
ss -lntp | grep -E '8080|8081'
# 期望:
#   LISTEN 0  100  127.0.0.1:8080  ...
#   LISTEN 0  100  127.0.0.1:8081  ...
```

---

## 六、可选：补 HTTPS（nginx ssl）

当前 `smart-wallet-prod.conf` 只配了 80 端口。如果你要上 HTTPS，把 server 块改成：

```nginx
server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$host$request_uri;        # 强跳 HTTPS
}

server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate     /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;

    # ↓ 剩下的 location / 配置照搬 smart-wallet-prod.conf
}
```

申请证书：

```bash
sudo certbot --nginx -d your-domain.com
```

certbot 会自动改 nginx 配置。

---

## 七、可选：防火墙双保险

即使 job 监听了 loopback，加 OS 层防火墙是良好实践。

**Ubuntu / Debian**：

```bash
sudo ufw allow ssh
sudo ufw allow 'Nginx Full'         # 80 + 443
sudo ufw deny 8080/tcp              # 显式拒绝外部访问 8080
sudo ufw deny 8081/tcp              # 显式拒绝外部访问 8081
sudo ufw enable
```

**CentOS / Rocky**：

```bash
sudo firewall-cmd --zone=public --add-service=https --permanent
sudo firewall-cmd --zone=public --add-service=http --permanent
sudo firewall-cmd --zone=public --add-rich-rule='rule family="ipv4" port port="8080" protocol="tcp" reject' --permanent
sudo firewall-cmd --zone=public --add-rich-rule='rule family="ipv4" port port="8081" protocol="tcp" reject' --permanent
sudo firewall-cmd --reload
```

---

## 八、回滚

如果上线后 web 调不到 job：

1. **检查 job 监听地址**：
   ```bash
   ss -lntp | grep 8081
   ```
   期望 `127.0.0.1:8081`。如果是 `0.0.0.0:8081`，说明 application.yml 改动没生效（可能 jar 还是旧版）。

2. **检查 web → job 连通**：
   ```bash
   curl http://127.0.0.1:8081/strategy/api/smart-wallet/health
   ```
   如果失败，job 没正常启动，看日志。

3. **紧急回滚**（仅应急用）：
   ```bash
   # 临时让 job 监听所有网卡
   sudo systemctl set-environment JOB_SERVER_ADDRESS=0.0.0.0
   sudo systemctl restart smart-wallet-backend
   ```
   排查完事后**必须**清掉环境变量，不能长期 0.0.0.0。

---

## 九、三层防御全景

| 层 | 位置 | 作用 |
|---|---|---|
| **代码层** | `SmartWalletReadProxyController` / `SmartWalletWriteProxyController` 加 INTERNAL_ONLY 禁单 | web 端拦截不该转发的路径，直接 404 |
| **应用层** | `server.address: 127.0.0.1` | job 进程不接受外部连接 |
| **网络层** | nginx 只暴露 web 路径 + 防火墙 deny 8080/8081 | OS 级别再加一道 |

任一层失守，下一层兜底。

---

## 十、本次 Priority 5 代码变更摘要

| 文件 | 改动 |
|---|---|
| `coin-strategy-job/src/main/resources/application-prod.yml` | `server.address: 0.0.0.0` → `127.0.0.1` |
| `coin-strategy-job/src/main/resources/application.yml` | 加 `server.address: ${JOB_SERVER_ADDRESS:127.0.0.1}` 默认 |
| `deploy/linux/env/smart-wallet-prod.env` | `SERVER_ADDRESS="0.0.0.0"` → `127.0.0.1`（防误用）|

build-env.sh、build-prod.sh、build-test.sh、restart-*.sh、systemd template、nginx prod conf 全部**不需要改动**。

打包流程不变：`./scripts/build-prod.sh` → 上传 tar.gz → 解压 → restart。新版 jar 自然带上 application-prod.yml 的 loopback 配置。
