package com.metaverse.coin.strategy.job.smartwallet.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 修复一（6.2）：MarketCategoryClassifier 关键词分类器单测。
 */
class MarketCategoryClassifierTest {

    @Test
    void shouldClassifySportsByKeyword() {
        assertEquals("SPORTS",
                MarketCategoryClassifier.classifyByText("Will the Lakers win the NBA game tonight?"));
    }

    @Test
    void shouldClassifyCryptoByKeyword() {
        assertEquals("CRYPTO",
                MarketCategoryClassifier.classifyByText("Will Bitcoin break $100k by year end?"));
    }

    @Test
    void shouldClassifyPoliticsByKeyword() {
        assertEquals("POLITICS",
                MarketCategoryClassifier.classifyByText("Will Trump win the presidential election?"));
    }

    @Test
    void shouldClassifyWeatherByKeyword() {
        assertEquals("WEATHER",
                MarketCategoryClassifier.classifyByText("Will it rain in New York tomorrow?"));
    }

    @Test
    void shouldFallBackToOtherWhenNoKeywordMatches() {
        assertEquals("OTHER",
                MarketCategoryClassifier.classifyByText("abc xyz placeholder topic"));
    }

    @Test
    void shouldReturnOtherForBlankOrNullInput() {
        assertEquals("OTHER", MarketCategoryClassifier.classifyByText(null));
        assertEquals("OTHER", MarketCategoryClassifier.classifyByText("   "));
    }
}
