---
title: "PyBroker 只读研究适配与许可证边界 v1"
type: third-party-adoption-boundary
status: active
created: 2026-10-04
tags: [pybroker, license, research]
---

# PyBroker 只读研究适配与许可证边界 v1

2026-10-04 读取 PyBroker 仓库 `LICENSE` 原文确认：其许可证为 **Apache 2.0 with Commons Clause**。Commons Clause 明确排除“向第三方以费用或其他对价提供其功能构成实质价值的产品或服务”的权利。

结论：CoinStrategy 不引入 PyBroker 源码、运行时依赖或服务端执行能力，也不把它用于任何对外策略服务。仅允许团队在本地、离线、只读研究中，用冻结数据做结果交叉核验。

`htx_trend_pybroker_export.py` 已输出经过 manifest 校验的 `date,symbol,open,high,low,close,volume` CSV 和内容摘要。2026-10-04 已在项目外的隔离 Python 3.11.15 虚拟环境安装并导入 `lib-pybroker 2.0.1`；本机默认 Python 3.9 仍不满足上游 Python 3.11+ 要求。`htx_trend_pybroker_runtime_smoke.py` 以 PyBroker 的 `DataSource` 读取该 CSV，只验证字段、时间范围、币种和内容摘要；它不访问 HTX、不含账户数据、不产生订单和 PnL，也不被 Java 实盘服务引用。

运行时兼容性已验证，但 P1 的完整执行事实按当前计划冻结。因此不得把本检查误称为 Walk-forward、Bootstrap 或独立策略收益验证；这些结论仍为 `INSUFFICIENT_EVIDENCE`。
