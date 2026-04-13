## 订单项目安全审计与修复计划（Polymarket）

### 1. 审计范围
本轮审计覆盖：
- coin-strategy-job：订单执行、撤单、结算、数据修复、策略触发接口
- coin-strategy-web：对外查询与聚合接口暴露面
- smart-wallet + binance + sunwk 相关控制器与执行链路
- 配置项、Swagger、CORS、敏感信息处理

---

### 2. 发现的主要安全问题（按优先级）

#### P0（必须立即修复）

1) 高风险交易接口缺少统一鉴权与授权  
- 多个 Controller 直接暴露下单/撤单/执行/修复能力，未见统一登录态或签名鉴权。  
- 风险：未授权请求可触发真实交易、撤单、数据重置、结算推进。

2) API 明文返回敏感凭证（严重泄露）  
- 派生 API 凭证接口与部分下单回包会返回 apiKey/apiSecret/apiPassphrase。  
- 风险：调用方或日志链路一旦泄漏，可直接接管交易权限。

3) 存在并发重复执行风险（潜在重复下单）  
- 手动执行 LIVE 任务缺乏原子状态流转保护（无 CAS/乐观锁幂等保护）。  
- 风险：并发请求下同一任务可能重复提交订单。

#### P1（高优先级）

4) 参数校验不足  
- 多处接口仅使用基础 @RequestParam/@RequestBody，缺少 @Valid/@Validated 与约束注解。  
- 风险：异常输入导致业务异常、资源消耗、日志污染。

5) 异常信息可能向外透传过多内部细节  
- 上游异常 message 和部分远端响应内容可能直接进入返回或日志。  
- 风险：暴露内部实现、链路细节、第三方返回结构。

6) 默认配置存在生产误用风险  
- execution-wallet.enabled 为 true、Swagger 默认开启、部分高风险能力可直接通过 HTTP 调用。  
- 风险：环境配置误用时扩大攻击面。

#### P2（中优先级）

7) CORS 策略偏宽且带 credentials  
- 当前主要面向 localhost，但若部署配置迁移不当容易放大跨域风险。  

8) 查询接口存在资源消耗风险  
- 部分 list 接口先全量查库再内存截断，缺少统一 limit 上限与分页规范。  

---

### 3. 修复目标（安全基线）

- 所有交易类/管理类接口默认拒绝，最小权限开放
- 任何响应不得返回明文密钥/私钥/passphrase
- 所有高风险操作必须具备：身份认证 + 权限授权 + 幂等保护 + 审计日志
- 统一参数校验与错误码，禁止内部堆栈和敏感上下文外泄
- 默认配置遵循“安全默认值”（默认关闭 LIVE 关键能力）

---

### 4. 分阶段修复计划

#### Phase 0：紧急止血（当天完成）
1. 下线/封禁高风险开放接口（网关或应用层临时开关）
2. 立即移除响应中的 apiSecret/apiPassphrase/apiKey 明文返回
3. 暂时关闭或限制以下能力到内网：
   - live execute / cancel / derive creds / reset / repair / settlement process
4. 轮换已可能泄露的 API 凭证（Polymarket/Binance/Sunwk）

交付物：
- 热修复发布记录
- 已轮换密钥清单（脱敏）
- 封禁接口清单

#### Phase 1：接入统一鉴权与权限（1~2 天）
1. 引入 Spring Security（或统一网关鉴权）  
2. 接口分级：
   - READ：只读查询
   - TRADE：下单/撤单/执行
   - ADMIN：重置/回填/修复/结算推进/凭证派生
3. 高风险接口加角色控制（如 ROLE_TRADE_ADMIN / ROLE_OPS）
4. 增加来源限制（内网白名单或 mTLS/签名）

交付物：
- 权限矩阵（接口 -> 角色）
- 鉴权配置与回归结果

#### Phase 2：输入校验与错误处理收敛（1~2 天）
1. Controller 统一启用 @Validated，DTO 添加约束注解
2. limit/price/quantity/leverage/orderId 等参数统一边界
3. 全局异常处理（ControllerAdvice）统一错误码
4. 对外错误文案脱敏，不回传上游原始异常细节

交付物：
- 参数校验规范
- 错误码文档（内部）

#### Phase 3：幂等与并发安全（1~2 天）
1. execute-live / cancel 等高风险操作增加幂等键
2. 任务状态更新改为条件更新（CAS）或乐观锁版本号
3. 增加“执行中”状态与重入保护
4. 审计日志记录 requestId + operator + taskId + result

交付物：
- 幂等设计说明
- 并发回归测试结果

#### Phase 4：配置与暴露面治理（1 天）
1. 生产默认关闭 Swagger / API docs
2. CORS 按环境白名单严格控制，必要时关闭 credentials
3. execution-wallet.enabled 默认 false，必须显式开启
4. 清理明文敏感配置（密码、密钥）并改为密钥管理系统注入

交付物：
- 环境基线配置模板（dev/staging/prod）
- 配置审计清单

#### Phase 5：验证与持续化（1 天）
1. 安全回归清单执行（鉴权、越权、重放、并发、异常脱敏）
2. 增加最小安全测试用例（接口级）
3. 建立上线前安全检查 Gate（CI）

交付物：
- 安全回归报告
- CI 安全检查项清单

---

### 5. 建议的修复顺序（执行优先级）
P0-1 鉴权封禁与密钥脱敏 > P0-2 凭证轮换 > P0-3 并发幂等  
> P1 参数校验与异常治理 > P2 暴露面与配置基线

---

### 6. 验收标准（Done Definition）
- 未携带合法身份的请求无法访问任何 TRADE/ADMIN 接口  
- 任一接口响应/日志中不出现 apiSecret/privateKey/passphrase 明文  
- 同一 followTask 的并发 LIVE 执行不会产生重复下单  
- 所有输入参数均有边界校验与统一错误码  
- 生产环境 Swagger 关闭、CORS 白名单可审计、敏感配置零明文