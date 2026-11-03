# 分钟结构与逐笔回撤研究：触发增多，净R合格机会未增加

作者：Codex；日期：2026-11-03；状态：研究完成，RESEARCH_ONLY_NOT_ELIGIBLE。
业务基线b201b78a；事前协议提交a98d1181；分支codex/realtime-structure-entry。仅新增Python研究工具，未改变交易worker、配置或生产环境。

## 结论

真实逐笔和同期盘口证明：分钟级确认、逐笔回撤确认都能比原15m结构产生更多触发，ETH也出现新增触发。但在预登记的目标与成本条件下，新增触发没有增加净R≥1的机会；净R≥1.5仍全部来自原结构边界。

这不是“快速结构没有用”的结论。本次刻意保留原目标，以隔离选线效果；更近的入场线可能使旧目标落在入场价错误方向，或使剩余空间不足覆盖风险和成本。下一轮建议研究入场线、失效位、目标的配套映射，事前确定规则后再验证；本轮没有事后换目标、降低门槛或选定上线参数。

## 数据和规则

使用2026-11-03 10:44:27至11:33:24 CST冻结采集前缀，52合约、4916完整gzip分段。原始SHA、压缩字节及事件数全部核验。输入11675笔TRADE、886252条BBO、252条FRAME、2516条FUNDING，另有初始连接GAP 1条和SOURCE_ERROR 2条（DASH、NEAR的HtxApiError）。这不是完整采集终态，也不是完整金融回测。

- A：当前源码默认配置下原15m/1h结构，真实两笔成交＋同期BBO。未核实为现网worker的完整实际配置，不能声称重现了实盘决策。名称JavaEntryConfig为仓库既有Python配置类型，本轮没有Java开发。
- M：加入观测成交聚合1分钟的2左2右严格已确认拐点；优先选择离最新已闭合15m收盘较近的结构，平价优先A。
- T：加入逐笔回撤确认极值，距离max(2tick,0.25×当时已知15mATR)，每个跟踪阶段冻结阈值。
- M/T仅变更反弹回落入场线；突破回踩保留原规则。确认结构后必须再次触及，再由两笔成交和BBO触发，不能用确认事件回溯开仓。
- 两种k分别0.05、0.10；净R门槛1、1.5、2作为触发后筛选。手续费每边0.0004、滑点每边0.10ATR是假设，另记录0.05/0.20ATR敏感性，资金费结算未知。

完整规则见[协议](evidence/fast-structure-20261103/protocol.md)，处理流程见[draw.io图](diagrams/fast-structure-research-20261103.drawio)。分钟数据标为OBSERVED_TRADE_MINUTE；事后官方1m仅核验覆盖，不作为当时信号输入。

## 同输入对照

|组|区域k|真实事件触发|净R≥1|净R≥1.5|净R≥2|
|---|---|---:|---:|---:|---:|
|A|0.05|6|2|1|0|
|A|0.1|7|2|2|0|
|M|0.05|15|2|1|0|
|M|0.1|17|2|2|0|
|T|0.05|11|2|1|0|
|T|0.1|13|2|2|0|

这些是各独立研究组的触发，不能跨组或跨k相加为可执行订单。所有组均未下单。净R≥1.5通过的事件时点与原结构线完全相同：k=.10的DOGE、BTC各一次；k=.05的BTC一次。

M产生382条已确认线、T产生63条（每个k相同，不能把参数副本重复累加）。目标或失效位方向不符的触发：A均0；M两种k各2；T分别5、6。其余新增触发主要表现为成本后的空间不足，逐笔详情与三档成本均保留在results.json.gz。

## ETH新增触发具体看什么

全部为SHORT；参考价是当时BBO价格，不是实际成交。止损采用预登记1.25ATR合成边界，未收紧风险距离。

|组|k|触发时间CST|入场结构线|盘口参考价|计划止损|继承目标|净R（未知资金费未计）|
|---|---|---|---:|---:|---:|---:|---:|
|T|0.05|11:04:32|2249.37|2248.55|2254.79375|2251|目标方向不符|
|T|0.1|11:04:32|2249.37|2248.55|2254.79375|2251|目标方向不符|
|T|0.05|11:06:09|2249.49|2248.9|2255.14375|2251|目标方向不符|
|T|0.1|11:06:09|2249.49|2248.9|2255.14375|2251|目标方向不符|
|M|0.05|11:13:26|2252.86|2252.1|2258.34375|2251|-0.188|
|M|0.1|11:13:26|2252.86|2252.1|2258.34375|2251|-0.188|
|M|0.05|11:22:40|2254.19|2253.86|2260.72786|2244.02|0.7103|

