# 15m开仓执行修复交付

日期：2026-11-02；范围：Python候选代码与离线验证；作者：Codex；状态：代码已验证，未生产部署。

修复基于打包主线 `support_htx_python@4cbd5ae7dc6cc344b7099849ed7011909907e967`，分支 `codex/15m-entry-execution-fixes`。17:04 CST只读核验15m LIVE仍运行dead38c5，未配置maker，故实际仍为市价；4H PAPER为dbfee173，4H LIVE未运行。该快照不是交付时的实时状态。

## 问题与处理

| 问题 | 修复及作用域 |
|---|---|
| PAPER将DOGE/TRX等限价截为两位 | 限价/改单使用真实contract tick整数倍，买向下、卖向上；VWAP不再截为两位。无效tick拒绝。旧账本不重写。 |
| 限价配置未落实到LIVE | 读取ENTRY_ORDER_TYPE，limit启用post-only maker；冲突配置失败。仅支持固定价、无改单、超时撤单；缺报价不回退市价。 |
| 未决挂单恢复不完整 | 提交前保存client id、策略版本与deadline；先查、逾期撤、再查终态。未知结果保留意图/预算；部分成交按真实量管理，重复回报不重复记账；风险只在持仓整体终态释放，与账本同事务。 |
| 收益计划空头误选远端目标 | 新structure-cost-v1选LONG近端下界/SHORT近端上界；原止损结构不变。报价、限价、费用、净R和风险预算使用同一计划。 |
| PAPER/LIVE候选判断不等价 | 新合同让PAPER复用已有counter-market、candidate-state、mixed和Fib质量门，传入实际EMA/ADX与相同momentum计算。未新设成交量/波动率过滤。账户预算/准入仍各自真实，不能宣称两账户订单相同。 |
| 保护退出后旧信号开回 | confirm-v1仅确认退出；LIVE linked-v1现接同一恢复价门，要求退出bar后的新闭合bar和盘口恢复，缺历史退出事实拒绝该同向候选。模型换名不能绕过。 |
| 心跳持仓为0、队列误报满仓 | 按账户ID统计主仓，并排除paper及镜像，保留UNKNOWN占用。排队与真正满仓分开，队列终态可覆盖临时诊断。缓存不覆盖上轮降级。 |
| 4个合约持续“证据不可用” | 真实公开API共25次读取证明是日线不足121：PONS63/BOME73/RAY56/ACE79；改报具体周期、实际数与所需数。不缩短指标历史或伪造数据。 |

## 统一成本口径

显式设置 `HTX_TREND_ENTRY_PLAN_VERSION=structure-cost-v1`；不设置保持legacy，支持对照回放。

设开仓计划价P、失效位S、最近结构目标T，滑点预算δ=ATR×原slippage_atr_fraction：限价最差开仓=P，市价最差开仓=P±δ。目标/止损退出分别向不利方向计δ，开仓费按最差开仓价计算，退出费按各自退出估价计算。

净收益=到目标的方向价差−开仓费−目标退出费；净风险=到止损的方向损失＋开仓费＋止损退出费；净R=净收益/净风险。每项为单位价格金额，乘contract_size和contracts得到USDT计划金额。价差已包含滑点，不再重复扣相同滑点。资金费未来未知，不填零；LIVE暂以账户taker费率保守估算被动开仓，不将shadow maker费率当作确认费率。

几何无效或净收益≤0拒绝；**0.25字段没有被偷偷启用为阈值**。结构净R仍为价格计划，不是盈利概率或保证退出价。旧版本模型公式和退出公式保持；新合同会因真实质量/成本/风险而拒绝或缩量，需独立统计。

## 真实输入复算

[输入清单](evidence/entry-fixes-20261102/plan-inventory.json)来自31份PAPER快照的42候选和5笔LIVE绑定的gate/factor，共47。原始文件SHA和每条证据哈希保留；[结果](evidence/entry-fixes-20261102/plan-audit.json)由生产价格函数复算，限价情景明确为0bps，并不假设成交。

| LIVE输入 | 旧市价净R | 统一市价净R | 0bps限价净R |
|---|---:|---:|---:|
| TRX SHORT | 1.6054 | 1.5367 | 1.6465 |
| ETH LONG | 1.8615 | 1.4162 | 1.5295 |
| SUI LONG | 0.9000 | 0.7355 | 0.8478 |
| ZEC SHORT | 2.8927 | 1.5115 | 1.7262 |
| TRX LONG | 1.0394 | 0.8664 | 1.0828 |

PAPER42候选：统一市价发现2条几何无效；限价情景1条净收益非正。ORCL旧市价净R−0.0611，统一市价目标已在错误方向；0bps限价净R仍接近零（约0.0000093）。这说明算清净R后仍需正式阈值验证，不能以“正数”宣称好机会。

