# HTX 合约 API 报错 6023 客服工单

> 生成时间：2026-08-13 | 子账号现货 ID：73741966 | 模块：coin-strategy-htx（USDT 本位永续合约）

## 1. UUID（用户/子账号 UID）

- 子账号现货账户 ID：`73741966`
- ⚠️ 提交前请在 HTX 网页端「账户管理 → 子账号」核对该子账号的**主 UID**（现货账户 ID 与 UID 可能不同）。客服通常按 UID 定位账户。

## 2. AccessKey

```
47b0f1de-663f5eba-fr2wer5t6y-c4371
```

## 3. 完整的请求 URL

```
https://api.hbdm.com/linear-swap-api/v1/swap_account_info?AccessKeyId=47b0f1de-663f5eba-fr2wer5t6y-c4371&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=2026-08-13T10%3A16%3A39&Signature=qed3kJxZtPiLlnGmdNC8W%2BvExNtqim0L0tYCATvZKtU%3D
```

## 4. 请求参数

**Query 参数（用于签名）：**

| 参数 | 值 |
|---|---|
| AccessKeyId | `47b0f1de-663f5eba-fr2wer5t6y-c4371` |
| SignatureMethod | `HmacSHA256` |
| SignatureVersion | `2` |
| Timestamp | `2026-08-13T10:16:39`（UTC，URL 编码为 `2026-08-13T10%3A16%3A39`） |
| Signature | `qed3kJxZtPiLlnGmdNC8W+vExNtqim0L0tYCATvZKtU=`（URL 编码为 `qed3kJxZtPiLlnGmdNC8W%2BvExNtqim0L0tYCATvZKtU%3D`） |

**请求方法：** `POST`

**请求 Body（JSON）：**

```json
{}
```

## 5. 请求时间

- UTC：`2026-08-13T10:16:39`
- 服务器返回时间戳 ts：`1786616200039`（≈ 2026-08-13 10:16:40 UTC）

## 6. 接口返回原始数据

```json
{"status":"error","err_code":6023,"err_msg":"The new feature is temporarily unavailable.","ts":1786616200039}
```

## 7. 问题说明

该子账号（现货 ID `73741966`）**已开通 USDT 本位永续合约权限**，合约钱包有余额（约 **93.64 USDT**），且**已有历史合约成交记录**（可在 App 正常开仓/查看合约信息）。

但通过 API 调用**所有合约账户/持仓/杠杆类私有接口**时，恒定返回未在官方文档中记载的错误码 **6023「The new feature is temporarily unavailable.」**，导致无法通过程序化交易接口读取账户与下单。

**关键佐证——同一把 API Key（同一签名逻辑）下接口表现不一致：**

✅ 正常返回的接口：

- `GET /linear-swap-api/v1/swap_api_trading_status_by_user`（`is_disable=0`，即未被禁用）
- `GET /linear-swap-api/v1/swap_balance_valuation`（正常返回估值 93.64 USDT）
- `GET /linear-swap-api/v3/swap_unified_account_type`（返回 `account_type=1`）

❌ 恒返回 6023 的接口（逐仓 v1 / 全仓 v1 / 统一账户 v3 三套全部命中）：

- `POST /linear-swap-api/v1/swap_account_info`
- `POST /linear-swap-api/v1/swap_position_info`
- `POST /linear-swap-api/v1/swap_available_level_rate`（XAUT-USDT）
- `POST /linear-swap-api/v1/swap_cross_account_info`
- `POST /linear-swap-api/v1/swap_cross_position_info`
- `POST /linear-swap-api/v3/unified_account_info`

**请协助排查：** 6023 具体含义是什么？为何同一 Key 的现货接口、合约估值/状态/账户类型接口均正常，唯独合约账户/持仓/杠杆/下单接口被拦截？该账号是否处于「统一账户灰度/迁移」等中间状态需要在网页端完成某项升级或开通？如何解除该限制以恢复合约 API 访问？

## 8. 签名前字符串（Pre-sign string，4 行以 `\n` 连接）

```
POST
api.hbdm.com
/linear-swap-api/v1/swap_account_info
AccessKeyId=47b0f1de-663f5eba-fr2wer5t6y-c4371&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=2026-08-13T10%3A16%3A39
```

---

## 补充说明（提交时可一并附上，帮助客服快速定位）

- 6023 未出现在任何官方 API 文档中，err_msg 为 "The new feature is temporarily unavailable."
- 已排除签名问题（同 Key 同签名逻辑下多个接口正常）。
- 已排除权限被禁（trading_status `is_disable=0`）。
- 已排除账户无资产/无记录（有余额、有历史成交）。
- 强烈怀疑是**账户侧状态**问题（如统一账户灰度迁移未完成），而非 API 调用错误。
