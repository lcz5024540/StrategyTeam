# R0001 R0 → R1 差距矩阵

更新时间：2026-10-15  
基线：生产 R0 `d9b1ceca`；Python shadow `bde93d80`

## 判定口径

“代码存在”只表示仓库里有实现；“技术验证”表示有测试或独立 shadow 证据；“正式通过”还需要产品/风险决策和可回滚发布记录。本矩阵不把前两者混成正式放行。

## 矩阵

| 能力 | R0 当前事实 | R1 目标 | 当前差距 | 验收证据 | 状态 |
|---|---|---|---|---|---|
| 运行模式 | 主 LIVE 为 R0；Python 有 OBSERVE | LIVE/OBSERVE/PAPER 边界不可绕过 | formal guard 尚未统一 | guard decision trace、拒绝型单测 | 待 A04 |
| 凭据 | Python 从数据库字段读取并按 `ENC(...)` 解密 | LIVE 明文凭据硬拒绝 | `decrypt_if_encrypted` 对明文可返回 | 明文 fixture 拒绝测试 | 待 A04 |
| 账户 | Python 首轮要求 PRIMARY；shadow 无账户 | 账户/策略/分配关系可追溯 | formal approval 未统一绑定风险配置 | approval artifact + DB 查询 | 部分完成 |
| 版本 | 分支 `bde93d80`；主 LIVE 未切换 | 包 SHA、策略版本、配置指纹绑定 | 运行时尚未统一校验完整指纹 | package/config/admission hash | 待 A04 |
| 候选追溯 | `timeframe_profile`、`gate_result` 已落库 | 每个周期可回溯候选与 gate | 仅候选观察；尚未形成正式成交对账链 | DB 行 + evidence refs | 技术完成，观察中 |
| 周期隔离 | shadow 15m/4h 独立观察；LIVE 入口有 profile worker | 周期生命周期不互相平仓 | 4h 尚不等于高周期确认；尚无正式 R1 验收 | 2–3 天 shadow 分桶 | 观察中 |
| 模型路由 | policy 路由和候选记录存在 | Donchian/FIB/EMA 等主模型证据可比较 | 研究样本不足，不能锁统计门 | per-model candidate/decision report | 待 C01/C02 |
| 开仓 gate | Python 有 Java-compatible gate、资金/盘口/保证金检查 | gate 顺序、失败原因和版本固定 | formal guard 与运行 gate 尚未统一成一份签发证据 | gate trace + replay | 部分完成 |
| 风险 sizing | 账户风险 reservation、仓位上限代码存在 | 账户/策略总风险上限可审计 | 生产包和 Python 包的差异尚未完成正式对拍 | risk limit hash + reconciliation | 待 A07/A10 |
| 下单/对账 | intent → exchange → reconcile 链路存在 | 重启不重单、未知结果可恢复 | 仍需真实拒单 R1–R4 验收 | rejection evidence、restart replay | 待 A17 |
| 退出 | 正式策略要求 V4；研究代码含 V5 observer | LIVE 只允许 V4，研究模块不得串线 | 缺少统一版本 guard | V5-in-LIVE fail-closed test | 待 A04 |
| 健康 | shadow heartbeat 已恢复 HEALTHY；历史失败保留 | health CLI、失败原因、版本指纹可查 | B01/B04/B05/B07/B08/B10 未全部归档 | health report + journal evidence | 部分完成 |
| 数据质量 | shadow 使用实时已收盘 K 线并写 evidence | R0001/R0002 冻结数据含 gaps/funding/spec/bad_ticks | 当前分支不等于完整冻结数据验收 | manifest/hash/replay report | 待 C03/C05 |
| 统计门禁 | 研究工具与既有冻结基线存在 | permutation/bootstrap 双过后才能锁 | 当前未完成 R1 统计放行 | fixed seed result、CI、p 值 | 待 C01/C02 |
| 发布流程 | 分支已有直接 push；独立 shadow 已部署 | M3/PR/包 SHA/回滚记录齐全 | 现有变更需补过程追溯；不能倒写成已审查 | PR/包/SHA/rollback record | 待 A05/A06 |

## 当前可执行的闭环

1. 完成 B07/B08/B10 只读运行取证，确认服务、历史失败和旧订单状态是否存在未闭合异常。
2. 完成 C03/A15，固定 R0001 统计基线和 4h provisional 参数，标记不得直接 LIVE 的字段。
3. 完成 C01/C02，生成固定 seed 的消融、置换和 bootstrap 证据。
4. 在用户/产品确认 A03/A05 后，再做 formal guard 热修、干净包验收和 M4 观察。

## 明确不接受的替代

- 不能用“196 个测试通过”替代正式 LIVE guard 验收。
- 不能用 shadow 候选数量替代真实订单收益或 R1 统计通过。
- 不能把 `timeframe_profile=SWING_4H` 字段当作高周期确认已经接入。
- 不能因 shadow 没有真实下单而放宽主 LIVE gate。
