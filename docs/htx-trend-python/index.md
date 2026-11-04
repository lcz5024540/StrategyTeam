# HTX Python交付索引

- [15m开仓执行修复、输入复算与部署材料](entry-fixes-20261102.md)
- [本轮执行计划](../exec-plans/active/plan-20261102-15m-entry-execution-fixes.md)
- [角色待办](ROLE-TODOS.md)

- [热部署持仓延续交付（2026-11-02）](hot-deploy-20261102.md)

- [15m实时结构候选交付、验证与未完成项](realtime-entry-20261102.md)
- [实时全机会回放协议](realtime-replay-20261102.md)

- [独立公共行情采集及容量约束](public-capture-20261103.md)

- [历史ETH结构诊断（2026-11-03）](ohlc-diagnostic-20261103.md)：六组参数、两条假设路径；不作真实成交或收益准入。

- [局部结构研究（2026-11-03）](local-structure-research-20261103.md)：已确认拐点与失败突破极值对照；未选择生产规则。

- [分钟结构与逐笔回撤研究（2026-11-03）](fast-structure-research-20261103.md)：真实新样本增加触发，未增加净R合格机会。

- [受控实盘M1基线与候选关口（2026-11-03）](controlled-live-trial-20261103.md)：最新基线整合及证据核验完成；候选未确认，后续控制实现和部署未开始。
- [入场失效目标配套研究（2026-11-03）](joint-price-research-20261103.md)：激活时冻结目标，修正几何错误但未增加净R合格机会；尾段单列。
- [配套目标研究审查与边界修复（2026-11-03）](joint-target-review-20261103.md)：复用9ca7dd59，修复同包毫秒和连接换代两项研究边界；未选择实盘候选。
- [新实时结构LIVE待启用打包入口](../../htx-trend-python/release/REALTIME-LIVE-PACKAGE.md)：`build-live --realtime`，身份独立、入场关闭；不提供生产或试运行授权。

- [试运行控制与管理模式发布（2026-11-03）](trial-controls-20261103.md)：10仓、20U锁止、独立准入与真实费用覆盖；新入场仍关闭。

- [五模式并集Python交易接入](indicator-union-20261104.md)：三档ATR退出与IOC/恢复验证，入口关闭。
