# LIVE capture 本地冷热保留修复

保留全部原始行情、原压缩字节和引用，释放应用热区配额；不上传 COS，不自动删除历史。

作者：Codex；日期：2026-11-06；状态：已push修复分支、已部署主LIVE，未合并主分支。准确基线：`7a9620bfb5b3261b3bb88ba46d0ed85e43bd8729`；修复分支：`codex/htx-capture-local-archive`；线上运行源码：`73a43e665595a7df39506b0f9f3023f1700cbe28`。

## 生产发布回执（北京时间2026-11-06）

13:49:55预检交易所空仓、isolated/cross均无挂单。新旧包逐文件校验通过；Linux真实副本12段、33,384事件归档前后验读通过。13:50:10开始停止旧主实例，执行同trial审计升级并完成初始归档。13:52:53新LIVE启动，PID为`3376998`。13:54:09复验进程active/running、NRestarts=0，实际cwd/PYTHONPATH/source/package全部匹配。

| 验收项 | 实测结果 |
|---|---|
| 热区占用 | 2,133,115,638 → 1,288,988,104字节，约99.33% → 60.02% |
| 热区可用 | 858,495,544字节，约819MiB；上限仍为2GiB |
| 本地冷payload | 846,062,403字节、16,194段；同盘保留，原路径仍可读 |
| 历史完整性 | 原41,576条manifest前缀SHA不变；当前41,589条引用全部存在 |
| 归档验读 | 归档前逐段验SHA；上线后抽查52段、90,508事件通过 |
| 新行情 | 已新增13段，尾段包含BBO与TRADE，正常持续写入 |
| trial | 同一trial；升级前后所有非版本/审计字段完全相同；revision 32447→32448，未重置、未续期 |
| 物理磁盘 | 当时可用11,083,431,936字节（约10.32GiB）；显式保留5GiB |
| 影响范围 | 仅主LIVE实例专属drop-in；共享current、原env、独立COS采集及4H未切换 |
| 交易请求 | 切换工具只允许交易所GET查询；验收时没有新增订单 |

新包：`htx-trend-python-0.1.7-realtime-live-73a43e66.tar.gz`；SHA256：`8fe339fca784a0b00ca0246143d0e6fdcb83de3fed2143881555f8322aef0d45`。

线上release：`/opt/coinstrategy/htx-trend-py/releases/0.1.7-realtime-live-73a43e66`；发布材料：`/opt/coinstrategy/htx-trend-py-state/deploy-20261106-capture-73a43e66`。原环境文件保留，新增文件仅覆盖7个明确配置项；其余有效应用环境逐项比较不变。服务器凭据快照为0600，未下载或提交。

最终完整回归再次通过：**1,443项，72.74秒，无跳过**，包含13项隔离MySQL测试；测试容器已恢复原停止状态并保留volume。

13:55:44最后复验：同PID、NRestarts=0，新段由13增长至34；热区60.07%、可用857,507,202字节（约818MiB）；全部41,610条manifest引用仍存在。最近心跳无capture错误，仍有`STALE_BBO`。脱敏选录见`postdeploy-summary.json`。

边界与未解决事项：

- 容量修复已生效，但行情时效并未因此恢复：复验心跳仍为`DEGRADED/STALE_BBO`，不能称整体LIVE健康。
- 停止旧writer到新worker的continuity gap为166.48秒；capture前后观测边界相隔169.09秒，已记录GAP，没有补造停机期间行情。
- 冷区仍占服务器物理磁盘，且随历史增长；本次不是物理空间回收，也不是无限保留方案。5GiB余量保护仍会阻止继续写满磁盘。
- 许可仍按原时间在**2026-11-06 22:26:45.361 CST**到期；没有自动续期。

可审计脚本与脱敏回执位于[`evidence/capture-live-hotfix-20261106/`](evidence/capture-live-hotfix-20261106/)。`server_cutover.py`只针对本次确定旧版本的一次性切换，不是可随意重跑的通用部署脚本。

## 紧急发布补充：同trial版本衔接已实现

用户要求打包解决LIVE容量问题后，新增`htx_trend.realtime.trial_upgrade`。不修改`trial.py`与`trial_admission.py`的正常准入检查，不伪装运行版本；工具在原全局MySQL单写锁下，通过原revision CAS只修改source/package绑定，附加`storage_upgrade_history`审计，原交易计数、风险、亏损、开始/到期、stop_reason与所有其他状态保持不变。

新approval保留旧approval全部非版本授权字段，另绑定原approval哈希、操作者确认原文及包文件差异。`prepare`调用原package verifier，禁止脏源码或存储白名单之外的文件变化。seal用于完整性校验，不冒充签名或代替操作者授权。

