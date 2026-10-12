# HTX Trend Python — 开发 / 运维 Runbook

> 状态：维护中 | 集成分支：`support_htx_python` | 日期：2026-10-12

## 1. 架构速览

```mermaid
flowchart LR
  subgraph host [Production host]
    systemd[htx-trend-worker@instance]
    env[htx-python.env]
    venv[htx-trend-py-venv]
    rel[releases/current symlink]
  end
  subgraph runtime [Python runtime]
    lw[live_worker.LiveTrendWorker]
    repo[mysql_store.HtxTrendMySqlRepository]
    htx[htx_v5.HtxV5Client]
  end
  db[(MySQL coin_strategy)]
  ex[HTX USDT swap API]
  systemd --> lw
  env --> lw
  rel --> venv
  lw --> repo
  lw --> htx
  repo --> db
  htx --> ex
```

- **单写原则**：同一 account/strategy instance 上 Java 与 Python 不能同时作为 LIVE writer（见 `mysql_store` 模块头注释与 writer lock）。
- **正式 LIVE 路径**：`LiveTrendWorker` + `worker` 扫描 → breadth / admission → 下单；心跳写入 `strategy_runtime_heartbeat`。
- **代码树**：`htx-trend-python/src/htx_trend/`；部署单元见 `deployment/systemd/htx-trend-worker@.service`。

## 2. 部署：制品 tar + SHA（禁止生产 git pull）

运维主机 **不应** 依赖 `git pull` 更新策略运行时。标准流程：

1. 从制品库或 Cursor agent artifacts 取得 `htx-trend-python-0.1.1-live-<shortsha>.tar.gz` 与校验文件。
2. `sha256sum -c <file>.sha256` 或 `SHA256SUMS`。
3. 解压到 `/opt/coinstrategy/htx-trend-py/releases/<version>/`，更新 `current` 符号链接。
4. venv：`pip install --require-hashes -r requirements.lock`（与 `scripts/package.py` 验证布局一致）。
5. `systemctl restart htx-trend-worker@<instance>`；检查 `strategy_runtime_heartbeat.status`。

构建（维护者，在已合入的 commit 上）：

```bash
cd htx-trend-python
SKIP_PACKAGE_VERIFY=1 python3 scripts/package.py --version 0.1.1-live --branch support_htx_python
```

## 3. LIVE 热修链（cursor lineage → integration）

下列 commit 来自 LIVE 快速交付线，**合入 `support_htx_python` 时应 cherry-pick 窄集**，不要整支 merge cursor 大包：

| 顺序 | Commit | 说明 |
|------|--------|------|
| 1 | `982e5867` | 正式 LIVE 包守卫与 handoff（0.1.1-live 基线功能） |
| 2 | `04a03417` | `from_env`：`defaults=cls()`，修复 slots 描述符崩溃 |
| 3 | `05395708` | `LIVE_HOTFIX_FROM_ENV.md` 运维说明 |
| 4 | `b5af1219` | `latest_breadth_stats` PyMySQL `LIKE` → `%%` |

在 `support_htx_python` 上的推荐操作（与 PR#23 一致）：

```bash
git fetch origin support_htx_python
git checkout -B cursor/pymysql-breadth-like-escape-38f9 origin/support_htx_python
git cherry-pick 04a03417 05395708 b5af1219
# CHANGELOG.md 若与 integration 冲突：保持 integration 无 CHANGELOG，仅保留代码+docs
git push --force-with-lease origin cursor/pymysql-breadth-like-escape-38f9
```

**注意**：cherry-pick 后 commit SHA 会变（例如 breadth 修复 tip 可能为 `d906780f`），以 Git 为准；语义与 `b5af1219` 相同。

## 4. 合入 `support_htx_python`

1. PR **base 必须为 `support_htx_python`**（不是 `master`）。
2. Diff 应仅含热修与文档（个位数文件、数百行级）。
3. CI / 本地 pytest 通过后合并；合并后 `support_htx_python` tip 即为下一制品构建源。
4. **PR#11**（research MVP）与 LIVE 热修 **无合并顺序硬依赖**；可并行评审，冲突时以 integration tip 为准 rebase。

## 5. 技术债（跟踪）

| 项 | 影响 | 建议 |
|----|------|------|
| `htx-python.env.example` 不完整 | 运维漏配 `HTX_TREND_COMBINATION_ENTRY_ENABLED` 等导致启动失败 | 补 example + `test_config_from_env` 扩展 |
| integration 无统一 `CHANGELOG.md` | 热修可追溯性 | 用 `docs/htx-trend-python/` + 包内 `LIVE_HOTFIX_*.md` 直至统一 |
| LIVE lineage 与 research 版本号 | 制品命名混淆 | 单次对齐 `_version.py` 与 `package.py --version` |
| Breadth SQL 与 Java mapper 双份 | 漂移风险 | 已有 parity 测试；考虑共享 SQL 片段或 codegen |
| 大包 cursor 分支历史 | PR 噪音 | 禁止直接 merge；仅 cherry-pick |

## 6. 本地验证（热修相关）

```bash
cd htx-trend-python
pip install -e ".[test]"
pytest tests/test_config_from_env.py tests/test_live_entry_parity.py tests/test_mysql_store_platform.py -q
```
