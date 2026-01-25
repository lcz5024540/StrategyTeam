package com.metaverse.coin.strategy.common.client.sunwk;

import com.metaverse.coin.strategy.common.client.sunwk.interceptor.SunwkAuthenticationInterceptor;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

import java.util.concurrent.TimeUnit;

public class SunwkApiClientFactory {

    private final String baseUrl;
    private final String apiKey;
    private final String secretKey;

    public SunwkApiClientFactory(String baseUrl, String apiKey, String secretKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
    }

    public SunwkApiService create() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new SunwkAuthenticationInterceptor(apiKey, secretKey))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(ScalarsConverterFactory.create()) // For String response
                .addConverterFactory(JacksonConverterFactory.create()) // For JSON to Object
                .build();

        SunwkApi sunwkApi = retrofit.create(SunwkApi.class);
        return new SunwkApiService(sunwkApi);
    }
}
