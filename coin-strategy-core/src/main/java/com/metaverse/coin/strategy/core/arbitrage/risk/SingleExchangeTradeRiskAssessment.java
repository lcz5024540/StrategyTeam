package com.metaverse.coin.strategy.core.arbitrage.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单交易所现货-合约套利的账户级交易风控结果。
 *
 * <p>该对象由 {@link SingleExchangeRiskService#evaluateTradeRisk} 返回，
 * 用于表达一笔开仓计划是否满足账户余额、合约保证金、仓位上限和保证金率等硬性风控条件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleExchangeTradeRiskAssessment {

    /**
     * 交易风控是否通过。
     *
     * <p>true 表示当前交易计划可以继续进入下单流程; false 表示至少有一项硬性风控未通过，
     * 策略应拒绝开仓并记录风险快照。
     */
    private boolean passed;

    /**
     * 风控未通过时的拒绝原因。
     *
     * <p>常见原因包括交易计划无效、单笔名义价值超限、现货 USDT 余额不足、
     * 合约可用保证金不足、合约保证金率低于最低要求等。风控通过时通常为空。
     */
    private String rejectionReason;

    /**
     * 本次风控检查时采集到账户风险快照。
     *
     * <p>即使风控失败，该字段也会尽量保留当时可获取到的余额、保证金和保证金率信息，
     * 方便策略落库后排查“为什么这笔交易被拒绝”。
     */
    private SingleExchangeRiskSnapshotData snapshotData;
}
