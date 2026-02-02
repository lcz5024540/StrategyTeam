# Binance API 使用说明

## 集成测试配置 (Integration Test Configuration)

为了安全地进行真实接口调试，我们建议使用配置文件或环境变量来管理 API Key，避免将敏感信息提交到代码仓库。

### 方法一：使用配置文件 (推荐)

1.  在 `common-client` 模块下找到 `src/test/resources/binance-test.properties.template` 文件。
2.  将其复制为同目录下的 `binance-test.properties`。
3.  在 `binance-test.properties` 中填入你的 API Key：
    ```properties
    binance.api.key=your_actual_api_key
    binance.secret.key=your_actual_secret_key
    ```
    > **注意**: `binance-test.properties` 已被添加到 `.gitignore` 中，请确保不要强制提交该文件。

### 方法二：使用环境变量

你也可以在运行测试前设置以下环境变量：

*   `BINANCE_API_KEY`
*   `BINANCE_SECRET_KEY`

或者在 IDE 的 Run Configuration 中设置 JVM 参数：
```
-Dbinance.api.key=your_key -Dbinance.secret.key=your_secret
```

---

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

## 合约账户与交易 (Futures Account & Trade)

通过 `BinanceFuturesService` 提供 U 本位合约的账户信息查询与订单管理功能。

### 1. 账户信息与余额

获取账户信息、余额及持仓风险。

**用法示例:**

```java
// 初始化
BinanceFuturesService futuresService = apiService.getFuturesService();

// 获取账户信息 (V2)
BinanceFuturesAccount account = futuresService.account();
System.out.println("Total Wallet Balance: " + account.getTotalWalletBalance());

// 获取账户余额 (V2)
List<BinanceFuturesAccountBalance> balances = futuresService.getFutureAccountBalance();
for (BinanceFuturesAccountBalance b : balances) {
    System.out.println("Asset: " + b.getAsset() + ", Balance: " + b.getBalance());
}

// 获取持仓风险 (V2)
List<BinanceFuturesPositionRisk> positions = futuresService.getPositionRisk("BTCUSDT");
for (BinanceFuturesPositionRisk p : positions) {
    System.out.println("Symbol: " + p.getSymbol() + ", Entry Price: " + p.getEntryPrice());
}
```

### 2. 下单 (New Order)

支持限价单、市价单、止损单等多种订单类型。

**用法示例:**

```java
// 开多 (Open Long)
BinanceFuturesOrder order = futuresService.newOrder(
    "BTCUSDT", "BUY", "LONG", "LIMIT", "GTC", "0.001", "50000", false
);
System.out.println("Order ID: " + order.getOrderId());

// 快捷方法 (Hedge Mode)
futuresService.openLong("BTCUSDT", "LIMIT", "0.001", "50000", "GTC");
```

### 3. 批量下单与修改 (Batch Orders)

支持批量下单和批量修改订单。

**用法示例:**

```java
// 批量下单
String batchOrdersJson = "[{\"symbol\":\"BTCUSDT\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":\"0.001\",\"price\":\"50000\",\"timeInForce\":\"GTC\"}]";
List<BinanceFuturesOrder> orders = futuresService.batchOrders(batchOrdersJson);

// 批量修改
// 构造 Object List 或 JSON 字符串
// List<BinanceFuturesOrder> modifiedOrders = futuresService.modifyBatchOrders(batchOrdersList);
```

### 4. 撤单 (Cancel Order)

撤销订单、撤销所有挂单、倒计时自动撤单。

**用法示例:**

```java
// 撤销单个订单
futuresService.cancelOrder("BTCUSDT", 123456L, null);

// 撤销某交易对所有挂单
futuresService.cancelAllOpenOrders("BTCUSDT");

// 倒计时撤销所有订单 (例如 10000 毫秒后)
futuresService.autoCancelAllOpenOrders("BTCUSDT", 10000L);
```

### 5. 订单查询 (Query Order)

查询订单状态、当前挂单、历史订单。

**用法示例:**

```java
// 查询单个订单
BinanceFuturesOrder order = futuresService.getOrder("BTCUSDT", 123456L, null);

// 查询当前挂单
List<BinanceFuturesOrder> openOrders = futuresService.getOpenOrders("BTCUSDT");

// 查询历史订单
List<BinanceFuturesOrder> allOrders = futuresService.getAllOrders("BTCUSDT", null, null, null, 10);
```

### 6. 调整模式 (Position & Margin Mode)

调整持仓模式（单向/双向）和保证金模式（逐仓/全仓）。

**用法示例:**

```java
// 更改持仓模式 (true: 双向持仓 Mode)
futuresService.changePositionMode("true");
BinanceFuturesPositionMode mode = futuresService.getPositionMode();
System.out.println("Dual Side Position: " + mode.getDualSidePosition());

// 更改全仓/逐仓模式 (ISOLATED, CROSSED)
futuresService.changeMarginType("BTCUSDT", "ISOLATED");

// 调整逐仓保证金
futuresService.modifyIsolatedPositionMargin("BTCUSDT", new BigDecimal("10"), 1, "LONG");
```

### 7. 历史与流水 (History & Income)

查询成交历史、资金流水、下载交易记录。

**用法示例:**

```java
// 账户成交历史
List<BinanceFuturesAccountTrade> trades = futuresService.getAccountTradeList("BTCUSDT", null, null, null, 10);

// 收益流水 (如资金费、手续费)
List<BinanceFuturesIncome> income = futuresService.getIncomeHistory("BTCUSDT", "FUNDING_FEE", null, null, 10);

// 获取交易历史下载链接
BinanceFuturesDownloadId downloadId = futuresService.getDownloadIdForTradeHistory(startTime, endTime);
BinanceFuturesDownloadStatus status = futuresService.getDownloadIdStatus(downloadId.getDownloadId());
System.out.println("Download URL: " + status.getUrl());
```
