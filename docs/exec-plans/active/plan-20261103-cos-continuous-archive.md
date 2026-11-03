# COS 连续归档、回测缓存与历史释放
2026-11-03 · Codex · active · WIP=1

## 目标
按用户确认方向完善独立 Python 归档进程、增量上传、回测按需缓存和可审计的本地历史释放规则；验证完成且无阻塞时合入 support_htx_python。沿用 codex/cos-evidence-archive@9bcbf669；远端目标基线2502786d。

## 成功标准
无变化轮次不新增 COS 对象；未变化不可变对象不重读；变化、新修订和引用闭包仍完整。失败同任务恢复，独立退避，交易零接线。缓存限额/并发锁/使用中保护/哈希验证。释放预览默认保护活跃源、账本和未知引用，隔离样本完成恢复及释放演练。Python回归、真实COS接口测试、结构校验、目标分支提交核验。

## 范围与非范围
包括 Python 代码、配置、独立 systemd service/timer、使用说明及候选打包材料。已授权编码和条件合并；不部署/启动生产定时器、不删除生产原始文件、不启停交易、不变更MySQL或凭据权限。五类来源不扩大。方向已明确，不另选交易策略。

## 里程碑
- [x] M1 接口与依赖核验、状态合同和方案落档（completed，2026-11-03）
- [x] M2 增量归档及独立调度、故障恢复（completed）
- [x] M3 回测按需下载、本地有界缓存与使用锁（completed）
- [x] M4 验证后释放的规则、预览及隔离演练（completed）
- [ ] M5 验证 / 自检、遗留审查与条件合并（in_progress）
日期以实际完成填写，不虚报工期。

## 风险与缓解
活跃CAS引用依赖：整源保护，不引入交易进程远程读取。COS失败：独立进程和持久化退避，不共享交易锁。缓存删除竞态：锁覆盖整个回测进程；只淘汰工具自有缓存。历史删除：精确清单、全量恢复校验、源冻结声明和再校验；生产不执行。增量指纹非内容哈希：仅继承已验证目录及相同路径/文件身份，新增和变化强校验；周期审计/显式全读验证。

## 验证方式
同输入无变化/修订/重启/并发/远端失败/源变化；缓存命中时断网可用、损坏拒绝、占用上限；仅隔离历史目录释放及重试/变更拒绝。所有测试结果落业务仓 docs/exec-plans/evidence/cos-continuous-20261103/。

## 状态契约与入口
来源身份=namespace+规范配置；周期任务=source+持久代次，PENDING→UPLOADING→VERIFIED→IDLE；失败保留同job，退避不跳过。缓存身份=namespace+不可变manifest+选择集；EMPTY→RESTORING→READY→LEASED→READY→EVICTED；失败无READY。释放=不可变manifest+源目录+具体文件哈希+冻结声明；PREVIEW→VERIFIED_PLAN→APPLY→RECEIPT；任何变化拒绝。CLI与timer使用相同入口，不复用交易outbox。

## 收敛记录
run-from-goal多步实现类；复用既有设计合同，按start-exec-plan→impl→review-pr→close-exec-plan推进。Explore按AGENTS#10只读代理核验。用户已授权实现和满足条件后合并。

阶段证据：50项归档回归已通过。审查补SQLite WAL清单绑定、删除成员与远端对应、退避健康误报、源快照BLOCKED/reset、子进程继承缓存租约。实际历史15m全量catalogue331378816B，日常新增v2增量目录；7d或32代全量审计限制链长，旧v1仍可读。开始真实COS隔离演练及全回归。
