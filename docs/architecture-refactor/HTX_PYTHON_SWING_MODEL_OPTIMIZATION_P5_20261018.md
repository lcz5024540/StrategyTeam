# HTX Python R2 P5：4h 三模型独立优化与样本扩展

## 1. 结论

P5 已完成，最终结论为 `REJECT`。4h/1d 数据从六个月扩展到十八个月，每个币种的
4h K 线由 1,098 根增加到 3,288 根，约为原来的 2.995 倍。Donchian 和 Fibonacci
在固定 1d 趋势条件后，TRAIN/VALIDATION 的完整交易仍不足 30 笔，结论为
`INSUFFICIENT_EVIDENCE`；EMA/VWAP 有足够交易且基线盈利，但 TRAIN 选出的参数在
VALIDATION 没有产生任何准确率或收益增益，统计稳健性也未通过，结论为 `REJECT`。

本阶段只读取十八个月开发数据的前 80%：前 60% 用于选择，60%–80% 用于评价，
末尾 20% TEST 保持 `NOT_READ`。没有修改 V1/V2 运行配置、V4 退出、PAPER、LIVE
或 admission，也没有把任何条件或参数升级为 Gate。

## 2. 扩展数据与证据闭包

冻结区间为 `2025-04-11T00:00:00Z` 至 `2026-10-11T00:00:00Z`。

| 对象 | 内容 hash / 结果 |
|---|---|
| 4h/1d K 线 manifest | `81c4e09d30e36cb2f17212669670c8fe395c2999ad9341675cf88f2527fc1ee2` |
| 市场事实 manifest | `61ea6116b9a2a1f85fed31e81c6d0d203152485d1284607abf1b28aeee248794` |
| P5 计划 | `b8a5125c4d4565d2981e84a501cfd7ff0cb702d8ce5e36941cb50cda2fcb59a9` |
| 完整生命周期结果 | `5a7fe11aa4e459ecbeefd891299a63f1967ee40d90576b2cc56e0632226b1e3c` |
| P5 最终结果 | `36a037a837e142f6c768b38438787935e5b400b1839ffdeae918697f198152c3` |

K 线 manifest 的 55 个对象、市场事实 manifest 的 143 个对象、P5 最终结果的 201
个对象引用闭包均为 `VERIFIED`。BTC-USDT 与 ETH-USDT 各有 3,288 根连续 4h、547
根连续 1d K 线，以及 1,645 个 HTX 历史资金费事件。资金费按交易所实际
`funding_time` 和对应 rate 逐事件结算，没有假设固定费率或自行生成八小时间隔。

## 3. 冻结研究合同

P5 共运行 18 个变体：每个模型有一个不读取 1d 的 legacy 诊断对照和一个固定 1d
基线；可选择的单变量研究预算为 Donchian 4 个、Fibonacci 3 个、EMA/VWAP 5 个。

- 所有可选择变体先要求已闭合 1d 的 EMA20/60/120 排列、20 点 OLS 价格斜率和
  ROC12 与方向一致，且 ADX14 不低于 16；不足 121 根 1d K 线记录为 `UNKNOWN`
  并拒绝，横盘不能当作中性通过。
- Donchian 只研究预登记 lookback `42/84/126/168`，不引入 P4 失败的 15m 条件。
- Fibonacci 只允许 P3 获得研究资格的 MACD，以及 source lookback `5/12`。
- EMA/VWAP 只允许 P3 获得研究资格的真实价格斜率，以及 fast EMA `16/24`、
  momentum lookback `9/18`。
- 每个候选相对所属模型的 1d 基线只改变一个确认条件或一个入场参数。

决策窗口预登记为 200 根 4h K 线。第一次诊断运行暴露 lookback 168 至少需要 173
根预热，而旧 160 根窗口会让该变体错误地产生零候选。通用研究器现会在参数所需
预热超过决策窗口时直接失败；修正后使用新计划 hash 重跑全部变体。被替代的诊断
结果 `759b03cae99e887739ca5ce45aab9bee2d49746bca8033e8ec3f002f0e863f25`
仍在本地 evidence 中保留，但不参与本报告结论。

TRAIN 选择门槛固定为：完整交易不少于 30、覆盖 1d 基线至少 30%、BASE 净收益和
期望为正、PF≥1.30、CONSERVATIVE 净收益不为负。VALIDATION 另要求净胜率提高
至少 5 个百分点、置换 `p<0.05`、bootstrap 期望下界大于零且至少 4/5 时间窗口
盈利。VALIDATION 不参与参数选择。

## 4. 三模型结果

### 4.1 Donchian breakout

