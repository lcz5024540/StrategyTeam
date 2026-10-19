# K 线落业务库 v1：API 判单与库审计

范围锁定 A + P15 + W1 + R1，approval=SYNTHETIC。
基线 support_htx_python @ 273bf19b9896c18cc73c2713331ba41563bfa5cb。

- A：BTC-USDT、ETH-USDT。
- P15：`BUSINESS_KLINE_PERIODS = ('15min', '60min', '4hour', '1day')`。
  `src/htx_trend/timeframes.py` 中 INTRADAY_15M 的 entry/trend 为 15min/60min，
  SWING_4H 为 4hour/1day。INTRADAY confirmation 声明还包含 30min；本产品范围明确排除
  30min，不扩张为全部声明周期，不强制启用 1min、5min、30min。
- W1：扩展 `research/collector.py:ContinuousCollector`，可选 `business_sink`。
- R1：默认 2026-10-12T00:00:00+08:00 到执行时 now，支持明确 start/end。
  本次编码完成日为 2026-10-20；本任务只用 fixture 测试，未执行公开历史抓取或现网回填。

LIVE/PAPER worker 热路径未改：REST `history/kline` → `HtxMarketData` → FileEvidenceStore
的 `runtime_raw_kline` / `runtime_kline_window`。证据失败阻断新增风险；原有保护退出行为保留。
业务 MySQL 用于 audit / SQL / research freeze，本 PR 不把它设为默认判单数据源。

历史双路径参考：

- Java：`coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/HtxTrendRuntimeKlineRecorder.java`。
- Python：`htx-trend-python/src/htx_trend/market_data.py`、`research/store.py`。
- 既有表契约：`sql/htx/2026-09-30b__htx_trend_runtime_kline_evidence.sql`。

Collector 继续先保存 raw、quality、normalized page 及 COMPLETE coverage checkpoint。
仅 COMPLETE 且属于 A×P15 的页面投递业务表；失败不删除文件证据，tick 返回 DEGRADED，
不推进 continuous cursor；collect 返回 PARTIAL。重跑 collect 会验证并补投已缓存页面，
沿用原始 observed_at，不把重试时刻伪装成新观察。无独立后台投递任务。

唯一写表为 `htx_trend_runtime_kline_candle`，无 DDL、无并行业务表、无 snapshot 写入。
参数化 INSERT IGNORE，提交成功后返回 attempted/inserted/ignored，异常 rollback。
直接使用 Candle.content_hash；不添加 symbol/period 前缀，不改 hash 算法。
OHLC 修订保留新 hash。既有唯一键只含内容 hash：跨 symbol/period 如内容完全相同，也会被忽略，
这是继承的 schema 限制，不意味着每个请求一定增加行。重复投递不更新 captured_at。

CLI 接口：`collect-tick` 新增默认关闭的 `--business-db`，连接配置沿用 MySqlSettings，
前缀 `HTX_DB_`（HOST、PORT 默认 3306、DATABASE、USER、PASSWORD）。
文件采集的原有 symbols/periods 参数和默认值保持不变；sink 始终只接受锁定范围。
`collect-history` 默认行为不变。启用生产 sink 必须另走安装授权，本任务不提供安装命令。

R1 接口：`backfill-runtime-kline-business`，参数 `--evidence`、`--start`、`--end`、
`--page-bars`（1–2000，默认 500）、`--dry-run`。
Dry-run 只输出 planned pages / planned bars（不是实际返回条数），不创建文件库、不请求 HTTP、
不连接 MySQL。真实执行在打开连接前要求 `HTX_KLINE_BACKFILL_CONFIRM` 精确等于 `YES`，
所有 host 一律受限；这只是工具防误操作门槛，不是生产授权或 admission。
终点截到 now−10 秒，按每周期闭合边界取整；日线使用上海午夜锚点。
非完整页面不写库，PARTIAL 退出码为 1；可重新执行同窗口幂等恢复。
不完整历史不会被合成、补零或伪标 COMPLETE。
