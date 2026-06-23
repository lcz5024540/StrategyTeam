---
title: "Coin Strategy Common Modules"
type: overview
status: active
tags: [common-api]
created: 2026-01-25
updated: 2026-05-13
---

# Coin Strategy Common Modules

此目录包含 `CoinStrategy` 项目的所有公共模块。这些模块旨在提供可重用的基础架构、工具类、API 客户端和通用业务逻辑。

## 模块架构

我们将 `coin-strategy-common` 设计为一个 **BOM (Bill of Materials)** 兼 **Starter** 模块。

*   **coin-strategy-common**: 这是一个聚合模块。它本身不包含代码，而是依赖了目录下所有的子模块。外部服务只需依赖它，即可获得所有公共能力。
*   **子模块**: 每个子模块 (`common-client`, `common-model` 等) 都是独立的，直接继承自项目根父工程。

## 模块说明

### 1. [common-base](common-base)

- **定位**: 基础核心模块。
- **用途**: 存放项目中最基础的组件。
  - **Utils**: 通用工具类 (e.g., `DateUtils`, `SignUtils`)。
  - **Constants**: 全局常量定义 (e.g., `OkxConstants`)。
  - **Base Classes**: 基础异常类、基础响应对象等。

### 2. [common-client](common-client)

- **定位**: 第三方服务客户端集成。
- **用途**: 封装与外部交易所或数据源的交互逻辑。
  - **Exchanges**: Binance, OKX, Gate.io 等交易所的 API 封装。
  - **Features**: 包含签名认证、HTTP 请求封装 (Retrofit/OkHttp)、接口定义等。

### 3. [common-model](common-model)

- **定位**: 数据模型模块。
- **用途**: 存放全系统通用的数据结构。
  - **Entities**: 数据库实体类。
  - **DTOs**: 数据传输对象。
  - **Enums**: 通用枚举类型。

### 4. [common-dao](common-dao)

- **定位**: 数据访问层。
- **用途**: 处理数据库交互。
  - **Resources**: 包含数据库初始化脚本 (`db.sql`)。

### 5. [common-cache](common-cache)

- **定位**: 缓存支持模块。
- **用途**: Redis 配置与本地缓存工具。

### 6. [common-api](common-api)

- **定位**: 统一交易抽象接口层。
- **用途**: 定义跨交易所的统一数据模型与服务接口，让上层策略面向统一接口编程，与具体交易所 SDK 解耦。
  - **统一模型**: `UnifiedOrder`、`OrderStatus`、`Side`、`OrderType`。
  - **服务接口**: `ITradeService`（下单 / 撤单 / 查询）、`IMarketDataService`（行情数据）。

### 7. [common-service](common-service)

- **定位**: 通用业务服务层。
- **用途**: 承载可跨模块复用的业务逻辑与服务组件，依赖 `common-dao` / `common-model`。
  - **行为分析**: `BehaviorVectorizer`、`CentroidDriftDetector` 等行为向量化与漂移检测。
  - **记账与追踪**: `PositionAccountingPort` 记账端口、`TraceContext` / `TraceIds` 链路追踪。
  - **告警**: `StrategyAuditAlertSink` 审计告警下沉。
  - **智能钱包**: `SmartWalletProperties` 配置类及各类只读查询视图模型（`*View`）。

## 如何使用

在你的业务服务（如 `coin-strategy-web`）的 `pom.xml` 中，引入 `coin-strategy-common` 即可获得所有依赖：

```xml
<dependency>
    <groupId>com.metaverse</groupId>
    <artifactId>coin-strategy-common</artifactId>
    <type>pom</type>
</dependency>
```

> **注意**: 必须指定 `<type>pom</type>`，因为 `coin-strategy-common` 是一个 POM 类型的聚合工程。

## 构建与开发

在项目根目录下运行以下命令来构建所有公共模块：

```bash
mvn clean install -pl coin-strategy-common -am
```

---

<!-- kb-backlink -->
> 本文归属：[架构与平台 知识地图](../docs/MOC-架构与平台.md) ｜ [知识库首页](../docs/知识库首页.md)
