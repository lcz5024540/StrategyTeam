# COS保留与无固定时限 · 2026-11-11

基线：LIVE `73a43e66`；独立公共采集 `4fa6ccee`。分支：`codex/htx-cos-retention`。

结论：代码支持显式无固定时限；分段公共采集不再预留7GiB；完成的归档压缩目录缓存可以GET验哈后逐项释放并从COS恢复。**这些代码不解除现网TIME_LIMIT、不续授权、不自动恢复交易，也不把旧LIVE原始留证视为已迁到COS。**

## 改动及边界

| 请求 | 实现 | 保持不变 |
|---|---|---|
| 去TIME_LIMIT | 新审批显式 `limits.duration_ms: null` 且 `expires_at: null`；Trial期限保存null | 旧审批默认24小时、旧stop_reason和计数不清零；损失20U/每笔2U上限、10仓/10笔、账户风险/候选/身份/确认验哈仍有效 |
| 去7GiB启动要求 | `Capture.startup_required_free_bytes`：分段只检查已配置低空间下限 | 默认运行期5GiB保护、2GiB本地quota、上传失败保留原件；非分段仍7GiB |
| 清盘 | `archive.catalogue_cache`单个已完成job的`catalogue.sqlite3.gz`释放/恢复 | 不删来源行情、CAS对象、冻结index、manifest/receipt、DB、交易/分析结果、COS对象 |

无时限JSON仅为字段示意，不是可运行授权：

```json
{"limits":{"per_trade":"2","loss":"20","slots":10,"trades":10,"duration_ms":null},"expires_at":null}
```

仍须具备真实issued_at、既有全部绑定和人工确认文件。缺字段、未来issued_at、候选不匹配均拒绝。通用`admission.window()`不改。现网已到期的trial不能利用storage-upgrade转成无时限；代码也不提供清历史的迁移入口。

## 缓存清理/恢复契约

1. 指定一个workspace和一个job；不支持glob或递归删除。默认`inspect`无删除。
2. 持有已有archive.lock；本地manifest/receipt/job/namespace/content-address键一致，读取COS manifest和完整catalogue验SHA256及长度。
3. `evict`先写持久化INTENT，复查同inode/size/mtime/ctime，仅unlink该gzip文件，fsync，再写结果。远端失联/缺失/损坏时不删除。
4. `restore`按保留manifest从COS下载，验哈和长度、fsync后无覆盖发布；旧文件存在但不同则拒绝。中断可重试，任何源数据不参与操作。

```bash
python -m htx_trend.archive.catalogue_cache \
  --config /etc/coinstrategy/15m-cos-archive.json \
  --credentials /home/ubuntu/coinstrategy-cos-pilot/credentials.json \
  --workspace /home/ubuntu/coinstrategy-cos-archive/work \
  --job paper-15m-20261103 --action inspect
```

`--action evict`才释放；`--action restore`恢复。需以credentials属主ubuntu执行。恢复默认仍保留5GiB空闲空间。本工具不调用PUT/DELETE、不触发交易。需要使用旧`compact-staging`时，先restore目录缓存。

## 数据保留实情

- 独立公共采集本来已有 `SEALED → COS_VERIFIED → RETIRED` 两阶段释放，只保留OPEN及被pin的代；修正启动预算让它恢复写入、替换last_ref pin后由现有服务释放旧代。
- LIVE `.capture-cold`是同盘压缩alias，不是COS。不删除alias目标；删掉会使下次启动失败。
- LIVE/PAPER的`evidence`混有原始数据、分析证明、计划及恢复状态；不能整目录删掉。旧LIVE迁至COS有界缓冲需要停writer、转换alias布局、保留引用映射并验证恢复；**本轮未实施该生产迁移**。
- 当前5GiB仍是磁盘保护阈值，不将本改动描述成“完全取消所有空间保护”。

## 验证与审查

无时限矩阵：长期运行、重启、亏损、仓位/交易数、账户预算、覆盖过期、时钟倒退、CAS旧writer、storage升级/回滚及旧TIME_LIMIT不重启。

缓存矩阵：inspect/evict/重复evict/restore/重复restore、远端缺失/损坏、local篡改、namespace/receipt错误、symlink/路径逃逸、锁冲突、并发inode替换、unlink后故障与恢复失败无半文件。

