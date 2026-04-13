package com.metaverse.coin.strategy.job.hedge.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 风控检查结果
 */
@Data
@Builder
public class HedgeRiskCheckResult {

    private boolean passed;

    /** 阻断原因列表（passed=false 时不为空）*/
    private List<String> violations;

    /** 建议仓位 USDC（passed=true 时有值）*/
    private java.math.BigDecimal suggestedSizeUsdc;

    public static HedgeRiskCheckResult pass(java.math.BigDecimal suggestedSizeUsdc) {
        return HedgeRiskCheckResult.builder()
                .passed(true)
                .violations(List.of())
                .suggestedSizeUsdc(suggestedSizeUsdc)
                .build();
    }

    public static HedgeRiskCheckResult fail(List<String> violations) {
        return HedgeRiskCheckResult.builder()
                .passed(false)
                .violations(violations)
                .build();
    }
}
