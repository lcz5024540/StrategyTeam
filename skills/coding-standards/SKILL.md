---
name: coding-standards
description: "编写或修改本项目代码时必须遵循的编码规范。涉及 Java 后端（coin-strategy-* 模块、common-api/client/model）、前端（coin-strategy-frontend，React + TypeScript）的命名、分层、异常处理、日志、金额精度、并发安全等。当用户要求写新功能、改 bug、重构、做代码评审，或提到「规范」「编码风格」「代码质量」时使用。"
---

# 规范编码 (Coding Standards)

本技能定义 CoinStrategy 量化交易项目的编码规范。这是一个处理**真实资金**的金融系统，代码质量直接关系到资金安全，必须严格执行。

## 何时使用

- 编写任何新代码之前 —— 先确认本规范适用条款
- 修改、重构现有代码时
- 做代码评审（结合 superpowers 的 `requesting-code-review` 技能）时
- 用户提出「按规范写」「检查代码风格」时

## 核心原则

1. **资金安全优先**：任何涉及下单、转账、金额计算的代码，宁可保守也不冒险。
2. **不破坏现有契约**：修改公共接口（`common-api`）前先评估所有调用方。
3. **可读性 > 聪明**：代码是给人看的，复杂技巧必须配注释说明「为什么」。
4. **最小改动**：修 bug 时只改必要的部分，不顺手重构无关代码。

## 一、架构分层纪律（最重要）

项目采用严格分层，**违反分层是严重问题**：

| 模块 | 职责 | 允许依赖 | 禁止依赖 |
| --- | --- | --- | --- |
| `coin-strategy-core` | 策略逻辑 | `common-api`、`common-model` | `common-client`（不可直接调 SDK） |
| `coin-strategy-job` | 运行容器/调度 | `core`、`common-client` | —— |
| `coin-strategy-web` | 管理后台/BFF | `core` | 直接写策略逻辑 |
| `common-api` | 标准接口与模型 | `common-model` | 任何具体交易所实现 |
| `common-client` | 交易所 SDK 适配 | `common-api`、`common-model` | `core`（不可反向依赖策略） |

- 策略层只操作通用模型（`Order`、`Ticker`、`Balance`），**不感知具体交易所**。
- 新增交易所 = 在 `common-client` 加适配器，**不改策略代码**。

## 二、Java 编码规范

### 命名
- 类：大驼峰，名词。策略类以 `Strategy` 结尾（`ArbitrageStrategy`），适配器以 `Adapter` 结尾。
- 方法：小驼峰，动词开头（`calculateProfit`、`placeOrder`）。
- 常量：全大写下划线（`MAX_ORDER_AMOUNT`）。
- 包名：全小写，按模块/功能划分。
- 布尔变量/方法：`is/has/can` 前缀（`isFilled`、`hasPosition`）。

### 金额与精度（金融项目红线）
- **金额、价格、数量一律用 `BigDecimal`，禁止用 `double`/`float`。**
- `BigDecimal` 比较用 `compareTo()`，禁止用 `equals()`（`equals` 会比较 scale）。
- 除法必须指定精度和舍入模式：`a.divide(b, 8, RoundingMode.DOWN)`。
- 下单数量向下取整（`DOWN`），避免余额不足；交易所有最小精度/步长要求时先按步长归整。

### 异常处理
- 不吞异常：禁止空 `catch` 块。最低限度也要 `log.error` 并说明上下文。
- 区分可恢复 vs 不可恢复异常。网络/限流类异常应重试，业务校验失败不应重试。
- 涉及资金的操作捕获异常后**必须明确订单/资金的最终状态**，不可假设成功或失败。
- 自定义业务异常继承统一基类，携带错误码。

### 日志
- 用 SLF4J（`log.info`/`warn`/`error`），禁止 `System.out.println`。
- 关键节点必打日志：下单前后、撤单、策略启停、风控触发。
- 日志带可追踪标识：策略 ID、订单 ID、交易对。
- **脱敏**：apiKey/secretKey/私钥/签名禁止进日志（详见 `trading-security-audit` 技能）。
- 用占位符而非字符串拼接：`log.info("下单 {} 数量 {}", symbol, qty)`。

### 并发与状态
- 策略状态共享字段考虑线程安全：用 `ConcurrentHashMap`、`AtomicXxx` 或显式锁。
- 定时任务避免重入：上一轮未结束时跳过本轮或加锁。
- 下单要有**幂等/防重**机制（客户端订单 ID）。

### 通用
- 魔法数字提取为命名常量或配置项。
- 单方法不超过约 60 行，单类职责单一。
- 资源（连接、流）用 try-with-resources。
- 优先用项目已有工具类，不重复造轮子。
- 配置项进配置文件，不硬编码（限额、阈值、URL）。

## 三、前端规范（coin-strategy-frontend）

- TypeScript 严格模式，避免 `any`，为 API 响应定义明确类型。
- 组件用函数式 + Hooks；组件文件大驼峰，hook 以 `use` 开头。
- API 请求统一走封装层，base URL 走 `.env`，不硬编码。
- 金额展示注意精度，后端传字符串则前端不要用 `Number` 造成精度丢失。
- 避免在渲染中做重计算，必要时 `useMemo`/`useCallback`。

## 四、提交前自检清单

- [ ] 是否遵守架构分层（策略层未直接依赖 `common-client`）？
- [ ] 金额是否全部用 `BigDecimal`，比较用 `compareTo`？
- [ ] 异常是否都被合理处理，资金操作的最终状态是否明确？
- [ ] 关键路径是否有日志，是否已脱敏？
- [ ] 是否有魔法数字/硬编码配置？
- [ ] 并发字段是否线程安全？下单是否防重？
- [ ] 是否改动了公共接口？若是，调用方是否都已检查？
- [ ] 是否有对应的测试（结合 `test-driven-development` 技能）？

## 配套技能

- 写代码前用 superpowers 的 `brainstorming` + `writing-plans` 想清楚再动手。
- 写测试用 `test-driven-development`。
- 涉及资金/密钥的改动叠加 `trading-security-audit` 技能审查。
