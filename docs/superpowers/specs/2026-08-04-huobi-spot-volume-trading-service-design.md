# 火币(HTX)现货刷量交易服务 — 设计规格

- **日期**: 2026-08-04
- **状态**: 设计已与用户对齐,待评审
- **作者**: Agent(brainstorming 流程产出)
- **所属项目**: CoinStrategy

## 0. 背景与合规声明

CoinStrategy 现有交易所客户端仅覆盖 OKX / Binance / Gate,且只有 **Binance 现货**真正接入了运行中的策略;**火币(HTX)客户端完全不存在**(仅在 `ClientConfiguration.java:60` 有一句已移除的 `proxy.huobidev` 代理注释)。本规格新增一个**独立的火币现货交易服务**。

合规前提(必须持续成立):

- 运行在**用户自有火币账户 + 用户自有 API Key/Secret** 上的交易自动化。
- 采用**真实买卖两腿(非自成交)**产生真实撮合量,不使用自成交/对敲。
- **不实现任何规避交易所风控或检测的手段。**
- 自成交/刷量在部分交易所 ToS 与部分司法辖区受限;用户需自行确认符合火币 ToS 与当地法规。

## 1. 目标与非目标

### 1.1 核心目标(来自用户对齐)

1. 实现**火币现货自动买入/卖出**,并**独立成单独可部署服务**;用户后续自行配置 apiKey / secretKey。
2. 前期**不追求盈利**,但**绝不产生持仓方向性亏损(必须保持 delta 中性 / 及时平仓)**;**手续费+价差是可接受的刷量成本**。
3. 能把**交易量刷起来**(兼顾 VIP 费率档位、策略预热/链路测试、活动交易量任务三类用途)。

### 1.2 关键设计决策(用户已选)

| 维度 | 决策 |
|---|---|
| 亏损底线 | 手续费可接受,**不能有持仓方向亏损** |
| 执行机制 | **即时顺序往返**:buy 成交后立即 sell 相同数量 |
| 两腿价格敏感 | **顺序往返(标准、安全)**,残留由兜底逻辑立即平掉 |
| 交易对 | **主流币对**(如 BTC/USDT、ETH/USDT),做成配置项 |
| 服务形态 | **本仓新增 Maven 模块 + 独立可部署 jar** |
| 上线策略 | **直接小额实盘**,硬上限**全部配置化 + 保守默认** |

### 1.3 非目标(YAGNI)

- 不做盈利优化 / 做市赚价差策略。
- 不接入 coin-strategy-job 现有调度与数据库(保持独立)。
- 不实现自成交(对敲)或任何风控/检测规避。
- 不实现合约/杠杆;仅现货。

### 1.4 成本预期(已知并被用户接受)

主流币对现货 taker 费约 0.2%/腿 → **每次往返约 0.4% 成本**;刷约 1 万 U 成交量约花 ~40 U 手续费。此为「无方向亏损 + 主流币对」下的必然代价,用户明确接受。

## 2. 架构与模块

### 2.1 新增模块 `coin-strategy-huobi`

- 独立 Spring Boot 可执行 jar,挂到 parent pom `<modules>`。
- 依赖:`common-base`(工具/SignUtils 风格)、`common-client`(火币客户端)。**不依赖** `coin-strategy-job` / `coin-strategy-web` / `coin-strategy-core`。
- 主类 `CoinStrategyHuobiApplication`:`@SpringBootApplication(scanBasePackages="com.metaverse.coin.strategy")`、`@EnableScheduling`、`@EnableConfigurationProperties`、JVM 默认时区 `Asia/Shanghai`(与 job 模块一致)。
- 资源:`application.yml` + `application-{local,test,prod}.yml`、`logback-spring.xml`。

### 2.2 火币客户端(置于 `common-client`,`client/huobi` 包,与 okx/binance/gate 并列)

- `HuobiApi` — Retrofit 接口(复用 parent pom 已有的 retrofit2 / okhttp3 / jackson)。
- `HuobiAuthInterceptor` — OkHttp 拦截器,HTX `HmacSHA256` 签名。
- `service/HuobiSpotService` — 下单 / 撤单 / 查单 / 查账户与余额。
- `service/HuobiMarketDataService` — 最优买卖价(bbo)/ 盘口深度。
- `HuobiApiService` — 门面,聚合上述 service。
- Bean 创建:`@ConditionalOnProperty(name = "client.huobi.apiKey")`,未配置则不建 bean(与 OKX `ClientConfiguration` 一致),注入方使用 `required=false` / `ObjectProvider`。

