# OKX 交易 (OKX Trading)

通过 `OkxApiService` 提供统一访问接口，支持现货、合约、策略交易、赚币等全线业务。

## 1. 初始化 (Initialization)

```java
import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.client.okx.OkxApiService;
import com.metaverse.coin.strategy.common.client.okx.interceptor.AuthenticationInterceptor;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

String apiKey = "YOUR_API_KEY";
String secretKey = "YOUR_SECRET_KEY";
String passphrase = "YOUR_PASSPHRASE";
boolean simulated = false; // 是否模拟盘

// 构建 OkHttpClient (包含签名拦截器)
OkHttpClient client = new OkHttpClient.Builder()
        .addInterceptor(new AuthenticationInterceptor(apiKey, secretKey, passphrase, simulated))
        .build();

// 构建 Retrofit
Retrofit retrofit = new Retrofit.Builder()
        .baseUrl("https://www.okx.com")
        .client(client)
        .addConverterFactory(JacksonConverterFactory.create())
        .build();

// 创建 Service
OkxApi okxApi = retrofit.create(OkxApi.class);
OkxApiService okxService = new OkxApiService(okxApi, client, simulated);
```

## 2. 账户 (Account API)

管理账户资产、持仓、杠杆配置等。通过 `okxService.account` 访问。

### 2.1 查看余额 (Get Balance)
```java
// 获取所有币种余额
OkxRestResponse<Balance> allBalances = okxService.account.getBalance(null);

// 获取特定币种余额
OkxRestResponse<Balance> btcBalance = okxService.account.getBalance("BTC");
```

### 2.2 查看持仓信息 (Get Positions)
```java
// 查看所有持仓
OkxRestResponse<Map<String, Object>> positions = okxService.account.getPositions(null, null);

// 查看特定产品持仓 (如 BTC-USDT-SWAP)
OkxRestResponse<Map<String, Object>> swapPositions = okxService.account.getPositions("SWAP", "BTC-USDT-SWAP");
```

### 2.3 账户配置与风险 (Configuration & Risk)
```java
// 查看账户配置 (持仓模式、杠杆倍数等)
OkxRestResponse<OkxAccountConfig> config = okxService.account.getAccountConfig();

// 查看持仓风险
Map<String, Object> riskParams = new HashMap<>();
riskParams.put("instType", "SWAP");
OkxRestResponse<OkxPositionRisk> risk = okxService.account.getPositionRisk(riskParams);

// 设置杠杆倍数
Map<String, Object> setLeverageReq = new HashMap<>();
setLeverageReq.put("instId", "BTC-USDT-SWAP");
setLeverageReq.put("lever", "10");
setLeverageReq.put("mgnMode", "cross");
okxService.account.setLeverage(setLeverageReq);

// 设置持仓模式
Map<String, Object> setPosModeReq = new HashMap<>();
setPosModeReq.put("posMode", "long_short_mode"); // 双向持仓
okxService.account.setPositionMode(setPosModeReq);
```

### 2.4 账单与费率 (Bills & Fees)
```java
// 查看7日内账单
Map<String, Object> billParams = new HashMap<>();
billParams.put("instType", "SWAP");
OkxRestResponse<OkxBill> bills = okxService.account.getBills(billParams);

// 查看当前费率
Map<String, Object> feeParams = new HashMap<>();
feeParams.put("instType", "SWAP");
OkxRestResponse<OkxTradeFee> fees = okxService.account.getTradeFee(feeParams);
```

### 2.5 交易额度查询 (Limits)
```java
// 查看最大可买/卖数量
Map<String, Object> maxSizeParams = new HashMap<>();
maxSizeParams.put("instId", "BTC-USDT");
maxSizeParams.put("tdMode", "cash");
OkxRestResponse<OkxMaxSize> maxSize = okxService.account.getMaxSize(maxSizeParams);

// 查看最大可用数量
Map<String, Object> availParams = new HashMap<>();
availParams.put("instId", "BTC-USDT");
availParams.put("tdMode", "cash");
OkxRestResponse<OkxMaxAvailSize> availSize = okxService.account.getMaxAvailSize(availParams);
```

## 3. 交易 (Trade API)

支持下单、撤单、修改订单、查询订单及成交明细等。通过 `okxService.trade` 访问。

### 3.1 下单 (Place Order)
```java
// 单个下单
PlaceOrderReq req = PlaceOrderReq.builder()
        .instId("BTC-USDT")
        .tdMode("cash")
        .side("buy")
        .ordType("limit")
        .sz("0.01")
        .px("50000")
        .build();
OkxRestResponse<PlaceOrderRsp> response = okxService.trade.placeOrder(req);

// 批量下单 (最多20个)
List<PlaceOrderReq> batchReqs = new ArrayList<>();
batchReqs.add(req1);
batchReqs.add(req2);
OkxRestResponse<PlaceOrderRsp> batchResponse = okxService.trade.batchOrders(batchReqs);
```

