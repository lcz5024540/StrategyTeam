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

尚未执行；合并、目标分支构建、服务器验收结果将在本节追加，不能以上述测试代替生产验收。