例如T在盘口2248.55做空的研究触发，继承目标却为2251，目标在不盈利的一侧，必须拒绝。M在2252.10触发、目标2251，仅1.10的毛空间被双边成本吃掉，净R约−0.188；另一笔目标2244.02虽然方向正确，净R也仅约0.710。不能从这些触发推导盈利或胜率。

## 覆盖与限制

全池52×48=2496个完整分钟槽中，2170个观测桶与事后官方OHLC、成交量一致，额外核对成交笔数也一致。ETH和BTC各48/48分钟一致。326个没有已封口观测桶，其中317官方成交量为零，9个官方成交量非零；不填造空分钟，也不把这9个直接定性为WS漏包（尾段封口、过期和覆盖需单独查明）。结果另保留43个未封口桶。

55条TRADE、33条BBO过期被拒绝。逐笔极值有5035次START_OR_TRADE_GAP重置，包含启动和超过2秒无新鲜成交，不能称为5035次网络断线。ETH的2349笔有效TRADE中223笔因缺新鲜15m/1h FRAME不能评估；不能拿事后完整K线覆盖冒称触发时结构输入全部可用。

本批不足49分钟，虽独立于截图，也不足以作为正式70/30留出和财务准入。未模拟深度、真实延迟、IOC成交、账户预算竞争、保护退出重入门、完整持仓退出和资金费；没有实际收益、胜率和组合回撤结果。新交易入口保持关闭，未部署、未改4H/LIVE。

## 复现和证据

[证据清单与SHA](evidence/fast-structure-20261103/artifact-manifest.json)、[汇总](evidence/fast-structure-20261103/summary.json)、[完整研究结果](evidence/fast-structure-20261103/results.json.gz)、[触发原始事件核验](evidence/fast-structure-20261103/trigger-proof-audit.json.gz)、[验证记录](evidence/fast-structure-20261103/verification.txt)。results中的source_refs将结构、窗口、两笔成交与BBO映射到原分段位置及事件hash。

105656081字节导出输入位于本机`/tmp/coinstrategy-fast-structure-20261103/capture-prefix.jsonl.gz`，未加入Git。临时文件可能被系统清理；重建来源是服务器`/home/ubuntu/coinstrategy-research/realtime-20261103/capture`中的冻结manifest分段。不可删除这些分段后再声称可完整复现。服务器未创建第二份导出副本、未扩大采集预算。

复现（业务仓根目录，使用安装项目依赖的Python）：

```bash
PYTHONPATH=htx-trend-python/src python -m htx_trend.realtime.fast_structure_diagnostic \
  --tape /tmp/coinstrategy-fast-structure-20261103/capture-prefix.jsonl.gz \
  --official docs/htx-trend-python/evidence/fast-structure-20261103/official-1m.json.gz \
  --protocol docs/htx-trend-python/evidence/fast-structure-20261103/protocol.md \
  --output /tmp/fast-structure-repeat.json.gz
cmp /tmp/fast-structure-repeat.json.gz docs/htx-trend-python/evidence/fast-structure-20261103/results.json.gz
```

重建输入：在持有原分段的主机运行`python capture_prefix_export.py --root <capture目录>`，stdin传入解压后的capture-manifest.json.gz，stdout重定向到本地gzip文件；可通过SSH标准输出传输，避免服务器复制大文件。导出器只读，不需要交易凭据。原分段内容hash是确定性校验依据。

## 验证与影响范围

119项实时Python回归通过，新增10项覆盖分钟水位/迟到/缺口、对称极值与冻结阈值、完整导出footer/hash、真实事件触发、新鲜窗口门和不下单。69条各组触发（85个唯一证明事件）另外逐条核验两笔不同成交ID、同epoch、2秒新鲜度及BBO时间关系。相同源码和输入重复回放gzip逐字节一致；ruff、compileall、diff检查通过。

新增两个独立研究模块及测试，只复用既有generate/TriggerEngine/成本计算；未修改worker或其执行路径。此研究任务没有既有生产调用链重构，不激活codegraph；工具当前也未提供codegraph能力，不伪造图谱输出。实际访问52合约官方公共API并全部取得status=ok，不以离线豁免替代数据接入验证。真实私有交易与收益准入仍未通过，不在本轮研究内。

交付时另作只读状态核验：采集status的final=True，stop_reason=CAPTURE_QUOTA_EXCEEDED，已落盘2146377525字节（低于2GiB预算）；未重启或扩大预算。该终态不改变本轮事前冻结的4916分段研究前缀。最后队列未完全消化，尾段不能冒充完整采集覆盖。
