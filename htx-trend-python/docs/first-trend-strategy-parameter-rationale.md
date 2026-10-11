# 首个趋势策略参数依据

> 版本：v0.1（2026-10-11）｜对象：HTX USDT 本位永续，Python 体系｜依据：《CEX 趋势交易策略基础研究报告》v1.0（下称“基础报告”，编号 [n] 对应其参考来源）与团队《HTX Python 策略研究与运行框架重建计划》（下称“框架计划”）。
> **下表所有数值均为“示例 / 经验起点”，须在冻结数据上验证，不是研究结论或最优值。** 本文不构成投资建议。

## 1. 策略骨架（写入策略卡片，铁律阶段 1）
多回看期时间序列趋势（收益符号或 Donchian 突破）等权集成 → ATR 止损 → 波动率目标定仓 → 衍生品数据**只减仓不加仓** → t 收盘出信号、t+1 成交。核心品种先选 BTC/ETH：容量大、成本低、数据质量好 [5]。

## 2. 参数与依据

| 参数 | 起点（示例，待验证） | 依据 |
|---|---|---|
| 周期 | 4h 与 1d 两套独立运行，不混合调参 | 加密 TSMOM 在日频、周频上显著 [1]；4h EMA 21/55 属行业惯例 [基础报告 §2.2]；框架计划的采集器已覆盖 4h、1d。日线切分时刻是隐含参数（HTX 费率与统计口径用 GMT+8），UTC 00:00 与 GMT+8 00:00 两种切法都要做稳健性检验 [基础报告 §1.1] |
| 回看期集成 | 1/2/3/4 周等权：1d 取 7/14/21/28 根，4h 取 42/84/126/168 根（同一天数换算） | 有效期集中在 2–4 周，超过约 1 个月出现反转 [12]；CMOM 用 3 周回看 [3]；用多回看期集成对冲参数不确定性 [5][13]。1 周腿最需怀疑：修正幸存者偏差后，1 周动量不再显著 [14]。[5] 的回看期是 5–360 日，“>4 周”可以另立一组预注册对照 |
| 信号形式 | 每条腿输出 +1/0/−1（或突破状态），集成值取平均 | Donchian 集成 [5]；价格/均线比值的可预测性 [4]；海龟突破规则 [28] |
| C01 threshold（集成阈值） | 0.25（即 4 条腿平均值 >0.25 才触发开仓） | 4腿等权，平均值 >0.25 意味着至少2条净多头（+1）且另2条≥−0.5，或3条+1和1条−1等组合。设计意图：过滤噪音，要求多数腿一致才开仓。**该值属经验起点，须在训练集验证并与其他阈值（0.0/0.5等）做预注册对照。** |
| ATR 止损 | ATR 周期 14 或 20；初始止损 2×ATR；跟踪止损只朝有利方向移动 | 海龟：N = 20 日真实波幅均值，止损 2N，单笔风险 1% [28]；用通道中轨作只向有利方向移动的跟踪止损 [5]；ATR(14)/2×ATR 算例 [基础报告 §4.2]。止损成交另加压力滑点（强平连锁、ADL）[22][26] |
| 单笔风险 | 每笔 0.5%–1% 权益（示例） | 海龟 1% [28]；0.5% 算例 [基础报告 §4.2] |
| 波动率目标 | 年化目标 10%–25%；波动估计窗口 20/60/90 日 EWMA（4h 按 √(6×365) 年化）；名义上限 2 倍；权重漂移超过 20% 才调仓 | 波动率目标能改善夏普、压缩左尾 [27]；[5] 用 25%、90 日估计、2 倍上限、20% 调仓阈值；AQR 用 10% [13]；公式见 [基础报告 §4.1] |
| 杠杆上限 | 由波动率目标推导；用“单日 −20% 不触发强平”做压力测试，并优先逐仓 | [基础报告 §4.3]（算例）；ADL 风险 [26] |
| 资金费/OI | 只做**减仓/收紧止损**，不开仓、不加仓、不反手。示例：7 日平均资金费 > 近 1 年 90 分位 → 仓位减半；OI 的 N 日变化率处于高分位 → 收紧止损；价格新高但 OI 下降 → 降低权重 | 资金费不是可靠的收益预测因子 [7][24]；OI 只代表杠杆存量 [25]（从业者来源，可信度较低）；资金费长期偏正，反映多头杠杆需求 [9]；carry 反映杠杆拥挤 [8]。用滚动分位数标准化，区分“预测资金费”与“已结算资金费”，防止前视 [基础报告 §6.3] |
| 资金费成本 | 按 HTX 历史结算资金费与结算时刻逐期计入 | [17]（以 Binance 为例；结算频率会调整，HTX 需用自己的历史数据） |
| 成交假设 | t 收盘出信号，t+1 成交；四种成本场景（无成本/乐观/基准/保守） | 前视偏差的对策 [基础报告 §5.4]；框架计划 §二.4、阶段 7 |

