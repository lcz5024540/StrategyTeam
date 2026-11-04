# 独立机会策略：运行与验证

代码已接入完整 PAPER 生命周期，默认仍运行原候选策略。新策略尚未通过收益验收，不能开启 LIVE；本次没有修改服务器。

## 选择新策略

沿用 `HTX_REALTIME_STRUCTURE_15M_V1` 运行框架，新增候选版本
`OPPORTUNITY_SELECTION_V1`。原分支和默认配置保持原交易行为。
为研究实例分配独立 instance_key、PAPER 账本、capture 和 evidence 目录，
按现有实例配置与准入流程登记，不能复制现网账本冒充新实例。

```dotenv
HTX_RT_CANDIDATE=OPPORTUNITY_SELECTION_V1
HTX_RT_TARGET_MODE=OWN_STRUCTURE
HTX_RT_BAND_ATR=.10
HTX_RT_MIN_NET_R=0
HTX_RT_SELECTION_VARIANT=STRUCTURE
HTX_RT_ENTRY_ENABLED=false
```

这里 `0` 只取消原候选的固定 1.5R 门槛；新候选仍要求扣除成本后收益为正，
并通过全部账户、精度、盘口、追价、深度、资金费及预算检查。
原候选默认仍为 1.5R。PAPER 研究开仓需在独立实例通过原有准入后显式开启。
本版本的 LIVE factory 对新候选明确拒绝，不会因安装包而启用实盘。

## 策略实际行为

1. 从已闭合15m最近32根K线中取得20/32边界及已确认的2左2右拐点，双向发现机会。
   突破回踩、较高低点/较低高点的趋势回调、其余边界拒绝分别标记。
   区间拒绝可覆盖盘中刺穿再回收，但不会另造“假突破”身份重复开仓。
2. 目标优先取当前结构的最近对侧边界；外层突破没有边界时，用自己的箱体宽度投影。
   投影明确标记为估计目标，不是已观察到的阻力、成功概率或必须执行的止盈价。
   无目标机会照样记录，不生成可执行计划。
3. 两笔独立成交与有效盘口确认回收/回踩；结构、目标和ATR注册后冻结。
   修订使旧机会失效，同一锚点不会通过改模型名、修订或重启获得新开单身份。
4. 保留原极值/带宽与1.25 ATR底限失效位计算。盘口执行仍是有限价上限的IOC，
   不是保证成交的市价单，也不是无限等待的被动挂单。
5. 共用原持仓、初始止损、linked趋势保护退出、对账及费用结算。4H方向为入场软特征，
   旧趋势退出仍可能在下一根闭合K线上结束逆势仓；这是本版消融固定的退出基线。

## 排序和概率边界

- `STRUCTURE` / `DIRECTION` / `BOOK` / `BOTH` 只改变排序特征，不改变发现层或安全门。
- 冷启动按净R排序；选中的4H EMA20/60方向、ATR带内盘口不平衡只作同净R的排序依据。
  没有引入未经验证的加权总分。每批只有一个候选时，评分不能改变它的入场时机。
- 缺少4H数据明确为 UNKNOWN，不替换成1H方向；缺少必要15m/60m退出输入仍按原安全流程处理。
- 训练工具只接收完整平仓、成本已验证、特征形成于入场前、结果在截止前已知的净R样本。
  同一经济机会不能重复计入。每桶少于30个样本，概率保持未知。
- 足量样本提供净盈利频率、名义Wilson区间及净R分布。区间依赖独立样本假设，
  连续行情中的相关交易会削弱其解释力；这些是样本内统计，不宣称样本外校准通过。
  它们以“已成交且完整退出”为条件，不等于限价成交概率或目标先达概率。
- 同批全部候选都有兼容统计时按平均净R排序；有候选支持不足，则整批回退到同一净R基准，
  避免把经验期望值和计划赔率混为一个分数。提交前仍再次核验盘口与预算。

## 离线命令（Python 3.11+）

在 `htx-trend-python` 目录执行，使用本分支虚拟环境，或显式设置 `PYTHONPATH=src`。

```bash
python -m htx_trend.realtime.opportunity_smoke --contract ETH-USDT --output /tmp/opportunity-public
python -m htx_trend.realtime.opportunity_replay ablate --tape /path/tape.json.gz --sha256 SHA256 --output /path/report.json
python -m htx_trend.realtime.opportunity_replay fit --samples /path/closed-outcomes.json --cutoff-ms CUTOFF --policy-hash POLICY_SHA256 --output /path/model.json
```

训练样例字段及合法性约束见 `tests/test_opportunity_selection.py::samples` 和
`realtime/selection.py::fit`。策略参数、退出实现、执行成本、延迟必须绑定同一 policy hash。
回放可用 `--calibration` 指定模型；模型截止时间必须早于整个评估区间。
PAPER 可通过 `HTX_RT_CALIBRATION_FILE` 与 `HTX_RT_CALIBRATION_SHA256` 配置同一文件及内容哈希；
缺失、篡改、未来模型或政策不兼容都会阻止新开仓，不能静默替换。

正式数据先经已有 COS `ReplayCache.lease` / `cache-run` 下载并持有读取租约，
再把原始分段转换为 `htx-realtime-opportunity-tape-v1`；不可只用订单或OHLC替代完整输入。
本次没有新写自动数据转换器或自动训练任务；原始盘口、合约元数据、费用、资金费、延迟、
历史配置和逐条引用闭包缺失时，报告明确阻止正式验收。

四组均使用同一 broker、退出、预算和初始空仓，逐条输入回放所有机会。
70%诊断/30%留出按时间切分，不用留出选阈值。报告给出逐笔成交、拒绝原因、净权益、
费用、最大回撤和往返次数；边界统计沿用此前最后一个已观察权益，绝不生成报价或成交。
当前不自动挑赢家，不自动合并或部署。

## 关闭与回滚

关闭新入场后继续运行当前包，管理已有仓位并完成结算。新仓记录候选版本、结构族、
参数指纹、排序变体和模型哈希，历史仓保持原持仓策略快照。
在新模型仓位/未决订单仍存在时，不得回退到不认识 `RT_TREND_PULLBACK` 的旧包。
先使用当前包仅管理持仓，确认全部结算后，再按原冻结包流程回滚；禁止删除恢复账本。

## 后续验收

研究负责人先准备完整、可核验的历史输入和独立训练区间；用本版四组回放验证机会漏斗、
实际净收益及回撤，再决定阈值/排序是否值得启用。现有公开前缀和本轮合成用例不足以完成
这一步。任何线上启用均需独立部署授权，不能把12个结构候选当成12笔确定盈利交易。
