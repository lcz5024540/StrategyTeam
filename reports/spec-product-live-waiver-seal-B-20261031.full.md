# 规格 · 产品书面豁免可校验密封（路径 B · ProductLiveWaiver）

- **日期 CST**：2026-10-31 19:18 Asia/Shanghai
- **角色**：策略产品文书（只读写盘）
- **硬声明**：本规格 **≠ merge ≠装机 ≠启 LIVE ≠停 PAPER-15m ≠动 PAPER-4H ≠ SendToAgent**；**禁止**把 admission/approval 的 `synthetic:true` 改字冒充 `false`；**禁止**虚构 REAL_PAPER evidence；**禁止** demo / PRODUCT-DRAFT 当装机真签章。书面 σ_d/ORDERPX 豁免仍有效 ≠ 正式过闸；切机仍 HOLD 至：新冻包 + 可校验豁免密封齐 + 产品另盖切机戳。

## 背景裁定（照做）

用户经 BrainBot 原话裁定 **B**：立刻切 LIVE 意图成立，但当前冻包 `0.1.7-live-73fe4c1b`（package SHA `ca57bbf7d9c2ac585c5227ee2c3b8ae4d877806150400d0f2a29db90fa9b95be`）**不能直接切**。须新编码「产品书面豁免」**可校验**密封路径，且 LIVE-15m 行为对齐现网 PAPER-15m（包 `0.1.6-paper-db-dbfee173` + `confirm-v1`）：**能正常开单、逻辑一致**。

- 冻包 Pass 核验稿：`/workspace/reports/product-verify-live15m-package-73fe4c1b-20261031.md` SHA `b080a64c835a2a04861a588b766bd24959c06b7c7dec448e65fb27748ef725b0`
- G2 硬闸：`validate_admission(LIVE)` 要 `synthetic:false` + REAL_PAPER≥14d；扩池C 约 4.35d；`seal_live` 仅 `synthetic:true`
- 路径 A encode 冻包保留作基线，**明示不可装机**；本 B 规格定义旁路豁免密封，**不**把 A 包冒充可切机

---

## 1. Schema 名

建议 schema 名（与现码风格对齐：`htx-admission-g2` / `htx-live-approval-g2` / LegacyLiveWaiver）：

```text
htx-product-live-waiver-g2
```

JSON 顶层必须含：

| 字段 | 类型 | 约束 |
|---|---|---|
| `schema` | string | 精确等于 `htx-product-live-waiver-g2` |
| `synthetic` | bool | **必须为 `false`**（本豁免是产品书面签发的真旁路件，不是 fixture；但 **≠** 伪造 REAL_PAPER admission，也 **≠** 把旧 synthetic admission 改字） |
| `waiver` | object | 见下表必填字段 |
| `seal_hash` 或内嵌 `waiver_hash` | string | 见密封算法（推荐与 LegacyLiveWaiver 一致：字段内 `waiver_hash`） |

> 说明：`synthetic:false` 仅声明「本 ProductLiveWaiver 不是 demo/PRODUCT-DRAFT fixture」；**严禁**借此暗示已存在正式 G2 admission `synthetic:false` + REAL_PAPER≥14d。旁路语义写在 `waived_gates`。

---

## 2. 必填字段表（`waiver` 对象 / 或扁平等价）

仿 `LegacyLiveWaiver`（`live_approval.py`）：空 `waiver_hash` 清零再 `_hash`；`risk` 必须可被现网 `LiveRiskPolicy.validate` 接受。

| 字段 | 类型 / 约束 | 说明 |
|---|---|---|
| `package_sha256` | sha256 hex64 | 必须等于**即将装机的新 B 冻包** SHA（≠ 仅引用 A 包 `ca57bbf7…` 作为可装机对象；A 包仅基线） |
| `source_commit` | non-empty git SHA | 与冻包 `source_commit` / env `HTX_PYTHON_SOURCE_COMMIT` 一致 |
| `strategy_version` | 精确 `HTX_TREND_15M_V2` | 否决其他版本 |
| `timeframe_profile` | 精确 `INTRADAY_15M` | 否决 4H / 混绑 |
| `instance_key` | 精确 `htx-trend-live-primary` | |
| `config_fingerprint` | sha256 hex64 | 与实例 / 包 bindings 一致 |
| `strategy_spec_hash` | sha256 hex64 | 与 `profile_strategy(INTRADAY_15M).spec_hash` 一致 |
| `account_id` | int | PRIMARY 账户 id，与 runtime 一致 |
| `risk` | object | **必须**通过 `LiveRiskPolicy.validate()`：`per_trade_equity_rate=0.005`、`total_open_equity_rate=0.015`、`max_active_positions=3` |
| `exit_version` | 精确 `HTX_TREND_EXIT_V4` | V5/未知否决 |
| `waived_gates` | string[] 非空 | 枚举见下节；至少含 `PAPER_EXECUTION_14D`、`SIGMA_D_ORDERPX` |
| `user_ruling_ref` | object | BrainBot 转述原文摘要 + 时间（CST） |
| `product_stamp_sha256` | sha256 hex64 | 指向本路径 B 产品编码戳文件的本机 sha256 |
| `issued_at` | ISO8601 带时区 | |
| `expires_at` | ISO8601 带时区 | `expires > issued` 且窗口 **≤45 自然日**（同 Legacy） |
| `approved_by` | non-empty string | 产品签发人标识（非 `SYNTHETIC`） |
| `rollback_point` | non-empty string | 运维可执行回滚标签（非 `SYNTHETIC-*`） |
| `waiver_hash` | sha256 hex64 | canonical seal；见 §4 |

