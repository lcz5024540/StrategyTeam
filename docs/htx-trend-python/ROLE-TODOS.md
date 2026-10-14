# HTX Trend Python — 各角色待办（唯一共享文档）

> **用户要求：各角色只写本文件对应章节，禁止再建角色专档。**
> 仓内落点（合入 `support_htx_python` 后）：`docs/htx-trend-python/ROLE-TODOS.md`
> 草稿工作区：`/workspace/reports/docs/htx-trend-python/ROLE-TODOS.md`
> 运维原文已并入【策略运维】；`/workspace/reports/0.1.1-live-formal/ops-todos-support_htx_python.md` 仅作历史，以本文件为准。

## 【策略产品】

更新：2026-10-12 23:01 CST · 策略产品bot  
**唯一共享文档**：本文件。看板仍：`/workspace/reports/project-board.md`；风险单：`/workspace/reports/risk-approval-formal-auto-live-v0.1.md`。

### 正式 LIVE 验收口径（摘要）
| 项 | 口径 |
|---|---|
| 路径 | B：跳过 PAPER → 正式自动 LIVE |
| 资金/硬限 | 300U；≤5 仓；单笔 2%（≤6U）；总敞口 10%（≤30U）；同向 ≤3 |
| 杠杆 | `HTX_TREND_LEVERAGE=10` |
| 账户 | PRIMARY only；库表密文+RSA；禁 env 密钥；TradeX/mirror **不进 LIVE** |
| 品种 | BTC+ETH 4h；`COMBINATION_ENTRY=true` |
| 当前包 | `b5af1219`（hb HEALTHY）；回滚 `05395708` |
| T1 | **自然信号**开平证据 ONLY（禁探针）；现 **待信号** |
| C1 | T1 后一次手动停开仓（ENTRY=false+restart） |
| R1–R4 | 包级 Pass；真盘拒单待排期 |
| 统计 | **门禁未锁（§8）**；开跑≠统计过闸 |

按钮：「可以启」「可以热更」仅测试 Pass + 产品核 SHA/硬限后签发。禁临时关 ADAPTIVE 绕过。失败即停：解密/非 PRIMARY/超限/平仓失败/对账差/数据过期/回撤>10%/连亏8/鉴权失败/证据缺字段。

### P0 待办
- [ ] T1：收自然开平证据后关门禁
- [ ] C1：T1 后授演练并收证据
- [ ] R1–R4 真盘拒单排期与签发
- [ ] 风险单 7 天有效期跟踪（生效 ~21:44 CST）
- [ ] 维护看板（包/hb/T1）

### 条件 / 消融（v0.1.x）
- C01 Donchian、C02 ATR 止损：**LIVE 在用**；C04/C05：候选，以包配置为准；过滤类 R0003 起：**未上 LIVE**
- 退出引擎插件化；优先级：熔断>止损>跟踪>反向>时间；变更须新包+测试 Pass+「可以热更」
- [ ] 维护 LIVE 默认开关与研究集差异说明
- [ ] 插件消融入库后才批 LIVE 开关变更

### R0001 / R0002 决策点
- [ ] R0001 行研双过后：是否锁统计门禁、是否关 G2
- [ ] R0002（10 币已定）扩跑前：批强平距离/最小张数/相关敞口/集中度；经 BrainBot 问启动时机
- [ ] 持续声明：LIVE ≠ 统计过闸

### PAPER / 扩币
- [ ] 是否回补 ≥14 天 PAPER、窗口与是否阻断扩权
- [ ] 市值前 100 / 历史时点选币：**暂缓**；重启须立项
- [ ] 1h 线：4h R0001 后启动，分账不混试验次数

### 合流
- [ ] PR#23 干净合入 `support_htx_python` 后，确认**仅迁本文件**；抽查无 master 脏合


## 【策略开发】

主维护：策略开发bot · 更新 2026-10-12 23:00 CST

### 合入主线
- [ ] 收窄 PR#23 → 干净合入 `support_htx_python`（勿合脏 master 体量）
- [ ] LIVE 热修线干净落盘：`982e5867` → `05395708`（from_env）→ `b5af1219`（breadth `%%`）
- [ ] 合入 tip SHA / 文档路径回报 BrainBot

