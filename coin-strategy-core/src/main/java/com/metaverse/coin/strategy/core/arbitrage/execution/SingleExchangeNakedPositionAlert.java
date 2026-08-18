package com.metaverse.coin.strategy.core.arbitrage.execution;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 分腿回滚失败导致的裸露单腿仓位告警（P1）。
 *
 * <p>开/平仓采用双腿并行下单：一腿成交、另一腿失败时，执行器会对已成交腿发起反向 rollback 以恢复对冲。
 * 但当 <b>rollback 本身也失败</b> 时，已成交腿的方向性敞口会残留成一个未对冲的裸仓（naked position），
 * 若仅打 CRITICAL 日志则该敞口对上层与对账不可见。本告警把该事件结构化：标明残留在哪个市场
 * （{@code nakedHedgeSide}）、方向（{@code nakedPositionSide}）、数量与所处交易阶段，供上层熔断/人工介入
 * 与事后对账。告警一旦产生即代表存在需要人工处置的真实敞口。
 */
@Data
@Builder
public class SingleExchangeNakedPositionAlert {

    /** 交易对，如 BTCUSDT。 */
    private String symbol;

    /** 残留裸仓所在市场：{@code SPOT} / {@code FUTURES}。 */
    private String nakedHedgeSide;

    /** 残留裸仓方向：{@code LONG} / {@code SHORT}。 */
    private String nakedPositionSide;

    /** 裸露的仓位数量（基础资产计）。 */
    private BigDecimal quantity;

    /** 触发时所处交易阶段：{@code OPEN} / {@code CLOSE}。 */
    private String tradePhase;

    /** rollback 失败的错误信息，供排查与人工介入。 */
    private String rollbackErrorMessage;
}
