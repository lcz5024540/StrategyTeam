# HTX Trend Python — 文档索引

> 状态：维护中 | 集成分支：`support_htx_python` | 更新：2026-10-12

本目录是 **CoinStrategy 仓库根** 下 HTX Python 趋势运行时（`htx-trend-python/`）的对外文档入口。实现代码与单包文档仍在 `htx-trend-python/` 子树内。

## 读者导航

| 文档 | 面向角色 | 内容 |
|------|----------|------|
| [ROLE-TODOS.md](./ROLE-TODOS.md) | 全员 | 按角色划分的待办与责任边界 |
| [DEV-runbook.md](./DEV-runbook.md) | 策略开发、运维 | 架构、制品部署、热修链、合入 `support_htx_python`、技术债 |
| [LIVE-acceptance.md](./LIVE-acceptance.md) | 策略产品 | 正式 LIVE 验收口径（产品主笔） |

## 包内运维速查（热修细节）

| 主题 | 路径 |
|------|------|
| `from_env` slots P0 | `htx-trend-python/docs/LIVE_HOTFIX_FROM_ENV.md` |
| PyMySQL `LIKE` `%` P0 | `htx-trend-python/docs/LIVE_HOTFIX_BREADTH_PYMYSQL.md` |
| 正式 LIVE 验收（包内） | `htx-trend-python/FORMAL_LIVE_ACCEPTANCE.md` |
| 环境模板 | `htx-trend-python/deployment/htx-python.env.example` |

## 相关 PR / 分支

- 集成长期分支：**`support_htx_python`**（非 `master`）
- LIVE 热修窄 PR：[PR#23](https://github.com/lcz5024540/CoinStrategy/pull/23) → `support_htx_python`
- Research MVP：[PR#11](https://github.com/lcz5024540/CoinStrategy/pull/11) → `support_htx_python`（与 LIVE 热修无硬依赖）
