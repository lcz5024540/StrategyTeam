# SBOX 15m 候选包与操作者发布单

作者：Codex；日期：2026-11-04；状态：本地冻结完成，未部署、未启用真实交易。

唯一最终包为`0.1.7-realtime-live-e6577b52`。S＋J1_BOX32已接正式链路，k=.10、最低净R1.5。旧中间包`c3e3cf2e`已被取代，不应部署。

|项目|冻结值|
|---|---|
|源码|`e6577b522582aa797ee4bdda325c9082efc9e36a`|
|当前代码分支|`codex/sbox-live-entry`，本轮未合并/推送`support_htx_python`|
|基线|`support_htx_python@7183ddd50624f49430f71f7b8bf14d1f7863187d`|
|包大小|467960字节|
|包SHA256|`b42e2c9c0c6de7a5891b151a0a0dc4988fbfc3d7e8e26e7a0b3947f321faac96`|
|策略spec hash|`173f5f8e21d223f8955c7db57b87443c70a0d9101db874eb2d62b11086312ca6`|
|主账户/实例|目标账户7；`htx-trend-live-primary`；须在切换时重新核对|
|最后只读服务器快照|2026-11-04 00:08 CST：current仍为2502786d包、PID2213018；不是新包运行证据|
|回归|1187 Python通过，含8项隔离MySQL；30/30真实样本触发、120引用闭包|
|启用状态|包内示例entry=false；未签发新真实交易许可，未激活trial，未提交IOC订单|

本地包：`/Users/lichangzheng.ray/Ray/htx-frozen/20261104-sbox-live/0.1.7-realtime-live-e6577b52/htx-trend-python-0.1.7-realtime-live-e6577b52.tar.gz`。

[包收据](evidence/sbox-live-20261104/package-receipt.json)、[逐文件及语义校验](evidence/sbox-live-20261104/package-verification.json)、[实现与测试](sbox-live-20261104.md)、[流程图](sbox-live-20261104.drawio)。示例config_fingerprint属于离线示例，不能填入生产数据库或试运行approval冒充实际配置指纹。

## 第一步：验证本地交付物

以下只读取本地包，不联网下单：

```sh
cd /Users/lichangzheng.ray/Ray/CoinStrategy-sbox-live
/tmp/coinstrategy-public-capture-py314/bin/python htx-trend-python/release/package.py verify \
  /Users/lichangzheng.ray/Ray/htx-frozen/20261104-sbox-live/0.1.7-realtime-live-e6577b52/htx-trend-python-0.1.7-realtime-live-e6577b52.tar.gz
```

输出应为PASS且包哈希、source_commit与上表一致。若要求从`support_htx_python`打生产包，维护者须先审核合并，再从合并后的确切提交重新构建/校验并更新本单；不得把feature分支包改名冒充目标分支包。

## 第二步：准备真实现场绑定

操作者在现有主15m环境执行，只操作主账户7。生产基准目录：

- 旧包：`/opt/coinstrategy/htx-trend-py/releases/0.1.7-realtime-live-2502786d`。
- 旧运行env：`/opt/coinstrategy/htx-trend-py-state/trial-management-20261103/runtime.env`。
- 服务：`htx-trend-worker@htx-trend-live-primary.service`。
- 拟建新状态目录：`/opt/coinstrategy/htx-trend-py-state/sbox-20261104-e6577b52/`；本轮未创建。

先按包内`release/HOT-DEPLOY.md`导出真实持仓和未决订单，运行`holding_preflight`；核对交易所逐仓/全仓与DB归属、原52合约池、交易权限、费用、风险预算、磁盘和单writer。禁止将旧空仓清单当成当前事实，也不自动接管/撤销未知订单。保留原env、owner别名、COS/采集配置与证据目录。

本包不新增SQL；需要确认既有`2026-11-02__htx_realtime_structure_plan.sql`和`2026-11-03__htx_realtime_live_trial.sql`已经应用。worker启动只验表，不自动迁移。新实例名称加`:SBOX_FACT_V1`后最长96字符。

配置变化仅绑定本次候选，先保持管理模式：

```dotenv
HTX_RT_ENTRY_ENABLED=false
HTX_RT_CANDIDATE=SBOX_FACTS_CONSERVATIVE_V1
HTX_RT_TARGET_MODE=J1_BOX32
HTX_RT_BAND_ATR=0.10
HTX_RT_MIN_NET_R=1.5
HTX_PYTHON_SOURCE_COMMIT=e6577b522582aa797ee4bdda325c9082efc9e36a
HTX_PYTHON_PACKAGE_SHA256=b42e2c9c0c6de7a5891b151a0a0dc4988fbfc3d7e8e26e7a0b3947f321faac96
```

这只是增量配置，不能覆盖完整生产env。新trial ID、实际配置指纹、退出快照hash、52池hash、执行配置hash、持仓接续清单与确认文件必须从当时实际环境生成并复核；不能复用旧2502786d的approval或示例hash。`seal-management`仅能封存管理许可，不会签发TRIAL。具体动态绑定尚未制作，所以目前不能仅改entry=true就宣称已具备实盘启用条件。

## 第三步：由操作者切换和最终激活

停止旧worker并确认锁释放后，按原冻结安装流程切换确切包；先用新绑定的MANAGEMENT许可、entry=false启动，确认恢复、对账、行情/费用覆盖和监控正常。检查新增事实namespace的持久化，不以PID存在代替健康验收。

操作者确认新TRIAL许可绑定与上表一致、完成当前账户预检后，停止worker释放锁，使用包内`htx_trend.realtime.trial_ops activate`激活已准备的trial，再以entry=true启动。该最后一步会允许自动真实交易，必须由用户执行；本交付没有代执行。正式利润准入仍为NOT_ELIGIBLE，仅暂缓收益/PAPER时长条件，安全条件全部保留。

额度：最多10仓含未决；单笔min(2U、现有更严限制、剩余账户/试运行预算)；20U亏损锁止；首次激活24小时或10笔完整交易先到结束；无首单人工暂停、无自动续期。监控单列本trial损益、净浮盈亏、预留风险、费用覆盖、行情年龄、未知订单、剩余期限/笔数、锁止原因与磁盘。

## 第四步：停止与回滚

先关闭新入场，保留兼容SBOX的包管理旧仓、新仓与未知订单。带仓或有未决意图时不直接切回旧包；重新执行兼容预检后才能回切。不得删除计划状态、trial表、账本或旧evidence；不调整4H/TradeX。

真实IOC价格/部分成交/费用仍需在自然合格信号中核验；短时公开行情与故障注入不冒充实盘成交。正式收益与回撤评估继续走TODO1，不因本包冻结而宣告通过。
