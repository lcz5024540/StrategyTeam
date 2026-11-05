# A/B场景与贝叶斯门：支持显式开启入口的Python候选

2026-11-05。代码可独立选择A/B、实时价格确认及三档固定ATR退出；后验门是可选独立实验。安装不启用交易，收益准入不随测试或打包自动获得。

完整交付与实际诊断结果见 `docs/htx-trend-python/regime-bayesian-20261105.md`、`docs/htx-trend-python/regime-bayesian-night-20261105.md`。流程图源在 `docs/htx-trend-python/evidence/regime-bayesian-20261105/flow.drawio`。

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

另两档使用独立输出目录；概率包另传--regime-snapshot和--regime-threshold。包中快照示例路径相对于包根，人工部署时应设置绝对持久路径。未显式开启时入口关闭。不执行生产SQL；复用已有计划表，启动只验表。

### 开启本分支的贝叶斯选档入口

用户已确认新观察不阻止开仓。构建时加 `--enable-regime-entry`，包内config的`entry_enabled`与env的`HTX_RT_ENTRY_ENABLED`一起设为true，并纳入manifest校验；只允许显式指定A/B候选，不修改其他策略默认值：

```bash
python htx-trend-python/release/package.py build --regime-exit BAYES_NET_V1 --regime-snapshot /absolute/current-posterior.json --regime-threshold .90 --enable-regime-entry --out-root /absolute/new-enabled-paper
python htx-trend-python/release/package.py verify /absolute/new-enabled-paper/VERSION/htx-trend-python-VERSION.tar.gz
```

`.90`仅为命令示例，须与选定配置一致；开启开关不替换原阈值或快照。启用时应使用包中`release/realtime.env.example`的整组A/B参数，设置独立持久目录、绝对快照路径及已审账户配置，再由既有平台PAPER入口启动。不要将`config.example.json`直接当成真实账户配置。`build-live`支持同一显式选项，但只生成代码和配置，不部署、不签发LIVE授权，也不扩大旧trial候选白名单。已有授权、账户、行情、净收益、贝叶斯成熟样本条件仍须满足。

### 样本和实际成本观察

样本完整率、缺档数、排除原因和三档共同机会比较进入`sample_observation`，`tools/regime_report.py`显示摘要。字段`affects_entry=false`；它们不参与原`allowed`或ATR排序。旧快照没有这些字段时只提示不可用；本次代码升级仍遵循原有源码封印校验，不能手改hash冒充兼容快照。

每个新A/B持仓冻结`cost_plan`。PAPER从模拟台账、LIVE从独立连接和交易所只读收据重算数量、手续费、滑点及净损益，输出`regime_cost_observation`证据和`REGIME_COST_OBSERVATION`日志；观察错误不加入交易worker的失败列表。部分成交按实际量缩放预算，重复读取不累计金额，旧仓缺预算显示`PLAN_UNAVAILABLE`。

资金费前净损益单独显示；资金费不确定时，含资金费净损益保持null。LIVE资金流水按精确成交区间查询完整分页；空覆盖可证明0，非空合约流水不能仅因本地没有其他仓位就自动归属本仓（无法排除手工交易），保留原始流水并标未知。缺平仓参考价时退出滑点也为未知。LIVE每60秒调度一批、最多10仓，轮转包含已平仓；不是逐tick实时告警。

本地重算只读证据中的`input`对象（无网络、无下单）：

```bash
PYTHONPATH=htx-trend-python/src python -m htx_trend.realtime.cost_observation --input /absolute/cost-input.json --out /absolute/new-cost-report.json
```

`account`回放结果新增逐仓`cost_observations`及对应输入证据，模拟手续费明确标为`PAPER_SIMULATED`，不冒充交易所成交。观察告警、无法读取成本或样本不完整都不新增拒单条件。没有足够成熟后验时，原`BAYES_NET_V1`规则仍可能无订单；不会静默回退固定ATR或以测试样本授权真实交易。

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

Parameters文件可使用 `release/regime-parameters.example.json`（不是账户资金配置）。在用户提供且冻结账户配置后执行：

```bash
PYTHONPATH=htx-trend-python/src python -m htx_trend.realtime.regime_replay account --tape /absolute/account-tape.json.gz --parameters htx-trend-python/release/regime-parameters.example.json --matrix --out /absolute/new-account-matrix.json
PYTHONPATH=htx-trend-python/src python htx-trend-python/tools/regime_report.py --root /absolute/new-study --out /absolute/new-report.md
```

`--matrix`依次独立运行四组共18配置，保持同一账户配置与完整输入、重新计算实际持仓占用及开仓调度；输出各组净权益/回撤、费用、逐笔、拒绝及门控后新增的实际开仓计划。没有快照时概率组完整记录拒绝，不伪造通过。上述命令只操作本地文件。

采集新增封口文件时，先确认旧前缀已完成，再创建子研究，禁止覆盖旧结果：

```bash
PYTHONPATH=htx-trend-python/src python -m htx_trend.realtime.regime_replay extend --previous /absolute/old-study --source /absolute/extended-manifest.json --root /absolute/new-child-study --end-ms NEW_END
PYTHONPATH=htx-trend-python/src python -m htx_trend.realtime.regime_replay run --root /absolute/new-child-study
```

扩展必须保持原文件绝对路径、顺序、哈希的精确前缀并只追加封口文件。代码/规则/成本/快照变化需新freeze；不能延用旧检查点。后验fit每次从最终唯一成熟记录全量重算，不能把前后两个报告重复累计。新数据在看策略结果前冻结exposure与快照；未见数据结果只能一次检验，转入后续开发须显式记录SEEN，不得借extend偷看受保护留出。

## 恢复与回滚
精确兼容3d793d6d持仓源码向量，保留更早已验向量；未知hash拒绝。关闭新入口后本包继续管理新旧仓；禁止切回不认识RT_REGIME_AB的旧包漏管。快照改变不能追溯修改旧决策，规则/退出变化必须新建模型版本。合并、部署、档位选择及启用须另行执行。