1d 基线在 TRAIN 为 22 笔、净收益 `+0.5532U`、PF `1.0088`，本身未达到 30 笔
和 PF 门槛。按 TRAIN 排名最好的诊断参数为 lookback 168：11 笔、净收益
`+29.9651U`、PF `2.5406`，CONSERVATIVE 净收益 `+28.9437U`；样本仍远低于
30 笔，不能被选中。

该参数在 VALIDATION 为 7 笔、净收益 `+24.4689U`、PF `164.8263`、净胜率
85.71%，置换 `p=0.0300`，但只有 2/5 时间窗口盈利。极高 PF 来自很少的亏损额和
七笔样本，不代表稳定策略。结论为 `INSUFFICIENT_EVIDENCE`。

TRAIN 候选诊断中，legacy 可用候选 172 条；1d 基线通过 59、明确失败 60、因早期
1d 预热不足而 `UNKNOWN` 53。VALIDATION 已无 1d 预热缺口。

### 4.2 Fibonacci pullback

1d 基线在 TRAIN 为 22 笔、净收益 `-0.5712U`、PF `0.9844`。P3 合格假设
Fibonacci+MACD 降至 20 笔、净收益 `-10.1854U`、PF `0.7219`，没有在扩展样本上
复现 P3 的候选标签增益。按 TRAIN 排名最好的诊断参数为 source lookback 5：19
笔、净收益 `+4.8088U`、PF `1.1553`，仍未通过样本量与 PF 门槛。

source lookback 5 在 VALIDATION 只有 5 笔，净收益 `+16.5411U`、PF
`11.5184`，置换 `p=0.2584`，仅 1/5 时间窗口盈利。结论为
`INSUFFICIENT_EVIDENCE`，不能把少量正收益描述为已验证优势。

### 4.3 EMA/VWAP momentum continuation

1d 基线在 TRAIN 为 146 笔、净收益 `+44.0510U`、PF `1.4038`；说明固定 1d 趋势
条件在开发段能形成正经济性。TRAIN 选出的唯一最优参数为 fast EMA 24：148 笔、
净收益 `+46.5994U`、PF `1.4362`，CONSERVATIVE 净收益 `+32.6015U`。

但 fast EMA 24 在 VALIDATION 与 1d 基线完全相同：均为 51 笔、净收益
`+19.9861U`、PF `1.7794`、净胜率 84.31%。净胜率和期望 lift 都为零，置换
`p=0.1864`，只有 2/5 时间窗口盈利，因此结论为 `REJECT`。P3 的真实价格斜率
变体在 VALIDATION 为 51 笔、净收益 `+20.2602U`、PF `1.7986`，改善只有
`0.2741U`，并且 TRAIN 表现低于基线，没有被 TRAIN 选择。

EMA/VWAP legacy 对照在 VALIDATION 为 174 笔、净收益 `+31.9279U`、PF
`1.3330`；1d 基线减少交易后提高了单笔期望和 PF，但当前实验没有证明哪一个新增
单变量能稳定优于该 1d 基线，所以不能晋级 P7。

## 5. 解释与下一阶段边界

样本扩展解决了“4h 总历史只有六个月”的数据问题，但没有自动解决稀疏模型的有效
交易数。固定 1d 趋势门禁把 Donchian/Fibonacci 的生命周期样本进一步压低；继续
扩大参数网格会增加数据窥探，不能替代积累新样本。EMA/VWAP 已显示开发段正收益，
但新增参数没有样本外式验证增益，且时间窗口稳定性不足。

因此 P5 没有向 P7 提名条件或参数。P6 可以按原计划固定入场集合，独立比较 V4
持仓/退出条件是否改善亏损幅度和时间窗口稳定性；不得把 P5 的正点估计直接放入
PAPER/LIVE，也不得读取末尾 TEST 来继续挑参。

## 6. 调用方式

```bash
cd htx-trend-python
.venv/bin/htx-trend swing-optimize \
  --evidence research-data/p5-swing-expanded \
  --manifest-hash 81c4e09d30e36cb2f17212669670c8fe395c2999ad9341675cf88f2527fc1ee2 \
  --market-facts-manifest-hash 61ea6116b9a2a1f85fed31e81c6d0d203152485d1284607abf1b28aeee248794

.venv/bin/htx-trend verify-evidence \
  --evidence research-data/p5-swing-expanded \
  --hash 36a037a837e142f6c768b38438787935e5b400b1839ffdeae918697f198152c3
```

命令只读取公开冻结数据并写本地内容寻址 evidence。它不会读取私有账户、提交订单、
消费 TEST、生成 PAPER/LIVE admission 或修改线上配置。