### 2.1 `user_ruling_ref` 建议子字段

| 子字段 | 约束 |
|---|---|
| `channel` | 如 `BrainBot` |
| `ruled_at_cst` | 用户裁定时刻（Asia/Shanghai 标注） |
| `summary` | 原文摘要：选定路径 B；立刻切 LIVE 意图；须可校验产品书面豁免；LIVE 对齐 PAPER-15m（dbfee173+confirm-v1）能开单 |
| `verbatim_excerpt` | 可选短摘录（勿篡改） |

### 2.2 仓位硬限（本 B）

| 项 | 本 B 绑定 | 另开编码 |
|---|---|---|
| `LiveRiskPolicy.max_active_positions` | **必须 = 3**（代码硬限） | 若要支持用户历史目标 **5 仓**：本 B **仍绑 3**；改 5 **另开编码**（须改 `LiveRiskPolicy.validate` + 产品新戳），**不得**在本 waiver 写 5 并宣称可过现网校验 |

PAPER-15m 现网 `HTX_PAPER_MAX_ACTIVE_POSITIONS` 默认可至 5；LIVE 审批硬限 3 —— 对齐清单必须标为 **故意不对齐（代码硬限）**，且不得据此宣称「仓位数与 PAPER 完全一致」。开单逻辑对齐 ≠ 仓位上限数字相同。

---

## 3. `waived_gates` 枚举

至少：

| 枚举值 | 含义 |
|---|---|
| `PAPER_EXECUTION_14D` | 旁路 `validate_admission(LIVE)` 对 REAL_PAPER≥14 自然日的硬要求 |
| `SIGMA_D_ORDERPX` | 旁路既有书面 σ_d / ORDERPX 产品豁免所覆盖的研究闸（与既有 waiver-A 意图衔接；本密封可校验化） |

可选扩展（若编码需要显式列出，须在交审说明）：`STAGE_PAPER_EXECUTION_STATUS` 等——**不得**用枚举「豁免」去掩盖把 `synthetic:true` 改字成 `false`。

**与 REAL_PAPER≥14d 的关系（硬口径）**：

- 正式 G2 路径仍然保留：满窗后仍可走 `synthetic:false` admission + LiveApproval 三件套。
- 路径 B：**不**伪造 REAL_PAPER evidence；**不**改 admission 字；用 **独立** `ProductLiveWaiver` 在运行时 **旁路**「必须有正式 PAPER≥14d admission」这一闸。
- 扩池C 约 4.35d 的事实记入签发备注，**不是**本 waiver 的证据替身。

---

## 4. 密封算法（canonical · 仿 LegacyLiveWaiver）

与 `live_approval.py` 中 `_canonical` / `_hash(..., clear="waiver_hash")` 一致：

1. 将 waiver 对象转为 canonical 结构（dataclass→dict；`Decimal`→str；dict 键排序）。
2. 将 `waiver_hash` 字段置为 `""`。
3. `json.dumps(..., ensure_ascii=False, sort_keys=True, separators=(",", ":"))`。
4. `sha256(utf-8 bytes).hexdigest()` → 写入 `waiver_hash`。
5. `validate`：重算期望哈希；不匹配 → fail-closed。
6. 时间窗：`expires_at - issued_at ≤ 45d` 且 `now < expires_at`。

若顶层另用 admission 风格 `seal`/`seal_hash`（`admission.canonical` + 整文件字节相等），编码实现须二选一写死并单测；**推荐**内嵌 `waiver_hash` 以贴 LegacyLiveWaiver。

---

## 5. 禁止清单（交审一票否决）

