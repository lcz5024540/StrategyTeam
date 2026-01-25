package com.metaverse.coin.strategy.web.configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.client.okx.OkxApiService;
import com.metaverse.coin.strategy.common.client.okx.interceptor.AuthenticationInterceptor;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${client.okx.apiKey}")
    private String okxApiKey;

    @Value("${client.okx.simulated}")
    private boolean okxSimulated;

    @Value("${client.okx.passphrase}")
    private String okxPassphrase;

    @Value("${client.okx.secretKey}")
    private String okxSecretKey;

    @Value("${client.okx.url}")
    private String okxUrl;


    @Bean
    public OkxApiService getOkxService(){
        Duration defaultTimeout = Duration.ofSeconds(30);
//        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("tw02.ctg.wtf", 4649));
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy.huobidev", 3129));
        OkHttpClient client = defaultClient(okxApiKey, okxSecretKey, okxPassphrase, okxSimulated, defaultTimeout)
                .newBuilder()
                .proxy(proxy)
                .build();
        return new OkxApiService(buildApi(okxApiKey, okxSecretKey, okxPassphrase, okxUrl, okxSimulated, defaultTimeout), client, okxSimulated);
    }

    private static OkxApi buildApi(String apiKey, String secretKey, String passphrase, String url, boolean simulated, Duration timeout) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OkHttpClient client = defaultClient(apiKey, secretKey, passphrase, simulated, timeout);
        Retrofit retrofit = defaultRetrofit(client, mapper, url);
        return retrofit.create(OkxApi.class);
    }

    public static OkHttpClient defaultClient(String apiKey, String secretKey, String passphrase, boolean simulated, Duration timeout) {
        return new OkHttpClient.Builder()
                .addInterceptor(new AuthenticationInterceptor(apiKey, secretKey, passphrase, simulated))
                .connectionPool(new ConnectionPool(10, 4, TimeUnit.SECONDS))
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build();

    }

    public static Retrofit defaultRetrofit(OkHttpClient client, ObjectMapper mapper, String url) {
        return new Retrofit.Builder()
                .baseUrl(url)
                .client(client)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(JacksonConverterFactory.create(mapper))
                .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
                .build();
    }
}
