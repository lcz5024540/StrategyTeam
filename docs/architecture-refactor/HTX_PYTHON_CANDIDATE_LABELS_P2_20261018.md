# HTX Python R2 P2：候选全量标签与准确率归因

## 1. 结论

P2 已把 15m 与 4h 冻结数据中的每根闭合 K 线展开为三个独立模型评估。每条记录
要么携带该模型的候选及结果标签，要么携带同源生产条件给出的明确拒绝原因。

本阶段没有选择新阈值、没有修改策略条件、没有修改 V4 退出，也没有生成 PAPER
或 LIVE admission。这里的净收益是每个候选按一张合约独立标注的研究结果；候选可
重叠，不能与账户级生命周期回测、真实订单或可同时持有的组合收益混用。

## 2. 冻结输入和证据

| 对象 | 内容 hash |
|---|---|
| K 线 manifest | `66c65fd4d1dd69aa70d0153e63807ea9cb767d84779b41eb9e3aa745c494afe0` |
| 市场事实 manifest | `98863f2304dfdce8981221cb9eb0a2df34299cdf71107d5dbb9bde33d3c5a8b1` |
| P2 双 profile 索引 | `83c7a2089e1ec2c1b21bcb85d1185fdb7dfcb6ad9123257d973b483a438aaae7` |
| 15m 结果 | `88df297a54a608ead38554ac94f87363bd7e699bc28bc2b33136eb3f20d055df` |
| 4h 结果 | `47822133de398c7a83d90830247b32fcda01ffa2f26b33ea33c44268c6e27621` |

索引引用闭包共 519 个对象，校验为 `VERIFIED`。观察行按 2,000 条封存为不可变
分块；每条记录绑定 dataset、market facts、strategy/spec/feature/condition hash、
`decision_hash`、输入 `window_hash` 和未来 `label_window_hash`。聚合结果可按报告中的
维度过滤分块行重算。

## 3. 标签口径

```text
闭合信号 K 线及以前
  -> 公共指标和 feature vector
  -> 生产 java_entry_contract 的逐模型评估
  -> Candidate 或 RejectCode
  -> 冻结 observation_id / window_hash

仅 Candidate 进入未来标签侧
  -> 下一根 K 线开盘，加入 BASE 方向性滑点
  -> 15m 最多观察 96 根（24 小时）
  -> 4h 最多观察 42 根（7 天）
  -> 同根同时触发时先止损
  -> 实际 HTX 合约面值 + 逐 funding_time 资金费
  -> target-first / stop-first / horizon
  -> direction / MFE / MAE / fee / funding / net PnL
```

BASE 手续费率为 `0.0004`，方向性滑点率为 `0.0003`。标签以一张合约计算；方向
准确率使用完整观察窗口末端收盘价与下一根开盘成交价比较。观察窗口不完整时标签为
`UNKNOWN_INCOMPLETE_HORIZON`，不会强制记为失败。预热期也不丢弃：BTC/ETH 各自
前 124 根 K 线为每个模型记录 `INSUFFICIENT_BARS`。

## 4. 六个月开发数据结果

| Profile | 模型评估 | 候选 | 覆盖率 | 已知标签 | 方向准确率 | Target-first | 净胜率 | 单候选期望 | 净收益 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 15m | 105,408 | 13,329 | 12.65% | 13,310 | 45.85% | 63.01% | 57.25% | -0.0461U | -613.67U |
| 4h | 6,588 | 971 | 14.74% | 941 | 54.09% | 62.38% | 66.31% | +0.0193U | +18.15U |

模型切片：

| Profile / 模型 | 已知标签 | 净胜率 | 单候选期望 | 净收益 |
|---|---:|---:|---:|---:|
| 15m Donchian | 1,352 | 46.60% | -0.0509U | -68.84U |
| 15m EMA/VWAP | 10,443 | 61.07% | -0.0472U | -492.56U |
| 15m Fibonacci | 1,515 | 40.40% | -0.0345U | -52.27U |
| 4h Donchian | 87 | 59.77% | +0.4597U | +39.99U |
| 4h EMA/VWAP | 760 | 69.08% | -0.0357U | -27.14U |
| 4h Fibonacci | 94 | 50.00% | +0.0564U | +5.30U |

15m 的关键事实是“胜率较高但期望仍为负”。EMA/VWAP 净胜率超过 61%，却贡献
约 `-492.56U`，说明后续工作应先解释盈亏幅度和退出路径，不能只追求预测正确率。
4h 的独立标签总和略正，但 Donchian/Fibonacci 样本分别只有 87/94，且候选之间
重叠；它不满足新 OOS、组合容量或生命周期准入要求。

## 5. 条件分布和可区分度

报告已经按 model、side、symbol、本周期 regime、高周期 regime、ADX、ROC、ATR
分位、量比、EMA/VWAP 距离、价格斜率、EMA 斜率和退出原因切片。数值变量使用
开发数据四分位作描述性分组，不把分位边界写入条件库。

净盈利与非净盈利候选之间的绝对标准化均值差：

- 15m 最大为 ATR% `0.1101`，其次 ADX `0.0818`；
- 4h 最大为 ADX `0.1634`，其次 EMA 斜率 `0.1043`。

这些都是较弱的单变量区分度，当前证据不支持在 P2 直接选择某个阈值。高周期状态
为 `UNKNOWN` 的记录被单列保留，主要来自 1d EMA120/斜率所需的 121 根预热，不能
把它们自动解释为中性通过。

## 6. 调用命令

```bash
cd htx-trend-python
.venv/bin/htx-trend candidate-labels \
  --evidence research-data/six-month-btc-eth \
  --manifest-hash 66c65fd4d1dd69aa70d0153e63807ea9cb767d84779b41eb9e3aa745c494afe0 \
  --market-facts-manifest-hash 98863f2304dfdce8981221cb9eb0a2df34299cdf71107d5dbb9bde33d3c5a8b1
```

命令只写本地内容寻址研究 evidence，不消费新的 TEST checkpoint，不连接私有账户，
不下单。当前六个月数据已被看过，因此产物明确标记
`ALREADY_SEEN_DEVELOPMENT_DATA_NOT_NEW_OOS`。

## 7. P2 对下一阶段的约束

P3 可以使用这些记录审计条件是否真的改变候选集合，并验证命中数、准确率 lift 和
净期望 lift。P3 仍需预先登记单变量实验，不得把本报告里表现较好的分位直接提升为
门禁。任何候选规则冻结后，最终评价仍需使用新的未见数据。