### 2.3 客户端 API 细节

- **认证**:HTX 签名规范串 = `HTTPMethod\nHost\nPath\n按字典序排序的 query`,query 含 `AccessKeyId`、`SignatureMethod=HmacSHA256`、`SignatureVersion=2`、`Timestamp`(UTC,`yyyy-MM-ddTHH:mm:ss`);HMAC-SHA256 后 base64,再 URL-encode 作为 `Signature` 参数。
- **账户**:`GET /v1/account/accounts` 取 spot `account-id`;`GET /v1/account/accounts/{account-id}/balance` 取余额。
- **行情**:`GET /market/detail/merged?symbol=` 取最优买卖价;`GET /market/depth?symbol=&type=step0` 取盘口深度。
- **下单**:`POST /v1/order/orders/place`,`type ∈ {buy-ioc, sell-ioc, buy-market, sell-market, buy-limit, sell-limit}`。
  - ⚠️ **火币语义陷阱**:`buy-market` 的 `amount` 表示「花费的计价币金额」;`sell-market` 的 `amount` 表示「卖出的基础币数量」。服务层负责正确换算与精度处理。
- **查单**:`GET /v1/order/orders/{order-id}`;**撤单**:`POST /v1/order/orders/{order-id}/submitcancel`。
- **交易对规则**:`GET /v1/common/symbols` 取最小下单量、金额精度、数量精度(用于下单前校验与取整)。

## 3. 刷量核心 `VolumeCycleService`

`@Scheduled(fixedDelayString=...)` 驱动,单实例、单线程,防重入(`AtomicBoolean` guard)。每个 cycle:

1. **前置闸门(任一不通过即跳过本 cycle,绝不下单)**:
   - `enabled=true` 且未被 circuit breaker 暂停;
   - 当日累计成交额 < 日上限;当前本金占用 < 总上限;当日循环次数 < 上限;
   - 拉盘口:`spread(bps) ≤ maxSpreadBps`(价差过大直接不刷,避免成本失控);
   - 盘口深度足以吃下单笔目标数量;
   - 计价币余额充足。
2. **计算单笔数量**:notional 落在 `[minNotional, maxNotional]`;按交易对精度/最小下单量取整。
3. **买腿**:place `buy-ioc`(挂卖一 + 极小可配滑点容忍);查询**实际成交的基础币数量** `filledBase`。
4. **卖腿**:以 `filledBase`(扣除买入手续费占用后可卖部分)立即 place `sell-ioc`(挂买一);平回净持仓 ~0。
5. **对账**:计算本 cycle 净 delta 与成本(两腿手续费 + 价差);累加当日成交额;结构化日志 /(可选)落库。
6. **残留兜底(补平)**:
   - 若买腿成交但卖腿未完全平掉 → **立即市价强平剩余基础币**;
   - 若强平仍失败 → 触发**报警 + circuit breaker 暂停服务**(停止后续 cycle,等待人工介入)。

**铁律**:永远「先确认买腿实际成交量,再卖对应量」,不以预估量下卖单,杜绝超卖/欠卖敞口。

## 4. 风控与安全(全部配置化,保守默认)

`HuobiVolumeProperties`(`@ConfigurationProperties(prefix="huobi.volume")`):

