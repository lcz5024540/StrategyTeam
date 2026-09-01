# HTX 运行报告与证据归档

本目录保存 CoinStrategy HTX 策略在 ray-agent harness 运行期间形成的非敏感报告、脱敏 API 证据和故障日志。文件随 CoinStrategy 独立 Git 仓库管理。

## 文件清单

- `htx-spot-trading-pnl-report-20260809.md`：HTX 现货交易盈亏报告。
- `htx-contract-openapi-error-report-20260812.md`：U 本位合约 OpenAPI 6023 错误报告。
- `htx-contract-service-errors-20260812.log`：对应的服务错误日志。
- `htx-contract-api-evidence-20260812.json`：脱敏 API 请求与响应证据。
- `htx-contract-host-matrix-20260812.json`：合约 API 多域名验证矩阵。
- `htx-xaut-contract-trading-volume-report-20260816.md`：XAUT 合约成交量与损耗报告。
- `htx-moving-grid-hedge-degraded-runbook.md`：双向移动网格 `HEDGE_DEGRADED` 人工介入与影子盘开关 runbook。
- `htx-moving-grid-shadow-m8-1-runbook.md`：方案 C M8.1 影子盘第 1-3 天启动、日报、停止门与验收 runbook。
- `htx-moving-grid-shadow-m8-1-gates.drawio`：M8.1 授权、影子配置、观察、停机与进入 M8.2 的门禁流程图。

业务源码根目录为 `docs/projects/CoinStrategy/`；ray-agent 不再维护大小写不同的 `docs/projects/coin-strategy/` 管理目录。
