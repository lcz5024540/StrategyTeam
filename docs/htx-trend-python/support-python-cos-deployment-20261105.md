# support_htx_python 合并与数据/COS部署

日期：2026-11-05；作者：Codex；用户已确认合并，并从support_htx_python部署采集/COS。现有LIVE实例不动。

## 整合结果

将792a92bc合入56758aa4，并在mdqueue-b流循环中保留ReconnectGovernor、分批订阅、DEPTH catch-up与分层heartbeat。新增最新闭合K线校验、continuity/stale_kline_channels及每连接尝试一次的GAP_RECOVERED；控制消息和队列溢出不报恢复，不宣称全池或历史缺口补齐。unit允许容量保护退出后重试并联动两个COS timer，容量上限、磁盘下限和释放授权边界不变。

实际全套 **1387 passed，0 skipped**，含12项隔离MySQL；89条既有SQLite弃用警告，72.86秒。[终端末段](evidence/support-python-cos-deployment-20261105/tests-tail.log)。采集定向37通过；compileall与diff检查通过。

## 自审

结论：approve工程整合；不授权新A/B实盘。Correctness：最新闭合窗、状态与恢复标记符合既有修复语义，失败不报恢复；Tests：15m/1h/4h边界、控制消息、catch-up合并、overflow、heartbeat和全套回归通过；Design：既有流循环语义适配，不回退旧backoff；Style：沿用模块命名，改动局限4个原文件与证据；Performance/Security：无新增每事件IO，heartbeat不携带channels大映射，不加载交易凭据。超100行以回归/证据为主，保持一份可复现merge，不拆散冲突解决。

缺陷gate：无相关active条目；已resolved的DL-082/083回归随全套通过，未复发。已有COS修复此次整合，不重复登记新事故。只读Explore已核对发布入口和固定release绑定；codegraph工具本轮不可用，未伪称调用。

## 发布约束

从目标分支干净提交构建 `release/package.py build --paper --realtime`，不使用LIVE构建或QA后验。仅运行public_capture及archive，PAPER配置不作交易进程启动参数。

数据current独立于交易current；只更新data unit和版本env。archive包含线上cadence drop-in，不覆盖；maintenance/release保持冻结869660ab源码和原授权，archive/sealing源码已与当前整合版本比较一致。原池52项与capture身份保留，停止/启动形成真实采集缺口，不伪称无缝。

部署步骤与回滚边界见运行手册 `htx-trend-python/release/cos/15m-data-deployment.md`。部署前备份旧current、data unit/env，等待COS当前任务自然完成后切换；失败恢复旧独立current/unit/env，不删除采集数据或COS对象，不碰交易DB。

## 部署回执

已完成工程合并推送与独立采集/COS部署；**没有启用新A/B LIVE**。开发分支merge提交及目标分支发布源码为 `4fa6ccee67aae7635aa2e3393f314977912ddc14`，包含56758aa4与792a92bc，远端已核对。发布后仅追加本报告/证据，不改运行源码。

| 项目 | 实测结果 |
|---|---|
| 包 | `0.1.7-realtime-candidate-4fa6ccee`，从干净support_htx_python构建；SHA256 `18c4c3d0e3ad68e569d645c87445b48ebb7f2eed40c18e5bc90f43826b6f66c1`；本地与服务器verify通过 |
| 切换 | 北京时间19:32:07开始停止旧采集，19:32:10新进程启动；独立current已更新。该时段有计划内采集间隔，不声称零缺口 |
| 数据服务 | PID3083613，19:35:43核对active、NRestarts=0、stop_reason=null；队列0，高水位493/65536，catch-up结束、重连熔断关闭 |
| 新行情 | 52个FRAME窗口已采齐，stale_kline_channels为空；累计BBO62813、DEPTH32733、TRADE584；status年龄2.235秒 |
| 剩余告警 | 该时点28个BBO/DEPTH通道超过2秒，因此continuity=DEGRADED；部署前同类告警已存在。不调宽阈值，不宣称全通道HEALTHY或历史缺口补齐 |
| COS归档 | 按手册显式封存438代；19:34:14–19:34:56上传service退出0，后续只读GET校验153文件/46,911,137字节全部通过；样本302条包含98 DEPTH/203 BBO/1 FRAME，时间全部晚于新进程启动 |
| COS释放 | 原策略/授权、冻结maintenance/current与unit未改；19:35:07–19:35:36原定时释放service成功。未手工删除历史payload或COS对象；既有释放策略回收的payload仍可从已验证COS恢复 |
| 受保护实例 | 旧LIVE PID2624006、4H PID705510及进程env/unit哈希未变；共享交易current仍f57c409f，未续签/改账户/开新策略 |
| 兼容与回滚 | 固定旧版本archive 8个及sealing 5个Python文件与新版本逐字节相同；旧数据版本00765365、unit与env备份均保留 |

[部署/包/旧状态证据](evidence/support-python-cos-deployment-20261105/deployment.json) · [运行快照](evidence/support-python-cos-deployment-20261105/final-runtime.log) · [安装成员及不变项校验](evidence/support-python-cos-deployment-20261105/installed-verification.json) · [COS完整下载与样本](evidence/support-python-cos-deployment-20261105/cos-verification.json) · [部署图](evidence/support-python-cos-deployment-20261105/flow.drawio) · [AST证据（非codegraph）](evidence/support-python-cos-deployment-20261105/code-relations.json)。

服务器包、备份与操作脚本位于 `/home/ubuntu/coinstrategy-cos-archive/deploy-20261105-4fa6ccee/`；保留供回滚审计，不重新执行switch脚本（它有旧版本前置断言）。新release位于 `/opt/coinstrategy/15m-market-data/releases/0.1.7-realtime-candidate-4fa6ccee`。

## 后续与复盘

1. 运维后续检查continuity、两类stale列表、queue与两个COS timer；当前报价新鲜度告警需单独归因，不能仅凭服务active判健康。
2. 如需回滚，由运维按运行手册恢复上述独立current/unit/env，保留新数据和COS对象；本次未触发回滚。
3. 新A/B实盘仍需账户持有人完成真实后验及匹配准入并操作最终启用，不能使用本包PAPER示例或既有过期SBOX许可。

做到了什么：功能与连续性修复均进入目标分支，从目标源码构建并实际部署、真实采集和COS校验完成。未达成/偏差：全通道HEALTHY未达成，原报价新鲜度告警保留；新策略LIVE不在本轮确认的部署范围。学到：技术上固定释放API需字节比对，流程上等待在途任务自然结束，协作上部署范围与实盘授权分离。下次：运行维护者针对有告警通道检查实际更新频率和来源，不擅自放宽阈值。沉淀：无新增已复现代码缺陷、EXP/KN或skill改动；codegraph不可用已在根tech-debt记录WAIVED并提供AST替代。根治理历史H-10另行留档，不影响上述业务提交和服务器事实，也不冒充根检查通过。