### 3.2 撤单 (Cancel Order)
```java
// 单个撤单
CancelOrderReq cancelReq = CancelOrderReq.builder()
        .instId("BTC-USDT")
        .ordId("123456789")
        .build();
okxService.trade.cancelOrder(cancelReq);

// 批量撤单
List<CancelOrderReq> batchCancelReqs = Arrays.asList(cancelReq1, cancelReq2);
okxService.trade.cancelBatchOrders(batchCancelReqs);

// 撤销全部 (Mass Cancel)
MassCancelOrderReq massCancelReq = MassCancelOrderReq.builder()
        .instType("SWAP")
        .instId("BTC-USDT-SWAP")
        .build();
okxService.trade.massCancel(massCancelReq);
```

### 3.3 修改订单 (Amend Order)
```java
// 修改订单
AmendOrderReq amendReq = AmendOrderReq.builder()
        .instId("BTC-USDT")
        .ordId("123456789")
        .newPx("51000")
        .build();
okxService.trade.amendOrder(amendReq);
```

### 3.4 市价全平 (Close Position)
```java
// 市价全平 BTC-USDT-SWAP 多单
ClosePositionReq closeReq = ClosePositionReq.builder()
        .instId("BTC-USDT-SWAP")
        .mgnMode("cross")
        .posSide("long")
        .build();
okxService.trade.closePosition(closeReq);
```

### 3.5 订单与成交查询 (Query Orders & Fills)
```java
// 查询订单信息
OkxRestResponse<OkxOrder> order = okxService.trade.getOrder("BTC-USDT", "123456789", null);

// 查询未成交订单
OkxRestResponse<OkxOrder> pending = okxService.trade.getOrdersPending(null, "SWAP", "BTC-USDT-SWAP", null, null);

// 查询历史订单 (近7天)
Map<String, Object> historyParams = new HashMap<>();
historyParams.put("instId", "BTC-USDT");
historyParams.put("state", "filled");
OkxRestResponse<OkxOrder> history = okxService.trade.getOrdersHistory(historyParams);

// 查询成交明细 (近3天)
Map<String, Object> fillsParams = new HashMap<>();
fillsParams.put("instId", "BTC-USDT");
OkxRestResponse<OkxTransaction> fills = okxService.trade.getFills(fillsParams);
```

## 4. 策略交易 (Algo Trading API)

委托策略下单，如止盈止损、计划委托等。通过 `okxService.algoTrading` 访问。

### 4.1 策略委托下单 (Place Algo Order)
```java
Map<String, Object> algoReq = new HashMap<>();
algoReq.put("instId", "BTC-USDT");
algoReq.put("tdMode", "cash");
algoReq.put("side", "buy");
algoReq.put("ordType", "conditional"); // 计划委托
algoReq.put("sz", "100");
algoReq.put("tpTriggerPx", "55000"); // 止盈触发价
algoReq.put("tpOrdPx", "54000");     // 止盈委托价
OkxRestResponse<PlaceAlgoOrderRsp> response = okxService.algoTrading.placeAlgoOrder(algoReq);
```

### 4.2 策略委托撤单 (Cancel Algo Order)
```java
List<Map<String, Object>> cancelList = new ArrayList<>();
Map<String, Object> item = new HashMap<>();
item.put("algoId", "123456");
item.put("instId", "BTC-USDT");
cancelList.add(item);
okxService.algoTrading.cancelAlgoOrder(cancelList);
```

### 4.3 策略委托查询 (Query Algo Orders)
```java
// 查询未完成策略委托
Map<String, Object> pendingParams = new HashMap<>();
pendingParams.put("algoOrdType", "conditional");
OkxRestResponse<OkxAlgoOrder> pending = okxService.algoTrading.getAlgoOrdersPending(pendingParams);

// 查询历史策略委托
Map<String, Object> historyParams = new HashMap<>();
historyParams.put("algoOrdType", "conditional");
OkxRestResponse<OkxAlgoOrder> history = okxService.algoTrading.getAlgoOrdersHistory(historyParams);
```

## 5. 网格交易 (Grid Trading API)

现货/合约网格策略。通过 `okxService.gridTrading` 访问。

### 5.1 网格策略下单 (Place Grid Algo Order)
```java
PlaceGridAlgoOrderReq gridReq = PlaceGridAlgoOrderReq.builder()
        .instId("BTC-USDT")
        .algoOrdType("grid")
        .maxPx("60000")
        .minPx("40000")
        .gridNum("20")
        .runType("1") // 1: 算术, 2: 几何
        .build();
OkxRestResponse<PlaceGridAlgoOrderRsp> gridRsp = okxService.gridTrading.placeGridAlgoOrder(gridReq);
```

