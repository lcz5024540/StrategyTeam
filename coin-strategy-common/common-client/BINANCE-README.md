# Binance API 使用说明

## 现货账户与交易 (Spot Account & Trade)

通过 `BinanceSpotService` 提供账户信息查询与订单管理功能。

### 1. 账户信息 (Account Information)

获取当前账户的资产余额、权限等信息。

**用法示例:**

```java
// 初始化 BinanceApiService
BinanceApiService apiService = new BinanceApiService(apiKey, secretKey);
BinanceSpotService spotService = apiService.getSpotService();

// 获取账户信息
BinanceAccountInfo accountInfo = spotService.account();
System.out.println("Can Trade: " + accountInfo.getCanTrade());
for (BinanceBalance balance : accountInfo.getBalances()) {
    if (new BigDecimal(balance.getFree()).compareTo(BigDecimal.ZERO) > 0) {
        System.out.println("Asset: " + balance.getAsset() + ", Free: " + balance.getFree());
    }
}
```

### 2. 下单 (New Order)

支持限价单、市价单等多种订单类型。

**用法示例:**

```java
// 限价买入 BTCUSDT
String symbol = "BTCUSDT";
String side = "BUY";
String type = "LIMIT";
String timeInForce = "GTC";
String quantity = "0.001";
String price = "50000";

// 返回原始 JSON
String response = spotService.newOrder(symbol, side, type, timeInForce, quantity, price);
System.out.println(response);

// 或者使用强类型返回值
BinanceOrder order = spotService.placeOrder(symbol, side, type, timeInForce, quantity, price);
System.out.println("Order ID: " + order.getOrderId());
```

### 3. 测试下单 (Test New Order)

测试新订单创建和签名，不会发送到撮合引擎。

**用法示例:**

```java
// 测试限价买入
String testResponse = spotService.testNewOrder(symbol, side, type, timeInForce, quantity, price);
System.out.println("Test Order Response: " + testResponse);
```

### 4. 查询订单 (Query Order)

查询特定订单的状态。

**用法示例:**

```java
Long orderId = 12345678L;
BinanceOrder order = spotService.getOrderObject("BTCUSDT", orderId, null);
System.out.println("Order Status: " + order.getStatus());
```

### 5. 撤销订单 (Cancel Order)

撤销未成交的订单。

**用法示例:**

```java
// 撤销指定订单
BinanceOrder canceledOrder = spotService.cancelOrderObject("BTCUSDT", 12345678L, null);
System.out.println("Canceled Order ID: " + canceledOrder.getOrderId());

// 撤销某交易对下所有挂单
List<BinanceOrder> canceledOrders = spotService.cancelAllOpenOrders("BTCUSDT");
System.out.println("Canceled " + canceledOrders.size() + " orders.");
```

### 6. 当前挂单 (Open Orders)

查询当前未成交的订单。

**用法示例:**

```java
List<BinanceOrder> openOrders = spotService.getOpenOrders("BTCUSDT");
for (BinanceOrder o : openOrders) {
    System.out.println("Open Order: " + o.getOrderId() + " Price: " + o.getPrice());
}
```

### 7. 所有订单 (All Orders)

查询所有订单（包括已成交、已撤销）。

**用法示例:**

```java
List<BinanceOrder> allOrders = spotService.getAllOrders("BTCUSDT", null, 10, null, null);
for (BinanceOrder o : allOrders) {
    System.out.println("Order: " + o.getOrderId() + " Status: " + o.getStatus());
}
```

### 8. 历史成交 (My Trades)

查询账户的历史成交记录。

**用法示例:**

```java
List<BinanceTradeHistory> trades = spotService.getMyTrades("BTCUSDT", null, 10, null, null, null);
for (BinanceTradeHistory trade : trades) {
    System.out.println("Trade ID: " + trade.getId() + ", Price: " + trade.getPrice() + ", Qty: " + trade.getQty());
}
```

