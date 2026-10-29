# PAPER evidence compact-v2：运行与回滚说明

日期：2026-10-29；状态：本地候选，尚未部署。源码基线 `c00cbac653d7cd204f4af2f21781f6ffe4bf38f1`；分支 `codex/evidence-retention-optimization`。本次只使用 Python。

新格式保留闭合 K 线版本、实际决策输入、持仓评估和成交成本，减少同一窗口的重复全文。**没有自动删除或过期机制；切换未来写入不会立即释放历史占用。** 两实例的策略、公式、合约配置、45 秒服务循环及按新闭合 bar 评估的节奏沿用原值。

## 保留与去重合同

| 数据 | compact-v2 行为 |
| --- | --- |
| 闭合 K 线 | HTX/合约/周期/open_time/规范内容哈希；唯一内容 gzip 长期保留，修订及 A→B→A 变更独立留痕 |
| 完整窗口 | 有序 candle_refs、范围和完整性；观测时间独立放检查点，不再内嵌160根全文 |
| 无变化轮询 | 仅更新每个 stream 的有界检查点和观测计数，不增加完整 raw/window |
| 实际入场评估 | 无信号、拒绝、接受均保存指标/候选/门/配置版本及真实窗口、原始响应、盘口、资金费报价引用 |
| 实际持仓评估 | HOLD、武装、保护位变化、减仓、平仓均保存前后状态和实际 ExitInput；原退出规则不变 |
| 执行结果 | 订单、成交、费用、数量及上下文按 run/position 关联；未成交也留结果；p_shadow_taker 沿用 |
| 资金费 | 报价与结算费用区分；结算未知为 null/UNKNOWN，不能按0计算已知净收益 |
| 修订/异常 | 修订关联旧新版本和原始响应；异常首次原文、连续次数和恢复状态，避免逐轮异常全文 |
| 历史文件 | 原 JSON/CAS/manifest 不改写；未知归属、无引用对象默认保护 |

格式名是运行配置；CAS envelope 继续 `htx-python-evidence-v1`，新 `kind` 表示引用结构，哈希基于未压缩规范 JSON。新读取器同时支持 `.json` / `.json.gz`，验证哈希与引用闭包。研究 manifest 不改写。SQLite 仅新增本地索引表，无业务 SQL 迁移。

`compact_execution_result` 是 **PROVISIONAL**。只有成功发布的权威 `paper-state` 中包含相应引用，才生成 `compact_ledger_commit`，绑定实际账本文件 SHA256。读取者以确认回执和权威账本为准；崩溃前未提交的结果不能冒充成交事实。进程重启会补确认已落账本的结果，无需重复交易。

## 连续性与故障

首次完整窗口建立起点；覆盖范围仅为现有实例合约和周期（15m：15min/60min；4H：4hour/1day）。从持久 coverage_end 开始用现有公开历史分页补缺，每 stream 每次最多500根；独立客户端/线程逐源处理，每轮后等待60秒。日线按 HTX UTC+8 零点锚定。文件和索引先落盘、完整性验证通过后才推进连续检查点。补不到保留 coverage_end < target_end 的明确缺口，不插值。

正常交易循环与补采网络请求分开。两者仍共享磁盘及短时索引锁，实际延迟需上线观察；不承诺零磁盘竞争。服务轮询间隔与策略 bar 门不变。

行情有效而 evidence 失败：阻止新入场，仍管理已有持仓；已完成的有效管理不会因后续证据失败回滚。管理 bar 标记防止同 bar 重复退出。权威账本 checkpoint 失败：明确故障并先重试保存，保存成功前不继续模拟交易。MySQL 镜像保持原来的 JSON 权威与重试语义，不变更 DB。证据故障期间的详细输入可能缺失，账本显式标记 FAILED/INCOMPLETE，不能宣称故障期证据完整。

## 只读盘点与归档映射

```bash
PYTHONPATH=src python -m htx_trend.research.evidence_audit --evidence /path/to/evidence --max-objects 10000 > /separate/report.json
```

