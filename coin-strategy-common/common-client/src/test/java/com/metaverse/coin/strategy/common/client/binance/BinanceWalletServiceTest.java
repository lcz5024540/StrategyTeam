package com.metaverse.coin.strategy.common.client.binance;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.spot.Wallet;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceWalletService;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceCoinInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceSystemStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BinanceWalletServiceTest {

    @Mock
    private SpotClient spotClient;

    @Mock
    private Wallet wallet;

    private BinanceWalletService walletService;

    @BeforeEach
    public void setUp() {
        walletService = new BinanceWalletService(spotClient);
    }

    @Test
    public void testSystemStatus() {
        // Arrange
        String jsonResponse = "{\"status\": 0, \"msg\": \"normal\"}";
        when(spotClient.createWallet()).thenReturn(wallet);
        when(wallet.systemStatus()).thenReturn(jsonResponse);

        // Act
        BinanceSystemStatus status = walletService.systemStatus();

        // Assert
        assertNotNull(status);
        assertEquals(0, status.getStatus());
        assertEquals("normal", status.getMsg());
    }

    @Test
    public void testGetAllCoins() {
        // Arrange
        String jsonResponse = "[{\"coin\": \"BTC\", \"name\": \"Bitcoin\", \"networkList\": []}]";
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        
        when(spotClient.createWallet()).thenReturn(wallet);
        when(wallet.coinInfo(parameters)).thenReturn(jsonResponse);

        // Act
        List<BinanceCoinInfo> coins = walletService.getAllCoins();

        // Assert
        assertNotNull(coins);
        assertEquals(1, coins.size());
        assertEquals("BTC", coins.get(0).getCoin());
    }
}
