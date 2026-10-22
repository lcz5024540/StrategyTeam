# D1 + D2 + D3/D4 离线研究工具链

本目录为独立 Python 包 `coinresearch`。只读调用者指定的 CSV/JSON，只写指定输出目录；依赖标准库、numpy、pandas，测试依赖 pytest。没有交易执行、数据库、网络或现网 runtime 依赖。真实输入放在仓库外，测试数据在临时目录中合成；P6 小型上界与滑点报告保存在 outputs/p6，不提交原始 close CSV。

## 模块与命令

- `config.py`：不可变 `Config`；常量、窗口、阈值、成本、种子、抽样数、资金费 cap 和 DEFF 参数均可配置。CLI `--config /external/config.json` 接收字段覆盖对象。
- `data.py`：文件清单、覆盖/缺口表、资金费归一化、因果选池及事后持仓数据完整性矩阵。
- `guard.py`：D1 封存数据容器、预注册文件完整性校验。
- `g0.py`：随机分组、随机伪事件、残差离散度、ACF/DEFF、MDE 与成本统计。
- `g2.py`：全币联合日历块自助法及三态判定。
- `cli.py`：`d1`、`g0`、`g2`、`regress`。

从仓库的 `research/` 目录运行：

```sh
export RESEARCH_RING2_DIR=/Users/jenniferni/jenifer/research-data/ring2
export RESEARCH_BGATE0_DIR=/Users/jenniferni/jenifer/research-data/b-gate0-20261019
PY=/Users/jenniferni/jenifer/venv-research/bin/python
OUT=/Users/jenniferni/jenifer/research-data/d1d2-out

$PY -m coinresearch.cli d1 --out-dir "$OUT/d1"
$PY -m coinresearch.cli g0 --out-dir "$OUT/g0" --target-bps 20
$PY -m coinresearch.cli regress --out-dir "$OUT" --target-bps 20
$PY -m pytest -q
```

`--data-dir`、`--reference-dir` 可替代上述环境变量。G0 和 regress 强制显式提供正数 `--target-bps`；示例中的 20 只是调用参数，没有自动调整逻辑。缺环境变量或目录不存在时真实数据测试明确 skip；合成测试照常执行。`regress` 输出 `regression.json` 与 `regression.md`，对比参考 JSON 精确值及产品令目标值、三个 D1 CSV、两次独立构建的全部文件哈希；任何偏差超限返回非零。

## D1 口径

唯一数值参考是外部 `$RESEARCH_BGATE0_DIR/gate0_power.py`；运行工具不导入或执行该脚本，也不依赖 `raw/`。输入为 `manifest.json`、可选 `summary.json`、`klines_4h/{SYM}.csv` 和 `funding/{SYM}.csv`，币按字母排序。文件清单记录相对路径、SHA256、CSV 行数与首尾 UTC 时间；JSON 文件的行数/时间区间不适用，标为 null。输出 JSON 固定缩进、键排序、不含生成时刻；CSV 固定列顺序。

`eligibility_at_bars()` 对应参考 `load()`：4h 网格补齐，缺失成交额填 0，`rolling(540,min_periods=540).sum()/90` 为 ADV；ADV ≥ 5e6；`1-present.rolling(540).mean() < .01`；上架时长按 `bar_ts-first_ts+H4 >= 180天`；当前 close 必须存在。warm 列严格为累计存在根数 **>1180**。这些判定只依赖当前已完成 bar 及之前的数据。

`build_dataset()` 对应参考主循环：`PER=P*H4`、`E0=(t0//PER+1)*PER`、`entries=arange(E0,LAST_OPEN-PER+1,PER)`，调仓 bar 为 `E-H4`。最终矩阵还要求 E/X 开盘存在以及持仓资金费可用：调仓 bar 不早于首笔资金费、不晚于末笔+8h、不在缺口内部；末笔 ≥ X−9h、首笔 ≤ E、缺口与 `(E,X]` 无重叠。缺口边界比较保留参考的严格不等式。使用 manifest 的 `f_gaps`，同时检测相邻资金费 >9h 的遗漏缺口；在正常完整 manifest 上等价，在错误 manifest 上保守剔除。