**不是完整收益回放**：旧78笔PAPER没有直接输入闭包；并非所有历史候选/拒绝/盘口都有资料，缺挂单成交概率、全部资金费及逐时组合权益。不能报告该修改已提高净收益、已降低回撤或已解决盈利集中。保留原模型，按独立样本诊断/留出回放定阈值。

复算（在htx-trend-python目录）：
```bash
PYTHONPATH=src python -m htx_trend.entry_plan_audit ../docs/htx-trend-python/evidence/entry-fixes-20261102/plan-inventory.json --output /tmp/entry-plan-audit.json
```

## 验证与状态机

全Python回归708通过，81条SQLite默认datetime适配器弃用警告；[原始输出](evidence/entry-fixes-20261102/pytest-full.txt)。包含15m/4H默认回放、PAPER/LIVE门、原有费用/退出、订单恢复、账本事务回滚、真实tick/非十进制tick、迟到累计修订、价格计划及账户隔离。基线回放测试漏传profile和哈希测试越界调用已修正，仅测试接线，没有改4H策略。

| 入口/场景 | 本地结果 |
|---|---|
| env limit→gate→intent→request | post_only、tick限价与计划一致，state失败不place |
| 零/部分成交→超时 | 读→撤→读；仅终态释放/按实际量保留 |
| 撤单超时/成交竞态 | UNKNOWN维持预算；fill由再查询确定 |
| 重启/关闭新maker | 读取原deadline/client id，无重发 |
| 重复/迟到累计回报 | 重复无双记账/双缩量，迟到量修正风险 |
| 权威账本故障 | 订单/持仓/费用事务回滚，重试可恢复 |
| linked完整保护退出 | 无新bar/未恢复/缺事实拒绝，反向仍走原门 |
| 旧仓归属 | 新仓提交前固定版本；旧无标签仓baseline，已有标签不随env切换 |

流程图：[entry-fixes.drawio](diagrams/entry-fixes.drawio)。codegraph工具本会话不可用；调用点以源码及测试验证，未伪造图谱结果。

真实HTX行情接口已读取，结果见[public-coverage.json](evidence/entry-fixes-20261102/public-coverage.json)。**真实金融接口未进行候选版本的下单/撤单，MySQL并发锁未在测试服务验收；SQLite事务测试不能替代它们。** 上线验收保持待办，遵守不自动部署边界。

## 部署与回滚材料

1. 审核本分支diff及本报告；以最新代码commit打包、verify并核对SHA、source_commit和working_tree_dirty=false。LIVE、PAPER包分开；不能复用旧包授权绑定。
2. 根据独立15m实例准备配置，参考[配置片段](../../htx-trend-python/deployment/15m-entry-fixes.env.example)。限价开启时显式CANCEL、max_wait_bars=1、max_amends=0，保留审批过的offset/风控；新合同与linked需单独统计。片段默认ENTRY_ENABLED=false，不授予交易权限。
3. 补齐测试环境真实下单/部分成交/超时撤单/重启与MySQL持久化矩阵，完成策略准入及净R完整回放。由用户另批生产更新，不顺带改4H、TradeX、LIVE资金或账本。
4. 更新获批后核对启动日志instance/account/strategy/order_type/entry_plan/continuation，并核对交易所订单type、post_only、price、成交均价与账本。PAPER成绩与LIVE分列，已有C钟不重开。
5. 异常时先关闭新入场，保留本版本对既有仓与未决订单的管理。**存在本版本未决限价单/候选仓时不得直接退回缺恢复能力的旧包**；确认挂单终态、仓位已妥善处理后，按批准的空仓回滚恢复包与配置。保留exit_state、MySQL、paper-state与全部证据。

## 本地候选包回执

源码commit：`f748bae7cad31cebb01d3e2091bce1c631b941bd`，工作树干净构建，两个包verify均PASS；[完整绑定与SHA](evidence/entry-fixes-20261102/packages.json)。包仅保存在本机 `/tmp/htx-entry-fixes-20261102-f748bae7/`，未上传或冻结到服务器。

- LIVE：`0.1.7-live-f748bae7`，SHA256 `451b2c9cd20486236b8292b40345433f3b1ae4b1512eb6f494dfaa389adc23c7`。
- PAPER compact-v2：`0.1.7-evidence-v2-f748bae7`，SHA256 `f51216e0def07b8af3943e3017fec719b758a0b9c035bbf1e08df72794feb9f3`。

业务检查点：8231dd4e（精度/诊断）、4a568564（限价恢复/重入）、7ff1cdc5（结构成本）、f748bae7（最终回归/账本风险终态）。根治理检查被历史H-10阻断，未绕过或伪造commit；业务提交独立完成。