公共采集矩阵：分段5/6GiB通过磁盘门，低于5GiB拒绝；非分段6GiB拒绝/7GiB通过；既有quota/停机/崩溃恢复测试保留。

自审按review-pr五轴：正确性（显式可选值，不改旧authority）、测试（上述故障矩阵）、设计（复用archive锁/COS适配器，单文件清理）、风格（保持原模块风格）、性能安全（1MiB流式GET、无凭据输出、无交易调用）。总diff超过100行主要来自故障测试和独立缓存工具，按无时限与采集清理拆两个代码检查点。缺陷账本无本次改动模块相关active条目；不将其他模块resolved问题误标复发。

源码关系证据：codegraph当前不可用，Explore只读源码及精确调用点核对作为替代，不声称生成过codegraph图谱。

| 起点 | 后继/约束 | 证据 |
|---|---|---|
| trial_admission.validate | Limits → issued_at/expiry → candidate/file_hash | `src/htx_trend/realtime/trial_admission.py` |
| Trial.activate / budget / observe | expires_ms=null跳过期限；其余风险及stop保留 | `src/htx_trend/realtime/trial.py` |
| Capture.run | startup_required_free_bytes → recorder / runtime低空间保护 | `src/htx_trend/realtime/public_capture.py` |
| catalogue_cache | exclusive → verified_stream → atomic INTENT → fingerprint → unlink | `src/htx_trend/archive/catalogue_cache.py` |
| catalogue_cache.restore | COS GET → hash/size → fsync → no-clobber link | 同上 |

流程：[draw.io](diagrams/cos-retention-20261111.drawio)。现场执行、实际容量和COS进展另附于治理仓当日报告；本文件不把测试结果冒充生产结果。

## 现场交付补充（12:30 CST）

源码检查点bec308f2、ebf92875；最终1461测试通过/13隔离集成用例跳过，159Python文件编译检查通过。干净ebf92875构建candidate和LIVE双包；candidate SHA256 `df4c183a4f769f095a124e3e354ecacc6e639f4a3450cd306cab536a0c1fa0ac` 两端verify，LIVE SHA256 `83711a9f0e55d05b64f3c0020c4fd7f0009ce4525947525d7271bbba6789f164` 只在本地验证、未部署。

12:22:18独立采集切至candidate-ebf92875/PID1111538；累计NRestarts=1000在观测窗口内保持不变。LIVE仍73a43e66/PID3412311，TIME_LIMIT未清；4H PAPER705510不变，无本轮新增订单或仓位。独立data旧env/unit/current目标备份在服务器`/home/ubuntu/coinstrategy-cos-archive/deploy-20261111-ebf92875/backup/`。

清理3份已完成job的catalogue缓存583,175,161字节（paper-4h、paper-15m、shared-klines，均20261103）；每份先GET完整验哈，paper-4h实测恢复后再释放。另用pip自带purge清260份下载缓存约150.6MB，不改已安装依赖。根盘空闲由5.00GiB增至约5.68GiB，使用率92%→90%。冻结index和来源分析/交易数据保留。

第857代新数据12:24:59归档，manifest `manifests/126073c68b9ee82635dd3dde07572720f72dc9c3b5a9f8ef8eddc94efe8722fa.json`；原释放流程RETIRED后，从COS完整恢复29文件/3,378,232字节，26段解压hash均匹配，原位置成员剩余0。包含12:22:19.073—12:24:48.558的新数据，9050 BBO、218 DEPTH、582 TRADE、52 FRAME。858代OPEN/pin保留。

**仍未全部完成**：采集虽有数据推进但DEGRADED，TypeError/ValueError触发重连熔断，DEPTH暂停。隔离真实30秒探针复现`invalid depth snapshot`；TypeError未归因。WS解析/重连/catchup与旧4fa6ccee零差异，不把预算修复当已证实根因，也不顺带放宽解析校验。旧LIVE `.capture-cold`仍在本地，需要用户确认停写迁移窗口再做逐对象COS映射/恢复；本次没有做到全服务器“只留分析结果”。

下一步：运维补异常原始报文定位；用户决定LIVE停写留证迁移窗口；账户持有人在数据/账户覆盖恢复后自行执行匹配的无时限准入与最终启用。治理仓计划保留active，历史H-10阻断根提交，不冒称关闭。
