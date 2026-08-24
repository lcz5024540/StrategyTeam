package com.metaverse.coin.strategy.htx.config;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxMarketMakerPropertiesBindingTest {

    @Test
    void applicationYamlMapsAllStandardMmGridEnvironmentVariables() throws Exception {
        Path testClasses = Path.of(HtxMarketMakerPropertiesBindingTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        String yaml = Files.readString(testClasses.resolveSibling("classes/application.yml"), StandardCharsets.UTF_8);

        assertAll(
                () -> assertTrue(yaml.contains("base-url: \"${HTX_BASE_URL:https://api.huobi.pro}\"")),
                () -> assertTrue(yaml.contains("ws-url: \"${HTX_WS_URL:wss://api.huobi.pro/ws}\"")),
                () -> assertTrue(yaml.contains("grid-mode-enabled: ${HTX_MM_GRID_MODE_ENABLED:false}")),
                () -> assertTrue(yaml.contains("grid-step-pct: ${HTX_MM_GRID_STEP_PCT:0.001}")),
                () -> assertTrue(yaml.contains("grid-levels: ${HTX_MM_GRID_LEVELS:20}")),
                () -> assertTrue(yaml.contains("min-sell-order-quote-notional: ${HTX_MM_MIN_SELL_ORDER_QUOTE_NOTIONAL:1.1}")),
                () -> assertTrue(yaml.contains("grid-floor-price: ${HTX_MM_GRID_FLOOR_PRICE:0}")),
                () -> assertTrue(yaml.contains("max-total-exposure-quote-notional: ${HTX_MM_MAX_TOTAL_EXPOSURE_QUOTE_NOTIONAL:240}")),
                () -> assertTrue(yaml.contains("daily-net-loss-budget: ${HTX_MM_DAILY_NET_LOSS_BUDGET:5}")),
                () -> assertTrue(yaml.contains("max-consecutive-loss-cycles: ${HTX_MM_MAX_CONSECUTIVE_LOSS_CYCLES:3}"))
        );
    }
}
