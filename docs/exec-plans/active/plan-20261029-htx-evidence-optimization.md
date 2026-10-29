# HTX Python双实例evidence优化

日期：2026-10-29；状态：active；WIP=1。
分支codex/evidence-retention-optimization；工作树/Users/lichangzheng.ray/Ray/CoinStrategy-evidence-retention；固定基线c00cbac653d7cd204f4af2f21781f6ffe4bf38f1，独立于事件交易分支。桌面工具拒绝嵌套业务仓sourceRoot，已用原生git创建隔离工作树。

## 已批准的实现契约

必要数据去重压缩后长期保留、不自动过期；连续闭合K线及修订、每次实际入场/退出评估（包括拒绝/无信号/未成交）、持仓状态变化和成本必须可追溯。不新增分钟快照、不改变策略节奏/公式/风险参数。PAPER启用显式compact-v2，OBSERVE/LIVE保持旧写入。稳定K线/窗口与观测时间分离；gzip按未压缩规范内容hash。旧JSON/manifest/引用继续可读。现有paper-state恢复结构与MySQL镜像不迁移。

写盘故障：evidence失败阻止新增风险，但行情有效时继续既有持仓管理；后续entry失败不得撤销已完成manage；账本无法保存时明确故障。错误重复计数/状态变化。连续性从初始完整窗口续采，独立补采线程不拖慢交易轮询，不伪造缺口完整性。

历史工具只读预览：统计占用/类别/重复/范围/引用/归档映射/预计释放量；所有未知对象及缺失PAPER引用默认保护，无删除命令、定时清理或自动改旧对象。历史清理与生产部署另批。禁止修改生产evidence、MySQL、env、current和paper-state。

## 任务清单
- [x] E1：SSH恢复；两实例WorkingDirectory对应c00cbac6包，间隔45秒，根盘84%/9.6G可用。只读文件盘点见server-baseline.json；没有修改服务器。
- [x] E2：compact candle/window、gzip旧JSON兼容、PAPER显式开关及独立repair线程；1000轮×4周期无新大对象、修订/分页/重启/损坏测试，23项相关测试通过。
- [x] E3：实际输入原文/稳定窗口、版本配置、entry/exit和execution result引用关联，拒绝/无信号及前后状态/费用保留；23项PAPER/执行相关测试通过。
- [x] E4：完整行情下evidence失败禁新风险、允许管理并推进已管理bar；权威snapshot失败阻止继续执行并重试保存；47项PAPER/恢复/镜像测试通过。
- [x] E5：只读metadata/类别/引用/冻结根/语义重复/无损压缩映射预览；不打开索引，无删除入口；3项快照零改写/截断/损坏/缺引用/未知保护测试通过。
- [ ] E6 in_progress：验证 / 自检、打包/部署与回滚材料。

## 验证矩阵
重复1000轮无新大对象；修订/分页断档/日线锚点/重启不丢进度；15m/4h固定输入与旧行为等价；拒绝/无动作退出/部分成交退出可追踪；文件/索引/账本故障独立注入；旧JSON+gzip/哈希损坏/缺引用/迁移中断；本地容量对比与真实公开API；Python全套、结构校验与检查点。

首次上线仅未来写入切换，历史文件保留。批准后先15m两bar再4h一bar验证；本轮只准备包和说明，不上线。

## E6 验证进展与已知边界

- 真实HTX公开15min/60min/4hour/1day接口读取、160根精确重建及162对象闭包通过，见 public-smoke.json。
- c00cbac6与当前Python固定历史回放全部业务报告字段一致，source_sha256分别留档不作业务比较；见 equivalence.json。
- 15m/4H的HOLD→ARM→PARTIAL→FULL，保护状态、成交/费用及重启恢复等价；真实候选、拒绝和maker未成交测试通过。
- 追加A→B→A修订、异常原文、SQLite真实只读写失败恢复、并发计数、账本PROVISIONAL/确认回执和崩溃弃置测试。
- 合成1000次同窗口：每周期legacy2000对象约39.8MiB，compact161对象约0.79MiB（含本地索引）。这是无实际新决策的压力样本，不代表线上节省比例；见 capacity.json。
- 编译、CLI self-test、审计命令启动、图XML及diff校验通过；最终Python全套输出见 pytest.txt。
- 部署与历史删除未执行；根check-docs-sync仍有20项既有H-10历史轨迹缺commit，见 root-structure-check.txt，未伪造历史或夹带修复。
- 完整旧包回滚及生产15m两bar/4H一bar观察留给单独批准后的部署验收，不能视为本地已完成。新包中默认旧格式，新格式需显式env。
- 手工调用链复核替代不可用codegraph；数据流程图、幂等矩阵、材料索引见 docs/architecture-refactor/evidence/evidence-optimization-20261029/README.md；运行说明见 htx-trend-python/release/EVIDENCE-RETENTION.md。
