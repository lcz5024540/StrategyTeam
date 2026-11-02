# 实时结构入口：回放与数据准入

本轮真实数据结论为 **NOT_ELIGIBLE**。不部署、不启用新入口，不以模拟样例的利润证明策略有效。

输入盘点：[采样审计](evidence/realtime-20261102/input-audit/report.md)。已核验的公开ETH样本含323条逐笔/BBO事件；它证明公开订阅和解析可用，但不足以覆盖完整交易、费用和独立诊断/留出区间。六组正式净收益、回撤、费用和往返次数均记为未知，没有填零。

## 可执行回放

`htx_trend.realtime.replay` 使用真实 `TriggerEngine`、共享风险/价格计划、`PaperBroker`、`PaperManagement` 和原 `PaperTrendWorker`，处理全部行情及原扫描时点。未成交与拒绝保留，不从已成交订单倒推机会。不开网络连接、不读取交易凭据、不连接生产数据库。

协议固定：`k={0.05,0.10}` × `净R={1,1.5,2}`；前70%诊断，只将按净收益、回撤、往返次数及既定平局规则选中的一组送入后30%。未选组合不读取留出行情。留出失败不改选。持仓和预算跨分段延续，含浮盈亏的净权益按实际观测时点计算；结算资金费先计入现金，再评估下一笔风险。无资金费证据时净值明确为“不含未知资金费”，不可准入。

当前基线必须逐笔重现原开平仓、数量、价格、费用及所有扫描候选/拒绝，并核对原始来源。结构模型另列计划数、触发率、成交率、结构重新失守、未决标签、ATR入场位置、计划净R、已平净R兑现、持仓时长及最大利润回吐。“一小时同向往返”采用保守口径：同向完整退出后3600秒内重新开仓，包含有盈利的往返；不把仅手续费下降当成收益改善。

## 输入契约

冻结JSON或gzip JSON，schema为`htx-realtime-opportunity-tape-v1`，CLI校验文件SHA256。必要字段：

- `configuration`和`configuration_hash`：完整worker、entry、execution、maker、exit参数、baseline_continuation；相同初始300U、合约池、杠杆和预算边界。
- `start_ms/end_ms/clock_start_ms`：真实接收时钟，起点为空仓；非空起点没有已验证账本适配器时拒绝冒充全历史。
- `observations`：按接收顺序排列。FRAME保存当时可见闭合15m/60m/4hour窗口、发布时间与合约规格；DEPTH保存请求/接收时间和真实两侧深度；TRADE/BBO使用采集器的规范事件；FUNDING保存实际费率引用；FUNDING_SETTLEMENT保存结算时刻、标记价格和费率；CONTEXT保存当时市场广度；SCAN保存实际旧worker扫描时刻；GAP/RESTART明确中断。
- `latency`：固定毫秒数及来源，正式数据还须包含真实发送/首笔成交时间样本。配置的250ms只属于假设。
- `coverage_ref/observation_refs/reference_ref/latency_ref/fee_ref`：可校验的原始CAS来源闭包；coverage绑定区间、全部观测hash和配置hash；reference绑定实际fills与全部decisions；不得由研究输出自行冒充实盘基线。
- `reference_fills/reference_decisions`、`funding_status`与`provenance`：缺失、假设、不匹配均阻止准入。10月30～31日假设形成区间只作案例。

缺行情、未来修订、乱序时间、缺深度、错误配置hash直接拒绝。摘要/15m OHLC不能重建“两笔成交＋回踩”的盘中顺序。当前V5累计订单投影不证明精确首笔成交时间，已单独列为限制，不能拿订单创建或首次查询时间代替。

```bash
PYTHONPATH=src python -m htx_trend.realtime audit-capture \
  --input /absolute/capture --output /absolute/audit
PYTHONPATH=src python -m htx_trend.realtime replay \
  --input /absolute/tape.json.gz --sha256 VERIFIED_FILE_SHA256 \
  --evidence-root /absolute/evidence --output /absolute/replay-result
```

每组详情为独立gzip：逐笔成交、拒绝证据、净权益与持仓路径；summary JSON和Markdown单独输出。没有完整输入时输出失败原因，不编造六组收益表。

## 本轮验证与后续证据

9项回放测试通过：真实触发/风险/后续盘口路径、六组协议、未来/缺失输入拒绝、多指标比较、留出失败不重新选参。样例盘口为明确标注的合成数据，只验证软件行为。旧V4/legacy还通过600固定输入差分，不能据此宣称新策略盈利。

正式准入仍需完整独立区间、可复现的部署基线、真实费用/资金费和准确成交延迟；通过后仍需单独批准生产迁移与部署。
