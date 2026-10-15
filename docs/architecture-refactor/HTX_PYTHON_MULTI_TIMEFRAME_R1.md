# HTX Python 双周期独立生命周期 R1

## 目标

同一个 HTX 主账户、同一个 Python writer 同时运行两套相互独立的交易生命周期：

| Profile | 入场 K 线 | 趋势 K 线 | 候选 | LIVE 开仓 | 持仓管理与退出 |
|---|---|---|---|---|---|
| `INTRADAY_15M` | `15min` | `60min` | 独立 | 独立开关 | 只读取本 profile 持仓，以 15m 为主周期 |
| `SWING_4H` | `4hour` | `1day` | 独立 | 独立开关 | 只读取本 profile 持仓，以 4h 为主周期 |

保护性硬止损仍以可执行盘口实时检查，不能等待下一根 K 线。除此之外，15m 决策不得开平 4h 仓位，4h 决策不得开平 15m 仓位。

## 运行调用

```text
worker.py
  -> LiveTrendWorkerConfig.profiles_from_env()
  -> MultiTimeframeLiveTrendWorker
       -> LiveTrendWorker(INTRADAY_15M)
       |    -> 15m candidate cache
       |    -> LivePositionManager(profile=INTRADAY_15M)
       |    -> HtxTrendEntryExecutor(timeframe_profile=INTRADAY_15M)
       |
       -> LiveTrendWorker(SWING_4H)
            -> 4h candidate cache
            -> LivePositionManager(profile=SWING_4H)
            -> HtxTrendEntryExecutor(timeframe_profile=SWING_4H)
```

两个子 worker 共享：

- 同一个 `TrendWriterLock`；
- 同一个 HTX 账户事实与订单执行器；
- 同一个账户级最大持仓容量；
- 同一个交易所订单与成交对账账本。

每个 profile 独立保存：

- stable close epoch 与候选缓存；
- entry/trend K 线窗口及内容 hash；
- factor snapshot、signal、candidate、diagnosis；
- cooldown 查询；
- position/order 上的 `timeframe_profile`；
- 主周期退出状态。

## 同合约互斥

HTX 的同账户合约仓位不认识内部 timeframe 标签。R1 因此在真实 Entry Gate 前查询 `active_contract_position`：

```text
同账户 + 同合约 + 非终态仓位存在
  -> PROFILE_CONTRACT_MUTEX
  -> 不切杠杆
  -> 不写新仓位 intent
  -> 不发送订单
```

互斥状态包含 `INTENT / ENTRY_SENT / ACTIVE / PARTIAL_CLOSE_SENT / CLOSE_SENT / UNKNOWN`。现有仓位到达终态后，另一个 profile 才能取得该合约。

## 配置与准入

```bash
HTX_TREND_PROFILES=INTRADAY_15M,SWING_4H
HTX_TREND_INTRADAY_15M_LIVE_ENABLED=true
HTX_TREND_SWING_4H_LIVE_ENABLED=false
```

- profile 与周期是一一对应的启动合同；不匹配直接拒绝启动。
- LIVE 开关关闭时仍计算并保存候选，写为 `shadow=true` 和 `SHADOW_ONLY`，但不会调用真实执行器。
- 被关闭的 profile 会丢弃该轮 pending，后续打开开关不得补发旧候选。
- `HTX_TREND_V1` 只能继续执行既有 15m LIVE；它不能打开 4h LIVE。
- 4h LIVE 必须使用非 legacy 策略版本，并提供十二阶段 sealed admission；本次代码与 PR 不构成该准入。
- 已有持仓管理和保护退出不受新开仓开关影响。

## R1 作用面

R1 只改变 Python 调度、候选周期合同和账户合约互斥。它没有降低模型、policy、Gate、成本、流动性、仓位或退出阈值，也没有扩大币种。

当前 4h 使用与 15m 相同的入口公式和参数只是候选研究基线。真实启用 4h 下单前必须完成 4h 参数敏感性、成本、OOS、PAPER 和退出证据；未准入时保持 `HTX_TREND_SWING_4H_LIVE_ENABLED=false`。

## 验证

自动化覆盖：

- 4h 不再返回 `TIMEFRAME_UNSUPPORTED`，固定样本能产生候选；
- 15m 决策 golden hash 保持不变；
- profile/周期不匹配启动失败；
- 4h shadow 写候选但真实执行器调用为零；
- 4h LIVE 只有非 legacy sealed admission 路径能够触达执行器；
- 同账户同合约已有活动 profile 时返回 `PROFILE_CONTRACT_MUTEX`；
- 15m/4h manager 只管理归属自己的持仓；
- profile cooldown、candidate、order、position 与诊断证据保持隔离。

## 发布边界

本次交付止于代码、测试和 PR。不得据此修改生产环境、执行迁移、安装包或重启服务。生产当前发布包 `0.1.1-r0-d9b1ceca` 的 R0 诊断源码已先恢复到分支历史，便于 R1 基于真实线上版本审查。
