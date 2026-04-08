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
- `POST /smart-wallet/settlement/sync`
- `GET /smart-wallet/settlement/status`
- `GET /smart-wallet/settlement/runs`
- `GET /smart-wallet/settlement/retries`
- `GET /smart-wallet/settlement/retries/report`
- `POST /smart-wallet/settlement/retries/process`

## Risk Rules

The current implementation enforces these pre-task checks:

- wallet must still be qualified and watch-enabled
- `SELL` signals are blocked by default
- stale signals are rejected
- too-small trade events are rejected
- prices outside configured follow range are rejected
- recommended stake must be above execution-wallet minimum order
- optional multi-wallet consensus gate is enforced before a signal becomes pending
- single-wallet override can allow strong wallets through a stricter side lane
- portfolio-level position count limit is enforced
- portfolio-level total open cost budget is enforced
- long-duration markets can be rejected by market-end timing policy
- duplicate same-market same-outcome open positions are blocked
- duplicate same-market same-outcome pending tasks are blocked
- opposite-outcome positions on the same market are blocked
- opposite-outcome pending tasks on the same market are blocked

Rejected items are persisted as `SKIPPED` follow tasks so they remain auditable.

## Why Risk Downgrading Matters

This project is not only a signal follower. It is a portfolio system.

Without additional risk downgrading and blocking rules, a smart-wallet strategy can still fail for operational reasons:

- too many markets open at the same time and capital gets locked for too long
- the same market gets followed repeatedly, creating duplicated exposure
- both sides of the same market get opened by different source signals
- long-settlement markets consume budget and slow turnover
- stale signals are executed after the original edge is gone

In real runs, the most dangerous pattern is not always "low-quality wallets". It is often "good wallets but bad portfolio construction".

That is why risk handling in this project is split into two layers:

- signal quality control
  - freshness, size, consensus, wallet override, price range
- portfolio exposure control
  - max open positions, max open cost, market timing, duplicate blocking, opposite-market blocking

The new opposite-market controls are especially important because historical analysis showed that opening both outcomes on the same `marketId` created avoidable losses and distorted win-rate evaluation.

## Current Risk Controls

### Signal-Level Controls

- watchlist qualification check
- signal freshness window
- minimum trigger size
- follow price range check
- execution-wallet minimum order check
- consensus wallet count threshold
- consensus score threshold
- optional single-wallet override for strong wallets

### Portfolio-Level Controls

- `max-open-positions`
- `max-total-open-cost-usdc`
- `max-hours-to-market-end`
- `block-duplicate-open-position`
- `block-duplicate-pending-task`
- `block-opposite-open-position-same-market`
- `block-opposite-pending-task-same-market`

### Execution Safety Controls

- `PAPER` is the default validation path
- `LIVE` validation is a separate lane
- geoblock / TLS / network failures trigger circuit-breaker suspension
- live execution remains disabled by default in restricted environments

### Risk Observability

You can inspect risk decisions and bottlenecks through:

- `GET /smart-wallet/follow-tasks?includeRiskEvaluation=true`
- `GET /smart-wallet/follow-tasks/blocker-stats?taskLimit=200`
- `GET /smart-wallet/capacity/summary`
- `GET /smart-wallet/reports/performance`
- `GET /smart-wallet/reports/conflicting-markets?limit=20&openOnly=false`

The most useful interpretation is:

- `blocker-stats`
  - tells you which rule blocks the largest share of new tasks
- `capacity/summary`
  - tells you whether the portfolio is already full
- `performance`
  - now includes conflict metrics, so you can see whether same-market multi-outcome exposure is harming results
- `conflicting-markets`
  - lists the specific markets where multiple outcomes were held

## How To Tune Risk Parameters

Use the risk reports as a decision guide instead of adjusting parameters blindly.

### If `POSITION_LIMIT` Is Dominant

Meaning:

- too many open positions are already consuming portfolio slots

Check:

- `GET /smart-wallet/capacity/summary`
- `GET /smart-wallet/positions/live`

Tune:

- raise `smart-wallet.signal-risk.max-open-positions` carefully
- or reduce how many new markets the strategy is allowed to open

Recommended approach:

- first inspect whether many old positions should naturally settle soon
- only then increase the slot limit in small steps

### If `CAPITAL_LIMIT` Is Dominant

Meaning:

- open cost basis has already consumed the configured budget

Check:

- `GET /smart-wallet/capacity/summary`
- `GET /smart-wallet/pnl/summary`

Tune:

- raise `smart-wallet.signal-risk.max-total-open-cost-usdc`
- lower stake sizing if you want broader diversification without increasing total budget

Recommended approach:

- increase budget before increasing position count only if turnover is already healthy

### If `MARKET_TIMING` Is Dominant

Meaning:

- too many candidate markets settle too far in the future

Check:

- `GET /smart-wallet/follow-tasks?includeRiskEvaluation=true`
- `GET /smart-wallet/reports/conflicting-markets?limit=20&openOnly=false`

Tune:

- raise `smart-wallet.signal-risk.max-hours-to-market-end` if you want to allow longer-duration markets
- keep it tight if capital turnover is more important than signal coverage

Recommended approach:

- do not widen this first if capital is already constrained

### If `SIGNAL_FRESHNESS` Is Dominant

Meaning:

- signals are being evaluated after their useful window

Tune:

- raise `smart-wallet.signal-risk.max-signal-age-seconds`
- or reduce polling / scheduling delay

Recommended approach:

- widen freshness gradually
- confirm that widening it improves realized performance rather than just increasing fill count

### If `CONSENSUS_WALLETS` Or `CONSENSUS_SCORE` Is Dominant

Meaning:

- the default multi-wallet confirmation is too strict for current market conditions

Tune:

- `smart-wallet.signal-risk.min-distinct-wallets`
- `smart-wallet.signal-risk.min-consensus-score`
- or use a preset with `single-wallet override`

Recommended approach:

- prefer `single-wallet override` before fully weakening consensus rules

### If `MIN_ORDER` Is Dominant

Meaning:

- recommended stake is below your execution wallet minimum order threshold

Tune:

- lower `smart-wallet.execution-wallet.min-order-usdc`
- or raise strategy stake sizing constraints

Recommended approach:

- keep the minimum realistic for execution costs
- avoid lowering it so far that the strategy opens many low-signal micro positions

### If `DUPLICATE_POSITION` Or `DUPLICATE_TASK` Is Dominant

Meaning:

- the strategy is repeatedly trying to enter the same market and outcome

Tune:

- normally do not disable these protections

Recommended approach:

- treat this as healthy blocking, not as a parameter problem

### If `OPPOSITE_MARKET_POSITION` Or `OPPOSITE_MARKET_TASK` Is Dominant

Meaning:

- the system is trying to open both sides of the same market through different source signals

Check:

- `GET /smart-wallet/reports/conflicting-markets?limit=20&openOnly=false`
- `GET /smart-wallet/reports/performance`

Tune:

- normally do not disable these protections
- instead tighten strategy qualification or single-wallet override rules

Recommended approach:

- this is one of the highest-priority safety protections
- historical analysis showed that same-market opposite-outcome exposure caused avoidable losses

### If Conflict Metrics Are High In `performance`

Fields:

- `conflictingMarkets`
- `conflictingPositions`
- `openConflictingMarkets`
- `openConflictingPositions`
- `conflictNetPnlUsdc`

Interpretation:

- high counts mean the strategy has repeatedly held multiple outcomes on the same market
- a strongly negative `conflictNetPnlUsdc` means this behavior is materially damaging returns

Action:

- keep opposite-market blocking enabled
- review single-wallet override thresholds
- review source signal selection for sports and binary yes/no markets first

## Config Surface

Primary config lives under `smart-wallet` in:

- `coin-strategy-job/src/main/resources/application.yml`

Important sections:

- `smart-wallet.polymarket`
- `smart-wallet.signal-risk`
- `smart-wallet.execution-wallet`

## Environment Variables

The execution wallet now reads secrets only from environment variables.

Template file:

- `scripts/polymarket-env.example.sh`

Recommended local workflow:

1. copy `scripts/polymarket-env.example.sh` to `scripts/polymarket-env.sh`
2. fill in your own wallet and CLOB values
3. start the service with `scripts/run-smart-wallet.sh`

Variables used by the service:

```bash
export POLYMARKET_EXECUTION_WALLET_ADDRESS=""
export POLYMARKET_EXECUTION_PRIVATE_KEY=""
export POLYMARKET_EXECUTION_FUNDER_ADDRESS=""

export POLYMARKET_CLOB_API_KEY=""
export POLYMARKET_CLOB_API_SECRET=""
export POLYMARKET_CLOB_API_PASSPHRASE=""

export POLYMARKET_PYTHON_EXECUTABLE="/opt/homebrew/bin/python3.11"
```

Classification:

- `[SCRIPT][FIXED-ONCE]`
  - fill once in `scripts/polymarket-env.sh`
  - includes wallet address, private key, funder address, derived CLOB creds, Python path
- `[TUNE]`
  - strategy and risk parameters that you should revisit as live data comes in
  - includes freshness window, consensus gate, wallet thresholds, order sizing caps
- `[FIXED-ONCE]`
  - environment or integration constants usually set once per deployment
  - includes API base URLs, chain id, polling intervals, DB connection, helper script path

## Single-Wallet Override Preset

The single-wallet override board now supports a short preset so you do not need to pass a long list of tuning params every time.

Recommended preset:

- `preset=followable`

Example:

```bash
curl -s 'http://127.0.0.1:8081/smart-wallet/follow-tasks/single-wallet-override?limit=5&preset=followable'
```

To inspect all configured presets and the current default risk profile:

```bash
curl -s 'http://127.0.0.1:8081/smart-wallet/config/signal-risk-presets'
```

## Dual-Flow Automation

The service can now run two lanes automatically after startup:

- `PAPER` main flow
  - driven by the existing follow-task scheduler
  - processes normal `PENDING` tasks
- `LIVE` validation lane
  - driven by `smart-wallet.live-validation`
  - only scans single-wallet override candidates from a named preset
  - intended for tiny real-money validation, not broad production trading
  - disabled by default because Polymarket may reject trading by region (`geoblock`)

Status and manual trigger APIs:

```bash
curl -s 'http://127.0.0.1:8081/smart-wallet/live-validation/status'
curl -s -X POST 'http://127.0.0.1:8081/smart-wallet/live-validation/process'
```

Recommended startup mode:

- keep `smart-wallet.auto-execution-enabled=true` only when you want the normal main lane to auto-place `PAPER`/`LIVE` according to task mode
- keep `smart-wallet.live-validation.enabled=true` only after wallet funding, risk review, and confirming your runtime environment is not geoblocked
- keep `smart-wallet.live-validation.max-executions-per-run=1` for early testing
- keep the built-in circuit breaker enabled through:
  - `smart-wallet.live-validation.network-error-threshold`
  - `smart-wallet.live-validation.network-cooldown-minutes`
  - `smart-wallet.live-validation.geoblock-cooldown-minutes`

## Startup Steps

The repository is now configured so that a single service startup will always run the `PAPER` lane automatically, while the `LIVE` validation lane is disabled by default and should be enabled only when your environment is allowed to trade:

- `PAPER` main flow
  - wallet discovery
  - watchlist trade sync
  - follow-task creation
  - scheduled `PAPER` processing
- `LIVE` validation lane
  - scans `single-wallet-override` candidates from the configured preset
  - submits at most the configured number of tiny validation orders per cycle
  - pauses itself automatically on geoblock or repeated TLS/network failures

Current default startup config in `application.yml`:

- `smart-wallet.polymarket.auto-discover-enabled=true`
- `smart-wallet.polymarket.auto-sync-events-enabled=true`
- `smart-wallet.auto-execution-enabled=false`
- `smart-wallet.live-validation.enabled=false`
- `smart-wallet.live-validation.preset=followable`
- `smart-wallet.live-validation.max-executions-per-run=1`

### 1. Prepare env file

Create your local env file from the example and fill in your real values:

```bash
cp scripts/polymarket-env.example.sh scripts/polymarket-env.sh
```

Required local secrets live in `scripts/polymarket-env.sh`:

- `POLYMARKET_EXECUTION_WALLET_ADDRESS`
- `POLYMARKET_EXECUTION_PRIVATE_KEY`
- `POLYMARKET_EXECUTION_FUNDER_ADDRESS`
- `POLYMARKET_CLOB_API_KEY`
- `POLYMARKET_CLOB_API_SECRET`
- `POLYMARKET_CLOB_API_PASSPHRASE`
- `POLYMARKET_PYTHON_EXECUTABLE`

### 2. Start the service

```bash
cd /Users/jenniferni/jenifer/ray/CoinStrategy
scripts/run-smart-wallet.sh
```

