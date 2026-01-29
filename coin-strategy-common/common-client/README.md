# Coin Strategy Common Client

此模块封装了 Binance API 的调用，提供了现货 (Spot)、合约 (Futures) 和杠杆 (Margin) 的统一访问接口。

基于 `binance-connector-java` 进行扩展，对 Margin 接口进行了全面的增强和重构，支持所有官方文档中列出的 Margin Market Data 和 Trade 接口。

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

## 注意事项

1. **参数类型**: 大部分金额和价格参数使用 `String` 类型以避免精度丢失。
2. **异常处理**: 接口调用失败会抛出 `RuntimeException`，建议在业务层捕获并处理。
3. **网络**: 底层使用 `OkHttp`，默认连接超时和读取超时已配置。

## 参考文档

- [Binance Margin API Documentation](https://developers.binance.com/docs/margin_trading/market-data)
- [Binance Connector Java Example](https://github.com/binance/binance-connector-java/blob/master/clients/margin-trading/example_rest.md)