**时点选池与事后数据可用性不同**：最终矩阵需要 X 的价格及未来整段资金费，不能称为实时可交易信号。无前视测试针对 `eligibility_at_bars()`；最终回归矩阵保留参考的事后完整性口径。

`period_coin_logret_bps.csv` 为 `log(open[X]/open[E])*1e4`，四舍五入 3 位；`period_coin_funding_bps.csv` 为 `(E,X]` 的原始 funding_rate 合计乘 1e4，四舍五入 4 位；不可用单元留空。这两者仅为原始价格/资金费变换，不含信号或仓位。`period_eligible_counts.csv` 列序与参考相同；另有合格布尔矩阵、warmup 矩阵、缺口/覆盖表和剔除计数。基础门槛失败可同时计入多个原因；通过基础门槛后按 missing_open、funding 顺序记录首个失败原因。

资金费结算周期取相邻时间间隔众数（并列取较小者）。`normalize_funding_8h()` 对 1h 数据按 UTC `(8h窗口起点,终点]` 累加，残缺窗口保留且 `complete=False`；8h 数据保持原值；4h、6h 等按每笔 `rate*8/cadence_h` 给出该结算周期的 8h 等价速率，**不再把这些已缩放记录重复累加**。归一化只用于描述，不替换 G0 的实际结算合计。

cap 可由 `Config.cap` 或函数参数覆盖。缺省从该币全历史绝对最大 funding_rate 推断：最大值为正且出现 ≥2 次才视作上限；`abs(rate)>=cap` 标记 `cap_hit=True`，1h 聚合取 any，所有记录保留。唯一极值不推断为触顶。这是全历史描述性标记，不用于因果选池。`realized_rate`、`funding_fee_type`、`avg_premium_index` 原始字段保留在输入清单对应文件；费率计算使用 funding_rate，avg_premium_index 的含义是溢价指数。

## G0 口径

`random_ls` 和 `summarize` 保留参考算术和 RNG 顺序：每期一次 `rng.random((ndraw,n))`，按随机数排序，前 k 与后 k 为两组；`k=min(max(3,floor(frac*n+.5)),n//2)`。默认 SEED=20261019、ndraw=1000、frac=.2。每次摘要重新初始化同一种子。报告每抽样期序列标准差的中位数、q05/q95、MAD×1.4826、扣资金费后的标准差；代理 b 为 `sqrt(mean(2*s_p²/k_p))`。

ACF：先按抽样分别计算 lag 1..3，再取各 lag 中位数，`N_eff=min(N,N/(1+2Σ(1-l/N)ρ_l))`。DEFF：Unix epoch 锚定的 5 日日历块，单因素随机效应 ANOVA 估计 ICC，非等长簇用 `n0=(N-Σn_j²/N)/(J-1)`；负 ICC 截为 0，可显式覆盖。`N_eff=N/(1+(mean_cluster_size-1)*ICC)`。摘要给出各随机抽样 DEFF 估计的中位数；无法估计簇内方差时输出 null 和原因。`deff_neff()` 可直接处理带时间戳的事件观测。`random_events()` 均匀有放回抽取给定期集合内合格的期/币对，独立随机符号，报告每事件标准差中位数及分位数；不使用方向信号。

MDE=`Z*sigma/sqrt(N_eff)`，默认 Z=2.486。参考的主 MDE 使用 ACF N_eff，DEFF 的 MDE 另列。全换手成本为 `2*RT_COST`；随机换手为 `RT_COST/2 * mean(sum(abs(W_t-W_prev)))`，不连续期首按 4 计，与参考一致。资金费拖累是随机多组平均减空组平均；报告均值、sd、每腿绝对值、币绝对值、市场均值及最高 k 与最低 k 资金费均值差的上界。

