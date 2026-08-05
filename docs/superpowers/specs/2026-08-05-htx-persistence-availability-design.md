# HTX 现货执行服务 — 持久化 + 可用性设计

- 日期: 2026-08-05
- 分支: support_htx
- 模块: coin-strategy-htx（独立 Spring Boot 服务，端口 8082，context-path `/htx/api`）
- 状态: 设计待评审

## 1. 背景与目标

`coin-strategy-htx` 现状：locked-pair（买腿→等成交→卖腿）执行链已完成（`HtxLockedPairExecutor` / `HtxTradingService`），但**完全没有数据库**，结果只落在日志和 HTTP 返回里。SDK 设计文档 §8 明确把持久化推迟到 v2 —— 本设计即 v2。

目标：
1. 新建**专用**数据库 `htx_trade_strategy`，只服务本模块，不与 coin-strategy-job（8081）共用。
2. 完整表设计：下单、撤单、交易/成交记录，一笔完整买/卖 round-trip 记录**最终手续费消耗**；拆单场景记录聚合后的最终手续费。
3. 保证服务可用性**无缺口**：崩溃不留裸单、DB 与真实下单一致、单腿裸露可自动收敛。

## 2. 三项已批准的关键决策

| 决策点 | 选择 | 含义 |
|---|---|---|
| DB 故障行为 | **下单前写不进库就拒单（fail-closed）** | write-ahead：真实下单前必须先成功 INSERT 订单意图记录；写库失败则直接拒单，不发 REST。DB 是本地专用，可用性高，用记录完整性换绝不产生"无记录的真实订单"。 |
| 手续费粒度 | **订单级聚合** | 直接用 HTX 订单查询返回的 `HtxOrder.filledFees`（已是覆盖所有部分成交的最终聚合值）+ 费币种，存入 `htx_order`。不做逐笔 matchresults。 |
| 单腿裸露处置 | **对账器自动市价平掉** | 定时对账器检测到裸露 base 头寸，立即市价平回中性，写 `htx_exposure`，并告警。 |

## 3. 已识别的服务缺口（主动排查，非用户提出）

### A. 持久化缺口
- 零 DB：下单/撤单/成交/手续费结果只在日志与 HTTP response，进程重启即丢失，无法审计、无法对账、无法核算收益。

### B. 资源安全缺口
- **超卖风险** `HtxLockedPairExecutor:115`：买腿全成时 `filledForSell = req.getQuantity()`，用的是**请求数量**而非实际到手 base。HTX 买单手续费扣在 base 币（买 BTC，手续费扣 BTC），因此实际可卖 = 成交量 − base 手续费。直接按请求量挂卖 → 卖出量 > 账户余额 → 卖腿被拒或超卖。
- 手续费从不采集：`HtxOrder.filledFees`/`filledCashAmount` 字段存在但执行器从未读取，无法核算真实成本与净收益。
- 费币种未记录：买/卖手续费币种不同（买扣 base、卖扣 quote），不记录无法做净头寸/净成本核算。

### C. 可用性缺口
- **崩溃→裸单**：执行是同步内存流程。买腿成交后、卖腿挂出前进程崩溃 → 账户持有裸 base，无任何记录，无人收敛。
- 无对账器：没有周期性核对"DB 意图 vs HTX 实际 vs 账户余额"。
- 无死信/告警：单腿裸露（`FAIL_AFTER_BUY_FILL` / `SELL_PENDING`）只打日志，无持久死信、无告警。
- 无写前恢复：重启后无法从 DB 重放"未终态"的 pair 继续收敛。
- 无实盘预检：`spot-account-id` 为空时，`enabled=true` 才会在真实下单时才暴露问题。

## 4. 数据库设计 `htx_trade_strategy`

字符集 `utf8mb4`，金额一律 `DECIMAL(36,18)`，时间 `DATETIME(3)`，所有表带 `created_at` / `updated_at`。

