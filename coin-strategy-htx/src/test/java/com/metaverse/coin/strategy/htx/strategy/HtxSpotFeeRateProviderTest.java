package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSpotFeeRate;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxSpotFeeRateProviderTest {

    @Test
    void usesHighestOfConfiguredAndCurrentMakerTakerRatesAndCachesIt() {
        HtxApiService api = mock(HtxApiService.class);
        HtxSpotService spot = mock(HtxSpotService.class);
        when(api.getSpotService()).thenReturn(spot);
        HtxMarketMakerProperties properties = new HtxMarketMakerProperties();
        properties.setFeeRatePerSide(new BigDecimal("0.002"));

        HtxSpotFeeRate fee = new HtxSpotFeeRate();
        fee.setSymbol("xautusdt");
        fee.setActualMakerRate(new BigDecimal("0.0018"));
        fee.setActualTakerRate(new BigDecimal("0.0025"));
        HtxRestResponse<List<HtxSpotFeeRate>> response = new HtxRestResponse<>();
        response.setCode(200);
        response.setData(List.of(fee));
        when(spot.queryFeeRates("xautusdt")).thenReturn(response);

        HtxSpotFeeRateProvider provider = new HtxSpotFeeRateProvider(api, properties);

        assertEquals(new BigDecimal("0.0025"), provider.effectiveRate("xautusdt"));
        assertEquals(new BigDecimal("0.0025"), provider.effectiveRate("xautusdt"));
        verify(spot).queryFeeRates("xautusdt");
    }
}