### 5.2 停止网格策略 (Stop Grid Algo Order)
```java
StopGridAlgoOrderReq stopReq = StopGridAlgoOrderReq.builder()
        .algoId("123456")
        .instId("BTC-USDT")
        .algoOrdType("grid")
        .stopType("1") // 1: 市价全平, 2: 停止不平仓
        .build();
okxService.gridTrading.stopGridAlgoOrder(stopReq);
```

### 5.3 网格策略查询 (Query Grid Orders)
```java
// 查询网格策略详情
OkxRestResponse<GridAlgoOrder> details = okxService.gridTrading.getGridAlgoOrderDetails("123456", null);

// 查询网格持仓
Map<String, Object> posParams = new HashMap<>();
posParams.put("algoOrdType", "contract_grid");
OkxRestResponse<GridAlgoOrderPosition> positions = okxService.gridTrading.getGridAlgoPositions(posParams);
```

## 6. 信号策略 (Signal Bot API)

通过 `okxService.signalBot` 访问。

### 6.1 信号策略下单 (Place Signal Algo Order)
```java
PlaceSignalAlgoOrderReq signalReq = PlaceSignalAlgoOrderReq.builder()
        .signalChanId("123")
        .leverage("10")
        .investAmt("1000")
        .build();
OkxRestResponse<PlaceSignalAlgoOrderRsp> signalRsp = okxService.signalBot.placeSignalAlgoOrder(signalReq);
```

## 7. 定投策略 (Recurring Buy API)

通过 `okxService.recurringBuy` 访问。

### 7.1 定投策略下单 (Place Recurring Buy Algo Order)
```java
PlaceRecurringBuyAlgoOrderReq recurringReq = PlaceRecurringBuyAlgoOrderReq.builder()
        .stgyName("BTC Investment")
        .recurringTime("1") // 周期
        .build();
OkxRestResponse<PlaceRecurringBuyAlgoOrderRsp> recurringRsp = okxService.recurringBuy.placeRecurringBuyAlgoOrder(recurringReq);
```

## 8. 市场行情 (Market Data API)

获取深度、K线、最新成交、指数行情等。通过 `okxService.marketData` 访问。

### 8.1 深度数据 (Order Book)
```java
// 获取深度 (前20档)
OkxRestResponse<OkxOrderBook> books = okxService.marketData.getOrderBook("BTC-USDT", 20);

// 获取全量深度
OkxRestResponse<OkxOrderBook> fullBooks = okxService.marketData.getOrderBookFull("BTC-USDT", 20);
```

### 8.2 K线数据 (Candlesticks)
```java
// 获取 K线数据 (bar: 1m, 1H, 1D)
OkxRestResponse<OkxCandle> candles = okxService.marketData.getCandles("BTC-USDT", "1H", null, null, 100);

// 获取历史 K线数据
OkxRestResponse<OkxCandle> historyCandles = okxService.marketData.getHistoryCandles("BTC-USDT", "1D", null, null, 100);
```

### 8.3 行情与成交 (Tickers & Trades)
```java
// 获取所有产品行情
OkxRestResponse<OkxTicker> tickers = okxService.marketData.getTickers("SWAP", null);

// 获取单个产品行情
OkxRestResponse<OkxTicker> ticker = okxService.marketData.getTicker("BTC-USDT");

// 获取最新成交
OkxRestResponse<OkxTrade> trades = okxService.marketData.getTrades("BTC-USDT", 50);
```

## 9. 公共数据 (Public Data API)

获取交易产品信息、费率、系统时间等。通过 `okxService.publicData` 访问。

### 9.1 基础信息 (General Info)
```java
// 获取交易产品信息
OkxRestResponse<Map<String, Object>> instruments = okxService.publicData.getInstruments("SWAP", null, null);

// 获取系统时间
OkxRestResponse<Map<String, Object>> time = okxService.publicData.getSystemTime();
```

### 9.2 资金费率与持仓量 (Funding Rate & Open Interest)
```java
// 获取永续合约当前资金费率
OkxRestResponse<Map<String, Object>> fundingRate = okxService.publicData.getFundingRate("BTC-USDT-SWAP");

// 获取总持仓量
OkxRestResponse<Map<String, Object>> openInterest = okxService.publicData.getOpenInterest("SWAP", null, "BTC-USDT-SWAP");
```

## 10. 资金账户 (Funding Account API)

资金划转、提币等。通过 `okxService.funding` 访问。

