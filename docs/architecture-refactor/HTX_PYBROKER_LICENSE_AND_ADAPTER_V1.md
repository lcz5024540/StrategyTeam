---
title: "PyBroker 只读研究适配与许可证边界 v1"
type: third-party-adoption-boundary
status: active
created: 2026-10-04
tags: [pybroker, license, research]
---

# PyBroker 只读研究适配与许可证边界 v1

2026-10-04 读取 PyBroker 仓库 `LICENSE` 原文确认：其许可证为 **Apache 2.0 with Commons Clause**。Commons Clause 明确排除“向第三方以费用或其他对价提供其功能构成实质价值的产品或服务”的权利。

结论：CoinStrategy 不引入 PyBroker 源码、运行时依赖或服务端执行能力，也不把它用于任何对外策略服务。仅允许团队在本地、离线、只读研究中，用冻结数据做结果交叉核验。当前 Python 3.9 环境经包索引验证没有可安装的 `pybroker` 发布包，因此本轮不把不可复现的本地安装伪装成验证成功。

`htx_trend_pybroker_export.py` 已输出经过 manifest 校验的 `date,symbol,open,high,low,close,volume` CSV 和内容摘要，可作为未来获许可的隔离运行环境的自定义数据源。适配器不访问 HTX、不含账户数据、不产生订单，也不被 Java 实盘服务引用。
