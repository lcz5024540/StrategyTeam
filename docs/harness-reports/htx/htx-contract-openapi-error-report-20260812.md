# HTX U本位合约 OpenAPI 6023 错误证据

> 采样时间：2026-08-12 00:32:50 +08:00  
> 服务器：49.51.250.174  
> 服务：`coin-strategy-htx.service`  
> 账户：服务器现有子账号 API Key（已脱敏，UID 后四位 `2922`）  
> 合约：`XAUT-USDT`  
> 操作边界：只读查询；未下单、撤单、平仓或切换杠杆

## 1. 结论摘要

- 当前 Java 服务 active/running，PID `2985970`，`NRestarts=0`，自 2026-08-10 16:50:44 +08:00 运行。
- 从本次服务启动到日志导出，共有 `1,877` 条合约失败记录；全部为同一错误：开放委托查询返回 `6023 / The new feature is temporarily unavailable.`，随后服务以 `REJECT_PREFLIGHT` 拒绝开单。
- Java 日志中没有 `ERROR` 级别记录，也没有 Exception 堆栈；失败来自 HTX HTTP 200 响应内的业务错误码。
- 逐仓账户、持仓、开放委托、可用杠杆，以及全仓账户、持仓、开放委托、可用杠杆和统一账户资产均返回 6023。
- 对照接口正常：API 指标未禁用 (`is_disable=0`)、账户类型可读 (`account_type=1`)、U本位总资产估值可读 (`93.6519895034 USDT`)。
- App 已能成功下合约单，但 OpenAPI 无法读取持仓；这证明 App 内部交易通道和公开 OpenAPI 私有通道状态不一致。
- 服务没有调用真实 `swap_order`：开放委托预检先失败，代码按 fail-closed 规则停止执行。

## 2. 原始证据文件

- 完整服务错误日志（1,877 行）：[`htx-contract-service-errors-20260812.log`](htx-contract-service-errors-20260812.log)
- 完整 API 请求/响应证据（14 项）：[`htx-contract-api-evidence-20260812.json`](htx-contract-api-evidence-20260812.json)
- 三域名只读接口矩阵（45 项）：[`htx-contract-host-matrix-20260812.json`](htx-contract-host-matrix-20260812.json)

日志首条：

```text
2026-08-10T16:51:02+08:00 VM-0-11-ubuntu bash[2985970]: 2026-08-10 16:51:02.554 [scheduling-1] WARN c.m.c.s.h.s.HtxContractAutoTestScheduler - [HTX-CONTRACT-AUTO] cycle rejected contract=XAUT-USDT result=HtxContractTradeResult(code=REJECT_PREFLIGHT, contractCode=XAUT-USDT, leverage=5, cycleId=null, openOrderId=null, closeOrderId=null, openFilledVolume=null, closeFilledVolume=null, message=open orders query failed: 6023:The new feature is temporarily unavailable.)
```

日志统计：

```text
REJECT_PREFLIGHT = 1877
6023             = 1877
ERROR level      = 0
Exception        = 0
```

## 3. curl 使用说明

以下 curl 已删除真实 `AccessKeyId` 和 `Signature`。复现时必须使用新的 UTC 时间和签名；不要把 Secret Key放入工单。签名原文格式：

```text
<HTTP_METHOD>\n
api.hbdm.com\n
<REQUEST_PATH>\n
<按字典序 URL 编码的查询参数>
```

签名算法为 HMAC-SHA256，结果 Base64 后再做 URL 编码。所有失败请求的 HTTP 状态均为 `200`，公共错误响应均为：

```json
{
  "status": "error",
  "err_code": 6023,
  "err_msg": "The new feature is temporarily unavailable."
}
```

## 4. 失败接口与请求

### 4.1 逐仓可用杠杆

```bash
curl -sS -X POST 'https://api.hbdm.com/linear-swap-api/v1/swap_available_level_rate?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>' \
  -H 'Content-Type: application/json' \
  --data '{"contract_code":"XAUT-USDT"}'
```

