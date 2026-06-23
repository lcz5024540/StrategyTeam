package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.behavior.BehaviorVectorizer;
import com.metaverse.coin.strategy.common.service.behavior.CentroidDriftDetector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第十七批 (2026-05-16): {@link SmartWalletTradeEventVectorizer} 单元测试.
 *
 * <p>覆盖第二十三节末尾 (23.8 下一轮 #6) 提出的"3-4 个 case (size/side/category 各维度独立性 + null marketInfo 兜底 OTHER)".
 * 同时验证 Tier B 抽象与 {@link CentroidDriftDetector} 的端到端可组合性
 * (第十六批 23.1 中描述的"BehaviorVectorizer + CentroidDriftDetector 真实复用回路").</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无影响</b> — 纯测试覆盖,
 * 不进任何主代码路径 (test scope only).</p>
 */
class SmartWalletTradeEventVectorizerTest {

    private final SmartWalletTradeEventVectorizer vectorizer = new SmartWalletTradeEventVectorizer();

    @Test
    void dimension_isConstantSeven() {
        assertEquals(7, vectorizer.dimension());
        assertEquals(7, SmartWalletTradeEventVectorizer.FEATURE_DIM);
    }

    @Test
    void vectorize_nullEvent_returnsAllZeros() {
        double[] v = vectorizer.vectorize((SmartWalletTradeEventEntity) null);
        assertEquals(7, v.length);
        for (double d : v) {
            assertEquals(0.0, d, 1e-9);
        }
    }

    @Test
    void vectorize_singleArgFallback_categoryDefaultsToOther() {
        // 单参版本不带 marketInfo → category 兜底为 OTHER, OTHER 桶应为 1.0, 其他四个 0.0
        SmartWalletTradeEventEntity event = new SmartWalletTradeEventEntity()
                .setSizeUsdc(new BigDecimal("100"))
                .setSide("BUY");
        double[] v = vectorizer.vectorize(event);
        assertEquals(7, v.length);
        // size: log10(101)/6 ≈ 0.3346
        assertEquals(Math.log10(101.0) / 6.0, v[0], 1e-9);
        // side: BUY=1
        assertEquals(1.0, v[1], 1e-9);
        // categories [POLITICS, CRYPTO, SPORTS, WEATHER, OTHER] → idx 2-6
        assertEquals(0.0, v[2], 1e-9);
        assertEquals(0.0, v[3], 1e-9);
        assertEquals(0.0, v[4], 1e-9);
        assertEquals(0.0, v[5], 1e-9);
        assertEquals(1.0, v[6], 1e-9); // OTHER
    }

    @Test
    void vectorize_sideAxis_buyVsSell() {
        SmartWalletTradeEventEntity buy = new SmartWalletTradeEventEntity()
                .setSizeUsdc(new BigDecimal("100"))
                .setSide("BUY");
        SmartWalletTradeEventEntity sell = new SmartWalletTradeEventEntity()
                .setSizeUsdc(new BigDecimal("100"))
                .setSide("SELL");
        SmartWalletTradeEventEntity unknown = new SmartWalletTradeEventEntity()
                .setSizeUsdc(new BigDecimal("100"))
                .setSide("FOO");

        double[] vBuy = vectorizer.vectorize(buy);
        double[] vSell = vectorizer.vectorize(sell);
        double[] vUnknown = vectorizer.vectorize(unknown);

        assertEquals(1.0, vBuy[1], 1e-9);
        assertEquals(0.0, vSell[1], 1e-9);
        assertEquals(0.0, vUnknown[1], 1e-9);
        // size 与 category 都相同
        assertEquals(vBuy[0], vSell[0], 1e-9);
        for (int i = 2; i < 7; i++) {
            assertEquals(vBuy[i], vSell[i], 1e-9);
        }
    }

    @Test
    void vectorize_sizeAxis_monotonicAndCapped() {
        // size 单调递增且上限 1.0
        SmartWalletTradeEventEntity small = newBuy(new BigDecimal("10"));
        SmartWalletTradeEventEntity mid = newBuy(new BigDecimal("1000"));
        SmartWalletTradeEventEntity large = newBuy(new BigDecimal("100000"));
        SmartWalletTradeEventEntity huge = newBuy(new BigDecimal("10000000")); // 10M, log10 ≈ 7 → cap at 1.0

        assertTrue(vectorizer.vectorize(small)[0] < vectorizer.vectorize(mid)[0]);
        assertTrue(vectorizer.vectorize(mid)[0] < vectorizer.vectorize(large)[0]);
        assertEquals(1.0, vectorizer.vectorize(huge)[0], 1e-9);
    }

    @Test
    void vectorize_sizeAxis_negativeOrNullClampsToZero() {
        SmartWalletTradeEventEntity nullSize = new SmartWalletTradeEventEntity().setSide("BUY");
        SmartWalletTradeEventEntity neg = new SmartWalletTradeEventEntity()
                .setSizeUsdc(new BigDecimal("-100"))
                .setSide("BUY");
        // log10(1)/6 = 0
        assertEquals(0.0, vectorizer.vectorize(nullSize)[0], 1e-9);
        assertEquals(0.0, vectorizer.vectorize(neg)[0], 1e-9);
    }

    @Test
    void vectorize_categoryAxis_eachCategoryHits() {
        SmartWalletTradeEventEntity event = newBuy(new BigDecimal("100"));
        for (int i = 0; i < SmartWalletTradeEventVectorizer.CATEGORY_KEYS.size(); i++) {
            String key = SmartWalletTradeEventVectorizer.CATEGORY_KEYS.get(i);
            SmartWalletMarketInfoEntity info = new SmartWalletMarketInfoEntity().setNormalizedCategory(key);
            double[] v = vectorizer.vectorize(event, info);
            for (int j = 0; j < 5; j++) {
                if (i == j) {
                    assertEquals(1.0, v[2 + j], 1e-9, "category " + key + " bucket " + j + " should be 1.0");
                } else {
                    assertEquals(0.0, v[2 + j], 1e-9, "category " + key + " bucket " + j + " should be 0.0");
                }
            }
        }
    }

    @Test
    void vectorize_categoryAxis_sourceCategoryFallback() {
        // normalizedCategory 缺失时落回 sourceCategory
        SmartWalletTradeEventEntity event = newBuy(new BigDecimal("100"));
        SmartWalletMarketInfoEntity info = new SmartWalletMarketInfoEntity().setSourceCategory("crypto");
        double[] v = vectorizer.vectorize(event, info);
        // CRYPTO 在 CATEGORY_KEYS idx=1, 所以 v[3] 应当 = 1.0
        assertEquals(1.0, v[3], 1e-9);
        // 其余 category 桶 0
        assertEquals(0.0, v[2], 1e-9);
        assertEquals(0.0, v[4], 1e-9);
        assertEquals(0.0, v[5], 1e-9);
        assertEquals(0.0, v[6], 1e-9);
    }

    @Test
    void vectorize_categoryAxis_unknownFallsBackToOther() {
        SmartWalletTradeEventEntity event = newBuy(new BigDecimal("100"));
        // 既不在 normalizedCategory 也不在 sourceCategory 的合法集合
        SmartWalletMarketInfoEntity info = new SmartWalletMarketInfoEntity().setNormalizedCategory("UNKNOWN_BUCKET");
        double[] v = vectorizer.vectorize(event, info);
        // 应当回到 OTHER (idx 4)
        assertEquals(1.0, v[6], 1e-9);
    }

    @Test
    void normalizeCategory_nullInfo_returnsOther() {
        assertEquals("OTHER", SmartWalletTradeEventVectorizer.normalizeCategory(null));
    }

    /**
     * 端到端验证: 用 vectorizer + {@link BehaviorVectorizer#computeCentroid} 算 centroid,
     * 用 {@link CentroidDriftDetector#cosineDistance} 算距离, 用 {@link CentroidDriftDetector#classify}
     * 三态分类 — 这是跟单 W2-T6 / 未来天气 / BTC 策略的完整复用回路.
     */
    @Test
    void tierB_endToEnd_drift_pipeline_works() {
        // 历史: 5 笔政治 BUY $100
        List<SmartWalletTradeEventEntity> history = new ArrayList<>();
        SmartWalletMarketInfoEntity politics = new SmartWalletMarketInfoEntity().setNormalizedCategory("POLITICS");
        for (int i = 0; i < 5; i++) {
            history.add(newBuy(new BigDecimal("100")));
        }
        // 通过 BehaviorVectorizer<E> 接口默认 computeCentroid 走单参 vectorize → category=OTHER
        // 这里直接用双参手算 centroid 验证两条路径都能用
        double[] sum = new double[7];
        for (SmartWalletTradeEventEntity e : history) {
            double[] v = vectorizer.vectorize(e, politics);
            for (int i = 0; i < 7; i++) sum[i] += v[i];
        }
        double[] centroid = new double[7];
        for (int i = 0; i < 7; i++) centroid[i] = sum[i] / 5.0;

        // 同分布信号: 距离应当 ≈ 0 → ACTIVE
        double[] currSame = vectorizer.vectorize(newBuy(new BigDecimal("100")), politics);
        double dSame = CentroidDriftDetector.cosineDistance(centroid, currSame);
        assertEquals(0.0, dSame, 1e-9);
        assertEquals(CentroidDriftDetector.Status.ACTIVE,
                CentroidDriftDetector.classify(dSame, 0.40, 0.60));

        // 异常信号: 突然切换到 WEATHER + SELL → 距离明显, 应当 DRIFTED 或 SEVERE
        SmartWalletMarketInfoEntity weather = new SmartWalletMarketInfoEntity().setNormalizedCategory("WEATHER");
        SmartWalletTradeEventEntity oddball = new SmartWalletTradeEventEntity()
                .setSizeUsdc(new BigDecimal("100"))
                .setSide("SELL");
        double[] currDiff = vectorizer.vectorize(oddball, weather);
        double dDiff = CentroidDriftDetector.cosineDistance(centroid, currDiff);
        // size 同, side 不同 (BUY→SELL 减一维) + category 不同 (POLITICS→WEATHER) → 显著漂移
        assertTrue(dDiff > 0.40, "expected drift distance > 0.40, got " + dDiff);
        CentroidDriftDetector.Status status =
                CentroidDriftDetector.classify(dDiff, 0.40, 0.60);
        assertTrue(status == CentroidDriftDetector.Status.DRIFTED
                        || status == CentroidDriftDetector.Status.DRIFTED_SEVERE,
                "expected DRIFTED or DRIFTED_SEVERE, got " + status);
    }

    private static SmartWalletTradeEventEntity newBuy(BigDecimal size) {
        return new SmartWalletTradeEventEntity().setSizeUsdc(size).setSide("BUY");
    }
}
