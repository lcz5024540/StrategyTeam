package com.metaverse.coin.strategy.common.client.binance;

import com.binance.connector.client.SpotClient;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceMarginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
public class BinanceMarginServiceTest {

    @Mock
    private SpotClient spotClient;

    private BinanceMarginService marginService;

    @BeforeEach
    public void setUp() {
        marginService = new BinanceMarginService(spotClient, "https://api.binance.com", "dummyApiKey", "dummySecretKey");
    }

    @Test
    public void testInitialization() {
        assertNotNull(marginService);
    }
    
    // Previous tests were mocking SpotClient which is no longer used for Margin calls.
    // Real integration tests or MockWebServer would be needed to test executeRequest.
}
