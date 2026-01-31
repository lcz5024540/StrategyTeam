# Binance API 使用说明

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
