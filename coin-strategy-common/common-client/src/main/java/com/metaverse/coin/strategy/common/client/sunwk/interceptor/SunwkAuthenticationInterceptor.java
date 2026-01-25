package com.metaverse.coin.strategy.common.client.sunwk.interceptor;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class SunwkAuthenticationInterceptor implements Interceptor {

    private final String accessKeyId;
    private final String secretKey;

    public SunwkAuthenticationInterceptor(String accessKeyId, String secretKey) {
        this.accessKeyId = accessKeyId;
        this.secretKey = secretKey;
    }

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        Request original = chain.request();
        HttpUrl originalUrl = original.url();

        // 1. Add authentication parameters
        String timestamp = ZonedDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        
        HttpUrl.Builder urlBuilder = originalUrl.newBuilder()
                .addQueryParameter("AccessKeyId", accessKeyId)
                .addQueryParameter("SignatureMethod", "HmacSHA256")
                .addQueryParameter("SignatureVersion", "2")
                .addQueryParameter("Timestamp", timestamp);

        // 2. Sort parameters and construct string to sign
        // Format: METHOD\nHOST\nPATH\nSortedQueryString
        HttpUrl newUrl = urlBuilder.build();
        String method = original.method().toUpperCase();
        String host = newUrl.host();
        String path = newUrl.encodedPath();
        
        // Get all query parameters, sort them
        Set<String> queryParams = newUrl.queryParameterNames();
        Map<String, String> sortedParams = new TreeMap<>();
        for (String param : queryParams) {
            // Use the first value for simplicity as per standard signing
            sortedParams.put(param, newUrl.queryParameter(param));
        }

        StringBuilder queryString = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            if (queryString.length() > 0) {
                queryString.append("&");
            }
            queryString.append(urlEncode(entry.getKey())).append("=").append(urlEncode(entry.getValue()));
        }

        String stringToSign = String.format("%s\n%s\n%s\n%s", method, host, path, queryString.toString());

        // 3. Calculate Signature
        String signature = sign(stringToSign, secretKey);

        // 4. Add Signature to the request
        HttpUrl finalUrl = newUrl.newBuilder()
                .addQueryParameter("Signature", signature)
                .build();

        Request.Builder requestBuilder = original.newBuilder()
                .url(finalUrl);

        return chain.proceed(requestBuilder.build());
    }

    private String sign(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign request", e);
        }
    }

    private String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