### 9. OCO 订单 (OCO Orders)

OCO (One Cancels the Other) 订单管理，包括查询和撤销。

**用法示例:**

```java
// 查询所有 Open OCO
String openOcos = spotService.getOpenOCO();
System.out.println(openOcos);

// 查询特定 OCO
// String ocoDetails = spotService.getOCO(12345L, null);

// 撤销 OCO
// 需要 orderListId
// spotService.cancelOCO("BTCUSDT", 12345L, null, null);
```

### 10. 佣金费率 (Commission Rate)

查询当前账户的佣金费率。

**用法示例:**

```java
String commission = spotService.commissionRate("BTCUSDT");
System.out.println("Commission Rate: " + commission);
```

### 11. 阻止的撮合 (Prevented Matches)

查询由于自我交易预防 (STP) 而过期的订单被阻止的撮合。

**用法示例:**

```java
List<BinancePreventedMatch> preventedMatches = spotService.getPreventedMatches("BTCUSDT", null, null, 10);
for (BinancePreventedMatch match : preventedMatches) {
    System.out.println("Prevented Match ID: " + match.getPreventedMatchId());
}
```

## 钱包服务 (Wallet Services)

### 提现 (Travel Rule)

提交提现请求。此接口支持 Travel Rule 要求（针对特定地区实体）。

**用法示例:**

```java
// 初始化 BinanceWalletService
BinanceWalletService walletService = new BinanceWalletService(spotClient, apiKey, secretKey, baseUrl);

// 参数
String coin = "USDT";
String address = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"; // 示例 TRC20 地址
String amount = "100.5";
String network = "TRX"; // 可选
String name = "MyWallet"; // 可选
Integer walletType = 0; // 0: 现货钱包, 1: 资金钱包 (可选)

// 执行提现
String withdrawId = walletService.withdraw(coin, address, amount, network, name, walletType);
System.out.println("Withdrawal ID: " + withdrawId);
```

### 提现历史 (Withdraw History)

获取提现历史记录。

**用法示例:**

```java
// 参数
String coin = "USDT";
Integer status = 6; // 6: 完成 (可选)
Long startTime = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000); // 90天前 (可选)
Long endTime = System.currentTimeMillis(); // 现在 (可选)

// 执行查询
List<BinanceWithdrawHistory> history = walletService.withdrawHistory(coin, status, startTime, endTime, null, null);

for (BinanceWithdrawHistory entry : history) {
    System.out.println("TxID: " + entry.getTxId() + ", Amount: " + entry.getAmount());
}
```

### 其他钱包接口

- **系统状态 (System Status)**: `walletService.systemStatus()`
- **所有币种信息 (All Coins Info)**: `walletService.getAllCoins()`
- **充值历史 (Deposit History)**: `walletService.depositHistory(...)`
- **充值地址 (Deposit Address)**: `walletService.depositAddress(...)`

## 策略交易 (Alpha/Algo Services)

提供算法订单（如 TWAP, VP）的下单、撤单和查询功能。

### 下单 (New Order)

支持 VP (Volume Participation) 和 TWAP (Time Weighted Average Price) 策略。

**用法示例:**

```java
// 初始化 BinanceAlphaService
BinanceAlphaService alphaService = new BinanceAlphaService(spotClient, baseUrl, apiKey, secretKey);

// 参数
String symbol = "BTCUSDT";
String side = "BUY";
String quantity = "1.0";
String algoType = "TWAP"; // 或 "VP"

Map<String, Object> params = new HashMap<>();
params.put("limitPrice", "50000.0"); // 限价 (可选)
params.put("duration", 300);         // 持续时间秒 (TWAP 必填)
// params.put("urgency", "LOW");     // 紧迫度 (VP 必填: LOW, MEDIUM, HIGH)

// 下单
BinanceAlphaOrder order = alphaService.newOrder(symbol, side, quantity, algoType, params);
System.out.println("Algo Order ID: " + order.getAlgoId());
```

