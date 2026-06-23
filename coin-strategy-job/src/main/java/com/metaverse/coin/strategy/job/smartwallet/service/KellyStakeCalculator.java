package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.sizing.KellyCalculator;
import org.springframework.stereotype.Component;

/**
 * Kelly Criterion stake calculator for binary prediction markets.
 *
 * <p>Formula: f* = (p * b - (1-p)) / b
 * <ul>
 *   <li>p = event win probability (converted from follow-worthiness score if edgeFactor provided)</li>
 *   <li>b = (1 - entryPrice) / entryPrice (payout odds)</li>
 * </ul></p>
 *
 * <p>Returns null when probability is unknown (caller falls back to legacy formula).
 * Returns ZERO when EV is negative (caller should reject signal via EV gate).
 * Returns ZERO when entry price is near 0 or 1 (market nearly resolved, extreme slippage).</p>
 *
 * <p>When kellySignalEdgeFactor is non-null, calibratedFollowProbability is treated as a
 * composite follow-worthiness score and converted to a win-rate estimate:
 * p_win = 0.5 + (followProb - 0.5) * edgeFactor</p>
 *
 * <p><b>第十九批 (2026-05-16) Tier B 续</b>: 算法迁移到 common-service
 * {@link KellyCalculator}; 本类保留 {@code @Component} 让 Spring 装配 + 跟单调用方
 * (e.g. SmartWalletSignalRiskService) 完全不变。{@code extends} 让所有方法签名继承,
 * bit-equivalent. 未来天气 / BTC 5min 策略可直接 {@code new KellyCalculator()} 复用算法。</p>
 *
 * <p>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率: ⚪ 全部无影响 — 行为完全等价。</p>
 */
@Component
public class KellyStakeCalculator extends KellyCalculator {
}
