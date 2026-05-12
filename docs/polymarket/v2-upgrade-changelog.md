# Polymarket CLOB V2 升级改造记录

> 日期: 2026-05-11  
> 背景: Polymarket 于 2026-04-28 上线 V2 协议，V1 签名已不可用

---

## 一、核心签名层改造 (PolymarketJavaClobClient.java)

### EIP-712 Order 结构变更

| 字段 | V1 | V2 |
|------|----|----|
| ORDER_VERSION | "1" | **"2"** |
| taker | 签入 | **移除** |
| expiration | 签入 | **移除** |
| nonce | 签入 | **移除** |
| feeRateBps | 签入 | **移除** (费率由协议在撮合时决定) |
| timestamp | 无 | **新增** (毫秒级) |
| metadata | 无 | **新增** (bytes32, 当前为零值) |
| builder | 无 | **新增** (bytes32, 当前为零值) |

### 合约地址更新

| 网络 | 类型 | V1 地址 | V2 地址 |
|------|------|---------|---------|
| Polygon (137) | Standard | 0x4bFb41... | **0xE111180000d2663C0091e4f400237545B87B996B** |
| Polygon (137) | NegRisk | 0xC5d56... | **0xe2222d279d744050d28e00520010520000310F59** |

### placeOrder 请求体变更

- `owner` 字段: 从 `context.getAddress()` (钱包地址) → **`creds.apiKey()`** (API Key)
- `postOnly` 字段: **移除** (V2 不再支持该顶层字段)

### 删除的方法

- `fetchFeeRate()` — V2 不再需要客户端获取费率
- `orderStructHash()` → 替换为 `orderStructHashV2()`

---

## 二、OrderRequestFactory 适配

`PolymarketOrderRequestFactory` 无需修改 — 该类处理价格/仓位/tick 对齐等协议无关计算。V1 特有的 `feeRateBps` 仅存在于签名层，已在 Task #38 中移除。

---

## 三、Python SDK 桥接更新 (PolymarketPythonSdkBridge.java)

- `isAvailable()` 中的包检测: `py_clob_client` → **`py_clob_client_v2`**
- 当前部署使用 `sdk-mode: JAVA`，Python 桥接处于休眠状态

---

## 四、BTC/Grid 策略 LIVE 模式接入

### BtcMinuteStrategyJob

**之前**: LIVE 分支与 PAPER 完全相同 — 仅插入 DB 记录，不发起链上操作。

**之后**: LIVE 分支完整接入 CLOB V2 下单流程:
1. `walletContextLoader.requireLiveContext()` 加载钱包凭证
2. `orderRequestFactory.prepareLocalDraft()` 构建订单参数
3. `clobClientRouter.placeOrder()` 通过 V2 签名提交 CLOB 限价单
4. 成功后将 `externalOrderId` 回写仓位记录
5. 失败时将仓位状态标记为 `FAILED`

### GridOrderManager

**之前**: 所有 LIVE 分支仅打日志或直接标记 LIVE，无链上操作。

**之后**:
- `initializeOrders()`: LIVE 模式构建 `PreparedPolymarketOrder` 并通过 `clobClientRouter.placeOrder()` 下真实 maker 挂单
- `placeCounterOrder()`: LIVE 模式在对侧下真实补单
- `cancelAllOrders()`: LIVE 模式通过 `clobClientRouter.cancelOrder()` 撤销链上挂单
- 所有 LIVE 操作均有异常捕获，失败时标记 `FAILED` 而非崩溃

---

## 五、变更文件清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `PolymarketJavaClobClient.java` | 核心改造 | V2 签名结构、合约地址、placeOrder 请求体 |
| `PolymarketPythonSdkBridge.java` | 小改 | V2 包名检测 |
| `BtcMinuteStrategyJob.java` | 功能接入 | LIVE 模式接入 ClobClientRouter |
| `GridOrderManager.java` | 功能接入 | 挂单/补单/撤单 LIVE 模式接入 ClobClientRouter |
| `PolymarketOrderRequestFactory.java` | 无变更 | 已兼容 V2 |

---

## 六、注意事项

1. **当前 BTC/Grid 策略均已禁用** (`enabled: false`)，优先稳定跟单策略
2. LIVE 订单尚未经过真实环境测试，当前仍在模拟仓阶段
3. V2 使用 pUSD 替代 USDC.e 作为抵押品，CollateralOnramp 合约: `0x93070a847efEf7F70739046A929D47a521F5B8ee`
4. L1 Auth (ClobAuth EIP-712) 和 L2 Auth (HMAC-SHA256) 在 V2 中保持不变
