# PRIMARY apiKey 读取方式（给开发接入）

- 日期：2026-10-12
- 作者：策略运维bot
- 用途：LIVE 1 张连通性探针 / 后续 Python 交易路径；**只描述读法，不含密钥明文**
- 约束（产品/用户已定）：只解 `PRIMARY`；**禁止** env / `htx.env` 回退；**禁止**读、接、巡检非 PRIMARY（含 MIRROR / 他人账号）

## 1. 数据位置

| 项 | 值 |
|---|---|
| MySQL 库 | `htx_trade_strategy` |
| 表 | `htx_trade_account` |
| 主机 | 本机 `127.0.0.1:3306`（Java 的 `HTX_DB_HOST` 现写公网 IP，同机亦可连） |
| 必选过滤 | `account_role = 'PRIMARY'` **且** `enabled = 1` **且** `trend_enabled = 1` |
| 现网 PRIMARY 行（元数据） | `id=1`, `account_label=primary`, `exchange=HTX`, `market_type=USDT_SWAP`, `base_url=https://api.hbdm.com` |

查询示例（只取密文列，不要 `SELECT *` 打日志）：

```sql
SELECT id, account_label, exchange, market_type, base_url,
       api_key_cipher, secret_key_cipher
FROM htx_trade_strategy.htx_trade_account
WHERE account_role = 'PRIMARY'
  AND enabled = 1
  AND trend_enabled = 1
LIMIT 1;
```

- 0 行 / 多于 1 行 / `account_role` 校验失败 → **硬退出**
- 读到任何非 `PRIMARY` 行 → **禁止使用**（不要读 cipher，不要解密）

## 2. 密文格式

- 列：`api_key_cipher`、`secret_key_cipher`（现网 PRIMARY 各约 349 字符）
- 包装：`ENC(<base64>)`，前缀 `ENC(`、后缀 `)`
- 中间为标准 Base64；解码后是 RSA-OAEP 密文

## 3. 解密依赖（与 Java `HtxSecretCrypto` 一致）

| 项 | 值 |
|---|---|
| 私钥路径 | `/opt/coinstrategy/app-private.pem` |
| 环境变量名（仅路径，不是 key） | `APP_RSA_PRIVATE_KEY_PATH` |
| 权限 | `600`，属主 `ubuntu:ubuntu`；服务用户须为 `ubuntu`（或等价可读） |
| 私钥格式 | PEM → 去头尾 → Base64 → `PKCS8` → RSA 私钥 |
| 算法 | `RSA/ECB/OAEPPadding` |
| OAEP | digest=`SHA-256`，MGF=`MGF1`，MGF digest=`SHA-256`，`PSource=PSpecified.DEFAULT` |
| 明文编码 | UTF-8 |

步骤：

1. 确认值 `startsWith("ENC(")` 且 `endsWith(")")`，否则拒收
2. 取中间 Base64 → decode
3. 用上述 OAEP 参数 + 私钥 `Cipher.DECRYPT_MODE` → UTF-8 字符串，即 apiKey / secretKey
4. 解密失败 → **硬退出**（不要回退 env）

参考实现（Java 已有，不要改服务器上的 jar）：`com.metaverse.coin.strategy.htx.config.HtxSecretCrypto` / `HtxSecretDecryptor`。

## 4. 库连接怎么拿（运维侧）

- Java 现用 `EnvironmentFile=/opt/coinstrategy/htx-trade/env/htx.env` 里的 `HTX_DB_HOST` / `HTX_DB_PORT` / `HTX_DB_USERNAME` / `HTX_DB_PASSWORD` 连库。
- Python 探针/交易 unit：**可以**在包外 env 里配置**只读库连接** + `APP_RSA_PRIVATE_KEY_PATH`。
- **禁止**写入或读取：`HTX_ACCESS_KEY`、`HTX_SECRET_KEY`、`HTX_TREND_MIRROR_*` 及任何交易所明文 key。

## 5. 运维边界

- 运维只提供本说明与权限核验；**不替开发写接入代码**。
- 巡检、部署、取证：**不碰**非 PRIMARY 账号行与 mirror 相关 env。
- 1 张探针执行前仍须产品再说「可以按按钮」。