| 配置项 | 含义 | 默认(保守) |
|---|---|---|
| `enabled` | 服务总开关 | `false`(配好 key 后手动开) |
| `symbol` | 交易对 | `btcusdt` |
| `min-notional` / `max-notional` | 单笔计价币金额上下限 | 小额(如 5 / 10 USDT) |
| `max-slippage-bps` | 单笔滑点容忍 | 保守小值 |
| `max-spread-bps` | 可接受最大盘口价差 | 保守小值,超则不刷 |
| `daily-turnover-cap` | 当日累计成交额上限 | 小额(如 1000 USDT) |
| `capital-cap` | 总本金占用上限 | 小额(如 50 USDT) |
| `daily-cycle-cap` | 当日最大循环次数 | 保守值 |
| `cycle-interval-ms` | 循环间隔 | 保守较大值 |
| `max-retries` | 单腿失败重试次数 | 小值 |
| `net-position-tolerance` | 净持仓容忍阈值,超即强平+报警 | 极小值 |
| `breaker-consecutive-failures` | 连续失败触发熔断阈值 | 小值 |
| `dry-run` | 走完整 cycle 逻辑但不发真实下单请求(仅日志) | `false`;`enabled=true` 且 `dry-run=true` 时可做无风险冒烟 |
| `reconcile-whitelist` | 启动 reconcile 允许强平的基础币白名单 | 空(默认只报警) |

- 时区固定 `Asia/Shanghai`,按北京时间跨日重置「当日累计成交额 / 循环次数」。
- 所有金额用 `BigDecimal`;所有下单前按交易对精度取整校验。

## 5. 未完成任务补充(呼应用户第 3 问)

- **每 cycle 收尾**:核对净 delta,残留立即强平(补平)。
- **启动 reconcile**(`@PostConstruct`):查询账户是否存在本服务遗留的非计价币持仓;默认**只报警不自动处置**(避免误动用户其他资产);仅当基础币在 `reconcile-whitelist` 时才按配置强平。
- **IOC 免超时**:两腿走 IOC,天然即时成交或即时失效,不产生长期挂单;因此无需 GTC 超时撤单器。
- **熔断恢复**:熔断后保持 flat 并暂停;人工确认后经 `/resume` 或重启恢复。

## 6. 可观测与运维

- **结构化日志**:每 cycle 记录买/卖价、目标量、实际成交量、净 delta、成本、当日累计成交额与循环次数。
- **REST 控制端点(可选,便于实盘冒烟)**:`GET /huobi/volume/status`、`POST /huobi/volume/pause`、`/resume`、`/trigger-once`。
- **部署**:仿 `deploy/linux`(bin / env / systemd),新增 huobi 服务的 systemd unit 与 env 模板;jar 独立启动。

## 7. 测试策略(TDD)

- **签名单测**:对拍火币官方文档签名示例向量,验证 `HuobiAuthInterceptor` 规范串与 base64 结果。
- **`HuobiSpotService`**:mock HTTP server 覆盖 buy/sell/查单/撤单,重点验证 `buy-market`(计价币金额)与 `sell-market`(基础币数量)语义换算与精度。
- **`VolumeCycleService`**(fake client):
  - 正常往返后净 delta ≈ 0;
  - 买腿部分成交 → 卖对应实际量;
  - 卖腿失败 → 触发市价强平;
  - `spread > maxSpreadBps` → 跳过不下单;
  - 当日成交额达上限 → 停止;
  - 净 delta 超容忍阈 → 强平 + 报警;
  - 连续失败 → 熔断暂停;
  - `dry-run` → 不产生真实下单调用。

## 8. 交付物清单

1. parent `pom.xml` 增加 `coin-strategy-huobi` 模块。
2. `common-client` 新增 `client/huobi`:`HuobiApi`、`HuobiAuthInterceptor`、`HuobiSpotService`、`HuobiMarketDataService`、`HuobiApiService` + 对应 model。
3. `coin-strategy-huobi` 模块:主类、`HuobiVolumeProperties`、`VolumeCycleService`、`HuobiClientConfiguration`(条件化 bean)、(可选)控制 controller、application yml 各 profile、logback。
4. 单元测试(签名 / spot service / volume cycle)。
5. `deploy/linux` systemd unit + env 模板。
6. 文档:模块 README(配置项说明 + 上线冒烟步骤)。

## 9. 风险与开放项

- **火币 API 地域/网络**:火币域名在部分网络需代理;客户端保留可选 proxy 配置(仿 OKX)。
- **火币 API 版本**:下单/撤单/账户接口以火币官方最新现货 REST 文档为准,实现时以文档核对字段。
- **精度与最小下单量**:不同交易对差异大,依赖 `/v1/common/symbols` 动态获取,避免硬编码。
- **成本累积**:日上限是唯一硬性成本闸门,务必保守设置后再逐步放大。