### 包与交付口径
- [x] 正式 LIVE 包 tar+SHA256 交运维（服务器不 git pull）
- [x] from_env 热修 `0.1.1-live-05395708` SHA256 `ed3d7dd3…`
- [x] breadth PyMySQL 热修 `0.1.1-live-b5af1219` SHA256 `93e3411e77b5c6918073da7fefd073135c2fe0aeea6f9719ff6dee45a0d1568e`
- [ ] `env.example` 补 `HTX_TREND_COMBINATION_ENTRY_ENABLED`（硬门）说明
- [ ] 技术说明（架构/热修链）**写入本文件**【策略开发】节，勿另建 runbook 文件

### 技术债 / 后续
- [ ] journal 失败待命（T1 期间）
- [ ] R0002 统计门禁未锁：等测试/行研
- [ ] DSR/PBO、告警、双审、PAPER、消融（delayed）


## 【策略测试】

主维护：策略测试bot · 更新 2026-10-12 23:00 CST  
验收主清单（运行态）：`/workspace/reports/formal-auto-live-acceptance.md`  
当前正式状态：**T1 待信号**（`b5af1219`；hb HEALTHY；禁探针）

### 正式 LIVE 验收
- [ ] **T1**：自然 4h 信号路径开→持仓→平；禁探针/强制试单；首笔 fill 后补订单/持仓/风控证据
- [ ] **C1**（排 T1 开平后）：一次手动停开仓（`HTX_TREND_ENTRY_ENABLED=false` + restart；无 ExecReload）；确认停开+告警可取回
- [ ] **R1–R4 真盘拒单**（待产品排期）：超仓(>5)/超敞口(>10%)/单笔风险(>2%)/同向(>3)；包级已 Pass
- [ ] 探针后回归：正式包无 `LIVE_1CONTRACT_PROBE`；numeric `client_order_id`；PRIMARY-only / 无 env 密钥回退；探针结果不勾选正式 T1/C1

### 热修回归（合入 / 每正式包必过）
- [x] from_env（05395708）：`defaults=cls()`；保留 `test_config_from_env`
- [x] breadth `%%`（b5af1219）：包内 `LIKE '%%trend=`；专项单测；热更后 hb **HEALTHY**
- [ ] 回归门禁：每 LIVE 包级验含 from_env + breadth/`%%` + formal 风控套件；热更后独立重核 hb 非 FAILED
- [ ] ACTIVE 但 hb FAILED → 不得标可交易就绪；T1 阻塞至热修

### 风控边界用例
- [x] 包级硬限制：5 仓 / 2%(≤6U) / 10%(≤30U) / 同向≤3（formal 单测；口径 300U）
- [ ] 合入后干净环境复跑 formal 风控相关 pytest 并归档
- [ ] 真盘逼近上限拒单证据（待排期）

### 统计口径 / 置换（deferred）
- [ ] **deferred** rc.3 permutation 工程可用性核验；PF bootstrap 10000×；R0002 起 permutation+bootstrap CLI 前置
- [ ] 话术：LIVE 可交易就绪 ≠ R0001/R0002 统计过闸（门禁未锁）

### 合入 `support_htx_python` 动作
1. 确认主干含 from_env + breadth `%%`（或等价）
2. 干净 venv 跑 formal/worker/mysql_store/config_from_env/live_entry_parity → 归档
3. 更新本段勾选；缺口写明（T1/C1 真盘未过）
4. 线上新 tar：包级验 → 产品「可以热更」→ 运维热更 → 测试重核 hb → 维持 T1 待信号（服务器不拉仓库）

## 【策略运维】

更新：2026-10-14 · 策略运维bot
原则：服务器**不拉仓库**；发版只装 tar+SHA；合 PR **勿动线上包**。SSH：box `/home/box/.ssh/id_ed25519_htx_ops` → `ubuntu@49.51.250.174`。  
**权威落点**：本共享文档本节；勿再维护单独运维档（原 `ops-todos-support_htx_python.md` 仅作素材、已废止）。