全样本使用 ≥10 合格币期；时间切在这些期上 floor(2N/3)；币切按固定种子随机排列后一半为 B，A/B 分别使用 ≥10 阈值。`g0_results.json` 的 split 记录切分，可用于拟定外部预注册文件；工具不会替调用者生成已封存的科学方案。仅实现产品令要求的主摘要，不运行参考脚本的 200 次币切敏感性、固定 k 或波动率敏感性分支。

## 封存与 G2

D1/D2 封存与 G2 模块不提供信号排序、由信号生成多空收益或持仓的接口；D3/D4 的独立离线计划接口见下文。外部已准备的期×币观测由 `SealedDataset` 持有，初始未封存；D1 `Dataset.bind_observations()` 可绑定相同维度的外部矩阵，仍然未封存。`directional_observations()` 必须先通过 `require_sealed()`；切分未完成、未封存、缺 SHA、错 SHA 或封存后文件改变均拒绝输出。封存 API 校验 JSON 的非空 `split` 及整个文件 SHA256，不能验证调用者是否事实上遵守了预注册研究过程。返回数组为副本，修改副本不改变容器。

G2 函数和 CLI 都要求预注册路径与 SHA256。CLI 还要求 `--seal` 明确执行封存；预注册 JSON 至少含非空 `split`，应记录切分、观测构造、N_eff 算法和门槛。G2 的 N_eff 由调用者按预注册协议传入；门槛默认 4400、有效样本硬底线默认 1500、名义样本底线默认 1000，可通过 Config 或 run_g2 参数及 CLI 覆盖。数据须为完整、连续整数期索引及有限矩阵；不自动删除缺失期或填补收益。CLI 示例：

```sh
$PY -m coinresearch.cli g2 \
  --observations /external/observations.csv \
  --prereg-path /external/prereg.json --prereg-sha256 "$PREREG_SHA256" --seal \
  --neff 4400 --neff-threshold 4400 --hard-floor 1500 --nominal-floor 1000 \
  --block-length 30 --B 2000 --out-dir "$OUT/g2"
```

观测 CSV 首列 `period_index`，其余为固定币观测列；单位随输入。估计量为所有期、所有币等权均值。默认 circular block bootstrap；`--moving` 使用非循环移动块；全币共用一组完整日历索引。先对币求固定等权均值再按共同索引抽样，与联合抽取完整矩阵数学等价。默认块长 30 期（不能超过样本长度），B=2000，90% 百分位 CI。公开 CLI 不允许 B<2000；仅函数测试覆盖入口可明确 `allow_small_B_for_tests=True`，≥5000 次校准使用 B=500；块方差对比使用 B=2000。

- CI 下界 >0 且 N_eff 达门槛：PASS，`unlock_g3_request=True`。
- CI 下界 >0 但未达门槛：SMALL_SAMPLE_PASS，仅扩样复核，解锁为 False。
- CI 下界 ≤0：FAIL/NO_EDGE，解锁为 False。
- N_eff<1500 或名义 N<1000：REFUSED_BELOW_FLOOR，CI 和点估计为 null，不重抽，解锁为 False。

`unlock_g3_request` 只是输出字段，没有部署或执行动作。

## D2-acceptance-checklist-v1（产品 2026-10-19）

