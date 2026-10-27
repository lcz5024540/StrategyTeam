# 扩池 C 白名单交审 NOTES

## 绑定校验

| 文档 | SHA256 | 结果 |
|------|--------|------|
| 开发口令 | `b962382f9b2277892450dd35308241e27bf86e44b8953116b1d47fdbba1c663b` | 匹配 |
| 产品盖章 | `1168ac4e140c87c1b0c77bddbb920ca16fb201b50b51bd8c571c3844a26a9230` | 匹配 |
| 基线 | `1327779bbea262ab2ab2b979fa2188dba5abd910`（C1） | - |

## 权威配置

- **环境变量名**：`HTX_PAPER_CONTRACTS`
- **15m 与 4H 共用同一名单**（同一 env 名，同一逗号分隔字符串）
- **合约数量**：恰好 83，顺序固定，**不含 HYPE-USDT**
- 读取落点：`htx_trend_python/src/htx_trend/paper_worker.py` → `PaperWorkerConfig.from_env`

## 审计结论

1. **无硬编码覆盖 env**：`PaperWorkerConfig.from_env()` 使用 `os.getenv('HTX_PAPER_CONTRACTS', 'BTC-USDT,ETH-USDT')`；默认值仅为启动兜底，env 设置生效。
2. **无静默截断**：解析逻辑为 `tuple(dict.fromkeys(x.strip().upper() for x in value.split(',') if x.strip()))`，无长度上限、无前 N 截断，83 个全部保留且去重保序。
3. **正则允许股票/指数名称**：`re.fullmatch(r'[A-Z0-9]+-USDT', ...)` 可接受 `SPX500-USDT`、`NASDAQ100-USDT`、`ACNSTOCK-USDT`、`BRKB-USDT` 等。
4. **单合约失败可跳过**：`PaperTrendWorker.tick()` 现在对每个合约做 `copy.deepcopy` 快照；任意行情/元数据/指标失败时回滚该合约的 ledger 变更、记录 `contract:ExceptionType` 到 skipped 列表、打 `WARNING` 日志并继续处理后续合约。
   - skip 日志字段：`PAPER skipped contract <合约>: <原因>`（`exc_info=True` 带堆栈）
   - health detail 字段：`skipped=BTC-USDT:RuntimeError,UNKNOWN-USDT:ValueError`
   - 单合约失败不会导致整轮 rollback 或 UNHEALTHY。
5. **未做**：股票时段/熔断/休市、maker 冻结表、LIVE、admission、HYPE、仓位/保证金上限口径。

## 代码改动

- `src/htx_trend/paper_worker.py`
  - `import copy`
  - `PaperTrendWorker.tick`：per-contract 快照 + try/except skip + 健康状态 DEGRADED/HEALTHY
- `tests/test_paper_worker.py`
  - 新增 83 名单 env 解析测试
  - 新增股票/指数符号正则测试
  - 新增单合约失败跳过测试
  - 将旧的整轮失败测试改为 per-contract skip + retry
- `deployment/paper-4h/htx-python-paper-4h.env.draft`
  - 更新 `HTX_PAPER_CONTRACTS` 为 83 名单

## pytest

```
556 passed in 10.23s
```

## 交付状态

- [x] 代码改动 + pytest 全绿
- [x] commit + push + 开 PR
- [ ] merge（本任务禁止）
- [ ] 出 PAPER 包（本任务禁止）
- [ ] 装机热更（本任务禁止；需运维口令 + 产品「可以热更」）
- [ ] 动 LIVE（本任务禁止）
