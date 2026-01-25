package com.metaverse.coin.strategy.common.client.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.client.gate.interceptor.GateAuthenticationInterceptor;
import com.metaverse.coin.strategy.common.client.gate.service.*;
import lombok.Getter;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

@Getter
public class GateApiService {

    private final GateApi gateApi;
    private final GateAccountService accountService;
    private final GateTradeService tradeService;
    private final GateMarketDataService marketDataService;
    private final GateWalletService walletService;

    public GateApiService(GateApi gateApi) {
        this.gateApi = gateApi;
        this.accountService = new GateAccountService(gateApi);
        this.tradeService = new GateTradeService(gateApi);
        this.marketDataService = new GateMarketDataService(gateApi);
        this.walletService = new GateWalletService(gateApi);
    }

    public static GateApiService create(String apiKey, String secretKey) {
        return create(apiKey, secretKey, "https://api.gateio.ws/api/v4/");
    }

    public static GateApiService create(String apiKey, String secretKey, String baseUrl) {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new GateAuthenticationInterceptor(apiKey, secretKey))
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(JacksonConverterFactory.create(new ObjectMapper()))
                .build();

        GateApi gateApi = retrofit.create(GateApi.class);
        return new GateApiService(gateApi);
    }
}