| 项 | 实现与验收 |
|---|---|
| A1 | `test_regression.py` / regress：N 严格等于 286/309/857；MDE 目标 66.528/51.419/33.159，误差≤1%。Markdown/JSON 均列目标、参考、复现及误差%。两个外部目录缺任一个则真实测试 skip，不能视作真实验收通过。 |
| A2 | `m2_deff(6019,.5588,14.35,530)` 纯闭式函数；DEFF≈8.46、N_eff≈712、MDE≈49，各相对误差≤1%。无需收益数据或解封。 |
| B1 | `ledger.build_ledger`：Decimal bps，单笔往返默认 RT_COST=12，可覆盖；净值=已签名毛收益−往返成本−side×实际资金费合计。价格路径为开盘价简单收益（非 G0 的对数收益）。逐笔合成对账零误差。 |
| B2 | 输入每笔完整的预期结算时间表 `(entry_ts,exit_ts]`，funding 为币→时间→实际 bps。缺币、缺结算时间、缺费率或非有限费率返回 excluded 原因；不填代理。时间表来自调用者的结算日历，空表仅表示该持仓本来无结算，不能用空表掩盖缺数据。 |
| C1 | `test_calibration.py`：≥5000 次独立重复，4 币解析相关系数 0.5，共享波动尺度每 10 期变化，产生波动聚集；90% 双侧 CI 下界>0 的假阳性必须在 [.035,.065]。 |
| C2 | 同一零均值数据加事先按解析方差确定的 MDE，实际运行 G2，检出率必须在 [.75,.85]。MDE 不按测得功效反调。 |
| C3 | 跨币相关矩阵，用全币共用块和分币独立块比较；块长 30/60 各 B=2000；joint 方差>2×独立方差，且更接近解析真方差。 |
| C4 | 4000 个已知 m=8、rho=.4 的随机效应簇，ANOVA 估计 N_eff，测试相对误差≤6%（严于正式 10%）。 |
| C5 | `permutation_test`：≥5000 次，在持仓长度和成本分层内置换多空标签，保留每笔价格、持仓长度、成本和各层多空笔数。单侧毛边 p 值采用 `(1+超过数)/(B+1)`；`diagnostic_only=True`，没有 unlock 字段。 |
| D1 | 统计层 `statistical_decision=H1` 当 CI 下界>0；N_eff<4400 为 SMALL_SAMPLE_PASS。产品层仅正下界且 N_eff≥4400 为 PASS/unlock。N_eff<1500 或 N<1000 提前 REFUSED，不取方向观测、不计算 CI。其余 FAIL/NO_EDGE。函数和 CLI 均覆盖四态及边界。 |
| D2 | 未封存、未提供登记 SHA、错 SHA、无 split、文件被改，均拒绝方向观测。这里登记指调用者显式提供预注册文件 SHA，不对接外部注册服务。 |
| D3 | d1/g0/g2 CLI 每次输出 `run_record.json`，包含输入文件 SHA256（含 CLI 配置/预注册）、所有生效参数、seed。固定序列化，无运行时间和输出路径；两次运行比较全部输出文件字节哈希。D1 的 Dataset.write 同样写记录；纯计算函数不隐式写文件。 |

G2 的名义 N 是输入矩阵中标量观测数（期数×固定币数），不是独立样本数；N_eff 仍须按预注册协议估计。门槛通过 `--neff-threshold`、`--hard-floor`、`--nominal-floor` 覆盖；修改 Config 字段后应显式传给 run_g2 对应参数。封存校验先于底线判断。

校准采用每次 1200 期×4 币，共享高低波动使 E(vol²)=1，条件独立创新使收益自身无时间自相关，组合均值方差为 `(0.5+0.5/4)/1200`。MDE=`2.486*sqrt(0.625/1200)`。只在测试入口显式开启 `allow_small_B_for_tests=True` 将 B 降为 500，重复为 5000；校准 hard_floor=1000，以检验统计量而非产品准入。公开 CLI 仍强制 B≥2000。固定种子在测试源码中，测试打印实际假阳性/功效和方差对照。该校准验证此合成过程的名义水平，不代表所有依赖结构都校准。

置换诊断假设同持仓长度/成本层内方向标签可交换；它不保证时间相关下的主推断正确性，也不作为 G2 主闸。输入为封存的一列未签名价格收益，加外部 side/holding_lengths/costs 元数据。

验收命令（所有合成测试必须通过，真实数据缺失明确 skip）：