响应：`6023 / The new feature is temporarily unavailable.`

### 4.2 逐仓账户信息

```bash
curl -sS -X POST 'https://api.hbdm.com/linear-swap-api/v1/swap_account_info?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>' \
  -H 'Content-Type: application/json' \
  --data '{"contract_code":"XAUT-USDT"}'
```

响应：`6023 / The new feature is temporarily unavailable.`

### 4.3 逐仓持仓信息

```bash
curl -sS -X POST 'https://api.hbdm.com/linear-swap-api/v1/swap_position_info?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>' \
  -H 'Content-Type: application/json' \
  --data '{"contract_code":"XAUT-USDT"}'
```

响应：`6023 / The new feature is temporarily unavailable.`

### 4.4 逐仓开放委托

```bash
curl -sS -X POST 'https://api.hbdm.com/linear-swap-api/v1/swap_openorders?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>' \
  -H 'Content-Type: application/json' \
  --data '{"contract_code":"XAUT-USDT","page_index":1,"page_size":50,"sort_by":"update_time"}'
```

响应：`6023 / The new feature is temporarily unavailable.`

### 4.5 全仓账户信息

```bash
curl -sS -X POST 'https://api.hbdm.com/linear-swap-api/v1/swap_cross_account_info?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>' \
  -H 'Content-Type: application/json' \
  --data '{"margin_account":"USDT"}'
```

响应：`6023 / The new feature is temporarily unavailable.`

### 4.6 全仓可用杠杆

```bash
curl -sS -X POST 'https://api.hbdm.com/linear-swap-api/v1/swap_cross_available_level_rate?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>' \
  -H 'Content-Type: application/json' \
  --data '{"contract_code":"XAUT-USDT","business_type":"swap"}'
```

响应：`6023 / The new feature is temporarily unavailable.`

### 4.7 全仓持仓信息

```bash
curl -sS -X POST 'https://api.hbdm.com/linear-swap-api/v1/swap_cross_position_info?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>' \
  -H 'Content-Type: application/json' \
  --data '{"contract_code":"XAUT-USDT"}'
```

响应：`6023 / The new feature is temporarily unavailable.`

### 4.8 全仓开放委托

```bash
curl -sS -X POST 'https://api.hbdm.com/linear-swap-api/v1/swap_cross_openorders?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>' \
  -H 'Content-Type: application/json' \
  --data '{"contract_code":"XAUT-USDT","page_index":1,"page_size":50,"sort_by":"update_time"}'
```

响应：`6023 / The new feature is temporarily unavailable.`

### 4.9 统一账户资产

```bash
curl -sS -X GET 'https://api.hbdm.com/linear-swap-api/v3/unified_account_info?contract_code=XAUT-USDT&AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>'
```

响应：`6023 / The new feature is temporarily unavailable.`

### 4.10 全仓历史委托

```bash
curl -sS -X POST 'https://api.hbdm.com/linear-swap-api/v3/swap_cross_hisorders?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>' \
  -H 'Content-Type: application/json' \
  --data '{"contract":"XAUT-USDT","trade_type":0,"start_time":1786379570325,"end_time":1786465970325,"direct":"prev","type":1,"status":"0"}'
```

响应：`6023 / The new feature is temporarily unavailable.`

## 5. 正常对照接口

### 5.1 API 指标禁用状态

```bash
curl -sS -X GET 'https://api.hbdm.com/linear-swap-api/v1/swap_api_trading_status?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>'
```

```json
{
  "status": "ok",
  "data": {
    "is_disable": 0,
    "disable_reason": "",
    "disable_interval": 0,
    "recovery_time": 0
  }
}
```

### 5.2 U本位账户类型

```bash
curl -sS -X GET 'https://api.hbdm.com/linear-swap-api/v3/swap_unified_account_type?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>'
```

```json
{"code":200,"msg":"ok","data":{"account_type":1}}
```