默认完整遍历文件元数据，最多深读10000个对象，非随机抽样；类别、唯一窗口、引用根和时间范围仅覆盖已解析样本。`--max-objects 0` 深读全量，可能产生较大报告和 I/O，只在资源允许时运行。工具不打开 SQLite、不创建 WAL、不跟随软链接。报告应输出到 evidence 目录之外。

输出 logical/allocated bytes、类别、唯一内容、引用根/冻结 manifest/缺引用/未知归属，以及原哈希到 `.json.gz` 的无损归档预览。gzip节省估算与语义重复估算可能重叠，不能相加。所有对象 `deletion_eligible=false`，`approved_reclaimable_bytes=0`。本版本**没有 apply/delete 命令**，不安装清理任务。

历史 PAPER 缺少完整输入引用，不能把 orphan 当垃圾。后续实际归档必须在单独批准的副本上验证旧新读取/引用闭包/恢复、原哈希映射和旧包回滚，再提交逐文件删除清单另批；当前预览不能直接授权删除。

## 候选配置、打包与部署闸门

默认 `HTX_PAPER_EVIDENCE_FORMAT=legacy-v1`。获批切换时仅为目标 PAPER 实例显式设置：

```text
HTX_PAPER_EVIDENCE_FORMAT=compact-v2
```

其他 OBSERVE/LIVE 的格式行为不切换。保留现有各自 `HTX_PYTHON_EVIDENCE_DIR`、状态目录、instance_key、策略版本、完整合约列表及所有风控配置，不用候选示例替换生产 env。15m 实测79合约、4H实测45合约；不是示例默认 BTC/ETH 两合约。

本地冻结候选：

```bash
python release/package.py build --paper --compact-evidence --out-root /local/frozen/evidence
python release/package.py build --paper-4h --compact-evidence --out-root /local/frozen/evidence
python release/package.py verify /local/frozen/evidence/<version>/<archive>.tar.gz
```

两个包分别绑定 primary/15m 与4h实例，版本后者带 `-4h`；MANIFEST/BUILD/sha256 绑定实际源码和内容。config.example 仅为示例，候选和打包验证不代表生产批准。

获批部署后的执行顺序：

1. 运维记录当时磁盘余量、增长、状态/配置/包哈希和恢复基线；核对两个实例使用独立目录。先确认余量足够，不能靠删除未知 evidence 腾空间。
2. 按现有冻结和安装流程仅切15m未来写入，保持现有权威账本，观察至少两个完整15m bar。核对连续检查点、决策闭包、持仓数量/费用、服务耗时和增长。
3. 15m通过后切4H，观察至少一个完整4H bar，同样检查且不更改交易规则。
4. 两者通过才记录部署验收。生产 SQL、重启、配置、包切换和历史清理本轮都未执行；不得将本地测试标成已上线。

## 回滚

优先在本次兼容新旧对象的二进制上设置 `legacy-v1`，恢复未来旧格式写入；保留新旧文件、SQLite扩展和最新 paper-state。不要恢复旧状态快照，否则可能重复开平仓。

`c00cbac6` 原包读取器只认 `.json`，**不能声称它能读取新 gzip 或完整解释 compact 引用**。若必须回旧包，先在隔离副本验证最新账本恢复与实例身份、原包原格式写入、MySQL镜像和新证据保留路径；新证据由兼容读取器审计。旧 evidence 不删除，不原地重写manifest。不具备上述验证时禁止把整套旧包回滚标成已验收。本轮已做新包旧新读兼容、最新账本重启演练；未进行生产或旧包真实环境回滚。

## 验证与证据入口

源码仓 `docs/architecture-refactor/evidence/evidence-optimization-20261029/` 保存 server-baseline、包清单核验、4周期 HTX 真实公开读取、固定基线回放、容量对比和 Python 全套输出；实施计划在 `docs/exec-plans/active/plan-20261029-htx-evidence-optimization.md`。数据流程图在同证据目录的 `evidence-flow.drawio`。

1000次固定窗口合成压力输入：每周期旧格式2000对象/约39.8MiB实际占用，紧凑格式161对象/约0.79MiB（含索引）；这是无新决策/修订的本地实验，不能外推为线上98%节省。实际决策、修订、缺口补采仍会增长，必要证据长期保留。
