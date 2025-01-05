package com.metaverse.coin.strategy.web.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.base.constants.OkxConstants;
import com.metaverse.coin.strategy.common.client.ApiService;
import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.client.okx.OkxApiService;
import com.metaverse.coin.strategy.common.client.okx.interceptor.AuthenticationInterceptor;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class ClientConfiguration {

    @Bean
    public OkxApiService getOkxService(){
        // 模拟盘
        String apiKey = System.getenv("API_KEY");
        String passphrase = System.getenv("PASSPHRASE");
        String secretKey = System.getenv("SECRET_KEY");
        Duration defaultTimeout = Duration.ofSeconds(30);
        boolean simulated = true;
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 7890));
        OkHttpClient client = defaultClient(apiKey, secretKey, passphrase, simulated, defaultTimeout)
                .newBuilder()
                .proxy(proxy)
                .build();
        return new OkxApiService(buildApi(apiKey, secretKey, passphrase, simulated, defaultTimeout), client, simulated);
    }

    private static OkxApi buildApi(String apiKey, String secretKey, String passphrase, boolean simulated, Duration timeout) {
        ObjectMapper mapper = ApiService.errorMapper;
        OkHttpClient client = defaultClient(apiKey, secretKey, passphrase, simulated, timeout);
        Retrofit retrofit = defaultRetrofit(client, mapper, simulated);
        return retrofit.create(OkxApi.class);
    }

    public static OkHttpClient defaultClient(String apiKey, String secretKey, String passphrase, boolean simulated, Duration timeout) {
        return new OkHttpClient.Builder()
                .addInterceptor(new AuthenticationInterceptor(apiKey, secretKey, passphrase, simulated))
                .connectionPool(new ConnectionPool(10, 4, TimeUnit.SECONDS))
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build();

    }

    public static Retrofit defaultRetrofit(OkHttpClient client, ObjectMapper mapper, boolean simulated) {
        return new Retrofit.Builder()
                .baseUrl(simulated ? OkxConstants.SIM_BASE_URL : OkxConstants.BASE_URL)
                .client(client)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(JacksonConverterFactory.create(mapper))
                .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
                .build();
    }
}
