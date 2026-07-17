package com.metaverse.coin.strategy.core.arbitrage.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 单交易所套利风控检查时的账户风险快照。
 *
 * <p>该对象由 {@link SingleExchangeRiskService#loadRiskMetrics} 汇总账户指标后转换得到，
 * 主要用于 {@link SingleExchangeTradeRiskAssessment} 和策略风险快照落库，记录风控判断发生时的关键账户状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleExchangeRiskSnapshotData {

    /**
     * 现货账户可用 USDT 余额。
     *
     * <p>来自 Binance 现货账户 balances 中 USDT 资产的 free 数量。
     * 开仓时需要覆盖现货买入腿的名义金额。
     */
    private BigDecimal spotUsdtBalance;

    /**
     * U 本位合约账户可用 USDT 余额。
     *
     * <p>来自 Binance 合约账户 assets 中 USDT 资产的 availableBalance。
     * 开仓时需要覆盖带安全缓冲后的合约初始保证金。
     */
    private BigDecimal futuresAvailableBalance;

    /**
     * 当前交易计划所需的合约初始保证金。
     *
     * <p>通常由下单计划根据合约名义金额和杠杆计算得出，只反映理论最低保证金需求。
     */
    private BigDecimal requiredMargin;

    /**
     * 加安全缓冲后的合约保证金需求。
     *
     * <p>计算方式为 {@code requiredMargin * requiredMarginBufferRate}。
     * 风控实际使用该值与合约可用余额比较，避免刚好贴线开仓。
     */
    private BigDecimal bufferedRequiredMargin;

    /**
     * 合约账户保证金健康度。
     *
     * <p>由合约账户 {@code totalMarginBalance / totalMaintMargin} 计算。
     * 值越高代表账户距离维持保证金风险越远; 若低于配置的最低保证金率，则拒绝继续开仓。
     */
    private BigDecimal marginRatio;
}
