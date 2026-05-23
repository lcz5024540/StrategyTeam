---
title: "Gate.io 交易 (Gate.io Trading)"
type: runbook
status: active
tags: [common-client]
created: 2026-02-01
updated: 2026-02-01
---

# Gate.io 交易 (Gate.io Trading)

通过 `GateApiService` 提供统一访问接口，集成官方 `gateapi-java` SDK，支持现货、合约、钱包等业务。

## 1. 依赖配置 (Dependency)

项目已集成官方 SDK：

```xml
<dependency>
    <groupId>io.gate</groupId>
    <artifactId>gate-api</artifactId>
    <version>7.1.8</version>
</dependency>
```

## 2. 初始化 (Initialization)

```java
import com.metaverse.coin.strategy.common.client.gate.GateApiService;

String apiKey = "YOUR_API_KEY";
String secretKey = "YOUR_SECRET_KEY";

// 初始化 Service (默认 Base URL: https://api.gateio.ws/api/v4)
GateApiService gateService = new GateApiService(apiKey, secretKey);

// 如需指定 Base URL (例如测试网)
// GateApiService gateService = new GateApiService(apiKey, secretKey, "https://fx-api-testnet.gateio.ws/api/v4");
```

## 3. 行情数据 (Market Data)

通过 `gateService.getMarketDataService()` 访问。

```java
import io.gate.gateapi.models.Ticker;
import io.gate.gateapi.models.FuturesTicker;
import java.util.List;

// 获取所有现货 Ticker
List<Ticker> allTickers = gateService.getMarketDataService().getSpotTickers(null);

// 获取特定现货交易对 Ticker (e.g., BTC_USDT)
List<Ticker> btcTicker = gateService.getMarketDataService().getSpotTickers("BTC_USDT");

// 获取合约 Ticker (settle: usdt, contract: BTC_USDT)
List<FuturesTicker> futuresTickers = gateService.getMarketDataService().getFuturesTickers("usdt", "BTC_USDT");
```

## 4. 账户信息 (Account)

通过 `gateService.getAccountService()` 访问。

```java
import io.gate.gateapi.models.SpotAccount;
import io.gate.gateapi.models.FuturesAccount;
import java.util.List;

// 获取现货账户余额
List<SpotAccount> spotAccounts = gateService.getAccountService().getSpotAccounts("USDT");

// 获取合约账户信息 (settle: usdt)
FuturesAccount futuresAccount = gateService.getAccountService().getFuturesAccounts("usdt");
```

## 5. 交易 (Trade)

通过 `gateService.getTradeService()` 访问。

### 5.1 现货下单 (Spot Order)

```java
import io.gate.gateapi.models.Order;

Order order = new Order();
order.setCurrencyPair("BTC_USDT");
order.setSide(Order.SideEnum.BUY);
order.setAmount("0.001");
order.setPrice("50000");
order.setTimeInForce(Order.TimeInForceEnum.GTC);

Order createdOrder = gateService.getTradeService().placeSpotOrder(order);
System.out.println("Order ID: " + createdOrder.getId());
```

### 5.2 现货撤单 (Cancel Spot Order)

```java
// 需要提供 Order ID 和 Currency Pair
Order cancelledOrder = gateService.getTradeService().cancelSpotOrder("123456", "BTC_USDT");
```

### 5.3 合约下单 (Futures Order)

```java
import io.gate.gateapi.models.FuturesOrder;

FuturesOrder futuresOrder = new FuturesOrder();
futuresOrder.setContract("BTC_USDT");
futuresOrder.setSize(10L); // 数量 (张)
futuresOrder.setPrice("50000");
futuresOrder.setTif(FuturesOrder.TifEnum.GTC);

FuturesOrder createdFuturesOrder = gateService.getTradeService().placeFuturesOrder("usdt", futuresOrder);
```

### 5.4 合约撤单 (Cancel Futures Order)

```java
// 需要提供 Settle 和 Order ID
FuturesOrder cancelledFuturesOrder = gateService.getTradeService().cancelFuturesOrder("usdt", "123456");
```

## 6. 钱包 (Wallet)

通过 `gateService.getWalletService()` 访问。

```java
import io.gate.gateapi.models.TotalBalance;

// 获取全账户总余额
TotalBalance totalBalance = gateService.getWalletService().getTotalBalance("USDT");
System.out.println("Total USDT Value: " + totalBalance.getTotal().getAmount());
```

---

<!-- kb-backlink -->
> 本文归属：[架构与平台 知识地图](../../docs/MOC-架构与平台.md) ｜ [知识库首页](../../docs/知识库首页.md)
