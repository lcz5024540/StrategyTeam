---
title: "Coin Strategy Common Client"
type: overview
status: active
tags: [common-client]
created: 2025-01-31
updated: 2026-02-01
---

# Coin Strategy Common Client

此模块封装了主流交易所 API 的调用，提供了统一且强类型的访问接口。

- [Binance 文档 (当前页面)](#快速开始)
- [OKX 文档 (OKX-README.md)](OKX-README.md)
- [Gate.io 文档 (GATE-README.md)](GATE-README.md)

---

## Binance API

封装了 Binance API 的调用，提供了现货 (Spot)、合约 (Futures)、杠杆 (Margin)、矿池 (Mining)、赚币 (Simple Earn) 和策略交易 (Alpha/Algo) 的统一访问接口。

基于 `binance-connector-java` 进行扩展，对所有接口进行了强类型封装，使用更安全、更易用。

## 快速开始

### 初始化

使用 `BinanceApiService` 作为统一入口。需要提供 API Key 和 Secret Key。

```java
import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;

String apiKey = "YOUR_API_KEY";
String secretKey = "YOUR_SECRET_KEY";

// 初始化服务
BinanceApiService apiService = new BinanceApiService(apiKey, secretKey);
```

---

## 现货交易 (Spot API)

通过 `BinanceSpotService` 和 `BinanceMarketDataService` 提供核心功能。

### 1. 账户信息

```java
// 获取账户信息（余额、权限等）
BinanceAccountInfo account = apiService.account();
System.out.println("BTC 余额: " + account.getAssetBalance("BTC").getFree());
```

### 2. 交易 (Trade)

```java
// 下限价单 (买入 0.01 BTC @ 50000 USDT)
BinanceOrder order = apiService.placeSpotOrder(
    "BTCUSDT", "BUY", "LIMIT", "GTC", "0.01", "50000"
);
System.out.println("订单ID: " + order.getOrderId());

// 撤单
apiService.cancelSpotOrder("BTCUSDT", order.getOrderId(), null);

// 查询所有挂单
List<BinanceOrder> openOrders = apiService.getSpotOpenOrders("BTCUSDT");

// 查询历史成交
List<BinanceTradeHistory> trades = apiService.getSpotMyTrades("BTCUSDT", null, 10, null, null, null);
```

### 3. 市场行情 (Market Data)

```java
// 获取最新成交
List<BinanceTrade> trades = apiService.getTrades("BTCUSDT", 20);

// 获取交易对信息
BinanceExchangeInfo info = apiService.exchangeInfo("BTCUSDT", null);
```

---

## 钱包服务 (Wallet API)

通过 `BinanceWalletService` 提供充提和资产管理功能。

```java
BinanceWalletService walletService = apiService.getWalletService();

// 系统状态
BinanceSystemStatus status = walletService.systemStatus();

// 获取所有币种信息 (充提状态、网络等)
List<BinanceCoinInfo> coins = walletService.getAllCoins();

// 查询充值历史
List<BinanceDepositHistory> deposits = walletService.depositHistory("USDT", 1, null, null, 0, 10);

// 查询提现历史
List<BinanceWithdrawHistory> withdraws = walletService.withdrawHistory("USDT", null, null, null, 0, 10);

// 获取充值地址
BinanceDepositAddress address = walletService.getDepositAddress("USDT", "TRX");
System.out.println("USDT-TRC20 地址: " + address.getAddress());
```

---

## 矿池服务 (Mining API)

通过 `BinanceMiningService` 提供矿池相关功能。

```java
BinanceMiningService miningService = apiService.getMiningService();

// 获取算法列表
List<BinanceMiningAlgo> algos = miningService.getAlgoList();

// 获取矿工列表
List<BinanceMiningWorker> workers = miningService.getWorkerList("sha256", "myUserName", 1, 0, 1, 1);

// 获取收益列表
List<BinanceMiningPayment> payments = miningService.getPaymentList("sha256", "myUserName", null, null, null, 1, 20);
```

---

## 赚币服务 (Simple Earn API)

通过 `BinanceSimpleEarnService` 提供理财产品管理。

```java
BinanceSimpleEarnService earnService = apiService.getSimpleEarnService();

// 获取活期产品列表
BinanceSimpleEarnListResponse<BinanceSimpleEarnFlexibleProduct> products = 
    earnService.getFlexibleProductList("USDT", 1L, 20);

// 申购活期产品
earnService.subscribeFlexibleProduct("PRODUCT_ID", new BigDecimal("100"), true, "SPOT");

// 获取活期持仓
BinanceSimpleEarnListResponse<BinanceSimpleEarnFlexiblePosition> positions = 
    earnService.getFlexibleProductPosition("USDT", null, 1L, 20);
```

---

## 策略交易 (Alpha/Algo API)

通过 `BinanceAlphaService` 提供算法订单（如 TWAP, VP）。

```java
BinanceAlphaService alphaService = apiService.getAlphaService();

// 下 TWAP 策略单
Map<String, Object> params = new HashMap<>();
params.put("limitPrice", "50000"); // 选填
params.put("duration", 300);       // 持续 300秒

BinanceAlphaOrder algoOrder = alphaService.newOrder(
    "BTCUSDT", "BUY", "1.0", "TWAP", params
);

// 查询进行中的策略单
List<BinanceAlphaOrder> openAlgos = alphaService.getOpenOrders();

// 停止策略单
alphaService.cancelOrder(algoOrder.getAlgoId());
```

---

## 杠杆交易 (Margin API)

所有 Margin 相关的接口都已通过 `BinanceApiService` 暴露，底层由 `BinanceMarginService` 实现。

### 1. 账户信息 (Account)

获取全仓和逐仓账户详情，查看资产、负债、权益等。

```java
// 获取全仓账户详情
BinanceCrossMarginAccount crossAccount = apiService.getCrossMarginAccountDetails();
System.out.println("全仓总资产: " + crossAccount.getTotalAssetOfBtc());

// 获取逐仓账户详情 (指定交易对，如 BTCUSDT)
BinanceIsolatedMarginAccount isolatedAccount = apiService.getIsolatedMarginAccountInfo("BTCUSDT");
System.out.println("BTCUSDT 逐仓状态: " + isolatedAccount.getAssets().get(0).getMarginLevel());

// 查询全仓最大可转出额
BinanceMarginMaxTransferable maxTransferable = apiService.getMarginMaxTransferable("USDT", null);
System.out.println("全仓 USDT 最大可转: " + maxTransferable.getAmount());

// 查询逐仓最大可转出额
BinanceMarginMaxTransferable isolatedMaxTrans = apiService.getMarginMaxTransferable("BTC", "BTCUSDT");
System.out.println("BTCUSDT 逐仓 BTC 最大可转: " + isolatedMaxTrans.getAmount());
```

### 2. 借贷与划转 (Borrow, Repay, Transfer)

支持全仓和逐仓的资金划转、借款和还款。

```java
// 全仓划转 (1: 现货->全仓, 2: 全仓->现货)
apiService.marginTransfer("USDT", "100", 1);

// 逐仓划转 (SPOT, ISOLATED_MARGIN)
apiService.marginIsolatedTransfer("BTC", "BTCUSDT", "0.1", "SPOT", "ISOLATED_MARGIN");

// 借款 (全仓)
// isIsolated=false (全仓), symbol=null
apiService.marginBorrow("USDT", "10", null, false);

// 借款 (逐仓)
// isIsolated=true, symbol="BTCUSDT"
apiService.marginBorrow("BTC", "0.01", "BTCUSDT", true);

// 还款 (全仓)
apiService.marginRepay("USDT", "10.5", null, false);

// 还款 (逐仓)
apiService.marginRepay("BTC", "0.01", "BTCUSDT", true);
```

### 3. 交易 (Trade)

下单、撤单及查询订单。

```java
// 下单 (全仓做多 BTC)
// symbol, side, type, quantity, price, isIsolated, sideEffectType, timeInForce
apiService.marginNewOrder(
    "BTCUSDT", 
    "BUY", 
    "LIMIT", 
    "0.01", 
    "50000", 
    "FALSE", // isIsolated: FALSE=全仓, TRUE=逐仓
    null,    // sideEffectType: 默认 MARGIN_BUY
    "GTC"    // timeInForce
);

// 下单 (逐仓做空 ETH)
apiService.marginNewOrder(
    "ETHUSDT", 
    "SELL", 
    "MARKET", 
    "1", 
    null,    // 市价单不需要价格
    "TRUE",  // isIsolated
    null, 
    null
);

// 撤单
apiService.marginCancelOrder("BTCUSDT", 123456L, null, false);

// 查询当前挂单
List<BinanceMarginOrder> openOrders = apiService.getMarginOpenOrders("BTCUSDT", false);

// 查询历史成交
List<BinanceMarginTrade> myTrades = apiService.getMarginMyTrades("BTCUSDT", false, null, null, null, 10);
```

### 4. 行情与基础数据 (Market Data)

获取杠杆资产、交易对信息、利率等。

```java
// 获取所有杠杆资产信息
List<BinanceMarginAsset> assets = apiService.getMarginAssets();

// 获取所有杠杆交易对
List<BinanceMarginPair> pairs = apiService.getMarginPairs();

// 获取逐仓交易对信息
List<BinanceIsolatedMarginSymbol> isolatedSymbols = apiService.getIsolatedMarginSymbols("BTCUSDT");

// 获取价格指数
BinanceMarginPriceIndex index = apiService.getMarginPriceIndex("BTCUSDT");

// 获取下一小时利率预测
List<BinanceMarginNextHourlyInterestRate> rates = apiService.getMarginFutureHourlyInterestRate("BTC,ETH", false);
```

### 5. 用户数据流 (User Data Stream)

用于订阅 WebSocket 账户变动推送。

```java
// 开启 ListenKey
String listenKey = apiService.startMarginUserDataStream();
System.out.println("ListenKey: " + listenKey);

// 延长 ListenKey 有效期 (建议每 30 分钟调用一次)
apiService.keepAliveMarginUserDataStream(listenKey);

// 关闭 ListenKey
apiService.closeMarginUserDataStream(listenKey);
```

---

## OKX 交易 (OKX Trading)

OKX 相关的 API 接口文档请参考 [OKX-README.md](OKX-README.md)。

---

## 注意事项

1. **参数类型**: 大部分金额和价格参数使用 `String` 或 `BigDecimal` 类型以避免精度丢失。
2. **异常处理**: 接口调用失败会抛出 `RuntimeException`，建议在业务层捕获并处理。
3. **网络**: 底层使用 `OkHttp`，默认连接超时和读取超时已配置。
4. **测试网**: 在 `BinanceApiService` 构造函数中可以传入 Testnet URL 进行测试。

## 参考文档

- [Binance Spot API Documentation](https://developers.binance.com/docs/binance-spot-api-docs)
- [Binance Margin API Documentation](https://developers.binance.com/docs/margin_trading/market-data)
- [OKX V5 API Documentation](https://www.okx.com/docs-v5/zh/)

---

## 测试 (Testing)

本项目包含单元测试和集成测试。

### 运行测试

使用 Maven 运行所有测试：

```bash
mvn test -pl coin-strategy-common/common-client
```

### 单元测试 vs 集成测试

- **单元测试**: 使用 `MockWebServer` 模拟 Binance API 响应，无需真实网络连接和 API Key。覆盖了 Spot, Margin, Wallet, Market Data, Mining, Simple Earn, Alpha 等模块的主要功能。
- **集成测试**: `BinanceApiServiceTest` 包含连接真实 API (Testnet) 的测试用例。
  - 默认情况下，需要真实网络连接的测试已被禁用 (`@Disabled`)，以确保构建可以通过。
  - 如需运行集成测试，请在 `BinanceApiServiceTest.java` 中移除 `@Disabled` 注解，并配置有效的 Testnet API Key 和 Secret Key。

---

<!-- kb-backlink -->
> 本文归属：[架构与平台 知识地图](../../docs/MOC-架构与平台.md) ｜ [知识库首页](../../docs/知识库首页.md)
