# LIVE capture 本地冷热保留候选

保留全部原始行情、原压缩字节和引用，释放应用热区配额；不上传 COS，不自动删除历史。

作者：Codex；日期：2026-11-06；状态：本地候选，未合并、未部署。准确基线：`7a9620bfb5b3261b3bb88ba46d0ed85e43bd8729`；候选分支：`codex/htx-capture-local-archive`。

## 结论

本地归档和无损编码优化已实现。历史 gzip 留在同盘冷目录，原 hash 路径作为相对软链接保持可读；热计量与冷保留量分别报告。它解决的是应用活动集持续累积，不减少磁盘上已有 gzip 的物理占用，也不证明行情消费积压已经修复。

策略、2秒 fresh 标准、交易额度、退出逻辑、trial 身份和许可到期时间均未改动。生产仍运行旧包；本候选不能直接套用旧实盘许可。

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
# 只有显式开启时才必须设置。生产值待预算确认；5GiB为建议而非已设置。
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

## 生产前必须解决的事项

1. 用户选择许可衔接方式：实现并审核**同trial、保留原计数/时限/损失/限额的源码与包绑定迁移**，或等待旧trial结束后另行授权新trial。本次未实现前者，不能仅修改approval JSON或DB字段绕过。
2. 明确总磁盘预算、保留余量及LIVE停止/切换窗口；切换前重新查交易所持仓和挂单，数据库旧状态不能代替交易所原子快照。
3. 确认后再整合目标分支、构建最终包、停旧writer并按批准流程切换；验收实际source/package、trial未重置、原引用、热/冷计量、新行情尾部和健康状态。

现有 `Trial.__init__` 会对 saved binding/limits 全量比较；旧许可绑定source=7a9620bf及旧package，且北京时间2026-11-06 22:26:45.361到期。仅签发新approval也不能在同trial下绕过DB身份检查。候选构包不签发许可，不重置trial。

本轮遵循 start-exec-plan / karpathy-guidelines / developer-mindset 的最小范围和可验证实施，并按 idempotency-state-machine 校验文件状态矩阵。M2消费延迟与M3历史补数未提前实施。