The service now uses the default Spring Boot embedded `Tomcat` container again.

### 2.1 Open Swagger UI

After the service starts, open:

- `http://127.0.0.1:8081/swagger-ui.html`
- OpenAPI JSON: `http://127.0.0.1:8081/v3/api-docs`

Use this page to inspect request/response models before building your own reporting UI.

### 3. Verify the service is up

```bash
curl -s 'http://127.0.0.1:8081/smart-wallet/health'
curl -s 'http://127.0.0.1:8081/smart-wallet/execution-wallet/status'
curl -s 'http://127.0.0.1:8081/smart-wallet/live-validation/status'
```

Expected checks:

- `health.status = UP`
- `execution-wallet-status.liveReady = true`
- `live-validation.status.enabled = true`

### 4. Verify the PAPER main flow

```bash
curl -s 'http://127.0.0.1:8081/smart-wallet/follow-tasks?limit=20&includeRiskEvaluation=true'
curl -s 'http://127.0.0.1:8081/smart-wallet/pnl/summary'
curl -s 'http://127.0.0.1:8081/smart-wallet/reports/performance'
```

Use these to confirm that watchlist events are flowing into tasks and that paper accounting is updating.

### 5. Verify the LIVE validation lane

```bash
curl -s 'http://127.0.0.1:8081/smart-wallet/config/signal-risk-presets'
curl -s 'http://127.0.0.1:8081/smart-wallet/follow-tasks/single-wallet-override?limit=5&preset=followable'
curl -s 'http://127.0.0.1:8081/smart-wallet/live-validation/status'
```

The `live-validation/status` response is the main checkpoint:

- `running`
- `lastRunAt`
- `lastRunSummary`
- `suspended`
- `suspendedUntil`
- `suspensionReason`

If `suspended=true`, the LIVE lane hit a protective circuit breaker. In this environment, the most common reasons are:

- Polymarket geoblock / regional restriction
- repeated TLS/network failures from the Python SDK bridge

### 6. Manual fallback checks

If you want to force a one-off validation cycle:

```bash
curl -s -X POST 'http://127.0.0.1:8081/smart-wallet/live-validation/process'
```

If you want to inspect the exact candidate before a LIVE validation order:

```bash
curl -s -X POST 'http://127.0.0.1:8081/smart-wallet/follow-tasks/{id}/dry-run'
```

Current `followable` preset is now configured in:

- `smart-wallet.signal-risk-presets.followable`

and expands to this strategy shape:

- `taskLimit=120`
- `maxSignalAgeSeconds=3600`
- `minDistinctWallets=1`
- `minConsensusScore=0`
- `singleWalletOverrideMinWalletScore=0.79`
- `singleWalletOverrideMinWinRate=0.65`
- `singleWalletOverrideMinTrades=50`
- `singleWalletOverrideMinTriggerSizeUsdc=100`
- `singleWalletOverrideMinRecommendedStakeUsdc=5`

Use this board to inspect candidates before deciding whether any of those values should become the default live policy.

To add more presets later, extend `smart-wallet.signal-risk-presets` in `application.yml`, for example `strict`, `followable`, `aggressive`.

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
- settlement sync supports scheduled batch processing with market de-duplication and cached metadata refresh control
- settlement sync persists run history and failed-market retry queue
- settlement retry tables are auto-created on service startup if missing

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
- settlement sync run history
- failed-market retry queue with backoff

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
- retry queue currently focuses on metadata fetch failures rather than all unresolved markets

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

## Strategy Dashboard

This repo now includes a lightweight dashboard page directly hosted by `coin-strategy-job`. The first connected strategy is `POLYMARKET_COPY`, and the page structure already reserves cards for future strategies such as hedge, robot, and intelligent strategies.

### Current Recommended Run Mode

For the current MVP, you do **not** need to start a separate front-end service to see the dashboard pages.

The recommended mode is:

- start `coin-strategy-job`
- let it expose both:
  - smart-wallet backend APIs
  - dashboard static pages
  - Swagger API docs

That means a single command is enough for the current product demo path.

### Dashboard Goals

- show current positions by strategy
- show strategy-level profitability and capital usage
- show recent orders and current settlement queue
- show win-rate / pnl timeline and conflict-market risk
- provide a front-end aggregation layer based on existing smart-wallet APIs instead of querying MySQL directly