### 5.3 U本位总资产估值

```bash
curl -sS -X POST 'https://api.hbdm.com/linear-swap-api/v1/swap_balance_valuation?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>' \
  -H 'Content-Type: application/json' \
  --data '{"valuation_asset":"USDT"}'
```

```json
{
  "status": "ok",
  "data": [{"valuation_asset":"USDT","balance":"93.6519895034"}]
}
```

### 5.4 逐仓 XAUT 历史委托

```bash
curl -sS -X POST 'https://api.hbdm.com/linear-swap-api/v3/swap_hisorders?AccessKeyId=<REDACTED>&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=<UTC_TIMESTAMP>&Signature=<REDACTED>' \
  -H 'Content-Type: application/json' \
  --data '{"contract":"XAUT-USDT","trade_type":0,"start_time":1786379570325,"end_time":1786465970325,"direct":"prev","type":1,"status":"0"}'
```

```json
{"code":200,"msg":"ok","data":[]}
```

这说明采样窗口内没有可由逐仓历史接口读取的 XAUT 委托；如果 App 成功订单为 XAUT，则更可能处于全仓通道，而全仓账户、持仓、委托和历史接口均被 6023 阻断。

## 6. 建议提交给 HTX 的问题描述

```text
子账号 UID：请填写完整 UID（服务器 Key 查询到的 UID 后四位为 2922）
产品：XAUT-USDT U本位永续

该子账号可以在 HTX App 正常提交合约订单。
同一子账号 OpenAPI Key 具有 readOnly + trade 权限，签名鉴权成功。
swap_api_trading_status 返回 status=ok、is_disable=0。
swap_unified_account_type 返回 code=200、account_type=1。
swap_balance_valuation 返回 status=ok、93.6519895034 USDT。

但是逐仓/全仓账户、持仓、开放委托、可用杠杆以及统一账户资产接口均返回：
HTTP 200
{"status":"error","err_code":6023,"err_msg":"The new feature is temporarily unavailable."}

服务从 2026-08-10 16:51:02 +08:00 起累计出现 1877 次相同错误。
请检查该子账号 UID 的 USDT-M OpenAPI entitlement、feature flag 和 App/OpenAPI 权限同步状态。
```

提交工单时附上本报告、原始 JSON和日志；不要提交 Secret Key，也不要提交仍可使用的完整签名 URL。

## 7. 三域名切换验证

按同一子账号 Key、同一 XAUT 合约和同一组只读请求，分别将请求域名及签名 Host 切换为：

- `api.hbdm.com`
- `api.btcgateway.pro`
- `api.hbdm.vn`

三个域名均能正常解析、建立 TLS并返回 HTTP 200，未出现 DNS、连接、证书、超时或签名错误。每个域名测试 15 项：5 项正常，10 项返回 6023。

| 域名 | 正常项 | 6023 项 | 全部请求延迟中位数 |
|---|---:|---:|---:|
| `api.hbdm.com` | 5 | 10 | 130.33 ms |
| `api.btcgateway.pro` | 5 | 10 | 141.10 ms |
| `api.hbdm.vn` | 5 | 10 | 288.20 ms |

三个域名的成功集合完全相同：

- 公共 XAUT 合约信息；
- API交易状态 (`is_disable=0`)；
- 账户类型 (`account_type=1`)；
- U本位资产估值；
- 逐仓 XAUT 历史委托查询（成功但 `data=[]`）。

三个域名的 6023 集合也完全相同：

- `swap_available_level_rate`；
- `swap_account_info`；
- `swap_position_info`；
- `swap_openorders`；
- `swap_cross_available_level_rate`；
- `swap_cross_account_info`；
- `swap_cross_position_info`；
- `swap_cross_openorders`；
- `unified_account_info`；
- `swap_cross_hisorders`。

因此 6023 与接入域名、CDN线路、DNS、TLS或签名 Host 无关。`api.hbdm.com` 在本次服务器实测中延迟最低，切换到 `api.btcgateway.pro` 或 `api.hbdm.vn` 不能恢复账户/持仓/委托接口，不应为绕过 6023 修改线上服务域名。

