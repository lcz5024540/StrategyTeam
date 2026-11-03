# 活跃 evidence 分段封存

仅 Python，默认关闭。按成员和引用决定释放，**文件年龄不是删除条件**。
交易使用本地数据；COS 客户端只存在于独立归档/恢复进程。此功能不改变交易公式、周期、MySQL 或 PAPER 权威账本。

## 状态与安全条件

每个源有独立 `.sealing/control.sqlite3`，采用 FULL 同步、持久写入意图、引用边、consumer pins 和代成员清单。对象保持原 SHA 与原路径。

`OPEN → SEALED → COS_VERIFIED → RETIRED`。默认达到 64MiB 或 20,000 个成员，在成功交接后切段；显式 `segment-seal --force` 也必须通过交接检查。首次离线纳管按同样上限分代，原文件不搬移、不删除。最大单个对象、跨代引用闭包可使归档大小超过段目标；目标不是磁盘硬上限。

只有同时满足以下条件才进入释放清单：

1. 成员属于已封存代，并有 COS 中对应的不可变 manifest；路径、字节哈希和大小匹配。
2. 所有必要 consumer 已完成交接；无中断写入意图、控制库故障隔离标记。
3. 不被 OPEN、未归档代、当前检查点、修订锚点、活跃持仓、最近完整平仓恢复、未确认账本结果或盘口覆盖区间引用。
4. 用户批准具体 SQLite 清单的 SHA256；执行时重新验证远端数据、当前引用及文件设备/inode/大小/时间指纹。

单次最多处理 2,000 个文件，每批至多 64 个。COS 网络和大部分哈希校验不持本地独占锁；读者/写者持共享锁。每批独占交接时重验 revision、交接状态、故障隔离标记及文件指纹；变化则保留/部分完成或失败。先持久化 unlink INTENT，再删除并 fsync，最后记 DONE。重新生成的同名文件不能被旧清单删除。

**timer 只上传，不调用释放命令。** 不允许以 `find -mtime`、mtime、无引用估算或“已经上传”单独授权删除。

## 支持与边界

| 写入者 | 开关/合同 | 保留的热依赖 |
|---|---|---|
| classic 15m/4H PAPER CAS | `HTX_PAPER_EVIDENCE_SEGMENTED=true` / `paper` | 当前检查点、compact_latest 全部修订锚点、持仓及最近完整平仓、未确认结果 |
| continuous K线采集 | `HTX_COLLECTOR_EVIDENCE_SEGMENTED=true` / `collector` | continuous 游标；coverage 历史仅作为研究目录 |
| public capture | `--segmented` / `capture-public` | 最近段、正在写入的代 |
| realtime capture（含 LIVE 盘口记录） | `HTX_RT_CAPTURE_SEGMENTED=true` / `capture-runtime` | 最近段、Coverage BBO 缓冲覆盖的全部段 |

LIVE CAS、realtime 策略 CAS 暂不允许切换；仍按旧存储方式完整保护。历史 `collect` 不允许直接在活跃 segmented 源运行，先恢复独立研究数据集。

compact_latest 中的历史最新 K 线版本仍留本地，这是后续修订依赖；不能宣称所有历史 K 线都已可释放。本地全局索引/引用元数据随历史增长，非零且无自动过期。本轮主要释放无热依赖的 raw/window/决策对象及盘口段，长期容量仍需实测。归档进程每轮最多上传4代；积压时状态返回 pending_generations。

outbox 默认未接交付 consumer，不虚构 delivered。若另有交付消费者，初始化必须传 `--delivery-consumer`，所有未交付对象也会被 pin。已纳管后不能静默改变合同。

## 配置与操作

`config/archive/cos-sealed-sources.example.json` 为禁用模板，不能覆盖现网配置后直接启动。源 root 为 CAS 根或 capture 的 **segments 子目录**。不同实例目录、合同、归档工作目录分别核对；沿用现有只读写 COS 前缀的专用凭据文件，不把密钥塞入包或 env 模板。

已有目录须在获准维护窗口、对应全部写入者已停止后显式纳管（此命令不替你停服务）：

```bash
python -m htx_trend.archive.cli segment-init --root "$EVIDENCE_ROOT" \
  --kind cas --contract paper --enroll-existing --writer-stopped
python -m htx_trend.archive.cli segment-status --root "$EVIDENCE_ROOT"
```

已有数据纳管/故障重扫后，全部历史对象默认加 `external:legacy-enrollment` pin，保护未知归属和根目录之外的冻结研究依赖。即使无运行时引用也不会自动变成可删。先恢复验证旧 manifest 的归档映射，或为仍需本地的冻结根建立 `segment-pin --owner external:研究名称 --key SHA --reason 依据`。仅对已确认归属/可远端恢复的具体 SHA，才执行 `segment-unpin --owner external:legacy-enrollment --key SHA --reason 审核依据`，之后再预览并批准释放清单。pin/unpin 不删除文件，变更保存在本地审计记录中；禁止伪造 runtime consumer 的 ready。外部研究读者须显式 pin 根或使用独立 ReplayCache。