```sh
cd research
PY=/Users/jenniferni/jenifer/venv-research/bin/python
$PY -m pytest -q
# 显示校准和闭式数值证据
$PY -m pytest -q -s tests/test_calibration.py tests/test_acceptance.py
# 仅在两个真实数据环境变量已就位时运行
$PY -m coinresearch.cli regress --out-dir /external/d2-regression --target-bps 20
```

这些模块仅产生研究文件和诊断字段，不连接现网执行。

## P6 独立离线扩展

[成本上界与滑点口径](docs/P6_COST_CEILING_ZH.md)、[CAC 中文模板](docs/CAC_TEMPLATE_HTX_ZH.md)。独立命令 `python -m coinresearch.cost_ceiling_cli` 与 `python -m coinresearch.slippage_sensitivity`；不连接现网或修改 D1/D2 闸门。


## 产品口令 D3/D4：离线 F3–F8

本节为新增离线工具链，不是上文 D2 验收表中的 D3 记录项。没有修改 G2 门槛、C1 R=5000 或现网策略参数；报告不产生准入或执行动作。

| 模块 | 公共 API 与验收 |
|---|---|
| F3 `features.py` | `causal_features(bars, window=3, momentum_bars=1, bar_seconds=14400)`、`momentum_events(features, threshold=...)`。`test_future_replacement_preserves_features_and_events` 对每个截点替换未来 OHLCV 并验证历史逐位不变；另验手算均值/总体标准差/动量及 warm-up。 |
| F4 `signals.py` | `plan_trades(events, bars, coin=..., holding_bars=1, bar_seconds=14400)`。`test_direction_next_open_exit_and_nonoverlap` 验证方向、下一开、固定持有出场及重叠拒绝；`test_programmable_holding_and_incomplete_tail` 验证参数化出场和尾部。 |
| F5 `ledger.py` | 扩展原 `build_ledger`，保留 side、entry_ts/exit_ts、可选 signal_bar_ts 和 Decimal entry_open/exit_open。`test_f5_hand_calc_table` 打印逐笔零误差；原 `test_exact_ledger_and_no_proxy` 保留。 |
| F7 `report.py` | `build_report(rows, ...)` 纯计算、`write_report(input_path, out_dir, ...)` 一键写文件及 CLI `report`。`test_one_shot_report_hand_calculation_and_determinism` 验分币/分年/头部贡献/去最好年 CI 和字节复现。 |
| F8 `halt.py` | `evaluate_halt(..., health=Health(...), thresholds=HaltThresholds(...))` 纯函数返回 `should_halt` 和原因列表。`test_each_halt_rule`、`test_all_reasons_and_parameter_overrides` 验逐项、多项及阈值覆盖。**仅接口，未接现网**。 |

F3/F4 的 `ts` 为 bar 开盘 Unix 秒，单次输入只含一个币并按完整等间隔网格排列，缺 bar/乱序/重复拒绝。调用者负责只提交已完成 bar；特征第 i 行在 `ts[i]+bar_seconds` 才可用。特征采用 trailing mean、总体标准差（ddof=0）、`log(close[i]/close[i-k])`；warm-up 为 NaN，事件为 0，超过正/负阈值才生成 +1/-1。这些 API 不读取 D1 事后持仓完整性矩阵。

F4 输入事件表列为 ts/side（-1、0、+1），与 bars 的 ts/open 逐行对齐。信号 i 入场 open[i+1]，持有 N 根后在 open[i+1+N] 出场。新信号确认时仍处持仓期则记 `OVERLAPPING_POSITION`；恰在原仓出场开盘确认的信号允许在该开盘接续。缺下一开或出场开盘记 `INCOMPLETE_HORIZON`。返回 `{plans: [...], skipped: [...]}`；plans 中价格是历史模拟注记。每币完整历史调用一次，不可把相交分片分别规划后直接拼接。

