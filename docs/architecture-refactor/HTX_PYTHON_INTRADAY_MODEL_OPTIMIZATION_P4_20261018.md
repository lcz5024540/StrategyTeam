# HTX Python R2 P4：15m 三模型独立优化

## 1. 结论

P4 已按预登记搜索预算执行完成，最终结论为 `REJECT`。Donchian、Fibonacci、
EMA/VWAP 三个模型在 TRAIN 上均没有任何非基线变体同时通过 BASE 与
CONSERVATIVE 经济性门槛，因此没有可由 TRAIN 冻结并交给 VALIDATION 验收的候选，
也没有条件或参数获得 P7 晋级资格。

本阶段只使用六个月开发数据的前 80%：前 60% 用于选择，60%–80% 用于评价，末尾
20% TEST 未读取。没有修改 V1/V2 运行配置、V4 退出、PAPER、LIVE 或 admission。

## 2. 冻结实验合同

| 对象 | 内容 hash |
|---|---|
| K 线 manifest | `66c65fd4d1dd69aa70d0153e63807ea9cb767d84779b41eb9e3aa745c494afe0` |
| 市场事实 manifest | `98863f2304dfdce8981221cb9eb0a2df34299cdf71107d5dbb9bde33d3c5a8b1` |
| P4 计划 | `6d9bafd0345b10d15ba15a0bbea5cd5337de260310bf099fc198217c8f009f19` |
| 完整生命周期结果 | `4a13b40a880fbaac91ca8da5b87e86be05bcaf6bffc050aba0eda75aa68f580d` |
| P4 最终结果 | `0a0f077cc33b6961841899d3749070f8ab062a6b7ccaf1b3a9db8464bcc71a83` |

最终索引引用闭包共 461 个对象，完整性为 `VERIFIED`。生命周期产物明确记录
`test_segment=NOT_READ`，只包含 TRAIN、VALIDATION 两种 split；通用 walk-forward
的终点也固定为 80%，不会从末尾 TEST 读取 K 线。

实验共 22 个变体：Donchian 7 个、Fibonacci 7 个、EMA/VWAP 8 个。每个非基线
变体只改变一个条件或一个参数：

- Donchian：实体比例、突破 ATR 幅度、量比、60m 对齐、lookback 16/24；
- Fibonacci：60m 对齐、impulse/ATR、回踩区间中心、量比、source lookback 5/12；
- EMA/VWAP：60m 对齐、4h 不反向、ADX 上升、VWAP reclaim、目标空间、EMA fast 16/24。

60m/4h 条件只消费信号收盘时已经闭合的高周期 K 线；预热不足返回不通过。参数
变体重新生成同源候选，条件变体复用基线决策后过滤。两类变体使用相同 Gate、风险
sizing、下一根开盘、四成本、真实合约面值、逐事件资金费和既有退出核。

TRAIN 选择规则在运行前冻结：不少于 30 笔、覆盖模型基线至少 30%、BASE 净收益与
期望为正、PF≥1.30、CONSERVATIVE 净收益不为负。只有 TRAIN 通过后，候选才允许
在 VALIDATION 接受成功标准检验。

## 3. 三模型结果

### 3.1 Donchian breakout

TRAIN 基线为 288 笔、净收益 `-37.0373U`、净胜率 42.01%、PF `0.7724`。所有
单变量变体均未通过 TRAIN。按 TRAIN 期望排序最靠前的诊断变体是 60m 对齐：144
笔、净收益 `-15.3018U`、PF `0.8228`，仍是负期望，不能成为冻结候选。

该变体在 VALIDATION 的点估计看起来较好：40 笔、净收益 `+18.6205U`、净胜率
57.50%、PF `1.9928`，相对 90 笔模型基线的净胜率提高 10.83 个百分点。但它不能
推翻 TRAIN 失败，而且置换检验 `p=0.1184`、bootstrap 期望下界 `-0.0432U`，仅
2/5 时间窗口盈利。因此结论为 `REJECT`，不能把单段好结果升级为 60m Gate。
该条件确实改变候选集合：TRAIN 417/823 条通过、406 条拒绝；VALIDATION 133/269
条通过、136 条拒绝，所以失败原因不是条件未生效。

### 3.2 Fibonacci pullback

TRAIN 基线为 372 笔、净收益 `-58.6647U`、净胜率 30.11%、PF `0.4800`。TRAIN
诊断最靠前的是回踩区间中心：242 笔、净收益 `-27.8977U`、PF `0.5875`，仍未
通过选择门槛。

该变体在 VALIDATION 为 59 笔、净收益 `-6.9020U`、净胜率 33.90%、PF
`0.6134`；CONSERVATIVE 净收益也为负。置换检验、bootstrap 和五窗口全部未通过，
结论为 `REJECT`。候选层 TRAIN 494/957 条通过，VALIDATION 137/279 条通过。

### 3.3 EMA/VWAP momentum continuation

TRAIN 基线为 1,514 笔、净收益 `-102.0188U`、净胜率 61.29%、PF `0.6609`。
这再次说明高胜率没有抵消亏损幅度。TRAIN 诊断最靠前的是 4h 不反向：842 笔、
净收益 `-47.0844U`、PF `0.7249`，仍未达到经济性门槛。

该变体在 VALIDATION 为 274 笔、净收益 `-14.4039U`、净胜率 59.85%、PF
`0.7316`；净胜率只比模型基线提高 1.35 个百分点。bootstrap 期望下界
`-0.1235U`，仅 1/5 时间窗口盈利，结论为 `REJECT`。候选层 TRAIN
3,536/6,539 条通过，VALIDATION 1,255/1,905 条通过。

## 4. 结果解释

本次失败不是“候选太少”。三个诊断变体在 VALIDATION 的覆盖率分别为 44.44%、
60.20% 和 63.87%，Fibonacci 与 EMA/VWAP 仍有足够数量暴露负经济性。主要问题是：

1. Donchian 的 60m 对齐存在局部时段改善，但跨 TRAIN 和时间窗口不稳定；
2. Fibonacci 的回踩质量过滤只能减少亏损，不能把期望转正；
3. EMA/VWAP 净胜率较高但 PF 长期低于 1，说明平均亏损仍显著大于平均盈利；
4. 当前只调整入场单变量，而退出保持不变，P6 仍需对同一入场集合单独归因退出损失。

因此不能继续在 P4 扩大参数网格，也不能把 Donchian 60m 对齐直接加入交易条件。
按照原计划，P5 可以独立研究 4h；P6 可以在固定入场下研究 V4 持仓与退出。P7 没有
来自 P4 的 15m 合格条件可供组合。

## 5. 调用方式

```bash
cd htx-trend-python
.venv/bin/htx-trend intraday-optimize \
  --evidence research-data/six-month-btc-eth \
  --manifest-hash 66c65fd4d1dd69aa70d0153e63807ea9cb767d84779b41eb9e3aa745c494afe0 \
  --market-facts-manifest-hash 98863f2304dfdce8981221cb9eb0a2df34299cdf71107d5dbb9bde33d3c5a8b1

.venv/bin/htx-trend verify-evidence \
  --evidence research-data/six-month-btc-eth \
  --hash 0a0f077cc33b6961841899d3749070f8ab062a6b7ccaf1b3a9db8464bcc71a83
```

命令只读冻结行情与市场事实并写本地内容寻址 evidence。它不会连接私有账户、提交
订单、消费 TEST、生成 PAPER/LIVE admission 或修改线上策略配置。