### 热修要点归档

- Env：在 `htx-python.env` 显式设置 `HTX_TREND_LEVERAGE=10`；`from_env` 按 int 读取，未设置时 dataclass 默认值仍为 5。breadth PyMySQL 热修无需新增 env 键（`LIKE` 的 `%%` 说明见本节第 4 小节）。
- 启动：`from_env` slots 修复后，未设置 `HTX_TREND_*` 冷却 MS 变量应不再导致启动崩溃；预期 LIVE 杠杆仍须按上条显式设置。
- 紧急回滚：breadth `%%` 热修线的 `0.1.1-live-05395708` 包由 commit `05395708e496e490aa2c0f5a03bf0e94c43214e7` 构建；该基线仍有 breadth `%` 崩溃问题，仅供紧急回滚。

### 1. 当前正式 LIVE 运行态（基线）

| 项 | 值 |
|---|---|
| 包 / current | `0.1.1-live-b5af1219` |
| commit | `b5af1219426221b8d02968f599ad624beab87f9b` |
| SHA256 | `93e3411e77b5c6918073da7fefd073135c2fe0aeea6f9719ff6dee45a0d1568e` |
| Unit | `htx-trend-worker@htx-trend-live-primary` · **active/enabled**（热更 22:50:18 CST） |
| instance_key | `htx-trend-live-primary` · LIVE · PRIMARY `account_id=1` |
| TradeX id=2 | `mirror_enabled=0` · `trend_enabled=0`（保持关） |
| 杠杆 | `HTX_TREND_LEVERAGE=10` |
| 组合入场 | `HTX_TREND_COMBINATION_ENTRY_ENABLED=true` |
| 其它开关 | ENABLED=true · ENTRY=true · OBSERVE=false · EXIT_PARITY=true |
| 风险帽 | max_pos=5 · risk/trade=6 · total=30 · same_dir≤3 |
| admission | **V1 无需**；勿造 admission json |
| 路径 | app `/opt/coinstrategy/htx-trend-py` · env `/opt/coinstrategy/htx-trend-py-state/htx-python.env` |
| 回滚点 | `0.1.1-live-05395708`（仍有 breadth `%` 崩，仅紧急）/ 再前 `982e5867` |

证据：启机 `formal-live-enable-05395708-20261012/retry-combination/` · 热更 `formal-live-hotfix-b5af1219-20261012/` · T1 只读 `formal-live-t1c1-05395708-20261012/` · 冻结 `/workspace/frozen/0.1.1-live-b5af1219/`

### 2. T1 自然信号等待与值班检查项

状态：**T1 待信号**（禁探针 / 禁强制试单）。

- [ ] `systemctl is-active htx-trend-worker@htx-trend-live-primary` == active
- [ ] 最近 heartbeat **HEALTHY**（无 FAILED / `unsupported format character`）
- [ ] 自热更以来是否有 OPEN_FILL / 新持仓 → 有则立刻喊测试 + BrainBot
- [ ] TradeX id=2 mirror/trend 仍为 0
- [ ] 无异常下单、无重启风暴

例程：`T1首笔fill盯梢`（每小时 :11/:41）。C1 在 T1 开平后：优先 `HTX_TREND_ENTRY_ENABLED=false` + **restart**（无 ExecReload）；需产品/用户另授；尽量不 stop unit。

### 3. tar 热更流程（非 git pull）与回滚

**正向**
1. 开发交 tar + SHA256 → 约定目录（如 `/workspace/reports/0.1.1-live-hotfix-<sha>/`）
2. 运维核 `sha256sum` MATCH → 通知测试包级验收
3. 测试 Pass + 产品「可以热更」/「可以启」
4. 用户运维私聊确认（启机 / 改交易开关 / 改杠杆等）
5. 备份 env；解压到 `releases/<version>/`；原子切 `current`
6. `systemctl restart htx-trend-worker@htx-trend-live-primary`（热更通常**勿 disable**）
7. 核 current/commit、hb HEALTHY、写证据；可选 `/workspace/frozen/<version>/`
8. 喊测试重核 hb / 继续 T1

