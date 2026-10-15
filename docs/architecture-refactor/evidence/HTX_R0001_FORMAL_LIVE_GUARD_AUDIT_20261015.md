# HTX Python formal LIVE guard 只读审计

审计日期：2026-10-15（Asia/Shanghai）  
代码基线：`support_htx_python` / `bde93d80`  运行主线：生产仍为 R0，未切换本审计结果

## 结论

当前 Python 分支已经具备多层运行时保护：实例必须为 `LIVE`、绑定 PRIMARY 账户、需要通过首次切换环境门、需要 admission artifact、需要 writer lock、HTX 客户端需要显式 `live_trading_enabled=True`，并且执行前有账户事实、策略 gate、风险预留和订单账本链路。

但它还不是附件 R0001 所要求的“formal LIVE guard 完整实现”。主要缺口是：没有独立的 `formal_live.py`/正式 guard 契约；没有显式拒绝交易所明文凭据的硬检查；风险/版本/包指纹没有在一个不可绕过的正式放行对象中统一签发；部分检查仍分散在 `worker.py`、`HtxLiveExecutor` 和 `HtxTrendEntryExecutor`。因此当前只能判定为“代码已有分散保护，formal guard 审计未过”，不能据此放行 R1 或 M4。

## 已确认存在的保护

| 检查点 | 代码位置 | 现状 |
|---|---|---|
| 实例启用与模式 | `htx-trend-python/src/htx_trend/worker.py` | 从 `strategy_runtime_instance` 读取；未启用或模式不支持直接退出 |
| LIVE 必须绑定账户 | `worker.py` | `account_id` 为空直接退出 |
| PRIMARY-only | `worker.py` | 账户不存在或 `role != PRIMARY` 直接退出；镜像启用时首轮切换拒绝 |
| 首次切换门 | `worker.py::_validate_first_live_cutover` | 要求 `HTX_PYTHON_EXIT_PARITY_APPROVED=true`，并显式要求 mixed-market 变量为 `false` |
| 新版本 admission | `worker.py`、`execution_service.py` | 非 legacy 版本必须加载匹配版本的 admission artifact，并通过 ranking/gate/sizing surface |
| 共享 writer lock | `worker.py`、`live.py` | LIVE 启动获取锁；每次变更前再次校验锁归属 |
| HTX 变更开关 | `htx_v5.py`、`live.py` | `place_order`、`cancel_order`、`set_leverage` 都必须通过 `live_trading_enabled` 和 `LiveAuthority` |
| 账户事实与风险 | `execution_service.py` | 执行前读取账户事实、策略 gate、保证金/仓位容量并做风险 reservation |
| 订单可恢复性 | `execution_service.py` | 先写 intent，再发单；未知结果保留 UNKNOWN；重启只对账，不重复发单 |
| 证据落库 | `execution_service.py`、evidence store | gate 条件、运行版本、候选、账户和订单链路可写入证据 |

## 缺口与风险

### G1：没有统一的 formal LIVE guard

入口保护分散在多个类，缺少一个返回“允许/拒绝 + 原因 + 版本/包/环境指纹”的不可绕过对象。后续维护者可能只调用某个底层 worker 或 executor 测试路径，而没有经过完整签发链。

**修复方向**：新增只负责校验的 `formal_live.py`，不发单、不改环境；由 worker 在创建私有客户端和 writer lock 前调用。输出必须包含 strategy version、package version、config fingerprint、account role、execution mode、risk limits、admission hash 和拒绝原因。

### G2：明文凭据未被显式拒绝

`credentials.py::decrypt_if_encrypted` 对 `ENC(...)` 做解密，但对非加密字符串会直接返回原值。当前凭据来源是数据库记录，且没有发现从环境变量读取 API key 的路径；但代码层面没有把“明文密钥禁止进入 LIVE”做成硬失败。

**修复方向**：formal guard 中要求 LIVE 账户凭据字段满足加密记录契约；明文只允许 OBSERVE/PAPER 测试 fixture，生产 LIVE 直接拒绝，并只记录字段状态，不记录密钥内容。

### G3：包/配置指纹未成为统一放行条件

运行实例有 `deployment_unit`、`config_fingerprint` 等字段，但当前 worker 没有把完整包 SHA、环境白名单、风险配置和 admission hash 统一绑定后再签发 LIVE authority。只依赖分散 env 检查，难以证明线上包与已验收包完全一致。

**修复方向**：formal guard 生成不可变 `LiveApproval`，至少绑定 release/package SHA、config fingerprint、strategy version、account id/role、risk limit hash、admission hash 和过期时间。

### G4：退出版本边界需要显式验证

当前任务要求退出固定 V4，但代码仓库同时存在 `exit_kernel_v5.py` / `exit_observer_v5.py` 研究模块。现有入口没有在 formal guard 中统一校验“LIVE 只能使用 V4；V5 只能 OBSERVE/PAPER”。这属于需要补强的可观测/防串线检查。

**修复方向**：把 exit engine version 纳入 admission/guard；LIVE 发现 V5 运行时立即 fail closed，并记录版本冲突。

## 不在本次审计中执行的动作

- 没有修改 `worker.py`、环境变量、生产数据库或 systemd。
- 没有切换主 LIVE，也没有接入 shadow 到真实下单。
- 没有把上述缺口当成已修复；下一步应先补测试和设计，再单独评审 formal guard hotfix（A04）。

## 验收建议

1. 先为 G1–G4 写拒绝型单测：明文凭据、非 PRIMARY、缺失/过期 admission、包指纹不匹配、V5 进入 LIVE、writer lock 丢失。
2. 在干净环境运行完整测试，输出 guard decision trace；确认 OBSERVE 不受 LIVE guard 阻断，LIVE 缺任一硬条件都不启动。
3. 由产品确认 A03/A05 后，才能把 guard 热修作为 A04/A08 包进入下一阶段；在此之前主 LIVE 保持 R0。
