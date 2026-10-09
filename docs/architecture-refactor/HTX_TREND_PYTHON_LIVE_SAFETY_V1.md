---
title: HTX 趋势 Python 实盘安全闭环 v1
date: 2026-10-09
status: implemented-not-deployed
---

# HTX 趋势 Python 实盘安全闭环 v1

## 结论

Python 已具备可测试的订单恢复、累计成交、退出管理和跨语言唯一 writer 协议，但尚未获得实盘切换资格。切换仍需执行两份 SQL、完成冻结数据逐笔零差异验收、停 Java 并验证共享锁释放，然后只启 PRIMARY 小仓灰度。

流程图：[HTX_TREND_PYTHON_LIVE_SAFETY_V1.drawio](HTX_TREND_PYTHON_LIVE_SAFETY_V1.drawio)

## 已落实的安全合同

1. 下单前先持久化 intent；请求可能发出后发生异常时记 `UNKNOWN`，不得释放结构或重发。
2. 恢复只按客户端订单号查询交易所；累计成交增量在一个事务内更新订单、仓位和统一账本。
3. 持仓每轮先恢复订单，再核对交易所数量；硬止损只依赖确认仓位和可执行价格，不受软信号数据缺失影响。
4. Java 与 Python 共用 `coinstrategy:htx:trend:writer:v1` 生命周期锁；每次交易所变更前复核锁仍属于当前连接，失锁后永久 fail closed。
5. v4 启用但 live 关闭时只记录退出决策，不回退到 legacy 执行；`reduce_only` 成交确认后才减少剩余仓位。

## 切换步骤与验收

1. 在非生产库依次执行 `2026-10-08__python_strategy_platform.sql`、`2026-10-09__python_exit_state.sql`，启动时验出全部必需表。
2. 用同一 `run_id` 的 15m/60m 冻结窗口导出 Java/Python 决策；`htx-trend compare` 必须退出 0，覆盖开仓、硬止损、v4、部分成交撤单和重启恢复。
3. 保留 `HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE=false`，逐项核对其他 `HTX_TREND_*` 配置指纹。
4. 停止 Java 趋势 writer，确认服务不再持有共享锁；随后只启动 PRIMARY Python 实例。当前镜像账户启用会拒绝启动。
5. 小仓灰度期间核对 HTX 订单、`htx_trend_order`、`htx_trend_position` 与 `coin_strategy_ledger_event` 四方累计数量和费用；任一不一致即停止 Python 并恢复 Java。

## 当前证据边界

本地 Python 测试覆盖 SQLite 事务投影和锁协议模拟；Java 测试覆盖共享锁连接的持有与失锁。尚未在真实 MySQL 上做 `GET_LOCK` 双进程竞争，也未用生产冻结退出样本完成逐笔零差异，因此不得部署或设置 `HTX_PYTHON_EXIT_PARITY_APPROVED=true`。
