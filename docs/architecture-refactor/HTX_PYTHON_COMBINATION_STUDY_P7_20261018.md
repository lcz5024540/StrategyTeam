# HTX Python R2 P7：条件组合与嵌套稳健性门禁

## 1. 结论

P7 已执行，15m 与 4h 均得到 `NO_ELIGIBLE_INPUTS / REJECT`。P4、P5、P6 共十二个
profile/model/stage 上游结果中，没有一个结论为 `ELIGIBLE_FOR_P7`，因此组合集合为空。
按照预注册规则，nested walk-forward、组合消融、四成本组合回测、置换和 bootstrap
均记录为 `NOT_RUN_GATE_FAILED`，不能用失败条件拼接后继续搜索。

本阶段没有读取末尾 TEST，没有产生 P8 候选，也没有修改策略、正式 V4、PAPER、LIVE、
admission 或运行配置。

## 2. 实际输入与终态证据

| Profile | 入场研究输入 | 退出研究输入 | 合格候选 | P7 结果 | 闭包 |
|---|---|---|---:|---|---|
| 15m | P4 `0a0f077c...1a83` | P6 `d1b9b7af...947c` | 0/6 | `61ca8374b8fe237862a58226fe4eb2f6314b6219b7cd9541134b067363498ecb` | 465 对象 VERIFIED |
| 4h | P5 `36a037a8...52c3` | P6 `3108ce6d...44af` | 0/6 | `3fdd312a8c8cef9005a4e1155a86c70375ce926b94d825452cfc10f3f02c36fc` | 205 对象 VERIFIED |

每个 profile 审计三个模型的入场结果和三个模型的退出结果。只有同时满足
`conclusion=ELIGIBLE_FOR_P7` 且具有冻结 `selected_by_train` 的结果才能成为 nominee。
上游 TEST 必须是 `NOT_READ`；任何已读取 TEST 的输入会直接报错并拒绝建立 P7 计划。

## 3. 门禁后的调用逻辑

```text
P4/P5 入场单变量结果 + P6 退出单变量结果
  → 校验 profile、artifact kind、TEST=NOT_READ
  → 建立六行 eligibility matrix
  → 只保留 ELIGIBLE_FOR_P7 + frozen nominee
  → 当前 eligible nominees = 0
  → combination variants = 0
  → nested walk-forward = NOT_RUN_GATE_FAILED
  → ablation / four costs / permutation / bootstrap = NOT_RUN_GATE_FAILED
  → P7 REJECT；不进入 P8
```

流程图见
[`HTX_PYTHON_COMBINATION_STUDY_P7_20261018.drawio`](HTX_PYTHON_COMBINATION_STUDY_P7_20261018.drawio)。

这不是“工具没有运行”。`combination-study` 已建立内容寻址的冻结计划、逐行审计全部
上游结论并写入终态结果。nested walk-forward 没有运行，是输入门禁的预期行为；运行
空组合或把失败条件强行组合都会破坏消融归因并扩大数据窥探范围。

## 4. 调用方式

```bash
cd htx-trend-python
.venv/bin/htx-trend combination-study \
  --evidence research-data/six-month-btc-eth \
  --profile INTRADAY_15M \
  --upstream-result-hashes \
    0a0f077cc33b6961841899d3749070f8ab062a6b7ccaf1b3a9db8464bcc71a83 \
    d1b9b7afed2ced48c6cf0503c6226f7179a62006cdbe0c8e2ab36ba77038947c

.venv/bin/htx-trend combination-study \
  --evidence research-data/p5-swing-expanded \
  --profile SWING_4H \
  --upstream-result-hashes \
    36a037a837e142f6c768b38438787935e5b400b1839ffdeae918697f198152c3 \
    3108ce6d55f4821e758e2bed3bfd9ad78245b653834423dc1174850a6bdd44af
```

下一步不能进入 P8。若要继续研究，需要先增加独立数据或提出新的预注册单条件假设，
重新通过对应的 P4/P5/P6 单变量门禁；不得读取当前保留的 TEST 来挑选新条件。