### Dashboard Data Sources

The page is currently front-end aggregated from existing smart-wallet APIs such as:

- `/smart-wallet/reports/performance`
- `/smart-wallet/pnl/summary`
- `/smart-wallet/capacity/summary`
- `/smart-wallet/positions/live`
- `/smart-wallet/positions/next-settlements`
- `/smart-wallet/orders`
- `/smart-wallet/pnl/timeline`
- `/smart-wallet/reports/conflicting-markets`

### Dashboard Frontend Entry

- overview page: `http://127.0.0.1:8081/dashboard/index.html`
- strategy detail page: `http://127.0.0.1:8081/dashboard/detail.html?strategy=POLYMARKET_COPY`
- charts page: `http://127.0.0.1:8081/dashboard/charts.html?strategy=POLYMARKET_COPY`
- the pages read directly from the existing `/smart-wallet/...` APIs on the same host

### How To Start

1. Prepare local execution env if needed:
   - make sure `scripts/polymarket-env.sh` exists
   - fill your own wallet / CLOB values only if you need execution features
2. Start the integrated backend + dashboard service:
   - `scripts/run-smart-wallet.sh`
3. Wait until the script prints:
   - `[run-smart-wallet] service is ready.`
4. Open any of these URLs in your browser:
   - Swagger: `http://127.0.0.1:8081/swagger-ui.html`
   - Dashboard overview: `http://127.0.0.1:8081/dashboard/index.html`
   - Dashboard detail: `http://127.0.0.1:8081/dashboard/detail.html?strategy=POLYMARKET_COPY&days=30`
   - Dashboard charts: `http://127.0.0.1:8081/dashboard/charts.html?strategy=POLYMARKET_COPY&days=30`

### What `run-smart-wallet.sh` Does

The script already combines startup concerns for the current MVP:

- loads local env from `scripts/polymarket-env.sh` if present
- builds `coin-strategy-job`
- starts the Spring Boot application
- waits for `/smart-wallet/health` to become available
- prints the ready-to-open backend and page URLs

So for the current repository state, this is the main command you should remember:

```bash
scripts/run-smart-wallet.sh
```

### Quick Access After Startup

Backend / docs:

- health: `http://127.0.0.1:8081/smart-wallet/health`
- swagger: `http://127.0.0.1:8081/swagger-ui.html`

Frontend pages:

- overview: `http://127.0.0.1:8081/dashboard/index.html`
- detail: `http://127.0.0.1:8081/dashboard/detail.html?strategy=POLYMARKET_COPY&days=30`
- charts: `http://127.0.0.1:8081/dashboard/charts.html?strategy=POLYMARKET_COPY&days=30`

### Optional Separate Web App

The repo still contains `coin-strategy-web`, but for the current MVP validation path it is **not** required to see the dashboard pages.

- `coin-strategy-job`
  - recommended for current use
  - exposes APIs + dashboard pages together
- `coin-strategy-web`
  - reserved for the future BFF / product-side evolution
  - not required for the current dashboard browsing flow

### Current Page Sections

- overview page
  - left sidebar navigation
  - global strategy / time-window filter bar
  - strategy cards
  - strategy core metrics
  - strategy health score
  - strategy health score breakdown
  - strategy insights
  - time window switcher
  - risk signal cards
  - wallet attribution summary cards
  - market attribution summary cards
  - current positions summary
  - next settlements summary
- strategy detail page
  - left sidebar navigation
  - global strategy / time-window filter bar
  - time window switcher
  - strategy-level metrics
  - current positions table
  - next settlements table
  - recent orders table
- charts page
  - left sidebar navigation
  - global strategy / time-window filter bar
  - time window switcher
  - cumulative pnl timeline chart
  - daily pnl bar chart
  - win-rate trend chart
  - wallet attribution bars
  - market attribution bars
  - conflict markets table
  - strategy analysis summary cards
  - URL state retention for `strategy` and `days`

### Expansion Guidance

If you add more strategies later, keep the same contract:

- each strategy gets one `strategyCode`
- the dashboard backend maps that code to one aggregation pipeline
- page cards stay strategy-agnostic
- strategy-specific metrics should still be normalized into:
  - pnl
  - win rate
  - drawdown
  - open positions
  - capacity
  - risk insights
