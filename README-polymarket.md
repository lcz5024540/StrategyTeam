# Polymarket Smart Wallet Copy Trading

## Product Goal

This module is a Polymarket smart-wallet discovery and copy-trading system.

The product goal is not just "place orders on Polymarket", but:

1. discover wallets worth following
2. determine whether a fresh wallet action is still followable
3. execute with constrained risk
4. persist events, tasks, and results for review

## Product Layers

### 1. Information Layer

- Polymarket market data ingestion
- Polymarket wallet trade ingestion
- leaderboard and profile discovery

### 2. Intelligence Layer

- smart wallet discovery
- wallet scoring
- qualification and watchlist selection
- signal freshness and followability judgement

### 3. Execution Layer

- task creation
- task processing
- paper execution
- live-wallet configuration loading

### 4. Analytics Layer

- follow task status tracking
- watchlist monitoring
- product overview and readiness inspection
- portfolio exposure and attribution views

## Current Domain Mapping

### market-ingestion

- `PolymarketApiClient`
- `PolymarketWalletDiscoveryService`

### wallet-intelligence

- `SmartWalletProfileStoreService`
- `SmartWalletScoringService`
- `SmartWalletSelectionService`

### signal-engine

- `PolymarketSmartWalletMonitorService`
- `SmartWalletSignalRiskService`
- `SmartWalletFollowTaskService`

### execution-engine

- `ExecutionWalletContextLoader`
- `PolymarketOrderRequestFactory`
- `PolymarketMarketMetadataService`
- `PolymarketPythonSdkBridge`
- `PolymarketClobExecutionService`
- `PolymarketExecutionGateway`
- `LoggingPolymarketExecutionGateway`

### ops-and-analytics

- `PolymarketProductOverviewService`
- `SmartWalletController`

## Product Workflow

1. fetch candidate wallets from Polymarket leaderboard
2. compute wallet metrics from closed positions and recent trades
3. upsert profiles into `smart_wallet_profile`
4. qualify wallets into watchlist
5. sync recent trades for watchlist wallets
6. convert trade events into follow tasks
7. run signal-risk checks before a task becomes `PENDING`
8. execute `PENDING` tasks through paper or gated live execution
9. expose overview, watchlist, and task status for operations

## Core APIs

- `GET /smart-wallet/health`
- `GET /smart-wallet/overview`
- `GET /smart-wallet/portfolio/summary`
  - supports `windowSeconds`, `limit`
- `GET /smart-wallet/portfolio/positions`
  - supports `windowSeconds`, `limit`, `offset`
- `GET /smart-wallet/portfolio/attribution/wallets`
  - supports `windowSeconds`, `taskLimit`, `limit`
- `GET /smart-wallet/portfolio/attribution/markets`
  - supports `windowSeconds`, `taskLimit`, `limit`
- `GET /smart-wallet/profiles`
- `GET /smart-wallet/watchlist`
- `GET /smart-wallet/consensus-signals`
  - supports `windowSeconds`, `marketId`, `outcome`, `side`, `limit`
- `POST /smart-wallet/polymarket/analyze`
- `POST /smart-wallet/polymarket/sync-events`
- `GET /smart-wallet/follow-tasks`
- `POST /smart-wallet/follow-tasks/process`
- `GET /smart-wallet/execution-wallet/status`
- `POST /smart-wallet/execution-wallet/derive-api-creds`
- `GET /smart-wallet/clob/markets/{conditionId}`
- `GET /smart-wallet/clob/orders/{orderId}`
- `POST /smart-wallet/clob/orders/{orderId}/cancel`

## Risk Rules

The current implementation enforces these pre-task checks:

- wallet must still be qualified and watch-enabled
- `SELL` signals are blocked by default
- stale signals are rejected
- too-small trade events are rejected
- prices outside configured follow range are rejected
- recommended stake must be above execution-wallet minimum order
- optional multi-wallet consensus gate is enforced before a signal becomes pending

Rejected items are persisted as `SKIPPED` follow tasks so they remain auditable.

## Config Surface

Primary config lives under `smart-wallet` in:

- `coin-strategy-job/src/main/resources/application.yml`

Important sections:

- `smart-wallet.polymarket`
- `smart-wallet.signal-risk`
- `smart-wallet.execution-wallet`

## Official API Binding

This service is aligned with the official Polymarket API domains from the public docs:

- Gamma API: `https://gamma-api.polymarket.com`
- Data API: `https://data-api.polymarket.com`
- CLOB API: `https://clob.polymarket.com`

There is no official Java SDK in the public Polymarket docs today. The official SDKs are TypeScript, Python, and Rust.

For that reason, the Java integration in this repo uses direct HTTP bindings instead of a Java SDK wrapper:

- `PolymarketApiClient` uses `OkHttp`
- base URLs remain configurable from `application.yml`
- discovery and analytics currently rely on Gamma/Data APIs
- live CLOB execution is bridged through the official Python SDK helper in `scripts/polymarket_clob_client.py`

## Delivery Status

### Implemented

- Polymarket leaderboard discovery
- official Gamma/Data API base URL binding
- Java `OkHttp` transport for official Polymarket API requests
- official Python SDK bridge for CLOB live execution
- conditionId to outcome tokenId resolution for live orders
- wallet profile persistence
- smart wallet selection and watchlist
- watchlist trade sync
- follow task generation
- signal risk filtering before follow task becomes pending
- grouped consensus signals across multiple wallets
- historical consensus signal query by custom time window and filters
- paper execution
- execution-wallet config loading
- execution-wallet readiness endpoint
- overview endpoint
- portfolio summary endpoint
- portfolio position and attribution endpoints
- resilience for intermittent Polymarket TLS failures

### Partially Implemented

- live execution
  - wallet config is loaded
  - order payload preparation exists
  - conditionId is resolved to tokenId from Gamma API market metadata
  - order submission / get-order / cancel-order are wired through the official Python SDK helper
  - production readiness still depends on installing `py-clob-client` and validating wallet/funder/signature settings in your environment

- signal engine
  - supports freshness, size, side, price, and multi-wallet consensus checks
  - does not yet use order book depth

- analytics
  - task, profile, portfolio, and attribution visibility exist
  - consensus signal visibility exists
  - strategy attribution and portfolio PnL are not yet complete

### Not Yet Implemented

- position lifecycle tracking after execution
- realized PnL attribution dashboard
- full historical backtest engine
- order-book-aware slippage modelling
- alerting / notification pipeline

## Integration Guidance

If you already built part of the stack, use this repo's smart-wallet module as the integration anchor and standardize around these shared objects:

- wallet profile
- trade event
- signal risk decision
- follow task
- execution wallet context

Recommended event flow:

`wallet discovery -> wallet scoring -> trade event -> signal risk -> follow task -> execution -> analytics`

## Source Files

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/...`
- `coin-strategy-common/common-model/.../SmartWallet*Entity.java`
- `coin-strategy-common/common-dao/.../SmartWallet*Mapper.java`
- `sql/arbitrage_schema.sql`
