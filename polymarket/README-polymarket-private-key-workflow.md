# Polymarket 跟单策略私钥管理与创建流程

本文档整理当前代码库里，Polymarket 跟单策略如何管理执行钱包私钥，以及如果要新建一个私钥参与跟单策略，推荐按什么流程落地。

目标是把这件事拆成两部分：

1. 当前系统里，私钥是如何被读取、校验和使用的。
2. 如果我们要新建一个执行钱包，应该如何创建、注入、校验，再逐步开启 LIVE 跟单。

---

## 1. 当前代码中的私钥管理方式

### 1.1 私钥来源

当前 smart-wallet LIVE 执行钱包的核心配置在：

- `coin-strategy-job/src/main/resources/application.yml`

关键配置项如下：

```yaml
smart-wallet:
  auto-execution-enabled: false
  execution-wallet:
    enabled: true
    address: "${POLYMARKET_EXECUTION_WALLET_ADDRESS:}"
    private-key: "${POLYMARKET_EXECUTION_PRIVATE_KEY:}"
    funder-address: "${POLYMARKET_EXECUTION_FUNDER_ADDRESS:}"
    api-key: "${POLYMARKET_CLOB_API_KEY:}"
    api-secret: "${POLYMARKET_CLOB_API_SECRET:}"
    api-passphrase: "${POLYMARKET_CLOB_API_PASSPHRASE:}"
    derive-api-creds-if-missing: true
```

结论：

- 执行钱包私钥不写死在代码里。
- 私钥通过环境变量 `POLYMARKET_EXECUTION_PRIVATE_KEY` 注入。
- 地址通过 `POLYMARKET_EXECUTION_WALLET_ADDRESS` 注入。
- CLOB API 三元组可以单独注入，也可以先留空，启动后由钱包私钥派生。

### 1.2 私钥如何进入运行时

运行时加载逻辑在：

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/ExecutionWalletContextLoader.java`

当前实现要点：

- 系统先读取 `address` 和 `privateKey`。
- 若任一缺失，则 LIVE 钱包视为未配置完成。
- `privateKey` 与 `apiSecret` 会先经过 `SecretDecryptor.decryptIfEncrypted(...)`。
- 也就是说，环境变量既支持明文，也支持 `ENC(...)` 密文。

对应解密逻辑在：

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/config/SecretDecryptor.java`

结论：

- 当前私钥管理模型本质上是“环境变量注入 + 启动时可选解密 + 运行时内存使用”。
- 私钥不是从数据库读取的。
- 私钥也不是通过接口动态上传的。

### 1.3 私钥如何被用于 Polymarket 下单

当前 CLOB 下单逻辑在：

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/PolymarketJavaClobClient.java`

这里会用执行钱包私钥做两件事：

1. 派生或创建 Polymarket CLOB API 凭证。
2. 对订单做 EIP-712 签名。

如果 `api-key/api-secret/api-passphrase` 没配，但 `derive-api-creds-if-missing=true`，系统会自动尝试：

- `POST /auth/api-key`
- 失败后回退到 `GET /auth/derive-api-key`

这意味着当前代码允许两种接入方式：

- 方式 A：提前把 CLOB API 凭证也配置好。
- 方式 B：只配置钱包地址和私钥，第一次使用时由系统派生 CLOB API 凭证。

### 1.4 私钥是否会从接口暴露出来

执行钱包状态接口在：

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/controller/SmartWalletController.java`
- 路径：`GET /smart-wallet/execution-wallet/status`

根据 `ExecutionWalletContextLoader#getStatus()` 的实现：

- 返回的地址会被 mask。
- 返回结果不会包含私钥明文。
- notes 中明确写了：`Private key is read from configuration but never exposed by the status endpoint.`

结论：

- 当前实现对“状态查询接口泄露私钥”这件事做了显式规避。
- 但私钥仍然存在于环境变量和进程内存中，所以运维侧仍然需要控制 env 文件、机器权限和日志输出。

---

## 2. 当前推荐的私钥管理策略

结合现有代码，推荐采用下面这套规则。

### 2.1 使用专用执行钱包，不要复用个人主钱包

建议为跟单策略单独创建一个 Polygon/Polymarket 执行钱包：