### 4.1 `htx_locked_pair` — round-trip 主单（一次 locked-pair = 一行）
| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AUTO | |
| client_order_id | VARCHAR(64) UNIQUE | 幂等键，来自调用方；派生 `$key-BUY`/`$key-SELL` |
| symbol | VARCHAR(32) | |
| req_quantity | DECIMAL(36,18) | 请求数量 |
| buy_price / sell_price | DECIMAL(36,18) | |
| status | VARCHAR(32) | 见 §5 状态机 |
| result_code | VARCHAR(48) | 对应 `HtxLockedPairResult.Code` |
| buy_order_id / sell_order_id | BIGINT NULL | HTX 订单号 |
| buy_filled_amount / sell_filled_amount | DECIMAL(36,18) | 实际成交 base |
| net_base_delta | DECIMAL(36,18) | 净 base 头寸（=买到手 base − 卖出 base），理想=0 |
| total_fee_base | DECIMAL(36,18) | 买腿 base 手续费聚合 |
| total_fee_quote | DECIMAL(36,18) | 卖腿 quote 手续费聚合 |
| net_quote_cost | DECIMAL(36,18) | 净 quote 成本（卖得 − 买付 − quote费） |
| dry_run | TINYINT(1) | 是否 dry-run |
| message | VARCHAR(512) | |
| created_at / updated_at | DATETIME(3) | |

### 4.2 `htx_order` — 每个真实子订单一行（write-ahead 核心表）
| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AUTO | |
| pair_id | BIGINT FK→htx_locked_pair | |
| client_order_id | VARCHAR(64) | 该腿的 client id（`$key-BUY`/`-SELL`）|
| htx_order_id | BIGINT NULL | 下单成功后回填；**先写行、后发 REST** |
| side | VARCHAR(8) | buy / sell |
| type | VARCHAR(16) | buy-limit / sell-limit / *-market（对账平仓）|
| price | DECIMAL(36,18) NULL | 市价单为空 |
| req_amount | DECIMAL(36,18) | 请求量 |
| state | VARCHAR(32) | HTX 订单状态：submitted/filled/partial-canceled/canceled/rejected |
| filled_amount | DECIMAL(36,18) | 实际成交 base |
| filled_cash_amount | DECIMAL(36,18) | 实际成交 quote（`HtxOrder.filledCashAmount`）|
| filled_fees | DECIMAL(36,18) | **聚合最终手续费**（`HtxOrder.filledFees`）|
| fee_currency | VARCHAR(16) | 买=base 币，卖=quote 币 |
| intent_status | VARCHAR(16) | PENDING_SEND / SENT / SEND_FAILED / TERMINAL —— 写前恢复用 |
| raw_response | TEXT | 查询原始 JSON（审计）|
| created_at / updated_at | DATETIME(3) | |

索引：`(pair_id)`、`(htx_order_id)`、`(intent_status)`。

### 4.3 `htx_cancel` — 撤单记录
| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AUTO | |
| order_id | BIGINT FK→htx_order | |
| htx_order_id | BIGINT | |
| reason | VARCHAR(64) | timeout / partial-recover / reconciler |
| cancel_ok | TINYINT(1) | submitcancel 是否成功 |
| post_cancel_state | VARCHAR(32) | 撤单后 re-query 状态（cancel-race 校验）|
| post_cancel_filled | DECIMAL(36,18) | 撤单后实际成交（可能非 0）|
| created_at | DATETIME(3) | |

### 4.4 `htx_exposure` — 单腿裸露死信
| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AUTO | |
| pair_id | BIGINT FK | |
| symbol | VARCHAR(32) | |
| exposed_side | VARCHAR(8) | 裸的是 base 多头还是空头 |
| exposed_amount | DECIMAL(36,18) | 裸露数量 |
| status | VARCHAR(24) | OPEN / FLATTENING / FLATTENED / MANUAL |
| flatten_order_id | BIGINT NULL | 对账器市价平仓订单号 |
| detail | VARCHAR(512) | |
| created_at / updated_at | DATETIME(3) | |

### 4.5 `htx_trade` —（可选，暂不实现）
逐笔成交表。当前 `HtxSpotService` 无 matchresults 端点，逐笔费率不可得。保留表定义占位，本期不落地；订单级聚合已满足"最终手续费消耗"需求。

## 5. round-trip 状态机（`htx_locked_pair.status`）

```
NEW → BUY_SENT → BUY_FILLED → SELL_SENT → DONE
  │        │           │           └→ SELL_PENDING（卖未终态，可继续收敛）
  │        │           └→ EXPOSED（卖腿被拒/裸露 → 写 htx_exposure）
  │        └→ BUY_TIMEOUT / BUY_REJECTED（无成交，安全终态）
  └→ REJECTED（校验/深度/最小名义/DB写失败）
```
- DB 写失败发生在 NEW→BUY_SENT 之前 → 直接 `REJECTED`（fail-closed），不发 REST。
- 任一"未终态"（BUY_SENT/BUY_FILLED/SELL_SENT/SELL_PENDING/EXPOSED）都是对账器的扫描目标。

