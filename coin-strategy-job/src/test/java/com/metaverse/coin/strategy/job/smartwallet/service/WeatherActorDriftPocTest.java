package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.behavior.CentroidDriftDetector;
import com.metaverse.coin.strategy.common.service.behavior.example.WeatherActorDriftDetector;
import com.metaverse.coin.strategy.common.service.behavior.example.WeatherActorEvent;
import com.metaverse.coin.strategy.common.service.behavior.example.WeatherActorEventVectorizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第十七批 (2026-05-16) Tier B 跨策略复用样例 — 天气 actor drift PoC 单测.
 *
 * <p>验证 {@link WeatherActorEventVectorizer} + {@link WeatherActorDriftDetector} +
 * {@link CentroidDriftDetector} 端到端通路: 与跟单 W2-T6 复用同一套 Tier B 接口,
 * 但完全不依赖 SmartWalletXxx 实体. 一旦本 PoC 通过, 即证明 Tier B 抽象
 * "天气 / BTC 等任意策略" 可复用的假设成立.</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无影响</b> —
 * 纯测试覆盖, 不进任何主代码路径 (test scope only).</p>
 */
class WeatherActorDriftPocTest {

    private final WeatherActorEventVectorizer vectorizer = new WeatherActorEventVectorizer();

    @Test
    void vectorizer_dimension_is8() {
        assertEquals(8, vectorizer.dimension());
        assertEquals(8, WeatherActorEventVectorizer.FEATURE_DIM);
    }

    @Test
    void vectorizer_nullEvent_returnsAllZeros() {
        double[] v = vectorizer.vectorize(null);
        assertEquals(8, v.length);
        for (double d : v) assertEquals(0.0, d, 1e-9);
    }

    @Test
    void vectorizer_directionHotVsCold_oneHotIndependent() {
        WeatherActorEvent hot = new WeatherActorEvent("st-1", "NY", "HOT", 50, 0L);
        WeatherActorEvent cold = new WeatherActorEvent("st-1", "NY", "COLD", 50, 0L);
        WeatherActorEvent rain = new WeatherActorEvent("st-1", "NY", "RAIN", 50, 0L);

        double[] vHot = vectorizer.vectorize(hot);
        double[] vCold = vectorizer.vectorize(cold);
        double[] vRain = vectorizer.vectorize(rain);

        assertEquals(1.0, vHot[1], 1e-9);  // HOT
        assertEquals(0.0, vHot[2], 1e-9);
        assertEquals(0.0, vCold[1], 1e-9);
        assertEquals(1.0, vCold[2], 1e-9); // COLD
        assertEquals(0.0, vRain[1], 1e-9); // 既不 HOT 也不 COLD
        assertEquals(0.0, vRain[2], 1e-9);
    }

    @Test
    void vectorizer_cityOneHot_unknownFallsBackToOther() {
        WeatherActorEvent ny = new WeatherActorEvent("st", "NY", "HOT", 10, 0L);
        WeatherActorEvent unknown = new WeatherActorEvent("st", "SHANGHAI", "HOT", 10, 0L);
        double[] vNy = vectorizer.vectorize(ny);
        double[] vUnknown = vectorizer.vectorize(unknown);
        // NY → idx 0 in CITY_KEYS → v[3]
        assertEquals(1.0, vNy[3], 1e-9);
        assertEquals(0.0, vNy[7], 1e-9);
        // SHANGHAI → OTHER → idx 4 → v[7]
        assertEquals(0.0, vUnknown[3], 1e-9);
        assertEquals(1.0, vUnknown[7], 1e-9);
    }

    @Test
    void detector_coldStart_returnsActiveEvenIfAlgorithmWouldSayDrifted() {
        WeatherActorDriftDetector detector =
                new WeatherActorDriftDetector(vectorizer, 0.40, 0.60, 10);
        // 只有 3 笔历史 < minSamples=10 → cold-start
        List<WeatherActorEvent> history = new ArrayList<>();
        history.add(new WeatherActorEvent("st", "NY", "HOT", 50, 0L));
        history.add(new WeatherActorEvent("st", "NY", "HOT", 50, 1L));
        history.add(new WeatherActorEvent("st", "NY", "HOT", 50, 2L));
        WeatherActorEvent current = new WeatherActorEvent("st", "LON", "COLD", 50, 99L);
        WeatherActorDriftDetector.Result r = detector.evaluate(history, current);
        assertEquals(CentroidDriftDetector.Status.ACTIVE, r.status());
        assertTrue(r.coldStart());
        assertEquals(3, r.sampleCount());
    }

    @Test
    void detector_consistentBehavior_isActive() {
        WeatherActorDriftDetector detector =
                new WeatherActorDriftDetector(vectorizer, 0.40, 0.60, 5);
        List<WeatherActorEvent> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(new WeatherActorEvent("st-A", "NY", "HOT", 50, i));
        }
        WeatherActorEvent current = new WeatherActorEvent("st-A", "NY", "HOT", 50, 100L);
        WeatherActorDriftDetector.Result r = detector.evaluate(history, current);
        assertEquals(CentroidDriftDetector.Status.ACTIVE, r.status());
        assertFalse(r.coldStart());
        assertEquals(0.0, r.cosineDistance(), 1e-9);
    }

    @Test
    void detector_anomalousBehavior_isDriftedOrSevere() {
        WeatherActorDriftDetector detector =
                new WeatherActorDriftDetector(vectorizer, 0.40, 0.60, 5);
        // 长期 NY/HOT
        List<WeatherActorEvent> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(new WeatherActorEvent("st-A", "NY", "HOT", 50, i));
        }
        // 突然切换 LON/COLD (3 维同时翻转)
        WeatherActorEvent oddball = new WeatherActorEvent("st-A", "LON", "COLD", 50, 100L);
        WeatherActorDriftDetector.Result r = detector.evaluate(history, oddball);
        assertFalse(r.coldStart());
        assertTrue(r.cosineDistance() > 0.40,
                "expected drift distance > 0.40, got " + r.cosineDistance());
        assertTrue(r.status() == CentroidDriftDetector.Status.DRIFTED
                        || r.status() == CentroidDriftDetector.Status.DRIFTED_SEVERE,
                "expected DRIFTED or DRIFTED_SEVERE, got " + r.status());
    }

    @Test
    void detector_validatesConstructorArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherActorDriftDetector(null, 0.40, 0.60, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherActorDriftDetector(vectorizer, 0.60, 0.40, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherActorDriftDetector(vectorizer, 0.40, 0.60, 0));
    }
}