- 只用于跟单策略。
- 不和个人主资产钱包混用。
- 不和其它策略共用。

这样做的好处：

- 风险隔离更清晰。
- 轮换私钥更容易。
- 账务归因、故障排查、权限控制都更简单。

### 2.2 生产环境优先使用 `ENC(...)`，不要直接明文落盘

当前仓库已经提供了加密方案：

- 生成 RSA 私钥：`scripts/generate-rsa-keys.sh`
- 生成密文：`scripts/encrypt-secret.sh`

推荐做法：

- 本地临时联调可以接受明文 env。
- 测试/生产环境使用 `ENC(...)`。
- RSA 私钥路径通过 `APP_RSA_PRIVATE_KEY_PATH` 注入。

### 2.3 不把真实私钥提交到仓库

仓库里已有示例文件：

- `scripts/polymarket-env.example.sh`
- `scripts/local-dev.env.example`
- `deploy/linux/env/smart-wallet.env`

这些文件的定位应该是“模板”或“部署样例”，不应该作为真实生产密钥的最终保存位置。实际落地时应当：

- 使用未提交 git 的本地 env 文件。
- 或使用服务器私有 env 文件。
- 严格限制文件权限。

---

## 3. 新建一个私钥参与跟单策略的推荐流程

下面是建议直接执行的流程。

### Step 1. 创建一个新的执行钱包

建议创建一个全新的 Polygon EOA 钱包，专门用于 Polymarket 跟单。

最低要求：

- 拿到钱包地址。
- 拿到对应私钥。
- 明确这个钱包是否需要单独的 `funderAddress`。

实操建议：

- 如果没有特殊账户结构，`funderAddress` 通常可以先留空，或与执行地址保持一致。
- 不要使用已有大额主钱包。
- 创建后先做一笔小额资金验证。

### Step 2. 为执行钱包准备链上资金

当前策略跑 LIVE 之前，至少需要准备：

- Polygon 链上的 USDC：用于下单。
- Polygon 链上的少量 POL/MATIC：用于 gas。

建议：

- 先只充值一小笔测试金额。
- 在 PAPER、dry-run、状态校验都通过后，再增加资金。

### Step 3. 决定私钥保存方式

有两种实际可用方案。

方案 A：本地明文 env，适合临时调试

- `POLYMARKET_EXECUTION_PRIVATE_KEY=0x...`

方案 B：`ENC(...)` 加密 env，适合长期使用

1. 生成 RSA 密钥对：

```bash
bash scripts/generate-rsa-keys.sh
```

2. 设置 RSA 私钥路径：

```bash
export APP_RSA_PRIVATE_KEY_PATH="/你的路径/app-private.pem"
```

3. 生成私钥密文：

```bash
APP_RSA_PRIVATE_KEY_PATH="/你的路径/app-private.pem" bash scripts/encrypt-secret.sh
```

然后把输出的 `ENC(...)` 写入：

- `POLYMARKET_EXECUTION_PRIVATE_KEY`

如果想把 `POLYMARKET_CLOB_API_SECRET` 也做加密，也走同一套方式。

### Step 4. 配置执行钱包环境变量

可以参考：

- `scripts/polymarket-env.example.sh`
- `scripts/local-dev.env.example`

最小必需配置：

```bash
export POLYMARKET_EXECUTION_WALLET_ADDRESS="0x你的执行钱包地址"
export POLYMARKET_EXECUTION_PRIVATE_KEY="ENC(...) 或明文私钥"
export POLYMARKET_EXECUTION_FUNDER_ADDRESS=""
```

推荐补充：

```bash
export POLYMARKET_CLOB_API_KEY=""
export POLYMARKET_CLOB_API_SECRET=""
export POLYMARKET_CLOB_API_PASSPHRASE=""
```

说明：

- 如果这三项留空，只要 `derive-api-creds-if-missing=true`，系统会尝试自动派生。
- 如果你已经提前拿到了三元组，也可以直接填进去。

### Step 5. 启动服务并检查执行钱包状态

先不要急着开自动 LIVE。

先调用状态接口：

```bash
curl -s http://127.0.0.1:8081/smart-wallet/execution-wallet/status
```