**禁止**：服务器 `git pull`；未核 SHA；产品按钮 + 用户确认前改交易行为；合 PR 自动改线上包。

**回滚**：`current` → 上一已知好 release → `systemctl restart …@htx-trend-live-primary` → 核 hb → 报产品/开发/BrainBot。

### 4. 心跳 / SQL-% / HEALTHY 监控与告警

已知根因（已热修 b5af1219）：`latest_breadth_stats` 中 `LIKE '%trend=…%'` 未写成 `%%` → PyMySQL 格式化炸 → 每 tick FAILED → **入场未跑通**。

- [ ] heartbeat 连续 HEALTHY（id 递增且 status≠FAILED）
- [ ] journal 无 `unsupported format character` / `ValueError` 循环
- [ ] active 但 hb FAILED → **视为不可交易就绪**，立刻甩开发+产品（是否 failsafe 听产品）
- [ ] 4h 巡检含 hb 摘要（待把 box 密钥写入巡检例程）

告警：BrainBot → 用户；并行产品/开发/测试（视严重度）。

### 5. 小侠抓取

**已暂停**。待用户经 **BrainBot** 再启；运维不擅自恢复。

### 6. 其它运维 backlog（非阻断）

- [ ] 用户改服务器密码 + 腾讯云安全组收紧
- [ ] 4:00/8:00 事后比对 + 冒烟补齐
- [ ] 完整取证窗口（重拉/资金费/kill 续采/停服 health）
- [ ] 4 小时巡检例程改为 box 运维密钥
- [ ] 第二服务器 `43.139.186.27`（仅密码）纳入监控

### 7. 角色接口（运维视角）

| 对象 | 何时找 |
|---|---|
| 策略开发bot | 新 tar+SHA、崩栈、热修 |
| 策略测试bot | 包级验、T1/C1、hb 重核、fill |
| 策略产品bot | 「可以启」「可以热更」、改 env 产品项 |
| BrainBot | 对用户汇报/决策；密钥/权限等安全敏感可运维私聊用户 |
| 行情分析bot | 取证拼接明细 |


## 【行研】

负责人：行研bot · 决策/报告一律经 BrainBot  
原则：**统计门禁线 ≠ 工程 LIVE 线**；门禁未锁前不得宣称「统计过闸」。

### 1. R0001（BTC/ETH · 4h）后续研究与复盘
- [ ] **流程结案归档**：确认 R0001 流程达标材料齐套（四字段 diff、stats-review、product-confirm）；路径以 `/workspace/reports/R0001/` 为准。
- [ ] **数值复盘（非放行）**：整理验证集权益/PF/分品种成交与跳过统计，仅作基线记录；**不**用单次 PF/权益替代置换或 bootstrap。
- [ ] **rc.3 工程可用性双认**：等测试用 `016b10af` 复跑 permutation（1000×, seed=99）后，核对 `null_n_samples==1000`、分品种匹配、200 硬失败、同种子可复现；出具「工程可用性 Pass/Fail」短裁定（**仍不锁统计门禁**）。
- [ ] **本源 run 门禁状态备忘**：rc.2 joint 已见 bootstrap `ci_lower`≪0.8 → 本源数值门禁未过；复盘报告须显式写出，避免与 LIVE 开跑混淆。
- [ ] **R0001 → R0002 交接清单**：冻结 dataset / manifest / 外置配置约定、成本口径、仓位上限差异（R0001 最多 2 仓 vs R0002 最多 5 / 同向 ≤3）写入复盘附录。

### 2. R0002（10 币 · 4h）数据 / 统计推进与 deferred
币池：BTC、ETH、SOL、XRP、DOGE、ADA、SUI、LTC、PEPE、SHIB（固定名单；幸存者偏差须在报告中写明）。

