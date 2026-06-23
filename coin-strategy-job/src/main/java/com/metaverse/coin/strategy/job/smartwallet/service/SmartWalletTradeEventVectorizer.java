package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.behavior.BehaviorVectorizer;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 第十六批 (Tier B 验证落地): {@link BehaviorVectorizer} 的跟单策略实现.
 *
 * <p>把单个 {@link SmartWalletTradeEventEntity} 转成 7 维特征向量, 算法与
 * {@code LeaderBehaviorDriftService} 内联版完全等价 (后者改为委托本类调用):
 * <ol>
 *   <li>{@code sizeBucket} — {@code log10(sizeUsdc + 1) / 6}, 上限 1.0 (~$1M)</li>
 *   <li>{@code sideBuy}    — BUY=1, SELL=0</li>
 *   <li>{@code categoryPolitics}</li>
 *   <li>{@code categoryCrypto}</li>
 *   <li>{@code categorySports}</li>
 *   <li>{@code categoryWeather}</li>
 *   <li>{@code categoryOther}</li>
 * </ol>
 *
 * <p><b>设计要点</b>:
 * <ul>
 *   <li>无 Spring 依赖 (纯 PoJo), 任何策略 / 测试可 {@code new} 出来调用.</li>
 *   <li>{@link #vectorize(SmartWalletTradeEventEntity)} 单参版本 — 无市场上下文, category 兜底 OTHER.
 *       仅用于 {@link BehaviorVectorizer} 接口合规, hot path 应当用 {@link #vectorize(SmartWalletTradeEventEntity, SmartWalletMarketInfoEntity)}.</li>
 *   <li>{@link #dimension()} = 7, 恒定.</li>
 * </ul>
 *
 * <p>本类替代 {@code LeaderBehaviorDriftService} 内联的 {@code featureVector(event, info)} +
 * {@code featureVector(event)} 私有方法, 为天气 / BTC 5min 策略提供"可参考实现"作为
 * Tier B 通用化（{@code BehaviorVectorizer + CentroidDriftDetector}）的真实使用样本.</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无影响</b> —
 * 仅是算法位置迁移, 输出向量与历史等价, 与决议文档第十六批的 5 维评估一致.</p>
 */
public final class SmartWalletTradeEventVectorizer
        implements BehaviorVectorizer<SmartWalletTradeEventEntity> {

    /** 类别一热编码顺序; 与 {@code LeaderBehaviorDriftService.CATEGORY_KEYS} 必须保持一致. */
    public static final List<String> CATEGORY_KEYS = Arrays.asList(
            "POLITICS", "CRYPTO", "SPORTS", "WEATHER", "OTHER");

    /** 特征向量维度: 1 sizeBucket + 1 sideBuy + 5 category-one-hot = 7. */
    public static final int FEATURE_DIM = 2 + 5;

    /** log10(sizeUsdc + 1) 归一化的分母. */
    private static final double SIZE_LOG10_DIVISOR = 6.0;

    @Override
    public int dimension() {
        return FEATURE_DIM;
    }

    /**
     * 单参版本 — 不带 marketInfo, category 兜底落入 OTHER 桶. 仅用于满足 {@link BehaviorVectorizer}
     * 接口签名要求; 实际调用方应当用 {@link #vectorize(SmartWalletTradeEventEntity, SmartWalletMarketInfoEntity)}
     * 双参版本以获得正确的 category one-hot.
     */
    @Override
    public double[] vectorize(SmartWalletTradeEventEntity event) {
        return vectorize(event, null);
    }

    /**
     * 双参版本 — 推荐入口. 给定 event + marketInfo 计算完整 7 维向量.
     *
     * @param event      trade event (null 时返回全零)
     * @param marketInfo 市场元数据 (null 时 category 兜底为 OTHER)
     * @return 长度恰好 {@link #FEATURE_DIM} 的特征向量
     */
    public double[] vectorize(SmartWalletTradeEventEntity event, SmartWalletMarketInfoEntity marketInfo) {
        double[] v = new double[FEATURE_DIM];
        if (event == null) {
            return v;
        }
        BigDecimal size = event.getSizeUsdc();
        double sizeUsdc = size == null ? 0.0 : Math.max(0.0, size.doubleValue());
        v[0] = Math.min(1.0, Math.log10(sizeUsdc + 1.0) / SIZE_LOG10_DIVISOR);
        v[1] = "BUY".equalsIgnoreCase(event.getSide()) ? 1.0 : 0.0;
        String cat = normalizeCategory(marketInfo);
        int catIdx = CATEGORY_KEYS.indexOf(cat);
        if (catIdx < 0) {
            catIdx = CATEGORY_KEYS.indexOf("OTHER");
        }
        v[2 + catIdx] = 1.0;
        return v;
    }

    /** 将 marketInfo 的品类字段规范化到 {@link #CATEGORY_KEYS} 集合中, 未知一律兜底 OTHER. */
    public static String normalizeCategory(SmartWalletMarketInfoEntity info) {
        if (info == null) return "OTHER";
        String n = info.getNormalizedCategory();
        if (StringUtils.hasText(n)) {
            String upper = n.trim().toUpperCase(Locale.ROOT);
            if (CATEGORY_KEYS.contains(upper)) return upper;
        }
        String s = info.getSourceCategory();
        if (StringUtils.hasText(s)) {
            String upper = s.trim().toUpperCase(Locale.ROOT);
            if (CATEGORY_KEYS.contains(upper)) return upper;
        }
        return "OTHER";
    }
}
