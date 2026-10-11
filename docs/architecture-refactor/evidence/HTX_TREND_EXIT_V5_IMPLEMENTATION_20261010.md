---
title: HTX 趋势退出 v5 本地实施与门禁结果
type: evidence
status: implementation-complete-gates-blocked
created: 2026-10-10
author: Codex
---

# HTX 趋势退出 v5 本地实施与门禁结果

## 结论

`trend-exit-v5` 已完成可在本地验证的实现边界：Java/Python 版本合同与纯决策核、三类模型适配、共享 golden、事务化状态/决策/意图、单调 owner token、唯一 fill、再入场预算、OBSERVE/PAPER 适配、费用分摊、证据门、默认关闭的只读解释 API 与 fail-closed LIVE readiness。现有 v4 和真实下单路径未切换。

本结果不等于上线完成。G0 仍为 `INSUFFICIENT_EVIDENCE`；G1 尚未覆盖 T-01..T-36 全部场景；G2-G5 缺冻结样本和至少 14 天 OBSERVE/PAPER；G6 缺原生 reduce-only 保护验证、回滚演练与用户批准。因此 `liveAllowed=false`，不得执行迁移、部署或切换实盘所有权。

## 已实现

| 里程碑 | 结果 |
|---|---|
| M1 | 完成代码与账本口径审计；Paper 开平费用均纳入 `contract_size`；数据缺口保持失败关闭。 |
| M2-M4 | Java/Python 同合同纯核、Fibonacci/Donchian/EMA-VWAP 适配器和共享 golden 已完成。 |
| M5 | 新增显式 MySQL migration 与 H2 bootstrap；CAS 前态锁定、决策/意图同事务、确定性 client order id、owner fencing、唯一 fill、再入场锁已完成。 |
| M6 | OBSERVE/PAPER 适配只允许无真实订单能力的路径；LIVE 输入直接拒绝；分段费用尾差由最后一笔吸收。 |
| M7 | 预注册 manifest 与结构化证据门已实现；因 G0 缺证据，E0-E6 经济计算未运行，正确返回 `INSUFFICIENT_EVIDENCE`。 |
| M8 | API-1 的账户隔离只读投影已实现且默认关闭；LIVE readiness 对迁移、围栏、原生保护、主/镜像账本和 G0-G6 失败关闭。API-2、前端展示、真实原生保护与镜像实盘对账仍属于晋级工作。 |
| M9 | 本地全量 Java 模块测试与 Python 测试通过；门禁按实际证据判定为未晋级。 |

## 验证

- Java：`JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -pl coin-strategy-htx -am test`，636 tests，0 failure/error/skip。
- Python：`htx-trend-python/.venv/bin/pytest -q`，121 passed。
- 新增事务测试覆盖：幂等 append、事务回滚、过期 owner fencing、接管 token、重复 fill、再入场预算、OBSERVE 无 intent、账户隔离查询、硬风险遇部分在途不生成第二 intent、篡改 input hash 拒绝。
- 已自动化的 v5 合同核心覆盖 T-01/T-03-T-13/T-16-T-20/T-22-T-28/T-31-T-34 的相关子场景；T-02/T-14/T-15/T-21/T-29/T-30/T-35/T-36 及部分执行级故障仍需专门 fixture 或受控环境，故 G1 不通过。

## 外部执行边界

迁移文件仅入仓，未执行数据库 SQL。未部署服务、未连接真实交易所 SDK、未创建/撤销订单、未改变账户配置。后续只有在 G0-G5 逐项通过并记录 G6 授权后，才可另立上线任务。
