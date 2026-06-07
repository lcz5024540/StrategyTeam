# 方案：用 Beta 后验 / Wilson 下界替代点估计胜率（2026-06-05）

> 目标：把 leader 胜率从「点估计 `wins/(wins+losses)`」升级为**带样本量惩罚的可信估计**（Wilson 下界用于门槛、Beta 后验均值用于排序），治小样本不稳与「选过去赢家」的幸存者偏差。
> 结论先行（放到哪个环节）：**这是 leader 质量闭环（选池/资格评估）层面的改造，不在每信号 hot path（S3 风控门）里**。三个落点——①**计算**：`SmartWalletProfileTruthRefreshService`；②**存储**：`smart_wallet_profile` 加列；③**消费**：资格门 `evaluateQualification` + 自动选池 `WeeklyTopLeaderAutoAllowlistJob`（及近端冷却门）。

---

## 1. 接入环节（精确到 file:line）

| 环节 | 位置 | 现状 | 改造 |
|---|---|---|---|
| **① 计算** | `SmartWalletProfileTruthRefreshService` `Stats.winRate()` (≈L450) 已用 `wins/(wins+losses)` 剔除平局 | 只算点估计 | 同处用 (wins,losses) 再算 **Wilson 下界** 与 **Beta 后验均值** |
| **① 计算(近端)** | 同类 `recentWinRate7d/30d`（≈L311 注释处） | 点估计、n 更小 | 近端窗口同样产出可信值（小样本惩罚收益更大） |
| **② 存储** | `SmartWalletProfileEntity`（现有 `winRate`/`totalTrades`/`recentWinRate7d/30d`，**无 wins/losses**） | 无可信字段 | 加 `decisive_trades`、`credible_win_rate`（Wilson LB）、`shrunk_win_rate`（Beta 均值）等列 |
| **③ 消费·资格门** | `SmartWalletProfileStoreService.evaluateQualification` L912-920：`winRate ≥ minWinRate`（含滞回 buffer） | 比点估计 | 比 **Wilson 下界**（fail-safe：小样本自动不达标） |
| **③ 消费·选池** | `WeeklyTopLeaderAutoAllowlistJob` L112-135：过滤 `winRate≥min` + 按 winRate 排序 TOP-N | 点估计排序→幸存者偏差 | 过滤+排序改用 **Beta 后验均值**（收缩），治小样本暴富 |
| **③ 消费·冷却(可选)** | 近端冷却门 `recentWinRate7d<0.45` | 点估计 | 用近端可信值，减少小样本误冷却 |

> 关键：**hot path 的开单门（G1~G17）不直接改**——它们读的是 profile 上已算好的胜率字段；我们只把"写进 profile 的那个数"换成可信估计，消费侧自然继承。改造面小、风险可控。

---

## 2. 数学方案

设某 leader 在某窗口的**决断样本** n = wins + losses（已剔除 breakeven 平局），点估计 p̂ = wins/n。

### 2.1 Wilson 得分区间下界（用于「过/不过」门槛）
```
            p̂ + z²/(2n) − z·√( p̂(1−p̂)/n + z²/(4n²) )
wilsonLB = ───────────────────────────────────────────────
                         1 + z²/n
```
- z = 置信系数（建议**单侧 95% → z=1.645**，或保守用 1.96）。
- 性质：n 越小、下界越低（自动惩罚小样本）；n→∞ 时 → p̂。
- 用途：**资格门 / 选池过滤的门槛比较**（保守，宁可漏选不可错选）。

### 2.2 Beta-Bernoulli 后验均值（用于「排序」与收缩）
```
后验 = Beta(α₀ + wins, β₀ + losses)
后验均值 = (α₀ + wins) / (α₀ + β₀ + n)
```
- 先验 Beta(α₀,β₀) 编码**基准率**：建议弱信息先验，向**同期 leader 群体均胜率 p₀** 收缩，伪计数 α₀+β₀ = m（建议 m=5~10，即"先验相当于 m 笔")，α₀ = m·p₀、β₀ = m·(1−p₀)。
- 性质：小样本向 p₀ 收缩，大样本逼近 p̂。
- 用途：**TOP-N 排序键**（比点估计更稳，避免 1 胜 0 负=100% 被排到最前）。

