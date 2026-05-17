package com.metaverse.coin.strategy.job.smartwallet.model;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletProfile;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SmartWalletSignal {
    private String signalId;
    /**
     * 跟单链路追踪 ID, 取自源 trade_event.trace_id, 持久化到 follow_task.trace_id.
     * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T3).
     */
    private String traceId;
    private SmartWalletProfile wallet;
    private PredictionMarketOpportunity market;
    private String followOutcome;
    private BigDecimal walletScore;
    private BigDecimal signalScore;
    private BigDecimal recommendedStakeUsdc;
    private boolean executable;
    /**
     * W2-T4.3: 价格冲击门导出的下游路由提示（{@code NORMAL} / {@code MAKER_FIRST} / {@code MAKER_ONLY}）。
     * 由 {@link SignalRiskDecision#getPriceImpactExecutionMode()} 透传，影响
     * {@code PolymarketOrderRequestFactory.prepareLocalDraft} 中的 orderStrategy 解析。
     * {@code null} 或 {@code NORMAL} 表示沿用钱包默认策略。
     */
    private String priceImpactExecutionMode;
    @Builder.Default
    private List<String> reasons = new ArrayList<>();
}
