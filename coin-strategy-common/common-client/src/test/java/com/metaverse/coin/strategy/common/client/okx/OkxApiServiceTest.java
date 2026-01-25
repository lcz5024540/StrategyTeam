package com.metaverse.coin.strategy.common.client.okx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.client.okx.interceptor.AuthenticationInterceptor;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.Balance;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.TdMode;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

@Disabled("Integration test - requires API keys")
public class OkxApiServiceTest {

    private OkxApiService okxApiService;
    // TODO: Replace with your actual keys
    private static final String API_KEY = "YOUR_API_KEY";
    private static final String SECRET_KEY = "YOUR_SECRET_KEY";
    private static final String PASSPHRASE = "YOUR_PASSPHRASE";
    private static final boolean SIMULATED = true;
    private static final String URL = "https://www.okx.com";

    @BeforeEach
    public void setUp() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new AuthenticationInterceptor(API_KEY, SECRET_KEY, PASSPHRASE, SIMULATED))
                .readTimeout(Duration.ofSeconds(30))
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(URL)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create(new ObjectMapper()))
                .build();

        OkxApi okxApi = retrofit.create(OkxApi.class);
        okxApiService = new OkxApiService(okxApi, client, SIMULATED);
    }

    @Test
    public void testGetBalance() {
        OkxRestResponse<Balance> response = okxApiService.getBalance("USDT");
        System.out.println(response);
    }

    @Test
    public void testOpenLong() {
        // Example: Open Long on BTC-USDT-SWAP
        // TdMode: CROSS (margin mode)
        okxApiService.openLong("BTC-USDT-SWAP", TdMode.CROSS, new BigDecimal("1"), null);
    }
    
    @Test
    public void testSetLeverage() {
        okxApiService.setLeverage("BTC-USDT-SWAP", "cross", "10");
    }

    @Test
    public void testGetPositions() {
        OkxRestResponse<Map<String, Object>> response = okxApiService.getPositions("SWAP", "BTC-USDT-SWAP");
        System.out.println(response);
    }

    @Test
    public void testClosePosition() {
        okxApiService.closePosition("BTC-USDT-SWAP", "cross", "long");
    }
}
