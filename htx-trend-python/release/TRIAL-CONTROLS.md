# 15m受控试运行控制

本版本实现独立试运行控制与管理模式；分钟／逐笔候选仍未接入，默认关闭新入场，正式收益准入保持NOT_ELIGIBLE。

## 已确认额度

- 主账户原15m实例，冻结52合约；最多10个新策略仓，未决意图占位。
- 每笔风险取2U、0.5%实际权益、账户剩余风险及20U剩余空间的最小值；账户总止损风险1.5%、原保证金限制继续生效。
- L为已实现净亏的正值加当前净浮亏的正值；浮盈不抵扣已实现亏损。L达到20U停止新入场。
- 首次显式激活起24小时／10笔完整交易先到者停止；无首单人工暂停，不自动续期。已完成＋未终结意图不超过10，避免并发超出首轮笔数。
- 部分退出不计完整交易；未知订单保留名额和风险。费用、结算或盘口覆盖不明则拒绝新增风险。

## 独立权限与状态

`HTX_RT_TRIAL_APPROVAL_PATH`指定`htx-realtime-trial-approval-v1`封存文件，使用现有canonical＋SHA封存机制，封存校验不是数字签名；签发仍由受信操作流程控制。

权限分为MANAGEMENT和TRIAL。绑定trial/账户/实例/提交/包/配置/参数/退出policy/52清单及确认文件。只有正式收益与PAPER14天条件可暂缓；旧waiver/G2与trial不能同时配置。MANAGEMENT不允许新入场，不启动24小时计时，不生成全池结构、不增加全池逐笔采样。

启用结构必须为正式运行链路已登记且另有确认文件的候选。已登记的正式候选含SBOX_FACTS_CONSERVATIVE_V1（用户确认的S＋J1_BOX32、k=.10/R1.5，详见SBOX-LIVE.md）；许可必须匹配实际候选身份与参数指纹。研究候选不得冒充其他身份。

显式执行`release/migrations/2026-11-03__htx_realtime_live_trial.sql`。worker只验表；新表保存配置绑定、期限、计数、逐意图关联、风险预留、费用覆盖及持久停止原因。单writer外加版本CAS防过期写入；更换包／参数不能无提示接续旧trial。

```sh
python -m htx_trend.realtime.trial_ops status --trial-id <id>
# 以下必须停worker释放writer锁，并完成独立候选与包确认；管理许可不能激活：
python -m htx_trend.realtime.trial_ops activate --trial-id <id> --approval <confirmed-trial.json>
python -m htx_trend.realtime.trial_ops stop --trial-id <id> --reason OPERATOR_STOP
```

`activate`只变更已存在trial状态，不修改运行env。实际入场还要求`HTX_RT_ENTRY_ENABLED=true`及启动/逐次提交校验通过。到期权限和停止状态均不关闭原有持仓管理。不得安装18点定时开启任务。

## 数据与恢复

账户事实读取在后台线程进行，不能阻塞实时价格止损。实际订单回执必须含USDT费用、成交数量和已实现毛盈亏；与权威持仓数量不一致时阻新入场。开仓费按已平/剩余数量分摊，平仓费计已实现，预计退出费计浮盈亏。资金费独立计入已实现，不重复扣减。

资金费使用HTX官方`POST /linear-swap-api/v3/swap_financial_record_exact`，逐仓按合约margin account查询类型30/31、显式next游标直到空页。重复游标、错误账户/资产/时间、无法唯一归属均不认定覆盖完整。HTTP成功或公开资金费率不能代替账户结算证据。

原始订单/资金费回执通过compact-v2引用保存。采样仍上限2GiB，盘剩余5GiB以下禁止新风险，不删除旧数据。严格新鲜度可能拒绝机会，不能为提高成交率放宽验证。

## 发布与回滚

1. 在support_htx_python确切提交打包并保存SHA；先解包校验。
2. 保存旧包、env和运行状态；实时核对主账户逐仓/全仓持仓与订单、DB归属及writer锁。
3. 显式应用计划表与trial表迁移；配置旧策略owner别名和真实空仓/持仓接续清单。
4. 部署MANAGEMENT权限、entry=false；检查账户对账、worker健康及trial未激活。此次只准备运行底座。
5. 独立候选确认及最终包冻结后再审批激活。采集到18点不自动表示候选通过。
6. 停止/回滚先关闭入场；有新仓或未知意图时必须保留兼容包管理。空仓且未激活时可以恢复旧env/策略绑定/风险名额和旧包，不删新表或证据。

## 真实验证边界

2026-11-03主账户只读回执覆盖SOL/TRX/ZEC/SUI四笔历史完整持仓，其中TRX分批退出及实际资金费-0.00315287U。历史订单类型均market，不能称为真实IOC验证。测试对真实回执做手续费缺失/错误数量等注入，注入场景属于模拟故障。隔离MySQL验证迁移、恢复和CAS；收益对照仍需后续完整采集及候选冻结。