- [ ] **预注册完备性**：核对 `prereg-batch1.md` 中 R0002 假设 / 保留 / 放弃 / 笔数门槛（全池 ≥200；币种组与市场状态各组 ≥30）与 phase1 §11 一致后再允许正式跑。
- [ ] **数据冻结配合**：与行情分析bot 对齐 10 币 4h 冻结（须含 funding/spec）；行研做外置配置 4 字段 diff 与 `strategy_manifest_sha256` 独立重算。
- [ ] **样本量与切片计划**：确认币种组（核心 BTC/ETH vs 山寨 8）与市场状态（趋势/过渡/震荡）各自切、不交叉；交叉格子只报告。
- [ ] **集中度规则落地检查**：单币净收益 ≤20%；去掉净收益最高 1 币后 PF 仍 ≥1.1。
- [ ] **Deferred（R0002 正式验收前 P0，开发交付 · 行研验收口径）**
  - [ ] `permutation` CLI：1000×；分品种匹配；主指标=保守成本后每笔平均净收益；p&lt;0.05；种子入账本；defer-until-flat（rc.3）口径。
  - [ ] `pf-bootstrap` CLI：10000×；分位数法；总体与切片 90% CI；切片下界 ≥0.8；种子入账本。
  - [ ] 同数据同种子字节级可复现（允许 `recorded_at` 异）。
- [ ] **正式验收裁定**：CLI 联调通过后，对验证集出具是否满足：总体 PF≥1.3、保守净期望&gt;0、置换 p&lt;0.05、切片/集中度；**CLI 落地前不得判 R0002 统计达标**。
- [ ] **风控前置只读核对**：确认产品「有条件通过」条款（同向 ≤3、SOL 1 张风险&gt;6U 跳过并报告比例等）已写入跑批配置说明。

### 3. 统计口径落地检查（通用 · 与 phase1 / prereg 对齐）
| 项 | 口径 | 行研检查点 |
|---|---|---|
| 置换 | 1000×，p&lt;0.05；分品种笔数+多空比；主指标=保守成本每笔净收益 | 账本字段、硬失败 200、不得用总笔数凑合 |
| PF bootstrap | 10000×，90% 分位；总体门槛 PF≥1.3；切片 CI 下界 ≥0.8 | 禁止点估计替代；种子入账 |
| 净期望 | 保守成本下 &gt;0（总体） | 成本假设与冻结一致 |
| 笔数 | R0001：BTC+ETH 合计≥30 / 切片≥10；R0002：全池≥200 / 切片≥30 | 不足→「待定」，不判通过也不算放弃 |
| 试验计数 | 置换/bootstrap/只报告切片 **不计**试验次数；消融/网格每种配置计 1 次 | 跑前核对 run_id 登记 |
| 门禁锁 | 双过（置换+bootstrap）且产品确认后才锁 | 工程 LIVE ≠ 统计过闸（§8） |

- [ ] 维护一页「口径速查」与本表同步；变更须产品确认后改 prereg/phase1，行研不私自改闸。
- [ ] 每次正式 run 后检查：种子、dataset_id、manifest_hash、成本档、杠杆统计值（`leverage_target`）与实盘 env 差异是否入报告脚注。

### 4. 扩至市值前 100 / 付费数据 — 评估节点
状态：**暂缓**（用户已定 R0002 用固定 10 币；前 100 与按历史时点选币暂缓）。

- [ ] **触发条件提案**（经 BrainBot 呈用户）：R0002 统计门禁首次锁定 **或** 用户主动重启范围讨论时，启动评估包。
- [ ] **评估包内容**：流动性门槛草案（HTX U 永续 30 日日均成交额 ≥1000 万 USDT）、历史时点选币方法、幸存者偏差披露模板、预计数据量/存储/回测时长。
- [ ] **付费数据节点**：对比现有免费/自建抓取 vs 付费 tick/资金费/深度；给出「不必买 / 可买 / 必须买」三分建议与年费量级（需行情/运维报价输入）；**不下单、不签约**，只出评估经 BrainBot。
- [ ] 评估结论写入同目录支撑文（如 `universe-expansion-eval.md`），并在本【行研】节勾「已评估」日期；**禁止**另建行研专档待办。