### 2.3 worked example（直观感受小样本惩罚）
| 战绩 | 点估计 p̂ | Wilson LB(95%双侧) | Beta 均值(先验 Beta(2,2)) |
|---|---|---|---|
| 7 胜 / 3 负 (n=10) | 0.70 | ≈ **0.40** | (2+7)/(4+10)= **0.643** |
| 70 胜 / 30 负 (n=100) | 0.70 | ≈ **0.60** | (2+70)/(4+100)= **0.692** |
| 1 胜 / 0 负 (n=1) | 1.00 | ≈ **0.21** | (2+1)/(4+1)= **0.60** |

⇒ 同样 70% 胜率，n=10 与 n=100 在门槛/排序上被明确区分；1胜0负的"假高手"被压到 0.21/0.60，不再霸榜。

---

## 3. 实现形态（落地时）

- **纯函数工具类** `WinRateStatistics`（common-service，无状态）：
  - `wilsonLowerBound(long wins, long losses, double z)`
  - `betaPosteriorMean(long wins, long losses, double priorStrength, double priorMean)`
  - 便于单测（用 §2.3 的已知值断言）、各策略复用。
- **计算接入**：`ProfileTruthRefreshService` 在已有 wins/losses 处调用，写入新字段。
- **存储**：`smart_wallet_profile` 加列 + 一次性 backfill（重算历史）；或先只加 `decisive_trades`，可信值在读取时按需算（省 schema 改动，但消费点要拿到 wins/losses → 仍需存 wins/losses 或 decisive_n+winRate 反推 wins=round(winRate·n)）。**推荐直接存 `credible_win_rate`/`shrunk_win_rate`/`decisive_trades` 三列**，消费侧零计算。
- **消费切换**：两处比较/排序改用新字段。

---

## 4. 配置与安全回滚

| 配置 | 默认 | 说明 |
|---|---|---|
| `enable-credible-win-rate` | **false** | true→门槛/选池用 Wilson LB / Beta 均值；false→沿用点估计（零行为变化） |
| `win-rate-wilson-z` | 1.645 | 单侧 95%；越大越保守 |
| `win-rate-prior-strength` (m) | 8 | Beta 伪计数；越大收缩越强 |
| `win-rate-prior-mean` (p₀) | 群体均值或 0.5 | 先验中心 |
| `win-rate-min-decisive-trades` | 10 | 决断样本不足则不视为达标（与 LB 互补） |

**注意（重要）**：Wilson LB ≤ 点估计，**直接启用会让现有 `minWinRate=0.62` 门槛突然变严**、大量 leader 掉出资格。建议：
1. 先**影子对照**：新字段照算照存，但门槛仍用旧值，记录"若用 LB 有多少 leader 会翻不达标"。
2. 据影子分布**重标 `minWinRate`**（用 LB 口径时门槛通常要下调，如 0.62→0.55）。
3. 再切 `enable-credible-win-rate=true`。
- 任意异常切回 false 即热回滚，无需发版。

---

## 5. 为什么这是最该先做的一项

- **独立**：只改 leader 画像的"那一个数"，不碰开单/执行/结算 hot path，风险最低。
- **可纯函数实现 + 单测**：Wilson/Beta 是确定性公式，可像前面 8 项一样抽 helper + JDK 验证。
- **直接改善 alpha 质量**：根治"小样本高胜率被误选"（审计 R13 幸存者偏差），且为后续"校准真 P(win)"（模型分析报告的第 2 项）提供可信的胜率输入——是整个概率链路修复的第一块基石。

---

## 6. 落地步骤建议（最小风险路径）

1. 写 `WinRateStatistics` 纯函数 + 单测（Wilson/Beta，用 §2.3 断言）。
2. `ProfileTruthRefreshService` 接入计算，profile 加列 + backfill 重算。
3. **影子期**：照算照存、门槛仍旧；产出"翻盘 leader 清单"与 LB 分布。
4. 据影子重标 `minWinRate`，再开 `enable-credible-win-rate`。
5. 选池排序键切 Beta 后验均值。

> 本文为方案设计，未改代码。要落地的话我建议从第 1 步（纯函数 `WinRateStatistics` + 单测）开始——独立、可验证、零生产影响。