### 10.1 资金划转 (Transfer)
```java
Map<String, Object> transferReq = new HashMap<>();
transferReq.put("ccy", "USDT");
transferReq.put("amt", "100");
transferReq.put("from", "18"); // 18: 资金账户
transferReq.put("to", "6");    // 6: 交易账户
OkxRestResponse<Map<String, Object>> transfer = okxService.funding.assetTransfer(transferReq);
```

### 10.2 充提币 (Deposit & Withdrawal)
```java
// 获取充值地址
OkxRestResponse<Map<String, Object>> depositAddr = okxService.funding.getDepositAddress("USDT");

// 提币
Map<String, Object> withdrawalReq = new HashMap<>();
withdrawalReq.put("ccy", "USDT");
withdrawalReq.put("amt", "100");
withdrawalReq.put("dest", "4"); // 4: 链上提币
withdrawalReq.put("toAddr", "TRX_ADDRESS");
withdrawalReq.put("fee", "1");
withdrawalReq.put("chain", "USDT-TRC20");
OkxRestResponse<Map<String, Object>> withdrawal = okxService.funding.withdrawal(withdrawalReq);
```

## 11. 交易大数据 (Rubik API)

获取多空比、持仓量、大单成交等。通过 `okxService.rubik` 访问。

### 11.1 交易数据 (Trading Data)
```java
// 获取支持的币种
OkxRestResponse<String> supportCoin = okxService.rubik.getSupportCoin();

// 获取主动买入/卖出情况
OkxRestResponse<OkxTakerVolume> takerVolume = okxService.rubik.getTakerVolume("BTC", "SWAP", null, null, "5m");

// 获取杠杆借币多空比
OkxRestResponse<OkxMarginLendingRatio> marginRatio = okxService.rubik.getMarginLendingRatio("BTC", null, null, "1D");

// 获取精英交易员多空持仓人数比
OkxRestResponse<OkxLongShortRatio> longShortRatio = okxService.rubik.getLongShortAccountRatio("BTC-USDT-SWAP", null, null, "5m");

// 获取合约持仓量及交易量
OkxRestResponse<OkxOpenInterestVolume> oiVolume = okxService.rubik.getOpenInterestVolume("BTC", "SWAP", null, null, "1D");
```

### 11.2 期权数据 (Option Data)
```java
// 获取期权持仓量及交易量
OkxRestResponse<OkxOpenInterestVolume> optOiVolume = okxService.rubik.getOptionOpenInterestVolume("BTC", "1D");

// 获取看涨/看跌期权合约持仓总量比/交易总量比
OkxRestResponse<OkxPutCallRatio> pcr = okxService.rubik.getOptionOpenInterestVolumeRatio("BTC", "1D");

// 获取期权大单成交
OkxRestResponse<OkxTakerVolume> optTakerBlock = okxService.rubik.getOptionTakerBlockVolume("BTC", "1D", null, null);
```

## 12. 金融产品 (Finance API)

赚币服务。通过 `okxService.earn` (Staking) 和 `okxService.savings` (Simple Earn) 访问。

### 12.1 简单赚币 (Savings / Simple Earn)
```java
// 获取余额
OkxRestResponse<OkxSavingsBalance> savingsBalance = okxService.savings.getSavingsBalance("USDT");

// 申购/赎回
OkxSavingsPurchaseRedemptReq req = OkxSavingsPurchaseRedemptReq.builder()
        .ccy("USDT")
        .amt("100")
        .side("purchase") // purchase: 申购, redempt: 赎回
        .rate("0.01")
        .build();
OkxRestResponse<OkxSavingsPurchaseRedemptRsp> response = okxService.savings.savingsPurchaseRedempt(req);
```

### 12.2 链上赚币 (Staking DeFi / On-chain Earn)
```java
// 获取项目列表
OkxRestResponse<OkxStakingOffer> offers = okxService.earn.getStakingOffers(null);

// 申购
OkxStakingPurchaseReq purchaseReq = OkxStakingPurchaseReq.builder()
        .productId("12345")
        .investAmount("100")
        .ccy("USDT")
        .build();
OkxRestResponse<OkxStakingPurchaseRsp> purchaseRsp = okxService.earn.purchaseStaking(purchaseReq);
```

## 13. 节点计划 (Affiliate API)

通过 `okxService.affiliate` 访问。

```java
// 获取被邀请人明细
Map<String, Object> params = new HashMap<>();
params.put("uid", "123456");
OkxRestResponse<OkxAffiliateInviteeDetail> invitee = okxService.affiliate.getAffiliateInviteeDetail(params);
```

---
参考文档: [OKX V5 API Documentation](https://www.okx.com/docs-v5/zh/)