### 5. 策略条件清单 · 消融实验 — 行研配合
- [ ] **条件清单对齐**：与产品维护的 C01–C08（及后续）开启表核对；每个条件的「假设 / 保留 / 放弃」在 prereg 有对应 run 行；发现 PRD 旧表与 prereg 冲突时以 prereg 编号为准并提请产品改 PRD。
- [ ] **消融/网格登记门禁**：任何关掉条件、改参、网格 N 组，须先单独 `run_id`（写清 parent、改动、假设、保留/放弃）再跑；行研抽查未登记不得开跑。
- [ ] **消融设计协助**：为 R0003+（C04/C05/C06/C08…）预写最小消融矩阵（单条件开关 × 关键参敏感点），交产品裁剪后再开发实现。
- [ ] **多重试验披露**：累计试验次数、选择性报告风险在阶段报告中单列；DSR/PBO 仍标第 2 阶段启用（本阶段只预留字段）。
- [ ] **与测试交接**：消融跑批的统计验收复用 §3 口径；切片不足判「待定」模板统一。

### 6. 近期并行（非阻断统计主线）
- [ ] 正式 LIVE T1 自然信号期间：只读观察成交是否与预注册信号逻辑明显偏离（异常则记研究债，**不**据此改门禁）。
- [ ] 保持 `/workspace/reports/R0001/` 与后续 `R0002/` 裁定文可追溯；群内结论同步 BrainBot。

### 依赖摘要
| 依赖方 | 行研等待 |
|---|---|
| 策略开发bot | permutation / pf-bootstrap 可复现 CLI（R0002 P0） |
| 策略测试bot | rc.3 permutation 复跑结果；R0002 联调包 |
| 行情分析bot | 10 币冻结含 funding/spec；sha 复核 |
| 策略产品bot | 口径变更确认；条件清单/消融范围裁剪；门禁锁定签字 |
| BrainBot | 一切对用户决策与阶段报告 |

## 【行情分析】

主维护：行情分析bot · 更新 2026-10-12 23:00 CST  
职责：数据层（K 线 / 资金费 / 拼接 / bad_ticks / 市场状态字段 / 数据层评审）  
决策与对用户汇报：**一律经 BrainBot**  
权威落点：本文件本节；**禁止**再建行情专档。

### 0. 当前基线（R0001 已冻）

| 项 | 值 |
|---|---|
| dataset_id | `4hour_8024e8fc92d12d85` |
| manifest_hash | `sha256:589719d297e13c9374c6cc5d0a080caedaf00deaf154406d022042491fbb3802` |
| 包（数据冻） | `f0e7b49c` @ `0e54ec24`（rc.13；**不作废**） |
| kline_gaps | `gaps: []` · sha `d4fd7c6e49c9de733a361cac044110aa59909d3c6afafe4718649ab4b8f00161` |
| bad_ticks v1 | confirmed **11** · sha `09e1ed52105909f8c9810a94ecc0cfdfb667bc2f5072ca0419c108965a197a4b`（BTC 6 / ETH 5） |
| funding_gaps_hash | `sha256:08b52442…` |
| funding/spec | BTC/ETH 四文件已入 manifest（STATE 同源；旧 `da62bd85`/`2750bf97` **作废**） |
| §1.1 复算 | 14856 / external 1830 / nc 61 / gaps:[] · 报告 `v0.1.0-ops-review/v3/section11_recount.md` |
| 冻结目录 | `/workspace/frozen/v0.1.0/` |

产品门禁：`/workspace/reports/product-gate-funding-spec-v0.1.0.md`（已满足重冻路径）。  
话术：**数据冻结齐 ≠ 统计过闸 ≠ LIVE 可交易就绪**（与 §8 同）。

### 1. R0001 数据层收口 / 遗留复核

- [x] §1.1 正式复算 + 锁 `kline_gaps` sha
- [x] funding/spec STATE 同源对拍 + 重冻写入 manifest
- [x] quality flags 门禁脚本通过（`check_frozen_quality_flags.py`）
- [ ] **拼接重叠段人工复核**：运维交接 BTC/ETH 各 2 处训练集偏差（`overlap_deviation.json`）；出复核纪要，不改已锁 gaps 除非产品重开
- [ ] **收盘完整取证例行**：12:00 / 20:00 UTC 窗口重拉比对 + 资金费 8h 间隔抽检（已有 `collector-close-20261012-1200/2000.md`）；固化检查清单进运维交接
- [ ] 质量标记列契约：`non_comparable` / `external` / `bad_tick` 缺失或 NaN → **硬失败**（与开发门禁对齐；缺则开数据层 P1）
- [ ] 分界点备忘核对：验证集 `2024-01-25`、测试集 `2025-06-03`、截止 `2026-10-12 00:00 UTC`（只收已收盘 bar）

