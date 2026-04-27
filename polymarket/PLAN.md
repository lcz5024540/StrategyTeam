# 多交易账户与成组凭证平台化规划 Task

## Summary
把当前“环境变量单钱包执行”的能力，升级为一套可持续演进的平台架构：同时支持 `Polymarket 托管钱包`、`Binance/OKX API 绑定`、`策略收益归因`、`服务费/分成结算`。后续执行按阶段推进，先打底层模型，再接执行链路，再补账务和收费，避免一次性改太大导致上下文和风险失控。

## Phase Tasks

### Task 1: 统一领域模型与边界定义
目标：先把概念统一，避免后面边做边改。
- 定义核心实体：`User`、`PortfolioAccount`、`TradingAccount`、`CredentialSet`、`StrategySubscription`、`StrategyPnLLedger`、`BillingAccount`、`BillingInvoice`
- 明确三类账户模式：
  - `POLYMARKET_CUSTODY`
  - `BINANCE_API`
  - `OKX_API`
- 明确两类资金模式：
  - `Custody Balance`：平台托管资金
  - `Observed Balance`：交易所 API 读取余额
- 明确策略绑定对象是 `TradingAccount`，不是私钥，也不是单个 API key
- 明确收费对象是 `BillingAccount`，不是直接从交易执行链路里扣

交付物：
- 领域模型说明文档
- 实体关系图
- 枚举值和状态流定义

### Task 2: 成组凭证模型设计
目标：把“私钥/单字段 secret”升级为“整组凭证”。
- 设计 `CredentialSet`，一条记录代表一组可用凭证，而不是一个字段
- 定义 provider-specific schema：
  - `POLYMARKET_WALLET_V1`：`walletAddress/privateKey/funderAddress/signatureType`
  - `BINANCE_API_V1`：`apiKey/secretKey`
  - `OKX_API_V1`：`apiKey/secretKey/passphrase`
- 数据库仅保存元信息与 `vault_ref`，敏感值存 KMS/Vault
- 增加凭证状态与版本：
  - `PENDING_VERIFY`
  - `ACTIVE`
  - `ROTATING`
  - `REVOKED`
- 增加凭证校验结果快照：
  - 权限集
  - IP 白名单状态
  - 最近验证时间
  - 最近使用时间

交付物：
- `TradingAccount` / `CredentialSet` 表结构草案
- Vault/KMS 存储协议
- 凭证版本切换与轮换规则

### Task 3: 执行上下文与网关重构规划
目标：把当前全局 execution wallet 改成按交易账户动态装载。
- 把 `ExecutionWalletContextLoader` 演进为 `TradingAccountContextLoader`
- 输入改为 `tradingAccountId`
- 输出统一执行上下文：
  - provider
  - account type
  - credential set
  - balance mode
  - risk config
  - strategy config
- 执行网关按 provider 路由：
  - `PolymarketExecutionGateway`
  - `BinanceFundingRateExecutionGateway`
  - `OkxFundingRateExecutionGateway`
- 策略运行时统一依赖交易账户，不再依赖 env 私钥或固定配置

交付物：
- 上下文对象定义
- 网关接口统一抽象
- 路由规则与调用链设计

### Task 4: 收益归因与内部账本设计
目标：解决“用户自带 API，平台如何识别策略收益”的问题。
- 新增 `StrategyPnLLedger`
- 收益统计只按平台策略归因，不按账户总收益计费
- 记录维度至少包含：
  - 开仓成本
  - 平仓收益
  - 资金费收入
  - 手续费
  - 滑点
  - 净收益
  - 已结算分成
  - 未结算分成
- 外部 API 账户优先要求“独立子账户 / 专用 API”
- 若不是独立子账户，系统需标记为“收益归因风险模式”，限制按收益分成收费

交付物：
- 策略收益归因口径说明
- `StrategyPnLLedger` 表结构草案
- 归因失败/混仓场景处理规则