```bash
python -m htx_trend.realtime.trial_upgrade prepare \
  --old-approval /absolute/old/trial-approval.json \
  --old-manifest /absolute/old-release/MANIFEST.json \
  --archive /absolute/new-package.tar.gz \
  --confirmation /absolute/operator-confirmation.txt \
  --output /absolute/new-authority-directory
# 旧writer停用后，在实际旧数据库环境内：
python -m htx_trend.realtime.trial_upgrade plan \
  --old-approval /absolute/new-authority-directory/old-approval.json \
  --new-approval /absolute/new-authority-directory/approval.json
# 核验plan后将plan改为apply；恢复旧包使用rollback，原状态不回退。
```

55项定向含真实隔离MySQL通过；全套1,443项通过（含13项隔离MySQL），77.09秒，无跳过。覆盖升级/正常Trial重启、重复执行不重复审计、CAS拒绝旧writer、锁丢失拒绝写、回退保留升级后的运行状态，以及非版本字段变更/续期/新trial/篡改确认均拒绝。隔离环境为本机`colima-hot-deploy-tests/hot-deploy-mysql`，非生产数据库。

生产切换严格限本实例：核对空仓/无未决、停旧writer、备份旧env和trial、生成最终持仓交接材料、执行有审计的版本升级、完成冷热归档，再通过实例专属systemd drop-in切换新release/env。共享`current`、独立COS采集与4H服务不动。失败时不得清空trial或重新激活；按同一receipt回退绑定与实例配置，旧包quota仍计冷payload，回退不代表容量问题已解决。以上流程已按本页生产回执执行。

以下首轮记录中的“迁移未实现”已由本节实现取代；是否生产生效以独立部署回执为准。

## 结论

本地归档和无损编码优化已实现。历史 gzip 留在同盘冷目录，原 hash 路径作为相对软链接保持可读；热计量与冷保留量分别报告。它解决的是应用活动集持续累积，不减少磁盘上已有 gzip 的物理占用，也不证明行情消费积压已经修复。

策略、2秒 fresh 标准、交易额度、退出逻辑、trial 身份和许可到期时间均未改动。生产已切换新包，使用同trial显式版本升级后的许可与账本绑定，并非直接套用旧版本许可。

## 架构与实现

[归档流程、模块边界、并发读取顺序（3页 draw.io）](diagrams/capture-local-archive-flowchart.drawio)

| 文件 | 本次职责 |
|---|---|
| `realtime/local_archive.py` | 同文件系统后台迁移、严格相对 alias 校验、writer flock、磁盘余量保护、冷热/保留计量 |
| `realtime/storage.py` | 显式接线、准确配额、故障锁存、关闭清理；精确 `MarketEvent` 类型省去第二遍 `primitive()` |
| `realtime/factory.py` | 读取本地模式/磁盘保留配置；装配异常释放归档器 |
| `realtime/worker.py` | 心跳显示热/冷/总保留字节；停止异常路径仍关闭归档器 |
| `sealing/control.py` | 明确拒绝将本地 alias 布局纳入 COS/segmented 释放流程 |
| `tests/test_capture_local_archive.py` | 正常、故障注入、重启、并发 reader、引用/coverage、异常关闭回归 |

新增模块约250行，没有新依赖或数据库变更。格式仍为 classic events JSON、gzip9、原文 SHA256；普通 dict、Decimal、自定义 dump 仍走原 canonical 路径。未减少 raw 字段、异常事件或采样频率。

## 行为契约与持久化矩阵

稳定身份为原始 canonical segment 的 SHA256；文件名、manifest 的 hash/bytes/count/时间字段不改。相对 alias 只允许精确 `.capture-cold/<相同hash>.json.gz`，外部/绝对/嵌套链接、跨文件系统、非普通成员拒绝。压缩和解压后的单段均限8MiB，超过上限锁存错误，不截断内容。

入口为首次启用、后台80%触发、每段迁移、崩溃后重新启动、重复命中同一hash、正常/异常停止；无HTTP写入端点、补偿交易或DB代次。本次文件状态变化不允许产生任何交易请求。