### 2. 包内数据层 P1（挡 R0002 冻结；随 v0.1.1 / `support_htx_python`）

以下为 rc.13 已确认、**正式 R0001 绕过、R0002 冻结前须关**（与开发/测试对齐，不私自改口径）：

- [ ] runtime **gaps hash** 写入 / 校验路径完整
- [ ] **funding-gaps** 可选路径与硬失败位置一致（禁止静默放过）
- [ ] False-fill / 布尔列独立性（禁止静默填 False 掩盖缺失）
- [ ] 文档与 CLI 关键路径对齐（含 `cli.py` 历史缺口位）
- [ ] 合入主干后：干净环境复跑数据层门禁 + 归档报告路径

### 3. R0002（10 币 · 4h）数据冻结准备

币池（产品已定）：BTC、ETH、SOL、XRP、DOGE、ADA、SUI、LTC、PEPE、SHIB。  
参考：`/workspace/reports/r0002-data-feasibility.md`（市值前 100 / 付费数据评估 **暂缓**，等用户经 BrainBot 再开）。

- [ ] **10 币 raw 完备性**：每币 K 线 + funding + spec；缺则列缺口表（含上线日 / 最早 bar）
- [ ] **同源冻结路径**：一律 STATE raw → freeze 写入 `funding_files`/`spec_files`；禁止事后拷 frozen
- [ ] 产出并锁：`kline_gaps.yaml` / `funding_gaps` / `bad_ticks`（新版本号；R0001 v1 不自动沿用）
- [ ] §1.1 类复算：expected-rows / external / non_comparable / bad_tick 分品种清单 + sha
- [ ] 与行研对齐：外置配置 4 字段 diff 所需 `dataset_id` / `manifest_hash` / 质量哈希
- [ ] 幸存者偏差披露素材：已下架合约是否可补、币安代理字段 `source`（若启用）
- [ ] **启动门槛**：§2 包内 P1 关完 + 产品经 BrainBot 放行后才开正式冻

### 4. LIVE / 取证配合（非主责，接口义务）

正式 LIVE（`b5af1219` / T1 待信号）**不归**行情主审；仅在以下场景介入：

- [ ] 运维交接 **拼接 / 收盘取证明细** 时复核 OHLC / funding 间隔 / 异常 bar
- [ ] 若 live collector 出现系统性缺口或坏点：出 bad_tick / gaps 候选草案 → 产品批 → 再锁 sha（不热改已冻 R0001）
- [ ] 杠杆 live=10 vs 统计 `leverage_target=5` 等差异：**只记入数据/报告脚注素材**，不改统计门禁（归行研 §8）

### 5. 合入 `support_htx_python` 后动作

1. 确认主干含数据层门禁修复（§2）与文档路径 `docs/htx-trend-python/`
2. 本【行情分析】节与仓内文件同步；更新勾选与冻结路径
3. 抽查：无角色专档；看板 `/workspace/reports/project-board.md` 数据层状态可追溯
4. 新数据包仍只经 tar+SHA 交接运维（服务器不拉仓库）

### 6. 角色接口（行情视角）

| 对象 | 何时找 / 被找 |
|---|---|
| 策略运维bot | STATE raw / 取证 / 拼接偏差明细；冻结执行与 SHA256SUMS |
| 策略开发bot | 数据层 P1 修复包、CLI/门禁行为、质量列契约 |
| 策略测试bot | 冻后质量门禁复跑、包级数据相关断言 |
| 行研bot | dataset/manifest/质量哈希；R0002 冻结配合与幸存者披露 |
| 策略产品bot | gaps/bad_ticks 锁定批、资金费同源门禁、R0002 开冻审批 |
| BrainBot | 一切对用户决策与阶段汇报 |