### 查询进行中的策略单 (Get Open Orders)

获取当前未结束的策略订单。

**用法示例:**

```java
List<BinanceAlphaOrder> openOrders = alphaService.getOpenOrders();
for (BinanceAlphaOrder order : openOrders) {
    System.out.println("Algo ID: " + order.getAlgoId() + ", Symbol: " + order.getSymbol());
}
```

### 查询历史策略单 (Get Historical Orders)

查询历史策略订单。

**用法示例:**

```java
// 参数
String symbol = "BTCUSDT";
String side = "BUY";
Long startTime = System.currentTimeMillis() - 86400000L; // 24小时前
Long endTime = System.currentTimeMillis();

List<BinanceAlphaOrder> history = alphaService.getHistoricalOrders(symbol, side, startTime, endTime, 1, 10);
```

### 撤销策略单 (Cancel Order)

撤销指定的策略订单。

**用法示例:**

```java
Long algoId = 123456L;
BinanceAlphaOrder result = alphaService.cancelOrder(algoId);
System.out.println("Cancelled Algo Order: " + result.getAlgoId());
```

## 杠杆交易市场数据 (Margin Market Data)

通过 `BinanceMarginService` 获取杠杆交易相关的市场数据。

**用法示例:**

```java
// 初始化服务
BinanceApiService apiService = new BinanceApiService(apiKey, secretKey);
BinanceMarginService marginService = apiService.getMarginService();

// 1. 获取所有全仓杠杆资产
List<BinanceMarginAsset> marginAssets = marginService.getMarginAssets();
for (BinanceMarginAsset asset : marginAssets) {
    System.out.println("Asset: " + asset.getAssetName());
}

// 2. 获取所有全仓杠杆交易对
List<BinanceMarginPair> marginPairs = marginService.getMarginPairs();
for (BinanceMarginPair pair : marginPairs) {
    System.out.println("Pair: " + pair.getSymbol());
}

// 3. 查询杠杆价格指数
BinanceMarginPriceIndex priceIndex = marginService.getMarginPriceIndex("BTCUSDT");
System.out.println("Price Index: " + priceIndex.getPrice() + ", Calculate Time: " + priceIndex.getCalcTime());

// 4. 获取所有逐仓杠杆交易对
List<BinanceIsolatedMarginSymbol> isolatedSymbols = marginService.getIsolatedMarginSymbols("BTCUSDT");
for (BinanceIsolatedMarginSymbol symbol : isolatedSymbols) {
    System.out.println("Isolated Symbol: " + symbol.getSymbol());
}

// 5. 查询逐仓杠杆交易对
BinanceIsolatedMarginSymbol isolatedSymbol = marginService.getIsolatedMarginSymbol("BTCUSDT");
System.out.println("Isolated Symbol Base: " + isolatedSymbol.getBase());

// 6. 获取全仓杠杆抵押率
List<BinanceCrossMarginCollateralRatio> collateralRatios = marginService.getCrossMarginCollateralRatio();
for (BinanceCrossMarginCollateralRatio ratio : collateralRatios) {
    System.out.println("Collaterals: " + ratio.getCollaterals());
}

// 7. 获取杠杆下架计划
List<BinanceMarginDelistSchedule> delistSchedule = marginService.getDelistSchedule();
for (BinanceMarginDelistSchedule schedule : delistSchedule) {
    System.out.println("Delist Time: " + schedule.getDelistTime());
}

// 8. 获取未来每小时利率
List<BinanceMarginNextHourlyInterestRate> interestRates = marginService.getFutureHourlyInterestRate("BTC,ETH", false);
for (BinanceMarginNextHourlyInterestRate rate : interestRates) {
    System.out.println("Asset: " + rate.getAsset() + ", Hourly Rate: " + rate.getNextHourlyInterestRate());
}
```
