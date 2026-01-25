package com.metaverse.coin.strategy.common.client.gate.interceptor;

import com.metaverse.coin.strategy.common.base.utils.SignUtils;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;

public class GateAuthenticationInterceptor implements Interceptor {

    private final String apiKey;
    private final String secretKey;

    public GateAuthenticationInterceptor(String apiKey, String secretKey) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
    }

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        Request original = chain.request();
        Request.Builder builder = original.newBuilder();

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String method = original.method();
        String path = original.url().encodedPath();
        String query = original.url().encodedQuery();
        if (query == null) {
            query = "";
        }

        String body = "";
        if (original.body() != null) {
            Buffer buffer = new Buffer();
            original.body().writeTo(buffer);
            body = buffer.readUtf8();
        }

        String payloadHash = getSha512Hex(body);
        String signatureString = String.format("%s\n%s\n%s\n%s\n%s", method, path, query, payloadHash, timestamp);

        String sign;
        try {
            byte[] hmac = SignUtils.hmacSHA512(secretKey.getBytes(), signatureString.getBytes());
            sign = bytesToHex(hmac);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign request", e);
        }

        builder.addHeader("KEY", apiKey);
        builder.addHeader("Timestamp", timestamp);
        builder.addHeader("SIGN", sign);
        builder.addHeader("Content-Type", "application/json");
        builder.addHeader("Accept", "application/json");

        return chain.proceed(builder.build());
    }

    private String getSha512Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] encodedhash = digest.digest(content.getBytes());
            return bytesToHex(encodedhash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