| 禁止 | 说明 |
|---|---|
| 改字冒充 | 禁止把既有 admission/approval 的 `synthetic:true` 改为 `false` 当正式件 |
| 虚构证据 | 禁止编造 `kind=REAL_PAPER` 或拉长窗的 evidence JSON |
| fixture 当真签 | 禁止 `seal_live.py demo` / `product-template`（PRODUCT-DRAFT）产物作装机真签章 |
| 用 A 包直接切 | `0.1.7-live-73fe4c1b` **明示不能装机**；仅作编码基线 |
| 本规格授权切机 | 切机须产品 **另盖切机戳** |

---

## 6. 与 LiveApproval 关系 · **主方案（唯一）**

### 选定主方案：**ProductLiveWaiver 替代正式 admission 的 PAPER≥14d 闸**

对标现码 `LegacyLiveWaiver` + `worker._validate_live_approval` 中 legacy 分支：V2 15m LIVE 在存在有效 `HTX_TREND_PRODUCT_LIVE_WAIVER_PATH` 时，**不再要求**正式 G2 `validate_admission(LIVE)`（即不要求 REAL_PAPER≥14d 的 `htx-admission-g2`）。Waiver 自身携带原 LiveApproval 级绑定字段（包/commit/config/spec/account/risk/exit/时间窗）。

**不选**「waiver+精简 approval」作为本 B 主方案（避免双文件交叉半状态）；正式满窗后的 G2 三件套路径仍保留并行，互不改字。

### 伪代码级校验步骤（`runtime_guard` / 启动路径）

```text
def runtime_guard(instance, account, profile):
    if instance.execution_mode == "PAPER":
        return None, None

    waiver_path = env("HTX_TREND_PRODUCT_LIVE_WAIVER_PATH")
    admission = env("HTX_TREND_STRATEGY_ADMISSION_PATH")
    approval  = env("HTX_TREND_LIVE_APPROVAL_PATH")
    context   = env("HTX_TREND_APPROVAL_CONTEXT_PATH")

    # --- 路径 B 旁路（优先显式开关：有 waiver 路径则走豁免枝）---
    if waiver_path:
        w = ProductLiveWaiver.load(Path(waiver_path))   # schema/hash/窗/risk.validate
        assert w.strategy_version == "HTX_TREND_15M_V2"
        assert w.timeframe_profile == profile == "INTRADAY_15M"
        assert w.instance_key == instance.instance_key == "htx-trend-live-primary"
        assert w.package_sha256 == env("HTX_PYTHON_PACKAGE_SHA256")
        assert w.source_commit == env("HTX_PYTHON_SOURCE_COMMIT")
        assert w.config_fingerprint == instance.config_fingerprint
        assert w.strategy_spec_hash == profile_strategy(INTRADAY_15M).spec_hash
        assert w.account_id == account.account_id
        assert "PAPER_EXECUTION_14D" in w.waived_gates
        # 失败一律 raise → fail-closed；成功则返回 (None, waiver_as_approval_view)
        return None, w.to_approval_view()

    # --- 正式 G2 路径（保留）---
    if not (admission and approval and context):
        raise ValueError("LIVE requires G2 admission+approval+context, or ProductLiveWaiver")
    return validate_approval(...)
```

启动侧（`_validate_live_approval`）对 V2：

- 若 waiver 枝成功：跳过「new strategy LIVE requires approval and sealed admission」对正式 admission 的硬依赖；仍执行 margin / `LiveRiskPolicy` 上限与 scan capacity ≤3 检查。
- 若 waiver 与正式三件套 **同时** 配置：fail-closed（禁止双路径歧义）**或** 文档规定「仅认 waiver、忽略三件套」——编码须选一并单测；**推荐 fail-closed 双挂**。

Entry 热路径（`execution_service` 每次开单调用的 `runtime_guard`）必须走同一枝，禁止缓存旧通过结果绕过磁盘重载（现注释：`Reload disk evidence on each entry`）。

---

## 7. 运行时接入建议

| 项 | 要求 |
|---|---|
| 新 env | `HTX_TREND_PRODUCT_LIVE_WAIVER_PATH` → 密封 JSON 路径 |
| 改动点 | `admission.runtime_guard`；`worker._validate_live_approval`；文档 `LIVE_PACKAGE.md` |
| 签发工具 | **新建** `release/seal_product_live_waiver.py`（或等价）：只从**已 verify 的新 B LIVE 冻包**+产品戳 SHA+user_ruling_ref 生成并密封；**不得**复用 `seal_live.py` 写出假 `synthetic:false` admission |
| 失败语义 | 缺字段 / hash 错 / 包 SHA 不一致 / 过期 / strategy 不符 → **fail-closed**（拒绝启动与拒绝开单） |

