# HTX USDT 永续合约私有接口 v1→V5 迁移设计

> 状态：已实现并完成离线验证（2026-08-15），实施记录见 `../plans/2026-08-15-htx-v5-dual-contract-cycle.md`
> 模块：`coin-strategy-common/common-client`、`coin-strategy-htx`
> 关联：`2026-08-07-htx-usdt-swap-contract-mode-design.md`（原始合约模式设计）
> 状态流图：`diagrams/2026-08-15-htx-v5-dual-cycle.drawio`

## 1. 背景与根因（客服确认）

子账号 73741966 的所有合约**私有接口**恒返 `6023 "The new feature is temporarily unavailable."`，导致程序化合约交易全挂。

**根因**：HTX 客服确认，旧 v1 合约私有接口 `/linear-swap-api/v1/...` 在 **2026-07-13 23:59:59 后停服**；账户一旦升级到 `asset_mode=1`（联合保证金）或 `asset_mode=2`（单币种保证金），私有接口必须走**新 V5 API**，否则恒返 6023。本子账号已升级，故所有 v1 私有调用全挂。

**旁证**：
- 公共接口不受影响（`swap_contract_info` / `market/depth` 实测正常）。
- 同一把 Key、同一签名逻辑下，`swap_api_trading_status_by_user`（`is_disable=0`）、`swap_balance_valuation`（估值 93.64 USDT）、`swap_unified_account_type`（`account_type=1`）均正常，唯独账户/持仓/杠杆/下单类被拦。
- 新 API 错误码表只有 6001-6005，**无 6023**——6023 是旧 API 专属的「你已迁移、请用新 API」信号。

**结论**：这是 API 版本迁移问题，非签名/权限/资产问题。修复 = 代码把 8 个私有接口从 v1 迁到 V5。

## 2. 范围

- **迁移全部 8 个私有接口**（一次迁完，接口层无残留 v1 私有调用）：`accountInfo`、`positionInfo`、`openOrders`、`availableLeverage`、`switchLeverage`、`placeOrder`、`cancelOrder`、`queryOrder`。
- **公共接口不动**：`contractInfo`（`swap_contract_info`）、`depth`（`market/depth`）保持 v1 公共路径不变。
- **本 spec 只做代码 + 测试 + dry-run 回归，不含真实账号调用**。实盘只读实探、小额往返验证均**另行单独授权**，不在本 spec 范围。
- 在 8 个私有接口之外增加 `/v5/trade/batch_orders`，用于在一个请求中提交同价开多/开空或同价平多/平空，减少两腿发送时差。
- 同账户双向订单强制 `self_match_prevent=cancel_both`；“双向”表示分别持有 long/short 仓位，不允许两条自有订单彼此成交。

## 3. 新 API 事实基线

- **API 家族**：「U 本位联合保证金」（文档顶级分类 id=5520）。
- **Host 不变** `https://api.hbdm.com`；**签名不变** HmacSHA256 / SignatureVersion=2 / UTC Timestamp。
- **REST 路径在 `/v5/...`**。
- **限频**：每 UID 3s ≤72 查询 + ≤72 交易。
- **读文档方法**（供实现阶段抓 response 字段）：文档 SPA 后端 `GET /oplt/api/open_api/category`（Playwright 页内 `fetch`，header `accept-language: zh-cn`）返回全树，叶子含 `interface_path`/`interface_name`/`interface_id`(UUID)；`interface/detail?interface_id=<UUID>` 用 **UUID** 才有效（数字 node id 恒返 13001）；detail 的 `req_param[]` 含 `param_name`/`data_type`/`required`/`value_range`/`desc`，response 段同理抓取。

## 4. 架构与分层

三层，改动集中在 Service 层、新增 V5 wire 模型和响应信封兼容；签名算法不变。

```
调用方(MarketMakerService / 合约调度器)   ← 零改动
        │  依赖稳定的 domain 模型
        ▼
HtxUsdtSwapService  ← 改 8 方法：路径 + HTTP 方法 + 请求参数 + 响应解析 + V5wire→domain 转换
        │  复用 signedGet / signedPost
        ▼
HtxRestClient  ← 增加 V5 code/message 信封识别，signedGet / signedPost 复用
HtxSignUtils   ← 零改动（signedQuery 已支持 GET/POST）
```

**关键决策**：
- **对外 domain 类型和既有字段保持兼容**；为 V5 增量增加 state、positionSide、fee、profit 等字段，旧调用方无需改签名。
- **新建 V5 wire 模型**只承接 V5 线上报文（字段名/层级与 domain 不同）；Service 内做 `V5wire → domain` 转换。这是「新建 V5 专用模型类」的落地方式，避免用 v1 字段语义硬套 V5 造成污染/兼容风险。
- **`signedGet` 已存在**（早前「需新增」判断错误，读源码已纠正）；GET 业务参数进签名 query string，POST 业务参数走 body，两者 `HtxSignUtils` 均已支持。

## 5. 接口映射（8 个私有接口）

