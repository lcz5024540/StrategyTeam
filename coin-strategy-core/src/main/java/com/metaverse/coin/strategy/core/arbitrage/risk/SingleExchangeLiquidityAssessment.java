package com.metaverse.coin.strategy.core.arbitrage.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleExchangeLiquidityAssessment {

    /**
     * 流动性检查是否通过。
     *
     * <p>开仓阶段为 false 时会阻断开仓; 平仓阶段为 false 时通常只作为流动性告警记录，
     * 不阻断风险退出。
     */
    private boolean passed;

    /**
     * 是否使用了降级数据。
     *
     * <p>当前由 {@link SingleExchangeRiskService#evaluateLiquidityRisk} 在深度快照不可用
     * 或 depth monitor 回退到 fallback 数据时置为 true。出现该值通常表示盘口数据不足以支撑可靠的滑点判断。
     */
    private boolean fallbackUsed;

    /**
     * 本次流动性检查所属交易阶段。
     *
     * <p>常见取值来自 {@link SingleExchangeRiskService}: {@code PHASE_OPEN} 表示开仓前检查，
     * {@code PHASE_CLOSE} 表示平仓前检查。不同阶段会读取相反方向的盘口档位。
     */
    private String phase;

    /**
     * 现货腿预期成交均价。
     *
     * <p>由现货订单簿按计划数量估算 VWAP 得到。开仓时通常使用卖盘 asks 估算买入现货成本，
     * 平仓时通常使用买盘 bids 估算卖出现货价格。
     */
    private BigDecimal spotExpectedPrice;

    /**
     * 合约腿预期成交均价。
     *
     * <p>由合约订单簿按计划数量估算 VWAP 得到。开仓时通常使用买盘 bids 估算开空成交价，
     * 平仓时通常使用卖盘 asks 估算买回平空成本。
     */
    private BigDecimal futuresExpectedPrice;

    /**
     * 预估组合滑点率。
     *
     * <p>计算方式为现货腿滑点率与合约腿滑点率之和。若盘口不可用，初始值会使用配置项
     * {@code arbitrage.spot-futures.estimated-slippage-rate}; 盘口可用时会被实际 VWAP 估算结果覆盖。
     */
    private BigDecimal estimatedSlippageRate;

    /**
     * 流动性检查未通过时的拒绝原因。
     *
     * <p>例如交易计划无效、深度数据重试后仍不可用、订单簿可成交数量不足、预估滑点超过阈值等。
     * 检查通过时通常为空。
     */
    private String rejectionReason;

    /**
     * 现货订单簿深度是否足以覆盖计划数量。
     *
     * <p>true 表示按本次阶段需要读取的现货方向档位能够吃完整个计划数量;
     * false 表示现货侧可用数量不足或交易计划无效。
     */
    private boolean spotTopLevelEnough;

    /**
     * 合约订单簿深度是否足以覆盖计划数量。
     *
     * <p>true 表示按本次阶段需要读取的合约方向档位能够吃完整个计划数量;
     * false 表示合约侧可用数量不足或交易计划无效。
     */
    private boolean futuresTopLevelEnough;
}
