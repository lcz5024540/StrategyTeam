package com.metaverse.coin.strategy.common.client.htx.rest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import com.metaverse.coin.strategy.common.client.htx.HtxSignUtils;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * Thin OkHttp3-based transport for HTX Spot REST APIs.
 * <p>
 * Auth-aware: private endpoints ({@link #signedGet}/{@link #signedPost}) automatically
 * attach the HTX signature via {@link HtxSignUtils}. Public endpoints ({@link #publicGet})
 * hit market data unauthenticated.
 * <p>
 * The client returns the raw {@link HtxRestResponse} envelope so callers can distinguish
 * success ({@code status=ok}) from HTX's error codes without exception overhead.
 */
@Slf4j
public class HtxRestClient {

    private final OkHttpClient http;
    private final String baseUrl;
    private final String host;
    private final String accessKey;
    private final String secretKey;

    public HtxRestClient(String accessKey, String secretKey) {
        this(accessKey, secretKey, "https://api.huobi.pro");
    }

    public HtxRestClient(String accessKey, String secretKey, String baseUrl) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.host = URI.create(this.baseUrl).getHost();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Test seam: build with a supplied OkHttpClient (used in MockWebServer-driven unit tests).
     */
    public HtxRestClient(String accessKey, String secretKey, String baseUrl, OkHttpClient http) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.host = URI.create(this.baseUrl).getHost();
        this.http = http;
    }

    // -------- public (market data) --------

    public <T> HtxRestResponse<T> publicGet(String path, Map<String, String> params, TypeReference<HtxRestResponse<T>> ref) {
        String url = baseUrl + path + toQueryString(params);
        Request req = new Request.Builder().url(url).get().build();
        return execute(req, ref);
    }

    // -------- private (auth) --------

    public <T> HtxRestResponse<T> signedGet(String path, Map<String, String> params, TypeReference<HtxRestResponse<T>> ref) {
        String qs = HtxSignUtils.signedQuery("GET", host, path, accessKey, secretKey, params == null ? Collections.emptyMap() : params);
        Request req = new Request.Builder().url(baseUrl + path + "?" + qs).get().build();
        return execute(req, ref);
    }

    public <T> HtxRestResponse<T> signedPost(String path, Map<String, String> params, Object body, TypeReference<HtxRestResponse<T>> ref) {
        String qs = HtxSignUtils.signedQuery("POST", host, path, accessKey, secretKey, params == null ? Collections.emptyMap() : params);
        String jsonBody = body == null ? "{}" : JSON.toJSONString(body);
        RequestBody rb = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request req = new Request.Builder().url(baseUrl + path + "?" + qs).post(rb).build();
        return execute(req, ref);
    }

    // -------- internal --------

    private <T> HtxRestResponse<T> execute(Request req, TypeReference<HtxRestResponse<T>> ref) {
        try (Response resp = http.newCall(req).execute()) {
            String bodyStr = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                log.warn("HTX HTTP {} for {}: {}", resp.code(), req.url(), bodyStr);
                HtxRestResponse<T> err = new HtxRestResponse<>();
                err.setStatus("error");
                err.setErrCode("http-" + resp.code());
                err.setErrMsg(bodyStr);
                return err;
            }
            // HTX returns "ch"/"ts"/"tick" for market data and "status"/"data" for trade endpoints.
            // fastjson2 can parse both when we let the envelope carry either field.
            JSONObject json = JSON.parseObject(bodyStr);
            String status = json.getString("status");
            if (status == null) {
                // Market data endpoints omit "status" when server returns success; treat as ok if "tick"/"data" present.
                if (json.containsKey("tick") || json.containsKey("data")) {
                    json.put("status", "ok");
                } else {
                    HtxRestResponse<T> err = new HtxRestResponse<>();
                    err.setStatus("error");
                    err.setErrMsg(bodyStr);
                    return err;
                }
            }
            return json.to(ref.getType());
        } catch (IOException e) {
            log.error("HTX I/O error for {}: {}", req.url(), e.toString());
            HtxRestResponse<T> err = new HtxRestResponse<>();
            err.setStatus("error");
            err.setErrCode("io");
            err.setErrMsg(e.getMessage());
            return err;
        }
    }

    private static String toQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String trimTrailingSlash(String s) {
        return s == null ? "" : (s.endsWith("/") ? s.substring(0, s.length() - 1) : s);
    }
}
