# 分段封存交付审查

候选基于 support_htx_python@87eaf55f；Python 本地封存、独立 COS 上传和按批准清单释放。生产默认关闭，不按文件年龄删除。

## 审查结论

正确性：代成员、引用闭包、必需 consumer、持久 mutation、独立故障隔离、删除 INTENT/DONE 和原布局恢复闭环。旧历史默认 external:legacy-enrollment pin，冻结/外部研究依赖可显式 pin，不把无已知运行时引用当可删。

测试：28项分段定向测试；全回归与真实COS隔离演练结果见同目录原始输出。运行时原有策略测试保留；SQL业务迁移为0。仅隔离数据演练了unlink，不动生产原evidence。

设计：source .sealing控制索引与原权威账本分离；后台scheduler只上传，回测用独立cache。每次清单审批和每批短独占交接两层检查，网络不持独占锁。source转入分段后，旧全量adapter显式拒绝，避免生成不完整“全历史”视图。

风格：沿用项目Python文件CAS/显式CLI模式，新增模块均在htx_trend/sealing；无Java修改，无生产配置变更。

性能/安全：离线纳管分批提交且按64MiB/20000成员分代；scheduler每轮最多4代且清生成暂存；capture仅布局变化时重扫，consumer心跳不全量stat。哈希基于原内容/实际归档字节，计划SHA绑定且复用approved_plan的SQLite/WAL守卫。COS凭据不进仓。

## 幂等矩阵

| 状态/入口 | 证据 |
|---|---|
| 处理中/上传失败 | 未封存或未确认consumer不释放；COS失败保留本地并继续put |
| 成功/重复 | 上传回执核对；二次释放不重复unlink；本地恢复可重试 |
| 发布中断 | mutation保留，离线重扫重新纳管孤儿并保护历史 |
| unlink中断 | 缺失物理文件不接受pin；可通过INTENT原位恢复 |
| 并发/变化 | 多线程/嵌套SH；EX前新pin、fault fence、revision改变阻止删除 |
| 输入变体 | plain/gzip CAS、capture、跨代refs、latest修订、legacy enrollment |
| 补偿与原路径 | 独立restore/cache、原路径restore-local、旧包layout-check |
| 故障隔离 | begin/ready/roots/mutation失败仍保留原管理调用与返回 |

## 缺陷回归

DL-077：未知历史及孤儿仍保护，分段纳管历史默认pin。
DL-078：释放复用不可变approved_plan，远端manifest成员绑定，apply前完整远端恢复校验。
DL-079：scheduler退避失败保留DEGRADED；源合同改变需新workspace，sealed源不继承可变快照。
DL-080：现有cache-run继续继承lease FD；本轮未改动该路径。
DL-081/082：本轮交付前审出的交接/物理恢复/控制错误隔离缺陷已修并形成测试；非生产事故。

## 明确边界

compact_latest全历史最新K线修订锚点、global index和控制元数据仍常驻本地；不承诺全量历史下沉或磁盘绝对有界。LIVE/realtime CAS仍默认完整保护；只支持其capture分段。旧包回滚须先恢复完整本地布局，空间不足不能跳过。外部研究冻结根须显式pin或转独立远端缓存；旧历史pin按具体SHA审核解除，释放仍另批。未启用生产timer、开关、删除或重启服务。
