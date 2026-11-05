# A/B场景与贝叶斯门：默认关闭的Python候选

2026-11-05。代码可独立选择A/B、实时价格确认及三档固定ATR退出；后验门是可选独立实验。安装不启用交易，收益准入不随测试或打包自动获得。

## 规则与归属
A多头：闭合1h EMA20<EMA60、1h MACD柱>0、15m MACD柱<0；最新15m阳线、下影>=实体、bid>收盘。B空头：EMA20>EMA60、两个MACD符号与A相同；阴线、上影>=实体、ask<收盘。spread<=10bp。相等、零值、无效/不完整窗口不匹配。EMA使用SMA种子、MACD12/26/9、ATR14使用Wilder。

WINDOW在下一根15m闭合前持续等有效盘口；FIRST_QUOTE只供时序对照。启动/断线首条报价建立基线，最多2秒新鲜度，不补历史触发。同实例/候选/时序/合约/方向/bar只有一次意图，普通修订和重启不重新下单。每次提交前复核当前场景与盘口、深度、tick、资金费报价、成本和现有账户限制；不套旧空头MACD反向门，不新增300U/0.5%研究约束。

模型为RT_REGIME_AB。目标/止损在首次意图前冻结；三档为止损/目标ATR_1_1=1/1、ATR_2_1=2/1、ATR_2_2=2/2。复用INDICATOR_BRACKET_V1实时可执行盘口退出、IOC限价入场和未决平仓管理。保护退出重入门保留。场景改变不重置已开仓止损/目标；开仓记录关联计划、场景、参数与后验hash，详细指标放计划/compact证据。

## 配置与包
独立15m环境显式配置：

```dotenv
HTX_RT_ENTRY_ENABLED=false
HTX_RT_CANDIDATE=REGIME_AB_V1
HTX_REGIME_EXIT=ATR_1_1
HTX_REGIME_TIMING=WINDOW
```

概率门额外要求HTX_REGIME_BAYES_THRESHOLD（.80/.90/.95）、HTX_REGIME_SNAPSHOT（冻结JSON文件）、HTX_REGIME_SNAPSHOT_SHA256（JSON中hash，规范payload摘要）、HTX_REGIME_COST_ID。缺少/损坏/不适用快照拒绝新机会，持仓管理继续；LIVE拒绝假设成本快照。运行不训练、不抽样下单。

```bash
python htx-trend-python/release/package.py build --regime-exit ATR_1_1 --out-root /absolute/output/paper-1-1
python htx-trend-python/release/package.py build-live --regime-exit ATR_2_1 --out-root /absolute/output/live-2-1
```

另两档使用独立输出目录；概率包另传--regime-snapshot和--regime-threshold。包中快照示例路径相对于包根，人工部署时应设置绝对持久路径。所有包入口关闭。不执行生产SQL；复用已有计划表，启动只验表。

## 贝叶斯定义
每场景/时序/退出/成本独立建模。完整净收益>0为1，其余完整结果为0；Beta(1,1)更新成Beta(1+盈,1+非盈)，直接抽50,000组概率。60分钟同步时间块Dirichlet权重抽10,000组平均净收益，开仓门检查正均值质量>=阈值；30/120分钟作敏感性。至少20个同合约非重叠完整结果、10小时块；不足拒绝该概率门，不隐藏原A/B结果。

每次从原先验和全部唯一成熟标签重算，排除未来、重复、重叠、缺口、未平及未知费用。假设费用单独标记ASSUMPTION成本ID。Beta是组内独立近似，时间块法仍有相关性与截断选择偏差，不是层级模型、校准未来概率或利润保证。历史搜索段都是SEEN；受保护/未见标签不能训练。

## 本地复现
研究依赖使用pyproject的research可选组；交易运行不需要numpy。

```bash
PYTHONPATH=htx-trend-python/src python -m htx_trend.realtime.regime_replay --help
PYTHONPATH=htx-trend-python/src python -m htx_trend.realtime.regime_replay freeze --source /absolute/data/manifest.json --root /absolute/new-study --start-ms START --end-ms END --latency-ms 250 --fee-rate .0004 --cost-id ASSUMPTION:fee4bp-no-funding --exposure SEEN
PYTHONPATH=htx-trend-python/src python -m htx_trend.realtime.regime_replay run --root /absolute/new-study
PYTHONPATH=htx-trend-python/src python -m htx_trend.realtime.regime_replay fit --records /absolute/new-study/records.json.gz --out /absolute/new-posterior.json --cutoff-ms CUTOFF --cost-id ASSUMPTION:fee4bp-no-funding
```

上面250ms和双边4bp仅明确标识的假设示例，不是恢复的真实成交延迟/全部费用。source manifest须含complete=true及files[path,sha256]；gzip JSONL每行segment/index/event，event使用现有公共采集标准对象。run按接收时间合并，checkpoint保留待进入路径、观察历史和去重状态；文件/协议/代码变化停止。--max-events可分轮处理，validator.lock禁止同时写。report和逐笔记录区分QUOTE_PATH与IOC/账户；不接私有API。

account子命令要求显式tape和Parameters JSON，复用实际worker/broker/ledger/management；tape须有真实FRAME/DEPTH/BBO/FUNDING、成本、延迟及账户配置，缺深度不以收盘补成交。原固定300U协议不应用于该入口。无账户配置则只输出机会收益率，不生成账户盈亏。

## 恢复与回滚
精确兼容3d793d6d持仓源码向量，保留更早已验向量；未知hash拒绝。关闭新入口后本包继续管理新旧仓；禁止切回不认识RT_REGIME_AB的旧包漏管。快照改变不能追溯修改旧决策，规则/退出变化必须新建模型版本。合并、部署、档位选择及启用须另行执行。
