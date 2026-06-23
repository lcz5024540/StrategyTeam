package com.metaverse.coin.strategy.web.configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.client.okx.OkxApiService;
import com.metaverse.coin.strategy.common.client.okx.interceptor.AuthenticationInterceptor;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 客户端配置（CORS + OKX）。
 *
 * <p>新架构下 OKX/Binance/Gate 等外部交易所客户端默认 <strong>非必填</strong>：
 * application.yml 未配置时 web 仍能启动，对应的 ApiService bean 不创建，
 * 调用方需要在 @Autowired 处加 {@code required=false} 或者 {@code ObjectProvider}。</p>
 */
@Configuration
public class ClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ClientConfiguration.class);

    // ── OKX 凭证（全部带默认空值，配置缺失也不会启动失败）──────────────────
    @Value("${client.okx.apiKey:}")
    private String okxApiKey;

    @Value("${client.okx.simulated:false}")
    private boolean okxSimulated;

    @Value("${client.okx.passphrase:}")
    private String okxPassphrase;

    @Value("${client.okx.secretKey:}")
    private String okxSecretKey;

    @Value("${client.okx.url:https://www.okx.com}")
    private String okxUrl;

    /**
     * OKX 代理（可选）。未配置时不走代理。
     * <p>之前硬编码 proxy.huobidev:3129，在本地/测试环境会导致解析失败而启动报错。</p>
     */
    @Value("${client.okx.proxy.host:}")
    private String okxProxyHost;

    @Value("${client.okx.proxy.port:0}")
    private int okxProxyPort;

    /**
     * 仅当显式配置了 {@code client.okx.apiKey} 时才创建 OKX 客户端。
     * 没配 → bean 不存在 → 注入方需要 required=false 或 ObjectProvider。
     */
    @Bean
    @ConditionalOnProperty(name = "client.okx.apiKey")
    public OkxApiService getOkxService() {
        if (okxApiKey == null || okxApiKey.isBlank()) {
            log.info("[ClientConfiguration] client.okx.apiKey 未配置, 跳过 OkxApiService 初始化");
            return null;
        }
        Duration defaultTimeout = Duration.ofSeconds(30);
        OkHttpClient.Builder clientBuilder = defaultClient(okxApiKey, okxSecretKey, okxPassphrase, okxSimulated, defaultTimeout).newBuilder();
        if (okxProxyHost != null && !okxProxyHost.isBlank() && okxProxyPort > 0) {
            log.info("[ClientConfiguration] OKX 走代理 {}:{}", okxProxyHost, okxProxyPort);
            clientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(okxProxyHost, okxProxyPort)));
        }
        OkHttpClient client = clientBuilder.build();
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

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://127.0.0.1:*", "http://localhost:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
