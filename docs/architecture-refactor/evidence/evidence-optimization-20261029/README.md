# Evidence 优化验收证据

日期：2026-10-29；所有测试为本地隔离验证；生产仅只读核验，未部署或删除。

| 材料 | 证明范围 |
| --- | --- |
| server-baseline.json | SSH实测文件数、logical/allocated bytes及白名单运行配置；运行中遍历，非原子快照 |
| server-package-manifests.json | 两个安装目录 MANIFEST 绑定 c00cbac6；不以分支 tip 代替包版本 |
| public-smoke.json / public_smoke.py | HTX公开接口4周期真实读取、160根重建一致、引用闭包通过；非私有成交API |
| equivalence.json / verify_equivalence.py | 隔离进程 c00cbac6 与新代码固定历史回放的全部业务报告字段一致；源码哈希作为溯源单列 |
| capacity.json / benchmark_storage.py | 本地固定窗口1000轮新旧占用对比，包含SQLite；合成无新决策，不代表线上节省率 |
| pytest.txt | Python全模块回归最终结果 |
| evidence-flow.drawio | 采集/去重/评估/持仓管理/确认/只读盘点和故障分支 |
| package-receipts.json | 候选打包、完整性验证、解包后CLI自检；不是部署凭据 |

运行与回滚：[EVIDENCE-RETENTION.md](../../../../htx-trend-python/release/EVIDENCE-RETENTION.md)。
实施计划：[plan](../../../exec-plans/active/plan-20261029-htx-evidence-optimization.md)。

## 幂等与故障验收矩阵

稳定身份：K线=HTX/合约/周期/open_time/规范内容；窗口=有序版本引用；实际决策=run关联输入；执行结果先PROVISIONAL，再由实际已发布账本SHA256确认。事件交易身份与本任务无关。

| 入口 / 状态 | 验收证据 |
| --- | --- |
| 首次采集 / 成功 / 重复轮询 | test_compact_evidence：4周期1000轮无新增大对象，重启同哈希 |
| 内容输入变体与修订 | 规范Decimal、raw时间变动不改身份；A→B→A独立修订引用；latest成功但stream checkpoint失败后，快路径核验并恢复版本索引 |
| 同时写入 | 共享RLock和SQLite；4线程32次同窗口一次对象写入且计数完整 |
| 文件或索引明确失败 | 模拟文件/检查点中途失败、真实SQLite query_only写入错误；覆盖检查点不推进，恢复补索引 |
| 历史补偿 | 分页缺口/重复页/日线锚点；失败不推进；重启后由相同coverage_end继续 |
| 实际entry成功/拒绝/未成交 | test_compact_paper：真实候选/门/成交及maker未成交与legacy一致，含无信号 |
| 持仓HOLD/ARM/PARTIAL/FULL | 15m/4H固定输入输出/保护/成交/费用等价；最新账本重启恢复 |
| evidence失败 | 禁新增风险，保持已有持仓评估；不会因后续entry/result证据错误回滚退出 |
| 权威账本写失败 / 补保存 | checkpoint故障后不继续交易；重试发布；崩溃遗留PROVISIONAL不获确认 |
| 读取兼容 / 迁移中断 | 旧JSON/新gzip共存、闭包/损坏/缺引用；对象先文件后索引，重试恢复 |
| 清理入口 | 只读盘点前后文件hash/mtime相同；未知引用/冻结/状态全保护，无删除入口 |
| 目标分支 | codex/evidence-retention-optimization，本地检查点和包内source_commit可追溯 |
| 生产生效 / 旧包回滚 | 未执行，需另批；不能以本地测试冒充上线验收 |

codegraph工具不可用：人工逐调用点核对 market_data → CompactEvidence → PaperTrendWorker → PaperTrendRuntime → PaperFileLedger/PaperExchange；无伪造codegraph结果。根仓H-10既有历史轨迹缺commit独立阻断治理全绿，不混入本分支修复。