| 方法 | v1（全 POST，已停服） | V5 路径 | V5 HTTP | 关键参数 |
|---|---|---|---|---|
| accountInfo | swap_account_info | `/v5/account/balance` | GET | 无 |
| positionInfo | swap_position_info | `/v5/trade/position/opens` | GET | contract_code(可选) |
| openOrders | swap_openorders | `/v5/trade/order/opens` | GET | contract_code, margin_mode, order_id, client_order_id, from, limit(≤100), direct |
| availableLeverage | swap_available_level_rate | `/v5/position/lever` | GET | contract_code, margin_mode, position_side |
| switchLeverage | swap_switch_lever_rate | `/v5/position/lever` | POST | contract_code(必), margin_mode(必), position_side, lever_rate(必, **String**) |
| placeOrder | swap_order | `/v5/trade/order` | POST | 见 §6 |
| cancelOrder | swap_cancel | `/v5/trade/cancel_order` | POST | contract_code(必), order_id / client_order_id |
| queryOrder | swap_order_info | `/v5/trade/order` | GET | contract_code(必), margin_mode, order_id / client_order_id |

> `availableLeverage`(GET) 与 `switchLeverage`(POST) 共用路径 `/v5/position/lever`，靠 HTTP 方法区分——迁移时须确保查询走 `signedGet`、切换走 `signedPost`。
> `queryOrder`(GET) 与 `placeOrder`(POST) 共用路径 `/v5/trade/order`，同理靠 HTTP 方法区分。

## 6. 下单字段大改（最高风险）

v1 与 V5 的下单语义模型不同，须逐字段核对：

| 维度 | v1 | V5 |
|---|---|---|
| 买卖方向 | `direction` = buy/sell | `side` = buy/sell |
| 开平方向 | `offset` = open/close | `position_side` = long/short/both |
| 订单类型 | `order_price_type` | `type` = limit/market/post_only |
| 保证金模式 | 靠选接口区分逐仓/全仓 | **新增必填 `margin_mode`** = isolated/cross |
| 杠杆 | `lever_rate`(数值) | `lever_rate`(**String**) |

**语义锚点（逐仓、双向持仓）**：
- **开多** = `side:buy` + `position_side:long` + `margin_mode:isolated` + `type:limit`
- **平多** = `side:sell` + `position_side:long` + `margin_mode:isolated` + `type:limit`
- **开空** = `side:sell` + `position_side:short` + `margin_mode:isolated` + `type:limit`
- **平空** = `side:buy` + `position_side:short` + `margin_mode:isolated` + `type:limit`
- 平多和平空均显式 `reduce_only=true`；全部四腿显式 `self_match_prevent=cancel_both`。

价格仍须整除 `price_tick`；`volume` 单位为「张」；不使用市价单兜底（沿用原 spec 安全不变量）。

## 7. V5 wire 模型

按 V5 官方 detail 的请求/响应示例核对后实现：

- `HtxV5Balance`（← `/v5/account/balance`）
- `HtxV5Position`（← `/v5/trade/position/opens`）
- `HtxV5Order`（← `/v5/trade/order/opens` 与 GET `/v5/trade/order`）
- `HtxV5Leverage`（← GET/POST `/v5/position/lever`）
- `HtxV5OrderResult`（← POST `/v5/trade/order`）

Service 层负责 `V5wire → domain` 转换；domain 只做向后兼容的字段增量。

## 8. 错误处理

- V5 使用 `code/message/data` 信封；`HtxRestClient.execute()` 将非 200 code 统一映射到 `errCode/errMsg`，并保留 HTTP 错误 `http-<code>` 与 I/O `io`。
- 迁移后 6023 消失即验证根因解除。
- 保留原 spec 全部 fail-closed 语义：写前落库失败不发单、发单后 DB 更新失败保留阻塞性 `INTENT`、查单基础设施连续失败以 `OPEN_UNKNOWN`/`EXPOSURE` 留人工核对、只限价不市价兜底。

## 9. 测试策略（只做测试 + dry-run）

- **每接口一组 MockWebServer 单测**，断言：① 请求路径 + HTTP 方法正确；② GET 的签名 query string 含全部业务参数且参与签名；③ POST 的 body / 签名参数正确；④ V5 mock 响应 JSON → 新 wire 模型 → domain 映射字段正确。
- **下单单测**专项覆盖开多 / 平多的 `side + position_side + margin_mode + type` 组合，及 `lever_rate` String 化。
- **共用路径歧义**单测：`/v5/position/lever` 与 `/v5/trade/order` 的 GET/POST 分流正确。
- **dry-run 回归**：`HtxContractAutoTestScheduler`（已硬门禁 dry-run/live-enabled=false）跑通，确认不回归；本模式不调私有接口，仅证明公共链路 + 门禁未坏。
- **不含**真实账号只读实探、真实小额往返——另行单独授权。

## 10. 安全不变量（沿用 + 新增）

沿用原 spec 六条安全不变量（白名单/停牌/tick 校验、深度+名义额+保证金门禁、实盘前确认无既有仓位与本地未完成周期、杠杆双重限制、写前落库、只限价）。

新增迁移期不变量：
1. 迁移不改三重实盘开关语义（`ENABLED`+`DRY_RUN=false`+`LIVE_ENABLED`），本 spec 交付物默认全关。
2. 公共接口路径不动，避免误伤已正常的行情/元数据链路。
3. domain 模型对外契约不变，上游调用方不改。

## 11. 官方依据

- 新 API 家族「U 本位联合保证金」文档分类 id=5520，站点 `htx.com.ua/zh-cn/opend/newApiPages/?id=5520`。
- 文档 SPA 后端 `GET /oplt/api/open_api/category` + `interface/detail?interface_id=<UUID>`。
- 客服工单原文：`docs/htx-6023-support-ticket.md`。