## 3. 预注册与过拟合控制（铁律阶段 9–10、12）
- **试验次数入账：** 每个参数组合、条件增删都写入实验账本，失败和被拒绝的也要记，用于 Deflated Sharpe Ratio [16] 与 PBO/CSCV [15]；每周跟踪累计试验数 [基础报告 §7.2-E]。
- **预先写死：** 参数网格（只在上表邻域内取值，看参数平原，不挑尖峰）、60%/20%/20% 时间切分、DSR/PBO 接受阈值、回撤限额和成本场景，都在跑实验前写进策略卡片。测试集只在候选冻结后运行一次（框架计划 §三）。
- **筛选不等于准入：** 仓库已有门槛“样本数 ≥ 30、PF ≥ 1.30、净收益与期望为正”只用于初筛；还须通过 DSR/PBO、分牛/熊/横盘子样本检验、walk-forward，以及至少 14 天 PAPER（框架计划；[基础报告 §5.4]）。
- **样本：** 使用时点宇宙，记录合约上线日期 [14]；首轮约六个月的数据可能覆盖不了多个牛熊周期，样本不足时如实报告“证据不足” [基础报告 §5.4]。

## 4. HTX 费率与保本说明（2026-10-11 查询）
- **官方：** HTX《USDT-Margined Futures Trading Fees》以 Prime 0 为例，USDT 本位永续开仓、平仓均为 **Maker 0.02% / Taker 0.06%**，按成交名义价值计费 —— https://www.htx.com/en-in/support/900000089923/ 。该页注明“费率仅作示例，以账户 My Fees 为准”，页面发布于 2022 年；官方费率总页 https://www.htx.com/fee/ 的合约费率表由页面脚本加载，本次没有抓取到，实盘前须登录核对。第三方站点 FeeEdge 在 2026-08-31 核验的数值与此一致（https://feeedge.com/exchanges/htx ，非官方来源）。
- **保本算术（按名义价值，不含滑点与资金费）：** 一次往返的手续费，Taker+Taker = 0.12%，Maker+Taker = 0.08%，Maker+Maker = 0.04%。也就是说，每笔交易的平均毛收益（占名义价值）必须超过“往返手续费 + 滑点 + 持仓期净资金费”才能保本；年化手续费拖累 ≈ 年往返次数 × 往返费率（波动率目标调仓产生的换手也要计入）。HTX Prime 0 的 Taker 比基础报告所列 Binance VIP 0 的 0.05% [30] 高 0.01 个百分点。4h 周期换手更高，对成本更敏感，应优先研究 maker 执行。

## 5. 引用来源（编号同基础报告）
[1] https://academic.oup.com/rfs/article/34/6/2689/5912024 ｜[3] https://onlinelibrary.wiley.com/doi/abs/10.1111/jofi.13119 ｜[4] https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3115846 ｜[5] https://papers.ssrn.com/sol3/papers.cfm?abstract_id=5209907 ｜[7] https://arxiv.org/html/2212.06888v5 ｜[8] https://www.bis.org/publ/work1087.pdf ｜[9] https://www.andrew.cmu.edu/user/azj/files/CarryTrade.v1.0.pdf ｜[12] https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3913263 ｜[13] https://www.aqr.com/Insights/Research/Journal-Article/A-Century-of-Evidence-on-Trend-Following-Investing ｜[14] https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4287573 ｜[15] https://www.carmamaths.org/resources/jon/backtest2.pdf ｜[16] https://www.pm-research.com/content/iijpormgmt/40/5/94.full.pdf ｜[17] https://www.binance.info/en/support/faq/detail/360033525031 ｜[22] https://www.coindesk.com/research/market-spotlight-the-19-billion-liquidation-that-shook-crypto ｜[24] https://papers.ssrn.com/sol3/papers.cfm?abstract_id=5576424 ｜[25] https://axeladlerjr.com/bitcoin-open-interest-funding-rate/ ｜[26] https://www.bybit.com/en/help-center/article/Auto-Deleveraging-ADL ｜[27] https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3175538 ｜[28] https://www.newtraderu.com/wp-content/uploads/2011/11/turtlerules.pdf ｜[30] https://www.binance.info/en/support/faq/detail/360033544231
