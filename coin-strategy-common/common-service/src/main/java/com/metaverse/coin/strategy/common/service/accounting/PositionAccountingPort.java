package com.metaverse.coin.strategy.common.service.accounting;

import java.math.BigDecimal;

/**
 * Tier B (第十八批): 通用「策略级仓位会计」端口.
 *
 * <p>把跟单 {@code SmartWalletAccountingService} 的「自由 / 锁定 / 在途 + 仓位计数 / 余量」
 * 三档披露语义抽出来. 任意策略 (跟单 / 天气 / BTC 5min) 都可实现本接口, 让
 * RiskService / Dashboard / Pre-trade check 等通用消费方零修改即可读到容量信息.</p>
 *
 * <p><b>语义约定</b>:
 * <ul>
 *   <li><b>availableCapitalUsdc</b>: 可立即用于新开仓的余额 (≥ 0)</li>
 *   <li><b>lockedCapitalUsdc</b>: 已挂未成 + 持仓占用 + 待 claim 的资金 (≥ 0)</li>
 *   <li><b>inFlightCapitalUsdc</b>: 已成交但尚未结算的仓位 cost basis (≥ 0)</li>
 *   <li><b>openPositionCount</b>: 当前 OPEN / PARTIALLY_CLOSED 仓位数 (≥ 0)</li>
 *   <li><b>openPositionLimit</b>: 策略上限; null / ≤ 0 表示无限</li>
 *   <li><b>remainingPositionSlots</b>: max(0, limit - count); limit 无限时返回 -1</li>
 * </ul></p>
 *
 * <p>跟单端的 {@code SmartWalletAccountingService.capacitySummary()} 字段一一对应,
 * 后续可由 adapter 桥接 (本批不动跟单生产代码). 详见 docs 二十七节.</p>
 */
public interface PositionAccountingPort {

    /** 可立即用于新开仓的 USDC 余额. 不为 null. */
    BigDecimal availableCapitalUsdc();

    /** 已挂单 / 持仓 / 待 claim 占用的 USDC. 不为 null. */
    BigDecimal lockedCapitalUsdc();

    /** 已成交但未结算的 cost basis. 不为 null. */
    BigDecimal inFlightCapitalUsdc();

    /** 当前持仓数. */
    int openPositionCount();

    /** 策略上限; null / ≤ 0 表示无限. */
    Integer openPositionLimit();

    /** 剩余仓位槽位. limit 无限时返回 -1. */
    default int remainingPositionSlots() {
        Integer limit = openPositionLimit();
        if (limit == null || limit <= 0) return -1;
        return Math.max(0, limit - openPositionCount());
    }

    /** 容量是否已满 (槽位 + 资金任一耗尽). */
    default boolean isExhausted() {
        BigDecimal avail = availableCapitalUsdc();
        if (avail == null || avail.signum() <= 0) return true;
        int slots = remainingPositionSlots();
        return slots == 0;
    }
}
