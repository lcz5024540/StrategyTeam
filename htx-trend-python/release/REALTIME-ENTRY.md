# 15m 实时结构入场候选操作说明

2026-11-02 · Python · 默认关闭新交易 · 当前收益准入 NOT_ELIGIBLE。

候选包包含 PAPER/LIVE 适配器、公共行情采集、全机会回放及显式SQL。包校验通过不构成生产迁移、部署或下单授权。新的策略名为 `HTX_REALTIME_STRUCTURE_15M_V1`，模型为 `RT_RANGE_REJECTION` / `RT_BREAKOUT_RETEST`；旧15m及4H默认入口不变。

## 本地验证

从 htx-trend-python 目录运行（已安装项目依赖的 Python 3.11+）：

```bash
PYTHONPATH=src python -m htx_trend.realtime capture --contracts ETH-USDT --seconds 30 --output /tmp/htx-rt-new-capture
PYTHONPATH=src python -m htx_trend.realtime verify --input /tmp/htx-rt-new-capture
PYTHONPATH=src python -m htx_trend.realtime audit-capture --input /tmp/htx-rt-new-capture --output /tmp/htx-rt-audit
python release/package.py build --realtime --out-root /tmp/htx-rt-frozen
python release/package.py verify /tmp/htx-rt-frozen/<version>/<archive>.tar.gz
```

capture 仅公开逐笔/BBO，不读取交易凭据。完整回放还要求当时可见K线、深度、合约、全部扫描/拒绝、资金费、真实延迟和CAS引用闭包；单独capture不能得到正式收益结论。`replay --input <tape.json> --sha256 <实际文件哈希> --evidence-root <CAS> --output <目录>`执行预登记六组、70%诊断与一次30%留出。缺证据输出未知，不填零。

## 配置与影子运行

1. 使用独立目录和明确实例身份。新PAPER示例为 `htx-realtime-paper-15m`；如替换既有15m实例，保留原 instance_key、paper-state 和初始权益，显式登记旧策略owner；不要把旧账本复制到另一实例冒充同一账户。
2. 沿用 HOT-DEPLOY.md 的持仓导出、实现向量、参数快照和预检流程。没有参数的旧仓必须先形成可验证的adoption；未知实现不得忽略哈希接管。旧4H实例和账户不进入此配置。
3. 参考 `realtime.env.example`；`HTX_RT_ENTRY_ENABLED=false`仅关闭新交易，仍采集、生成可观察结构、管理已有实时仓位。影子用PAPER禁入场；旧candle OBSERVE入口明确拒绝该策略，不能把旧K线候选标成新模型。
4. 公共WS≤20订阅/秒，事件/BBO双时钟默认≤2秒；缺行情、证据失败、未决结果禁止入场。结构刷新及compact历史补采后台运行；风控执行仍由唯一writer负责。capture上限默认2GiB，满后停止采样/告警并阻新风险，不删除旧文件。
5. 250ms PAPER延迟为示例假设，需替换为实测分布后才能正式验收。PAPER按后续新盘口L1容量模拟IOC，不能证明真实队列和成交；资金费结算证据缺失时也不得声称完整净收益。

## 生产前置（需另行批准）

1. 验证目标 support_htx_python 合入提交、冻结包及最终MANIFEST；保持原账户绑定、风险额度、C钟及独立存储。当前分支不自动合并/推送/部署。
2. DBA审核 `release/migrations/2026-11-02__htx_realtime_structure_plan.sql`（仓内原文件 `sql/htx/`）。仅LIVE需要此小型表；worker只验表，不建表。该迁移幂等且不修改旧订单/持仓表。生产数据库不在本次测试范围。
3. 按原准入机制登记真实模型、最终策略spec/config/package hash、账户和批准记录。示例SYNTHETIC材料、占位hash、candidate卡不能授权LIVE。`build-live`仍为旧15m打包入口，不能拿它冒充新RT策略包。
4. 完整实际数据先重现基线，再诊断选参、冻结选择并留出验证。两段净收益不降、组合回撤/手续费/一小时无效往返不增；失败不换参数重试留出。当前没有满足该要求的真实数据，保持入口关闭。
5. 当前V5明细接口已接入：`GET /v5/trade/order/details`按trade_id去重、id分页，updated_time为真实撮合时间；异步读取、writer核对当前累计数量/均价/费用后落盘。终态订单也每5秒重试，超过3天保守窗口标EXPIRED_UNRECOVERABLE；不会重放数量或重复收手续费。精确时间和BBO覆盖独立验收：缺覆盖标GAP，不用整根bar补造持仓极值或新启动保护；初始止损及可用的正常闭合bar退出继续。真实IOC、部分成交/撤单竞态仍须在获批环境实测。
6. 获准后先影子，再PAPER，再切换15m新入场；不得同时启动旧/新writer争抢账户。启用值及选中参数只在获批操作单中填写。

## 停止与回滚

SIGTERM先停止新机会、收口当前动作并持久化，再释放锁；不会因部署主动清仓。IOC未知结果查询同一client ID，部分成交保留实际量，余量终止，不追单补齐。保护退出重入仍要求新闭合bar及恢复价格。

回滚先关闭 `HTX_RT_ENTRY_ENABLED`，保留能解释新持仓的当前包继续恢复/管理。旧包不识别新模型/计划表/运行检查点时不能直接切回。保留原evidence、capture、paper-state、MySQL计划/订单状态及运行快照；不回滚SQL、不清空计划身份。后续只有在兼容预检通过或所有新仓/未决意图结束后，才按批准方案回切旧入口。
