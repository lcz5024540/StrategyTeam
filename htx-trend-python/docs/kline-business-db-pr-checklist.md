# K1–K5 review checklist

approval=SYNTHETIC。Tip：`<提交后以 feat/kline-business-db-persist 的 git rev-parse HEAD 为准>`。
基线：273bf19b9896c18cc73c2713331ba41563bfa5cb；仅目标分支开发，不 merge。
锁定 BTC-USDT / ETH-USDT × **15min、60min、4hour、1day**。

- [x] K1：`src/htx_trend/runtime_kline_business.py` + `research/collector.py` + `cli.py`；
  默认 off、COMPLETE gate、schema 字段、hash 复用、幂等/修订、失败重试和缓存补投。
- [x] K2：`docs/kline-business-db-health.md` + business_kline_health 冻结时钟测试。
- [x] K3：`src/htx_trend/runtime_kline_backfill.py` + CLI；dry-run 无连接、全 host 显式确认、
  闭合边界/上海日线、mock 分页和重跑幂等测试。
- [x] K4：`docs/kline-api-vs-business-db.md`；API 判单 / 库审计分界及历史双路径。
- [x] K5：本 checklist；测试记录位于仓外指定日志目录。

验证：`tests/test_research_collector.py`、`tests/test_kline_business_db.py`。
日志：`/Users/jenniferni/jenifer/build/codex-logs/kline-business-db-20261019/python-tests.log`。
使用本机已有 Python 3.11；不安装任何 collector 或 worker。
完整验证命令（在 htx-trend-python 下）：
`/opt/homebrew/bin/python3.11 -m pytest tests/test_research_collector.py tests/test_kline_business_db*.py tests/test_market_data.py tests/test_research_store.py -q`。
结果：25 passed；git diff --check 通过。

硬边界声明：**未装现网 / 未跑现网回填**；未连接生产 MySQL，未做现网 DDL/DML；
未改 Dashboard nginx 或 43.139 / 49.51 主机；未混入 PAPER-UI、PAPER-DB F-A、client_order_id；
未改 paper_clock、未恢复 patrol、未签真实 admission；未改 LIVE/PAPER 判单热路径。
未创建并行业务表，未改 hash；未制作 frozen 安装包、未输出安装命令或装机口令。
本提交只证明本地 fixture 行为，不证明部署、生产数据库持续增长或生产准入。