新空目录可由开关自动初始化。恢复运行的首轮会登记必要 consumer；在持仓恢复完成前不会允许释放。不要手工修改 consumers/pins。

独立进程：

```bash
python -m htx_trend.archive.cli --config "$COS_CONFIG" --credentials "$COS_CREDENTIAL_FILE" \
  --workspace "$ARCHIVE_WORK" segment-archive --root "$EVIDENCE_ROOT"
```

启用现有归档 timer 时 sources 使用 `kind: segments`；scheduler 不访问账本、不触发交易，也不执行删除。COS 失败保留 SEALED 状态，按原有退避重试；健康状态不会掩盖失败。成功代的生成清单缓存会回收，COS manifest 和本地代定位保留。

先预览，再由用户批准输出清单 SHA；以下第二条有实际删除副作用，必须单独批准具体清单：

```bash
python -m htx_trend.archive.cli --config "$COS_CONFIG" --credentials "$COS_CREDENTIAL_FILE" \
  --workspace "$ARCHIVE_WORK" segment-preview --root "$EVIDENCE_ROOT" --epoch 1 --output "$PLAN"
python -m htx_trend.archive.cli --config "$COS_CONFIG" --credentials "$COS_CREDENTIAL_FILE" \
  --workspace "$ARCHIVE_WORK" segment-release --plan "$PLAN" --approval-sha256 "$APPROVED_SHA256"
```

`local-reference-pin` 继续保留；`already-released` 不重删；后续 pin 消失需生成新清单。批次因读者占用或 revision 变化未完成时，可重试同一批准清单；文件指纹变化则需重新预览。没有时间到期自动放行路径。

## 回测与恢复

每个封存 manifest 是**引用闭合的代视图**，带 `sealed-scope.json`，不是全历史索引快照。CAS 代有仅含该闭包的研究索引；capture 代有相应段清单及可用的 capture.json。旧归档 manifest 不改写。

复用 `cache-run --manifest ... --cas SHA/--path .../--all -- command {data}` 按需下载并在回测进程全程持缓存 lease。跨代回测需要明确选取各代并按对象 SHA 去重；不能拿单代结果声称覆盖全历史。原全局索引和清单备份仍需保留，业务订单继续在原数据库/账本。

热路径缺少本地对象时明确 `NeedsRestore`，不在持仓管理线程下载 COS。完整 capture audit 遇冷数据直接拒绝，不把剩余本地段冒充完整历史。恢复到原路径：

```bash
python -m htx_trend.archive.cli --config "$COS_CONFIG" --credentials "$COS_CREDENTIAL_FILE" \
  --workspace "$RESTORE_WORK" segment-restore-local --root "$EVIDENCE_ROOT"
```

每次最多恢复 2,000 个对象，直到 remaining_cold=0；也识别 unlink INTENT 后缺失但尚未改 local 状态的对象。校验 manifest、成员哈希、原路径后原子发布，不覆盖现有不同内容，不替换 global index/paper-state/MySQL。下载暂存与源目录须分离，保留磁盘余量。

## 故障与旧包回滚

封存元数据失败：禁止释放，记录 `BLOCK_RELEASE` 或遗留 mutation；已有 PAPER/实时持仓仍进入原管理路径，采集证据故障继续按既有禁止新增风险规则处理。若故障隔离文件本身也不能持久化，进程保持共享锁，维护必须停写离线修复。权威账本失败仍明确报错，不能由封存层吞掉。

恢复空间/IO 后，在写入者停下的维护窗口重新运行 **同参数** `segment-init --enroll-existing --writer-stopped`：流式重扫、校验所有已发布文件和引用，重新纳管孤儿；失败保持 initialized=0。成功才清中断写入意图与故障标记，consumer 重新等待真实运行交接。不人工设 ready=1。

旧包回滚前：停止对应写入者和该源维护 → 恢复全部冷对象（需足够本地磁盘）→ 离线校验：

```bash
python -m htx_trend.archive.cli segment-rollback-check --root "$EVIDENCE_ROOT" --writer-stopped
```

通过后才允许旧包读该目录；capture 会重建完整旧 segments.jsonl，CAS 检查原全局索引及引用，账本不改。保留 `.sealing` 状态供再次启用，不与旧写入者同时运行封存/释放。当前代码在未显式启用分段却遇到 `.sealing` 时拒绝启动，防止误把冷目录当旧完整目录。回退到本版本但关闭开关时使用完整恢复的独立旧布局副本，不删除控制目录绕过检查。

本次交付不启用生产开关、timer，不删除现有 evidence，也不重启交易服务。部署后先观察增长和 pin 比例，再批准实际释放清单。