## 8. 官方文档与当前代码映射复核

### 8.1 `id=512` 不是接口详情 ID

开放平台的 `id=512` 页面是“U本位合约 > 合约交易接入说明 > 错误码详情”通用文章，不是某个 REST 接口的详情页。将 `512` 当作 `interface_id` 请求官方详情后端会返回：

```json
{"code":13001,"message":"interface-not-exist(NT)","data":null}
```

该通用错误码表确实未收录 `6023`，整个当前开放平台目录也检索不到 `6023` 或对应英文文案。这说明 `6023` 是未公开列出的业务错误码；仅凭通用表中没有它，不能反推请求路径错误。

### 8.2 Java 服务的逐仓路径与官方当前文档一致

官方当前接口详情与 Java 服务的实际调用一致：

| 用途 | 当前 Java 路径 | 官方接口 ID |
|---|---|---|
| 逐仓账户 | `/linear-swap-api/v1/swap_account_info` | [`8cb74610-77b5-11ed-9966-0242ac110003`](https://www.htx.com.ua/zh-cn/opend/newApiPages/?id=8cb74610-77b5-11ed-9966-0242ac110003) |
| 逐仓持仓 | `/linear-swap-api/v1/swap_position_info` | [`8cb74886-77b5-11ed-9966-0242ac110003`](https://www.htx.com.ua/zh-cn/opend/newApiPages/?id=8cb74886-77b5-11ed-9966-0242ac110003) |
| 逐仓开放委托 | `/linear-swap-api/v1/swap_openorders` | [`8cb78387-77b5-11ed-9966-0242ac110003`](https://www.htx.com.ua/zh-cn/opend/newApiPages/?id=8cb78387-77b5-11ed-9966-0242ac110003) |
| 逐仓可用杠杆 | `/linear-swap-api/v1/swap_available_level_rate` | [`8cb75e14-77b5-11ed-9966-0242ac110003`](https://www.htx.com.ua/zh-cn/opend/newApiPages/?id=8cb75e14-77b5-11ed-9966-0242ac110003) |
| 逐仓下单 | `/linear-swap-api/v1/swap_order` | [`8cb77019-77b5-11ed-9966-0242ac110003`](https://www.htx.com.ua/zh-cn/opend/newApiPages/?id=8cb77019-77b5-11ed-9966-0242ac110003) |

服务当前配置为 `margin-mode: isolated`，账户、持仓、开放委托和下单都只实现了上述逐仓接口。因此，“当前逐仓接口名称或路径对接错误”不成立。

### 8.3 存在的真实差异：服务尚未实现全仓交易

如果 App 中的成功订单是“全仓”，那么当前服务确实无法管理同一仓位，后续需要增加 `swap_cross_account_info` / `swap_cross_position_info` / `swap_cross_openorders` / `swap_cross_available_level_rate` / `swap_cross_order` 路由。这是代码能力缺口，但不是本次 6023 的单一原因：使用官方文档的精确全仓路径和请求体重测后，账户、持仓、开放委托和可用杠杆仍全部返回 6023。

本次复核同时修正了旧证据报告的两个辅助探针请求体：全仓持仓和开放委托不应传 `margin_account`。这两个多余参数从未出现在 Java 服务中；删除后三域名仍返回相同 6023。另外，XAUT 公开合约元数据返回 `contract_status=1`、`business_type=swap`、`support_margin_mode=all`，确认 `XAUT-USDT` 属于标准 U本位永续产品，且同时支持逐仓与全仓。

参数对照也已完成：将逐仓账户从 `XAUT-USDT` 换为 `BTC-USDT`，或将逐仓/全仓账户、持仓改为空筛选请求，仍全部返回 6023。因此已排除 XAUT 单品种、`contract_code` 值和可选筛选参数导致的请求格式问题。