| 场景 | 不变量与实际结果 |
|---|---|
| 原热文件，尚未归档 | 文件保留；先验 gzip/原文 SHA/大小 |
| 冷 hardlink 已创建但未发布 alias | 原路径仍可读；重启按 inode 去重计算保留量 |
| 冷文件/目录 fsync、symlink 或 replace 前故障 | 原文件不丢，不提前减热配额 |
| replace 后或目录 fsync 故障 | 原路径跟随 alias 可读；重启重算，不依赖内存计量 |
| 成功与重复归档 | 同 hash、同 gzip；不再次计费或生成新 manifest 记录 |
| 并发reader / 已打开FD | 内容始终一致；原路径没有缺失窗口 |
| 两个新writer | 第二个被 flock 拒绝；旧包不认此锁，必须先停旧进程 |
| 配额 / 磁盘保留耗尽 | 锁存 CAPTURE 错误；不自动删数据、清失败或伪补遗失事件 |
| 冷损坏 / 同名不同内容 | 不覆盖冲突；完整 audit 报错，不跳过损坏成员 |
| coverage 含 GAP | 继续拒绝证明，不把冷热迁移当成补齐覆盖 |
| 装配或停止途中异常 | 归档线程与writer锁被清理；原停止错误不静默吞掉 |
| COS enrollment | 拒绝本地 alias 布局，未放宽原 symlink 保护 |

冷目录永久保留payload，manifest/alias元数据继续计入热区。没有无限可持续承诺：元数据和冷历史都会增长；触及磁盘保留值仍会停采/阻止入场。

## 配置与运行限制

示例文件新增但默认关闭：

```dotenv
HTX_RT_CAPTURE_LOCAL_ARCHIVE=false
# 包内默认仍关闭；本次主LIVE已显式开启，且实际配置5GiB保留。
# HTX_RT_CAPTURE_MIN_FREE_BYTES=5368709120
```

启用后热量达到 `HTX_RT_CAPTURE_MAX_BYTES` 的80%启动后台归档，目标60%；原上限仍可保持2GiB。最近一段保留为常规文件，早期片段原路径仍在线可读。每段锁内完成验哈与耐久屏障，段间让出；状态提供 `max_member_lock_ms`，本地时延不能外推服务器慢盘。

关闭本地模式的新包遇到冷目录会拒启，防止误用旧计量。旧包reader能跟随alias，但旧quota仍按目标大小把冷数据计入，不能保证直接回滚旧包还能续写。还原常规热文件需停writer、单独验证计量与可用配额，不能把“旧包可读”等同于“可直接回滚采集”。

## 验证证据

最终日志、真实API响应、Graphify输入哈希和打包证据统一位于治理仓：
`/Users/lichangzheng.ray/Ray/ray-agent/docs/reports/evidence/20261106-htx-capture-repair/`。

- 定向回归：93项通过，覆盖本地归档、coverage、公共reader及segmented隔离。
- 全套回归及编译：1,411项通过、12项跳过（69.45秒），`compileall`通过；12项独立MySQL测试因本轮未提供隔离数据库跳过，未使用生产DB跑测试。
- 真实副本：`local-archive-real-validation-final.json`；13:09:50 SSH只读下载48段、119,061条事件，本机热量2,286,305降至1,489,627字节；gzip逐字节、事件顺序、manifest及跨进程重启通过。单段持锁实测最大1.503ms，仅为本机样本。
- 真实接口：无凭据调用HTX公共BTC-USDT深度，保存到本地测试capture并验证续写；没有交易请求。
- 旧版兼容：执行准确7a9620bf的reader源码，检查旧引用可读且旧quota仍计冷payload；没有启动旧writer。
- 无损优化：1000个真实BBO三轮序列化字节相等，编码速度中位为原来的1.305倍；基准仅覆盖本机编码，不包含网络、压缩、fsync或生产循环。
- 影响图：`archive-graph/{input-manifest.json,extraction.json,graph.json,graph.html,GRAPH_REPORT.md}`。codegraph不可用，按Graphify skill生成9文件AST与显式边；图为导航，不冒充运行调用链。

## 首轮生产前检查清单（本次已完成）

1. 使用上述显式版本升级工具和操作者确认材料，保留原trial计数/时限/损失/限额；禁止直接改DB绕过审计、新建trial或自动续期。
2. 明确总磁盘预算、保留余量及LIVE停止/切换窗口；切换前重新查交易所持仓和挂单，数据库旧状态不能代替交易所原子快照。
3. 确认后再整合目标分支、构建最终包、停旧writer并按批准流程切换；验收实际source/package、trial未重置、原引用、热/冷计量、新行情尾部和健康状态。

现有 `Trial.__init__` 会对 saved binding/limits 全量比较；旧许可绑定source=7a9620bf及旧package，且北京时间2026-11-06 22:26:45.361到期。仅签发新approval也不能在同trial下绕过DB身份检查。候选构包不签发许可，不重置trial。

首轮候选开发按当时治理指令完成文件状态矩阵检查；后续紧急发布未继续执行已撤销的治理流程。M2消费延迟与M3历史补数不属于此次容量热修复的完成结论。