## 6. write-ahead 执行流程（改造后）

真实下单路径（`enabled=true` 且非 dry-run）：
1. 插入 `htx_locked_pair`（status=NEW, client_order_id UNIQUE 幂等）。UNIQUE 冲突 → 幂等返回已有结果。
2. **买腿 write-ahead**：插入 `htx_order`（side=buy, intent_status=PENDING_SEND）。失败 → pair=REJECTED，**不发 REST**。
3. 发买单 REST → 回填 `htx_order.htx_order_id` + intent_status=SENT，pair=BUY_SENT。
4. `waitForFill` 终态后：读 `filledAmount/filledCashAmount/filledFees/fee_currency` 落 `htx_order`；pair=BUY_FILLED。
5. **超卖修正**（关键）：`sellAmount = buyFilledBase − buyFeeBase`（买腿手续费扣在 base 时）。按修正后数量挂卖，替代 `req.getQuantity()`。
6. **卖腿 write-ahead**：插入 `htx_order`（side=sell, PENDING_SEND）→ 发 REST → 回填 → pair=SELL_SENT。
7. 卖腿终态：落成交与费用；全成 → 计算 `net_base_delta`/`net_quote_cost`/`total_fee_*` → pair=DONE。
8. 卖腿未终态 → SELL_PENDING；卖腿被拒/裸露 → 写 `htx_exposure`(OPEN) + pair=EXPOSED。
9. 撤单动作（timeout / partial-recover / cancel-race）均写 `htx_cancel`。

dry-run 路径：仍写 `htx_locked_pair`(dry_run=1, status=DONE, result_code=DRY_RUN_OK)，不写 `htx_order`，不发 REST —— 保证 dry-run 也有审计轨迹。

## 7. 崩溃恢复 + 对账器（可用性无缺口）

### 7.1 启动恢复（`ApplicationReadyEvent`）
扫描 `htx_locked_pair` 非终态行：
- intent_status=PENDING_SEND 的 order（写了行但没确认发出）→ 用 client_order_id 调 `queryOrderByClientId` 判定 HTX 侧是否真的收单；据实回填或标记 SEND_FAILED。
- BUY_SENT/BUY_FILLED/SELL_SENT → 重新 `waitForFill`/续跑收敛逻辑。

### 7.2 定时对账器（`@Scheduled`，独立于请求线程）
- 拉未终态 pair + `htx_exposure`(status=OPEN)。
- 对每个 EXPOSED：计算裸露 base，**市价单**平回中性（写 `htx_order` type=*-market write-ahead），成功 → exposure=FLATTENED + 回填 flatten_order_id；失败重试有上限，超限 → status=MANUAL + 告警。
- 校验 `net_base_delta` 应趋近 0，偏离阈值告警。

### 7.3 告警
单腿裸露、DB 写失败拒单、对账平仓失败 → 统一走 `log.error` + 预留告警 sink（本期日志级，接口留扩展）。

## 8. 实盘预检
`enabled=true` 时启动即校验 `spot-account-id` 非空、`accounts` 可达；缺失则拒绝进入实盘（保持 dry-run 或启动失败），避免真实下单时才暴露。

## 9. 技术选型
- 持久层：Spring Data JPA + HikariCP（本模块此前无 DB 依赖，需在 `coin-strategy-htx/pom.xml` 引入 `spring-boot-starter-data-jpa` + `mysql-connector-j`）。保持模块隔离：仅本模块引入，不污染 job。
- 建表：`schema.sql`（Flyway 可作后续增强，本期先 `schema.sql` + `ddl-auto=validate`）。
- 配置：`spring.datasource.*` 走 `${HTX_DB_*}` 环境变量，密钥不入 git（沿用 htx.env 模式）。

## 10. 隔离性说明
`htx_trade_strategy` 库、JPA 依赖、对账器均只存在于 `coin-strategy-htx`。coin-strategy-job / core / web 不受影响，HTX 事故不外溢。

## 11. 不做（YAGNI）
- 逐笔 matchresults / `htx_trade` 落地（订单级聚合已够）。
- Flyway、分库分表、读写分离。
- 独立告警中间件（先日志 + 扩展接口）。
