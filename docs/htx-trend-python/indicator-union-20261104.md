# 五模式并集交易接入交付

2026-11-04 · Python · 基线 `support_htx_python@007653653b707f1f2a31315b683eca2011deb237` · 分支 `codex/indicator-union-entry`。

五模式并集已接入实际 Python worker、IOC broker、权威账本和独立ATR退出；三档均完成多空开平仓验证。未合并、部署、操作生产或发送交易订单。功能通过，不代表并集收益或LIVE准入通过。

## 改动与依据

1. **指标与并集**：新增`realtime/indicator_union.py`，实现影线/CCI/通道/MACD、EMA穿越/CMF及价差五组组合。任一命中即候选，同bar合并标签并去重，不同时开五笔。新模型`RT_INDICATOR_UNION`有独立归属，旧入口保持默认。详见[完整规则](../../htx-trend-python/release/INDICATOR-UNION.md)。
2. **开仓**：复用现有共享风险、最差限价、真实tick和IOC执行。并集不新增固定本金/0.5%/净R指标筛选门；资金与数量来自现有执行配置。目标和止损在提交前冻结，费用和最差成交风险仍记录。现有保护退出重入门不绕过。
3. **退出与恢复**：`ATR_1_1`、`ATR_2_1`、`ATR_2_2`分别表示止损/止盈为1/1、2/1、2/2ATR。持仓保存档位，后续改配置不改变旧仓。新仓按实时可执行盘口触及目标/止损退出，不套旧V4保护；旧模型仍走原退出。目标退出已触发后盘口恢复也继续管理未决退出；先撤入场余单、未知结果先对账、只平实际剩余量。关闭新入口不关闭持仓管理。
4. **封印与发布**：持仓封印纳入并集代码，仅兼容逐文件核验的00765365前版本向量，未知向量拒绝。打包工具新增显式`--union-exit`，PAPER/LIVE包都绑定档位、模型、参数和spec哈希，默认关闭；六组打包验证通过。沿用现有计划状态表，无新增业务迁移。

## 验证证据

| 验证 | 结果 | 证据 |
|---|---|---|
| Python全套（含隔离MySQL） | 1,244通过，0跳过；89条已有SQLite日期适配器弃用警告 | [完整输出](evidence/indicator-union-20261104/python-tests.txt) |
| 并集专项 | 35通过，含三档×多空×目标/止损、真实factory→worker→broker→ledger→management、关闭入口后继续退出、IOC零/部分/未知、重复/修订/重启/断线、六组包 | `tests/test_indicator_union.py` |
| 隔离MySQL | 9通过；127.0.0.1:23317，随机测试库，计划/退出状态重启恢复、幂等、账户隔离、累计成交与回滚 | `tests/integration`；无生产DB配置 |
| COS指标复算 | 52合约、1,834 FRAME、14,672值；最大误差约1.46e−11；方向差异0 | [audit](evidence/indicator-union-20261104/feature-audit.json) |
| 真实公开HTX API | 无凭据取得ETH合约规格、深度、160根15m/1h，指标正常计算 | [响应及引用](evidence/indicator-union-20261104/public-api.json) |
| 旧持仓兼容 | 精确前版本10个源码哈希逐一匹配Git；未知向量拒绝；旧默认与600样本退出等价回归通过 | [兼容核验](evidence/indicator-union-20261104/compatibility.json) |
| 编译/静态 | compileall、新增模块F类静态检查、diff-check通过 | 同本轮验证命令 |

LIVE委托网络响应使用测试执行器验证，未真实发单；真实API证据仅限公开行情，不能冒充真实IOC成交。PAPER平仓沿用原报价撮合，不代表全数量在真实盘口可成交。原COS研究是5min采样，本实现是实时BBO，所以原高胜率样本不能直接作为新并集收益。

## 可复现命令

在本业务仓根目录，Python3.11以上且已安装开发依赖：

```bash
python -m pytest -q htx-trend-python/tests/test_indicator_union.py
# 仅自行准备的隔离回环MySQL非3306端口；不读取生产env
HTX_TEST_MYSQL_PORT=23317 python -m pytest -q htx-trend-python/tests
PYTHONPATH=htx-trend-python/src python htx-trend-python/research/indicator_union/audit_features.py \
  --frames /absolute/data/cos-opportunity-atlas-20261104/full-frames.jsonl.gz \
  --research-root /absolute/CoinStrategy-realtime-structure/htx-trend-python/research \
  --output /absolute/output/feature-audit.json
```

审计脚本直接重算原始FRAME，与原研究公式对照，记录数据及参考源码SHA256；不读取收益排名或受保护留出。原始数据SHA为`4de9c745d2f3864429295941b2abd715922cee356698b4dc1cb6086f18542897`。

## 状态矩阵与调用链

| 入口/状态 | 处理与验证 |
|---|---|
| 正常信号、多条件/配置变体 | 稳定实例+合约+方向+闭合bar身份；标签、档位不参与身份；重复不增单 |
| TRIGGERED未提交重启、失联 | 拒绝历史机会，新连接先建基线 |
| SUBMITTED超时、UNKNOWN | 沿同client ID恢复，不生成新意图；共享唯一writer，MySQL锁/并发恢复测试 |
| IOC部分/零成交 | 已成交量入仓，余量终止；无自动补齐 |
| TARGET/STOP/已未决退出 | 共享target_remaining=0；保留首个原因，部分平仓后仅剩余量；未决订单阻止重复 |
| 缺行情/证据/账本 | 禁新增风险；有效行情的既有硬止损沿原链路执行；账本失败明确失败 |
| 实例/旧版本/4H | 状态隔离、精确策略归属；默认不切换4H |

[流程图](indicator-union-flow.drawio)。静态调用链由已有Explore核验及本轮源码/端到端测试核对：`factory.paper/live → create_engine → refresh_union_frame/accept → worker._preflight → risk.evaluate → PaperBroker/LiveBroker.submit → ledger/order reconciliation → PaperManagement/LiveManagement → LivePositionManager._manage/submit_close`。当前工具环境无codegraph输出，未把静态调用关系冒充图谱工具结果。

## 下一步与限制

1. 用户启用前选择三档之一及实际账户/风险配置；本轮只完成三档功能验证，没有以某档作为默认赢家。
2. 在独立PAPER环境做连续实时验证，分模式/并集、档位输出全部候选、拒绝、成交、费用、未平仓估值及净收益；不能以该模块测试中的合成利润作为收益证据。
3. 满足准入后另批部署。回滚先关闭新入口，保留本版管理已成交并集仓；禁止换不识别该模型的旧包。

六个实际离线包已从代码提交`6183f160`构建并验证，位置`/Users/lichangzheng.ray/Ray/research-results/indicator-union-packages-20261104-6183f160`，见[逐包SHA与档位](evidence/indicator-union-20261104/packages.json)。这些是本地入口关闭的候选/编码包，没有上传或部署。
