# ORDERPX-PERSIST 交审

仅编码；基线 `origin/support_htx_python@3fb573c1bce46039a53973d3cf4f4f30d52f02d0`。
分支 `feature/orderpx-persist-20261022`。脚本已合仓、生产未执行：这里的合仓指随本分支提交，不表示 PR 已 merge。

## 迁移产物与运行边界

- MySQL：`htx-trend-python/sql/2026-10-22__orderpx_nullable_columns.sql`
- SQLite：`htx-trend-python/sql/2026-10-22__sqlite_orderpx_nullable_columns.sql`
- 两份脚本首行均为「未执行；执行须另批装机口令 / DO NOT RUN ON PROD」，含回滚说明，无历史回填 DML。
- 同步 SQLite / MySQL CREATE 基线；字段全部可空，无计数默认 0；source 有三值 CHECK，写入层也验证价格/source 配对。
- ADD COLUMN 不具跨 MySQL/SQLite 通用的 IF NOT EXISTS 语法；另批执行前须逐列检查现有 schema，不能盲目重跑。此轮未运行这两份脚本，未验证生产 DDL 锁表或部署兼容性。
- 新写代码依赖新列，不可直接用于旧生产 schema。没有启动迁移或自动 schema 修复。

## 写路径

- `order_px_fields.py`：纯函数仅接 order_type、limit、mid、last；有效正价优先 limit（限价单）或 mid/last（市价单）。缺失整组 NULL，不接 signal/fill/average/desired_entry。
- `TrendOrderIntent` / `mysql_store.record_order_intent`：七个可空字段显式 INSERT；MySQL 时间转换为 UTC DATETIME，未知保留 NULL。
- `ledger.Order` / `TrendLedger.record_order`：七字段、显式列名 INSERT。
- `execution_service`：既有 ENTRY intent 写入候选信号价/候选 bar 时间、现有盘口 mid；不改变 request、gate、policy、sizing 或风险判定。
- `position_manager`：把已有 book mid 传入 CLOSE / PARTIAL_CLOSE；直接调用而无报价时 NULL。未修改止损或退出决策。
- `mirror`：ENTRY / reduction 使用镜像调用现有盘口，不使用主单成交价；信号缺失保持 NULL。
- LIVE `order_ts` 在执行器 place 调用前采集，随既有 accepted/unknown/rejected 状态写入落库；intent 阶段为 NULL。表示本地送单调用时间，不声称是交易所收到请求时间。若进程在调用后、状态写入前退出，此列可能仍为 NULL，不后补猜测时间。
- `PaperExchange.submit/submit_next_event`：构建 Order 时记录实际本地提交调用时间，显式接收信号与 mid/last，绝不从 fill candle 取得市价。回放事件时间仍在原 created_at/filled_at 中。
- `runtime`：仅在构建 PaperOrder 时传已有信号和盘口；`WorkerPaperExchange` 在已有真实 book 时把 mid 带入订单，平仓同样覆盖。无盘口的模拟平仓不把 exit_price 作为 last。
- `paper_db_persist`：JSON 新订单字段原样映射；旧 JSON 缺字段保持 NULL；既有 INSERT IGNORE 保留历史行，未添加 UPDATE 回填。
- cancel_count / amend_count 均默认 None；未从订单状态推断撤改次数。

## 只读导出

`load_ledger_pnl_export` 支持可选 `orders.json` 的新列，映射 signal_px / order_px / order_ts 至分析既有名称，保留 source、signal_ts、数量和计数。优先用 client_order_id / order_id 匹配同持仓同腿；无 ID 时仅唯一订单可匹配，多个候选不猜测。

未发现本链路已有可直接扩列的 MySQL 订单导出 SELECT 脚本。**导出仍由 fills 合成时新列保持 None，直至另有导出改动**；已有目录只有 positions.json / fills.json / trades_closed_window.csv 时行为不变。未修改 resolve_order_px，历史 NULL 继续 missing_*。

## pytest

使用 `/Users/jenniferni/jenifer/venv-research/bin/python`；未安装依赖或修改系统 Python。

| 工作目录 | 命令 | 结果 |
| --- | --- | --- |
| research | `$PY -m pytest -q tests/test_msa_p1_orderpx.py tests/test_msa_p1_execution_cost.py` | 16 passed |
| research | `$PY -m pytest -q` | 149 passed, 1 skipped（外部真实数据回归数据未配置） |
| htx-trend-python | `PYTHONPATH=src $PY -m pytest -q` | 499 passed |

覆盖 limit/mid/last、缺失与禁止回填、非法 source、计数 NULL、SQLite 实际落库、MySQL 参数与 UTC、ENTRY/CLOSE/mirror、PAPER 投影不覆盖已存价格、导出映射及歧义处理。`git diff --check` 通过。
测试仅使用临时/内存数据库及 mock；既有测试夹具的建表/ALTER 限定内存测试库，未连接 PAPER/LIVE/业务库。

## 硬禁确认

未执行迁移脚本或业务库 ALTER；未装机、未 SSH 交易机、未热更；未回填历史订单；未改开仓判定、sizing、gate/policy 或策略/止损/风控参数；未改 research/lob_prob、collector、nginx、前端；未创建 PR、未 merge；只作普通 push，无 force push。

## 文件清单

- `ORDERPX_PERSIST_NOTES.md`
- `coin-strategy-htx/src/main/resources/db/schema.sql`
- `htx-trend-python/sql/2026-10-22__orderpx_nullable_columns.sql`
- `htx-trend-python/sql/2026-10-22__sqlite_orderpx_nullable_columns.sql`
- `htx-trend-python/sql/schema.sql`
- `htx-trend-python/src/htx_trend/execution_service.py`
- `htx-trend-python/src/htx_trend/ledger.py`
- `htx-trend-python/src/htx_trend/mirror.py`
- `htx-trend-python/src/htx_trend/mysql_store.py`
- `htx-trend-python/src/htx_trend/order_px_fields.py`
- `htx-trend-python/src/htx_trend/paper.py`
- `htx-trend-python/src/htx_trend/paper_db_persist.py`
- `htx-trend-python/src/htx_trend/paper_worker.py`
- `htx-trend-python/src/htx_trend/position_manager.py`
- `htx-trend-python/src/htx_trend/runtime.py`
- `htx-trend-python/tests/test_execution_service.py`
- `htx-trend-python/tests/test_order_px_persist.py`
- `htx-trend-python/tests/test_paper_db_persist.py`
- `htx-trend-python/tests/test_position_manager.py`
- `research/msa_p1_execution_cost/cost.py`
- `research/msa_p1_execution_cost/docs/migration_draft_orderpx.md`
- `research/tests/test_msa_p1_orderpx.py`
