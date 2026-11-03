# 活跃 evidence 分段封存
2026-11-03 · Codex · completed · WIP=0 · 业务分支 codex/cos-segment-sealing，基线87eaf55f。

## 目标
按用户指令补充分段封存，禁止按文件年龄删除。保留本地交易依赖，把确定边界的历史段归档到COS，只有校验成功且无本地引用才允许释放。

## 成功标准
多代写入/封存/归档/释放/恢复可重建同一原文；跨代引用、K线修订、未确认账本、持仓证明和盘口读者被保护；故障/重启/并发不误删。旧格式默认行为不变，交易不依赖COS。相关Python与全回归、真实COS隔离演练、打包和结构校验通过。

## 范围与非范围
Python文件证据与盘口采集，新增显式分段配置、局部控制索引、只读清单、依赖pin/lease、独立归档与具体清单释放。原MySQL/权威PAPER账本语义保持。代码测试与交付授权已明确；不启停生产、不自动启用生产删除、不扩容、不改变策略公式。旧数据纳管需显式离线初始化，未知归属不自动推断。

## 里程碑
- [x] M1 依赖核验与状态契约（2026-11-03）
- [x] M2 本地分代成员、pin/lease与安全切段（2026-11-03）
- [x] M3 封存段归档适配与精确释放/恢复（2026-11-03）
- [x] M4 写入者和持仓/采集恢复接线，部署说明（2026-11-03）
- [x] M5 验证 / 自检、真实接口演练与交付检查（2026-11-03）
完成日期按实际记录，不预填天数。

## 风险与缓解
跨代被引用文件误删：显式引用闭包与单调revision，释放前重验；正在评估和后台采集：进程/线程协作lease，禁止锁跨COS网络；账本失败：consumer状态不安全，禁止切安全边界/释放；旧包不认识冷数据：默认关闭，恢复全本地布局后才允许旧包回滚。

## 状态机与入口
对象身份保持原SHA。OPEN代→SEALED不可变成员边界→COS_VERIFIED→按精确清单释放非pin成员→RETIRED；current/未归档代、显式pin、未完成consumer保护。跨代依赖由真实refs递归求闭包，不猜时间。新写入与pin变化使revision递增。归档只处理已封存代，不触碰OPEN。删除意图先持久化，重启按原哈希/身份恢复；COS故障仅影响归档释放。

## 验证方式
同对象重写/修订；切段时有引用；评估中读取；账本失败/进程中断；旧outbox未启用仍保留语义；盘口配额释放后重新计量；封存代完整恢复与冷读明确失败；新旧reader兼容与旧包回滚说明。终端证据落业务docs/exec-plans/evidence/cos-sealing-20261103/。

## 接口事实
Explore：CompactEvidence.capture依赖compact_latest全部旧K线和current checkpoint；confirm_ledger仅需未确认EVIDENCE_RESULT；ContinuousCollector coverage历史与continuous热游标区别；LIVE Coverage.prove读取4096条BBO覆盖区间内的真实段；FrameLoader/backfill线程需协作lease。现有outbox并无runtime deliver调用，必须显式consumer合同，不伪造delivered。

## 验证进展
最终28项封存定向测试通过；全回归1077通过/7隔离MySQL环境跳过。真实COS隔离CAS/盘口两条链路全部通过；15m PAPER/4H PAPER/LIVE三种包验证通过，源码提交96d4ae7f。新增独立fault fence、发布前mutation、required consumer、INTENT缺文件恢复，覆盖崩溃/新pin/并发reader/原布局回滚。文档明确历史compact_latest锚点和全局索引仍保留本地，不宣称本地总量完全有界。

## 交付证据
实现提交96d4ae7f；分支codex/cos-segment-sealing；治理21项结构校验通过。原始输出：docs/exec-plans/evidence/cos-sealing-20261103/{python-regression.txt,real-cos-drill.json,package-checks.json,review.md}。操作手册htx-trend-python/SEGMENT-SEALING.md，禁用配置模板随包。目标分支合入按此前条件授权执行，最终远端哈希在治理memory/trace中记录。

## 复盘 (Retrospective)
- 做到了什么：本地代边界、完整引用归档、consumer交接/故障栅栏、批准清单释放、unlink恢复、外部冻结pin、真实COS原布局恢复均完成。旧历史默认保护，不靠文件年龄删除。
- 未达成 / 偏差：生产开关/timer/删除不在本轮授权内，未执行；全历史compact_latest和本地索引仍增长，LIVE/realtime CAS不切换，不宣称磁盘绝对有界。七项隔离MySQL测试未启，但本轮不变更业务DB。
- 学到了什么：技术上，DB逻辑状态不能代替物理文件存在；流程上，辅助存储异常与持仓管理必须单独验收；协作上，部署批准和代码合入不能混为一项。
- 下次怎么做：运维获部署批准后先单源离线纳管，再开启分段写入和仅上传timer，观察pending_generations、pin占比与增长；按具体SHA核清历史归属，才生成并批准释放清单。旧包回滚前恢复全本地并校验。
- 沉淀的模式：DL-081/082记录交付前审查缺陷并由28项业务回归固定；暂无需扩大为跨项目规则，技能流程无需修改。既有DL-077..080未重现。连续收敛：依赖契约、实现、验证三阶段均更贴近用户“分段封存、不按年龄删”的原意。
