# HTX Python双实例evidence优化

日期：2026-10-29；状态：active；WIP=1。
分支codex/evidence-retention-optimization；工作树/Users/lichangzheng.ray/Ray/CoinStrategy-evidence-retention；固定基线c00cbac653d7cd204f4af2f21781f6ffe4bf38f1，独立于事件交易分支。桌面工具拒绝嵌套业务仓sourceRoot，已用原生git创建隔离工作树。

## 已批准的实现契约

必要数据去重压缩后长期保留、不自动过期；连续闭合K线及修订、每次实际入场/退出评估（包括拒绝/无信号/未成交）、持仓状态变化和成本必须可追溯。不新增分钟快照、不改变策略节奏/公式/风险参数。PAPER启用显式compact-v2，OBSERVE/LIVE保持旧写入。稳定K线/窗口与观测时间分离；gzip按未压缩规范内容hash。旧JSON/manifest/引用继续可读。现有paper-state恢复结构与MySQL镜像不迁移。

写盘故障：evidence失败阻止新增风险，但行情有效时继续既有持仓管理；后续entry失败不得撤销已完成manage；账本无法保存时明确故障。错误重复计数/状态变化。连续性从初始完整窗口续采，独立补采线程不拖慢交易轮询，不伪造缺口完整性。

历史工具只读预览：统计占用/类别/重复/范围/引用/归档映射/预计释放量；所有未知对象及缺失PAPER引用默认保护，无删除命令、定时清理或自动改旧对象。历史清理与生产部署另批。禁止修改生产evidence、MySQL、env、current和paper-state。

## 任务清单
- [x] E1：SSH恢复；两实例WorkingDirectory对应c00cbac6包，间隔45秒，根盘84%/9.6G可用。只读文件盘点见server-baseline.json；没有修改服务器。
- [ ] E2 in_progress：稳定K线/窗口、压缩兼容、独立连续补采与检查点。
- [ ] E3 决策、持仓与成本关联。
- [ ] E4 evidence故障与权威账本故障隔离。
- [ ] E5 只读盘点/引用保护/迁移清理预览。
- [ ] E6 验证 / 自检、打包/部署与回滚材料。

## 验证矩阵
重复1000轮无新大对象；修订/分页断档/日线锚点/重启不丢进度；15m/4h固定输入与旧行为等价；拒绝/无动作退出/部分成交退出可追踪；文件/索引/账本故障独立注入；旧JSON+gzip/哈希损坏/缺引用/迁移中断；本地容量对比与真实公开API；Python全套、结构校验与检查点。

首次上线仅未来写入切换，历史文件保留。批准后先15m两bar再4h一bar验证；本轮只准备包和说明，不上线。
