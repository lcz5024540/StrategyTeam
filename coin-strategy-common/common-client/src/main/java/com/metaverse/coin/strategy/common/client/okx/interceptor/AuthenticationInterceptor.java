package com.metaverse.coin.strategy.common.client.okx.interceptor;

import com.metaverse.coin.strategy.common.base.utils.SignUtils;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Instant;

public class AuthenticationInterceptor implements Interceptor {

    private final String apiKey;
    private final String secretKey;
    private final String passphrase;
    private final boolean simulated;

    public AuthenticationInterceptor(String apiKey, String secretKey, String passphrase, boolean simulated) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.passphrase = passphrase;
        this.simulated = simulated;
    }

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        Request original = chain.request();
        Request.Builder builder = original.newBuilder();

        String timestamp = Instant.now().toString();
        String method = original.method();
        String path = original.url().encodedPath();
        String query = original.url().encodedQuery();
        if (query != null && !query.isEmpty()) {
            path += "?" + query;
        }

        String body = "";
        if (original.body() != null) {
            Buffer buffer = new Buffer();
            original.body().writeTo(buffer);
            body = buffer.readUtf8();
        }

        String sign = SignUtils.signRest(secretKey, timestamp, method, path, body);

        builder.addHeader("OK-ACCESS-KEY", apiKey);
        builder.addHeader("OK-ACCESS-SIGN", sign);
        builder.addHeader("OK-ACCESS-TIMESTAMP", timestamp);
        builder.addHeader("OK-ACCESS-PASSPHRASE", passphrase);
        if (simulated) {
            builder.addHeader("x-simulated-trading", "1");
        }

        return chain.proceed(builder.build());
    }
}