你应该重点看三件事：

- `configured` 是否为 `true`
- `liveReady` 是否为 `true`
- notes 中是否提示缺少 API creds、缺少 Python bridge 或钱包未配置

补充检查接口：

```bash
curl -s -X POST http://127.0.0.1:8081/smart-wallet/execution-wallet/derive-api-creds
```

如果这里能成功返回 `apiKey/apiSecret/apiPassphrase`，说明“私钥 -> CLOB API 凭证”的链路是通的。

### Step 6. 先做 dry-run，不直接真实下单

当前控制器已经提供预演接口：

- `POST /smart-wallet/clob/dry-run`
- `POST /smart-wallet/follow-tasks/{followTaskId}/dry-run`

建议顺序：

1. 先看 `execution-wallet/status`
2. 再做 `derive-api-creds`
3. 再做 `dry-run`
4. 最后才做 `execute-live`

这样可以把“配置错误”和“真实资金风险”拆开。

### Step 7. 保持 `auto-execution-enabled=false`，先手动小额验证

当前 `application.yml` 里：

```yaml
smart-wallet:
  auto-execution-enabled: false
```

这很合理，建议继续保持这个默认值，先做：

- 手工选单
- 小额 stake
- 单次 LIVE 验证

确认以下都正常后，再考虑自动化：

- 订单能正常签名和提交
- 持仓/成交能回写
- 结算与 claim 正常
- 账务归因没有偏差

### Step 8. 最后再决定是否开启自动 LIVE

只有在前面都稳定后，才考虑打开：

- `smart-wallet.execution-wallet.enabled=true`
- `smart-wallet.auto-execution-enabled=true`

这两个开关含义不同：

- `execution-wallet.enabled=true`：执行钱包能力可用。
- `auto-execution-enabled=true`：策略允许自动进入 LIVE 执行。

建议不要一次把两个开关和真实资金同时放开。

---

## 4. 推荐的上线顺序

建议团队按这个顺序推进：

1. 创建专用执行钱包。
2. 只充值小额 USDC 和 gas。
3. 把私钥通过 env 注入，优先用 `ENC(...)`。
4. 启动服务。
5. 调 `GET /smart-wallet/execution-wallet/status`。
6. 调 `POST /smart-wallet/execution-wallet/derive-api-creds`。
7. 调 dry-run 接口。
8. 手工触发一笔小额 LIVE。
9. 观察订单、成交、持仓、结算、claim。
10. 通过后再考虑开启自动执行。

---

## 5. 建议补充的运维规范

虽然当前代码已经支持基础安全措施，但实际落地时建议再加几条规范。

### 5.1 私钥轮换

如果执行钱包泄露或怀疑泄露，处理顺序建议是：

1. 立即停止 LIVE 执行。
2. 将资金转出旧钱包。
3. 创建新钱包。
4. 替换 `POLYMARKET_EXECUTION_WALLET_ADDRESS`
5. 替换 `POLYMARKET_EXECUTION_PRIVATE_KEY`
6. 重新派生新的 CLOB API 凭证。

### 5.2 权限控制

至少要控制：

- env 文件权限
- RSA 私钥文件权限
- 部署机登录权限
- 应用日志与诊断导出权限

### 5.3 不要把私钥写进数据库、代码常量或排障截图

当前代码已经没有这样做，但排障流程里也要继续坚持：

- 不在 issue/飞书/IM 中贴明文私钥
- 不在截图中暴露 env
- 不在临时脚本里硬编码私钥

---

## 6. 一句话结论

当前 Polymarket 跟单策略对私钥的管理方式是：

- 用环境变量注入执行钱包地址和私钥；
- 支持 `ENC(...)` 加密后在启动时解密；
- 运行时由执行钱包私钥派生/使用 CLOB API 凭证并签名订单；
- 状态接口不会回显私钥；
- 推荐用“专用小额执行钱包 + `ENC(...)` + 先 dry-run 再小额 LIVE”的方式上线。

如果后续要继续演进，我建议下一步补两件事：

1. 增加一个“私钥已配置但不回显”的后台管理说明页。
2. 把执行钱包模板文件进一步去敏，并明确区分“示例模板”和“真实部署文件”。
