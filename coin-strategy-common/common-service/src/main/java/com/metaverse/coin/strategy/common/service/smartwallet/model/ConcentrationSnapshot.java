package com.metaverse.coin.strategy.common.service.smartwallet.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 单一市场单一 outcome 的集中度快照。
 *
 * <p>原为 {@code com.metaverse.coin.strategy.job.smartwallet.service.ConcentrationSnapshot}
 * 的嵌套 record，因 {@link com.metaverse.coin.strategy.common.service.smartwallet.service.MarketConcentrationSnapshotService}
 * 已下沉到 common-service，故抽离为顶层类。</p>
 *
 * @param conditionId            市场 conditionId
 * @param outcome                outcome（YES/NO 等）
 * @param observedHolders        采样到的持仓者总数
 * @param top1Pct                前 1 持仓者占比
 * @param top3Pct                前 3 持仓者合计占比
 * @param top5Pct                前 5 持仓者合计占比
 * @param hhi                    HHI 指数（0-10000）
 * @param isExtremelyConcentrated 是否触发极端集中阈值
 * @param isHighlyConcentrated   是否触发集中度阈值
 * @param topHolderAddresses     前 5 持仓者地址（用于鲸鱼身份检测）
 * @param capturedAt             数据采集时间戳（ms）
 */
public record ConcentrationSnapshot(
        String conditionId,
        String outcome,
        int observedHolders,
        BigDecimal top1Pct,
        BigDecimal top3Pct,
        BigDecimal top5Pct,
        int hhi,
        boolean isExtremelyConcentrated,
        boolean isHighlyConcentrated,
        List<String> topHolderAddresses,
        long capturedAt
) {
    /** API 不可用或数据为空时的占位快照（fail-open：不触发集中度拒绝） */
    public static final ConcentrationSnapshot UNKNOWN = new ConcentrationSnapshot(
            null, "UNKNOWN", 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            0, false, false,
            Collections.emptyList(),
            0L
    );

    public boolean isDataAvailable() {
        return observedHolders > 0 && capturedAt > 0;
    }
}