### Task 5: 收费与分成机制设计
目标：在不掌握 CEX 提现权限的前提下完成收费闭环。
- 设计三段式收费模型：
  - 基础订阅费
  - 绩效分成
  - 平台服务保证金
- `Polymarket 托管账户`：
  - 可直接从平台账本扣费
- `Binance/OKX API 账户`：
  - 不依赖提现权限
  - 用户需预存平台保证金或服务余额
  - 周期结算后从平台余额扣费
  - 余额不足时停止新开仓，保留平仓
- 新增 `BillingAccount` / `BillingInvoice`
- 明确账单状态流：
  - `PENDING`
  - `POSTED`
  - `PARTIALLY_PAID`
  - `PAID`
  - `OVERDUE`

交付物：
- 收费规则文档
- 账单模型与扣费规则
- 欠费后的策略限制规则

### Task 6: 充值/提现与外部账户区分方案
目标：避免托管资金和外部交易所资金混账。
- `portfolio_account` 仅承接平台资金账户能力
- `trading_account` 仅承接执行能力
- `POLYMARKET_CUSTODY` 支持平台充值/提现
- `BINANCE_API/OKX_API` 不支持平台充值提现，只支持绑定和观察余额
- 余额快照区分：
  - `CUSTODY`
  - `OBSERVED`
- 平台对外展示时清晰区分“平台资产”和“交易所观察资产”

交付物：
- 资金口径说明
- 充值/提现能力矩阵
- 余额快照模型

### Task 7: 安全与风控基线
目标：把多账户、多凭证体系的安全边界先定死。
- 禁止在接口和日志中暴露 `privateKey/apiKey/secretKey/passphrase`
- CEX 默认只允许 `read + trade`，禁止 `withdraw`
- 绑定时强校验：
  - 权限
  - IP 白名单
  - 账户模式
  - 子账户属性
- 凭证轮换必须整组切换版本
- 高风险操作必须审计：
  - 绑定
  - 校验
  - 轮换
  - 冻结
  - 解密使用
- 欠费、凭证失效、权限变更时策略自动降级为“禁新开仓”

交付物：
- 安全基线清单
- 风控规则清单
- 审计事件清单

## Public Interfaces / Key Additions
需要新增或重构的关键接口与类型：
- `TradingAccountContextLoader`
- `CredentialVaultService`
- `ExecutionGatewayRouter`
- `StrategyPnLCalculator`
- `BillingSettlementService`

关键数据对象：
- `TradingAccount`
- `CredentialSet`
- `BalanceSnapshot`
- `StrategyPnLLedgerEntry`
- `BillingInvoice`

## Test Plan
需要按阶段补齐以下测试场景：
- `Polymarket` 托管账户可正常加载钱包凭证并执行
- `Binance API` 凭证必须要求 `apiKey + secretKey` 成组通过校验
- `OKX API` 凭证必须要求 `apiKey + secretKey + passphrase` 成组通过校验
- 同一 `TradingAccount` 凭证轮换时，新旧版本不会混用
- 外部 API 账户收益归因只统计平台策略订单，不统计用户手动订单
- 欠费时禁止新开仓，但允许平仓
- 凭证失效、权限丢失、IP 白名单错误时，策略自动暂停
- 平台托管资金与外部观察余额不会混账
- 所有接口、日志、审计流中不出现明文敏感值

## Assumptions And Defaults
- 默认采用 `Polymarket 托管 + Binance/OKX API 绑定` 的混合模式
- `Binance/OKX` 收费默认采用“订阅费 + 绩效分成 + 平台保证金”
- `Binance/OKX` 不要求也不允许提现权限
- 收益分成默认基于“策略归因净收益”，不基于账户总收益
- 若用户不使用独立子账户，系统允许接入，但默认标记为高归因风险并限制收益分成模式
- 敏感凭证默认不落业务表明文，只通过 `vault_ref` 访问