---

## 8. 失效条件

任一成立即失效（运行时与离线 validate 均拒）：

1. `now >= expires_at`（过期）
2. `waiver_hash` 不匹配（改字/损坏）
3. `package_sha256` ≠ 当前 env / 拟装机包 SHA
4. `source_commit` / `config_fingerprint` / `strategy_spec_hash` / `account_id` / `instance_key` 与 runtime 不一致
5. `strategy_version` ≠ `HTX_TREND_15M_V2` 或 `timeframe_profile` ≠ `INTRADAY_15M`
6. `risk` 未过 `LiveRiskPolicy.validate`
7. 运维触发 `rollback_point` 所指回滚（装机回滚后不得继续挂本 waiver）
8. 产品撤销戳（新戳宣布本 waiver / 本 product_stamp_sha256 作废）后，磁盘件即使未过期也不得再挂

---

## 9. LIVE↔PAPER 行为对齐（规格层硬需求 · 催更）

用户裁定：LIVE-15m 须与现网 PAPER-15m（`0.1.6-paper-db-dbfee173` + `confirm-v1`）一样 **能正常开单且逻辑一致**。PR#54 / 路径 A 仅把 **exit** 侧 `confirm-v1` 接入 `LivePositionManager`——**不够**。

编码必须按开发口令交付「对齐清单」；规格预置最低清单：

| # | 能力 | PAPER（dbfee173+confirm-v1） | 路径 A LIVE（73fe4c1b） | 路径 B 必须 |
|---|---|---|---|---|
| 1 | 出场 / 保护位 confirm-v1 | `ContinuationConfig.from_paper_env` → PositionManager | 已接 `from_live_env` + LivePositionManager | **保持**；回归测不得回退 |
| 2 | **入场 / 开单主路径** | `PaperTrendWorker` → `runtime.scan` → 模拟/账本成交 | `LiveTrendWorker` + `HtxTrendEntryExecutor`；无 waiver 时 G2 拒启 | **在 ProductLiveWaiver 旁路下能正常提交开单**；信号→闸门→杠杆→下单与 PAPER 同逻辑族 |
| 3 | linked reentry（`reentry_gate`） | `previous_exit` 传入 `runtime.scan`；`version==linked-v1` 时生效 | **未接线**（LIVE_PACKAGE 已声明） | 目标 profile=`confirm-v1`：**不要求**启用 linked 再入；但须在对齐清单写明「confirm-v1 下 PAPER 亦不走 reentry_gate 约束（仅 linked-v1）」；若未来挂 `linked-v1` 却不接 reentry → **标 ≠用户对齐并阻断交审** |
| 4 | cooldown B 组 | `HTX_PAPER_COMPLETED_TRADE_COOLDOWN` | LIVE 无对应 | **故意不对齐（现网 confirm-v1 默认 false 时行为等同）**；若 PAPER 现网 cooldown=true 而 LIVE 无 → 必须标阻断或补齐 |
| 5 | 杠杆 | `HTX_PAPER_LEVERAGE` 默认 **10** | `HTX_TREND_LEVERAGE` 代码默认 **5** | 装机/交审文档与示例对齐 **`HTX_TREND_LEVERAGE=10`**（现网等价名以 LIVE `EntryExecutionConfig.from_env` 为准） |
| 6 | 最大活跃仓 | PAPER 默认可 5 | `LiveRiskPolicy` **硬限 3** | **本 B 绑 3**；标故意不对齐；改 5 另开编码 |

**阻断交审条件**：宣称「与 PAPER-15m 逻辑一致」却未完成开单路径审计/补齐；或把仅 exit confirm-v1 当作全量对齐。

---

## 10. 关联交付

| 文件 | 角色 |
|---|---|
| `/workspace/reports/product-stamp-live15m-path-B-waiver-encode-20261031.md` | 产品编码/出包戳（可执行） |
| `/workspace/reports/dev-order-live15m-path-B-waiver-encode-20261031.md` | 量化开发口令 |
| 被取代预期 | path-A encode 对「可切机」的预期；A 冻包保留基线 |

---

## 硬声明（文末重申）

- ≠ merge / ≠装机 / ≠启 LIVE / ≠停 PAPER-15m / ≠动 PAPER-4H / ≠ SendToAgent  
- ≠ 伪造 `synthetic:false` admission / ≠ demo·PRODUCT-DRAFT 当真签  
- ≠ 本规格 = 切机授权（须另戳）