F4 → F5：为每条 plan 显式添加 `funding_timestamps`，再传给原 `build_ledger`；`test_signals_ledger_report_chain` 覆盖串联到报告。预期结算日历必须独立于实际费率可用性，不能从已有费率键推导而漏掉缺失期；空日历仅表示持仓本无结算。仍按 `(entry_ts,exit_ts]` 计资金费，默认往返 12 bps；缺费率进入 excluded，不填代理。旧 gross_bps 输入兼容且仍视为已签名值；输入有价格时保存价格，未提供 gross_bps 时仍按简单收益公式计算。

F5 合成手算表（资金费原值合计 1.125 bps，费用列按方向签名，所有差值严格为 Decimal 0）：

| id | side | 入场 | 出场 | gross bps | RT bps | funding bps | net bps |
|---|---:|---:|---:|---:|---:|---:|---:|
| long | +1 | 100 | 101 | 100 | 12 | 1.125 | 86.875 |
| short | -1 | 100 | 99 | 100 | 12 | -1.125 | 89.125 |
| loss | +1 | 100 | 98 | -200 | 12 | 1.125 | -213.125 |
| flat | -1 | 100 | 100 | 0 | 12 | -1.125 | -10.875 |

### 一键报告

在 `research/` 目录运行（随附输入完全合成）：

```sh
PY=/Users/jenniferni/jenifer/venv-research/bin/python
$PY -m coinresearch.cli report --ledger examples/d3d4_ledger.json \
  --out-dir /tmp/coinresearch-d3d4-report --top-n 2
$PY -m pytest -q
$PY -m pytest -q -s tests/test_features.py tests/test_signals.py tests/test_f5_ledger.py tests/test_report.py tests/test_halt.py
```

输入为 CSV 或 JSON 数组 / `{"ledger": [...]}`，每行至少 coin、整数 Unix 秒 entry_ts/exit_ts、net_bps，且 entry_ts < exit_ts。只输入已完成 ledger 行；excluded 不作为观测。输出 `report.json`、`report.md`、`run_record.json`，键排序、不写运行时间戳；记录输入 SHA256 和所有生效参数。Decimal 汇总序列化为字符串以保留精度，CI 为浮点数。

分年按 **UTC 出场年份**；每币/每年统计笔数和净 bps 合计/简单均值，不解释为资金加权组合收益。头部贡献按净 bps 降序取 top-N（默认 1），除以全部净 bps 的有符号合计；总和 0 时为 null，负总和或亏损抵消时比例可能为负或大于 1。最好年按净 bps 合计最大选择，并列取最早年份。去除后对剩余交易等权求均值，以 IID 逐笔有放回 bootstrap 均值百分位给出 CI（默认 B=2000、90%、seed=20261022）。JSON/MD 明示方法忽略时间/跨币依赖，仅作描述性敏感性，不替代 G2；少于 2 笔返回 null CI 和 INSUFFICIENT_OBSERVATIONS，空输入有明确零计数。

示例共 6 笔，总净 bps=140，A=60、B=80；2022=20、2023=100、2024=20。top-2=100/140；剔除 2023 后 4 笔均值=10，CI=[10,10]。这些数字只用于工程验收。

### F8 仅接口

F8 **未接现网**：不发告警、不写现网、不调用 PAPER/LIVE 停机、不连接真实 PAPER、不回 R0 实操。健康字段是调用者提供的结构化离线输入。

`HaltThresholds` 默认：前向费后 CI 下界 ≤0 bps、回撤绝对幅度/回测参考幅度 >2 倍、实际与回测往返成本的绝对相对偏差 >50%、连续失败心跳 ≥3、成交失败率 ≥0.1、数据延迟 ≥60 秒。所有阈值可传参覆盖，仅为接口示例，不是策略参数或现网准则。回撤和成本必须非负且单位一致；参考值为 0 时当前正值触发、双方 0 不触发。非法/非有限输入报错，不默认为健康；多项失败返回全部稳定顺序原因。
