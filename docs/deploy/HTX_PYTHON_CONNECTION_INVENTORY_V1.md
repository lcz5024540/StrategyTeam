# HTX Python 服务器与数据连接信息清单 V1

这份清单用于整理部署信息，不保存密码、API secret 或私钥正文。实际值放入主机权限为 `0600` 的环境文件或现有密钥管理系统；仓库只保留 [`../../htx-trend-python/deployment/htx-python.env.example`](../../htx-trend-python/deployment/htx-python.env.example)。

## 服务器

以下状态于 2026-10-11 从目标机只读复核：

| 字段 | 待整理值 |
|---|---|
| 环境/用途 | 当前 HTX 服务主机 |
| 地址 / 主机名 | `49.51.250.174` / `VM-0-11-ubuntu` |
| SSH 用户 | `ubuntu` |
| SSH 端口 | `22` |
| 认证方式/密钥文件位置 | SSH key：`/Users/jenniferni/jenifer/ray/ChainStrategyPrime.pem` |
| 当前应用目录 | `/opt/coinstrategy/htx-trade` |
| 当前 systemd 单元 | `coin-strategy-htx.service`，复核时为 `active` |
| Python systemd 实例名 | 待部署时确定 |
| evidence 持久化目录 | `/var/lib/coinstrategy/htx-python-evidence`（建议） |
| 日志入口 | `journalctl -u coin-strategy-htx.service`；Python unit 部署后独立查看 |

## MySQL

| 环境变量 | 含义 | 待整理值 |
|---|---|---|
| `COINSTRATEGY_DB_HOST` | 数据库地址 | `49.51.250.174` |
| `COINSTRATEGY_DB_PORT` | 端口 | `3306` |
| `COINSTRATEGY_DB_DATABASE` | schema | `htx_trade_strategy` |
| `COINSTRATEGY_DB_USER` | 当前用户 | `root` |
| `COINSTRATEGY_DB_PASSWORD` | 密码 | 只进入主机 secret，不写本文 |
| `APP_RSA_PRIVATE_KEY_PATH` | 解密 `ENC(...)` 账户凭据的私钥路径 | 待部署时填写，仅写路径 |

MySQL 负责运行实例、账户、订单、成交、仓位和绩效事实。原始行情大对象保存在文件 CAS；`htx_python_evidence` 只保存对象 URI及可查询的小型 manifest/trace。

远端现有 Java 环境文件使用 `HTX_DB_HOST/PORT/USERNAME/PASSWORD`；Python 读取 `COINSTRATEGY_DB_HOST/PORT/DATABASE/USER/PASSWORD`。部署文件必须显式完成这组名称映射。2026-10-11 已用远端环境完成只读 `SELECT DATABASE(), CURRENT_USER(), @@hostname, @@port`，返回 `htx_trade_strategy / root@% / VM-0-11-ubuntu / 3306`。密码未输出。长期应为 Python worker 建立最小权限用户，当前连接事实仍记录为 `root@%`。

## HTX 与准入

| 环境变量 | 用途 |
|---|---|
| `HTX_PUBLIC_BASE_URL` | 公共行情 API，默认 `https://api.hbdm.com` |
| `HTX_TREND_CONTRACTS` | 运行品种清单 |
| `HTX_PYTHON_EVIDENCE_DIR` | 内容寻址证据根目录，必须位于持久盘 |
| `HTX_TREND_STRATEGY_ADMISSION_PATH` | 非 V1 策略的 sealed admission 文件 |

HTX 账户 access/secret 不放在环境模板中。worker 通过 account id 从 MySQL 读取现有加密记录并在内存解密。

## 到机后核验顺序

1. SSH 只读核对主机、目录、磁盘空间、Python 版本和 systemd unit，不先启动 worker。
2. 用环境文件建立 MySQL 连接，执行 `SELECT 1` 和运行表只读检查。
3. 执行一次 `collect-tick`，确认 CAS、SQLite index、fsync 和 manifest 可写。
4. 执行 `verify-evidence` 验证引用闭包。
5. OBSERVE 启动后核对 heartbeat、决策 trace 和无订单事实。
6. PAPER 与 LIVE 分别按 admission 作用面验收；不能用 OBSERVE 成功替代 PAPER/LIVE 准入。
