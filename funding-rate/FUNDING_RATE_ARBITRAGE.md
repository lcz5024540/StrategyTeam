---
title: "资金费率套利文档导航"
type: overview
status: active
tags: [funding-rate, arbitrage, overview]
created: 2026-04-10
updated: 2026-04-13
---

# 资金费率套利文档导航

原先将“单交易所套利”与“多交易所套利”混写在同一份文档中，后续维护和实现对照容易混淆。现已拆分为两份独立策略文档：

## 1. 官方 API 文档 

### 1.1、币安
1. 现货：https://developers.binance.com/docs/zh-CN/binance-spot-api-docs
2. 合约：https://developers.binance.com/docs/zh-CN/derivatives

## 2. 策略原理文档

1. [单交易所套利策略](./FUNDING_RATE_ARBITRAGE_SINGLE_EXCHANGE.md)
2. [多交易所套利策略](./FUNDING_RATE_ARBITRAGE_MULTI_EXCHANGE.md)

## 3. 实施进度文档

1. [单交易所套利实施进度](./FUNDING_RATE_ARBITRAGE_SINGLE_EXCHANGE_SHARE.md)
2. [多交易所套利实施进度](./FUNDING_RATE_ARBITRAGE_MULTI_EXCHANGE_SHARE.md)

## 4. 当前状态

* 当前代码已实现并持续优化的是“单交易所期现套利”。
* “多交易所套利”目前仍处于方案设计和实施规划阶段，尚未进入正式执行链路。

---

<!-- kb-backlink -->
> 本文归属：[交易策略 知识地图](../docs/MOC-交易策略.md) ｜ [知识库首页](../docs/知识库首页.md)
