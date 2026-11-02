# Python 热部署持仓延续操作说明

2026-11-02 · 候选开发交付；本文件不授予生产部署或交易权限。

部署不构成平仓理由。旧仓继续使用已冻结的退出/指标参数及保护状态；新仓使用新配置。所有权、订单、余额仍以原MySQL/PAPER账本为准。升级失败不得把未接管状态报告为READY。

## 支持与限制

- LIVE、PAPER的15m/4H；OBSERVE监控进度。账户、实例、周期独立，禁止把模拟持仓导入LIVE。
- 已知旧管理实现对应support_htx_python@44d3150b及现网8e0a0cc4。纯计算、周期及管理源文件指纹均核验；未知旧实现拒绝，不能仅修改版本号放行。
- 当前格式：`htx-holding-policy-v1`、`htx-runtime-continuity-v1`。未来代码变动若不能解释旧快照，需提供兼容适配器。
- 不承诺零停顿。记录上次更新时间、是否正常停止及恢复间隔。原规则已满足止损时正常退出；不得因部署豁免风控。
- 本轮无新增业务SQL。旧包所需表及其历史迁移仍须存在；启动不执行DDL。
- 不改变正常轮询间隔，不触碰历史evidence、MySQL数据、既有paper-state目录或C钟。

## 第一步：在旧实例运行期间只读导出

使用**新候选包的Python代码**运行工具，指定实际旧worker PID及实例。工具核验`/proc/PID/cmdline`、实例、启动时间和包路径，读取旧进程有效环境，并在旧代码下开启只读DB事务。输出不含交易凭据。

```bash
PYTHONPATH=/absolute/new-package/src python -m htx_trend.tools.holding_export \
  --pid OLD_WORKER_PID --instance INSTANCE_KEY \
  --output /absolute/handoff/adoption-preview.json
```

输出使用独占创建，0600权限，不覆盖旧文件。这只是时点预览，旧进程随后可能开平仓。正式切换在旧worker停止后用**旧包+旧env**重新导出最终库存：

```bash
PYTHONPATH=/absolute/new-package/src python -m htx_trend.tools.holding_export \
  --old-package /absolute/old-package --env-file /absolute/old.env \
  --instance INSTANCE_KEY --output /absolute/handoff/adoption-final.json
```

`--env-file`只接受字面KEY=value；不执行shell、不扩展变量。它必须是旧实例实际使用的环境备份；不能填新env猜旧参数。无旧env依据或未知实现时停止切换，恢复旧包原配置。

## 第二步：准备独立持久状态与归属

部署负责人确认后配置以下字段。每个实例保留各自的原账本目录；运行检查点目录必须绝对路径且位于release包外。

```bash
HTX_RUNTIME_STATE_DIR=/absolute/persistent/INSTANCE_KEY/runtime-state
HTX_HOLDING_ADOPTION_PATH=/absolute/handoff/adoption-final.json
# 首次从旧包迁移时显式设置；初始化成功后移除此行。
HTX_CONTINUITY_INITIALIZE=true
# 只有策略名称变化时登记旧名；同instance_key、账户、family不变。
HTX_MANAGED_PREVIOUS_STRATEGIES='["OLD_STRATEGY_NAME"]'
```

不改策略名时旧名数组为`[]`。已有检查点记录的owner不能被静默移除；保留alias，避免漏管旧仓/未决单。旧仓profile仍须保留配置；可以关闭其新入场。关闭全部entry_enabled仍可启动管理，但现有账户/包准入检查保持生效。PAPER支持更换合约池及新入场风险设置，原权益、策略身份不可隐式变更；移出的合约继续管理到退出。

## 第三步：核验最终交接材料

```bash
PYTHONPATH=/absolute/new-package/src python -m htx_trend.tools.holding_preflight \
  --old-package /absolute/old-package --env-file /absolute/old.env \
  --instance INSTANCE_KEY --strategy CURRENT_STRATEGY_NAME \
  --strategy OLD_STRATEGY_NAME --profile INTRADAY_15M \
  --adoption /absolute/handoff/adoption-final.json
```

4H改为`SWING_4H`；无旧名时省略第二个`--strategy`。`ready=true`只表示当前材料可接管；还须原包校验、环境准入及运行对账。缺策略、profile、订单所属持仓、参数不匹配均阻断。离线`--observation`只用于开发复核，输出`offline_only=true`，不得作为现网时点验收。

## 第四步：按既有批准流程切换并验收

1. 先验证候选tar哈希和MANIFEST，备份旧包引用、旧env及持久状态位置；不回填或清空任何交易账本。
2. 停止旧实例：SIGTERM只请求排空，本轮安全步骤收口；超出systemd停止预算被SIGKILL时，按异常恢复验收，不当作正常STOPPED。
3. 旧进程释放单写锁后，完成最终导出与预检，再通过原冻结/安装流程启动新包。
4. 新进程恢复参数及队列，重新对账原订单、管理所有持仓、检查全部profile；此后才允许新入场。未知订单、参数/检查点/账本错误不能READY。
5. 核对原持仓ID、合约、方向、剩余量、开仓均价、保护价、确认次数、费用累计和旧策略归属；核对未决订单仍为原client_order_id、无重复委托。
6. 观察心跳`continuity=READY`与coverage；断档、DEGRADED/FAILED、恢复前后数量差异需查清。不得只以PID存在判定成功。
7. 首次初始化成功后删除env里的初始化开关（不删除数据）。关闭新入场仍继续管理旧仓。

## 第五步：兼容回滚

回滚前用当前实例重新导出/预检，并加`--rollback-package /absolute/target-package`；已有运行检查点时同时传`--runtime-checkpoint /absolute/persistent/...json.gz`。目标解释器须校验每个持仓快照并解码实际运行检查点；同名schema不足以证明兼容。

旧包没有holding-policy/runtime-continuity支持且仍有仓或未决单时，拒绝直接回退。继续运行可管理原仓的包，关闭新入场排查；不能为回滚而自动平仓。无持仓/未决单的回退也须原部署验收；旧程序的缓存语义与新程序不同，不把它当带仓兼容回滚。

## 开发验收证据

见`docs/architecture-refactor/evidence/hot-deploy-20261102/`：参数及归属测试、同bar队列恢复、正常/SIGKILL双进程PAPER交接、隔离MySQL锁/并发/回滚/三个进程开平仓对账，以及20:04现网只读参数预检。测试用订单事实为确定性夹具，没有真实下单；生产带仓验收留给批准部署后执行。
