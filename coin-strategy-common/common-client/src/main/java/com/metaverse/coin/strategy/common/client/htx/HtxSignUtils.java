package com.metaverse.coin.strategy.common.client.htx;

import com.metaverse.coin.strategy.common.base.utils.SignUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * HTX (Huobi) REST signature helper.
 * <p>
 * HTX signs each authenticated request with HmacSHA256 over the canonical string:
 * <pre>{@code
 *   METHOD\n
 *   Host\n
 *   Path\n
 *   SortedQueryString
 * }</pre>
 * The sorted query MUST include the required auth params in ASCII-ordered form.
 * The result is Base64 encoded and appended as the {@code Signature} query parameter.
 * <p>
 * We compose only the query string here; the caller builds the final HTTP request
 * (see {@link com.metaverse.coin.strategy.common.client.htx.rest.HtxRestClient}).
 */
public final class HtxSignUtils {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private HtxSignUtils() {
    }

    /**
     * @return current UTC timestamp in HTX-required format (yyyy-MM-ddTHH:mm:ss).
     */
    public static String utcTimestamp() {
        return TS.format(Instant.now());
    }

    /**
     * Compose a signed query string.
     *
     * @param method    HTTP method, upper case (GET/POST)
     * @param host      lower-case host (e.g. api.huobi.pro)
     * @param path      request path with leading slash (e.g. /market/depth)
     * @param accessKey HTX AccessKeyId
     * @param secretKey HTX SecretKey
     * @param bizParams business parameters (may be empty); iteration order preserved for readability but
     *                  signature always uses ASCII-sorted params
     * @return the full query string (including Signature), URL encoded and ready to append to the URL.
     */
    public static String signedQuery(String method,
                                     String host,
                                     String path,
                                     String accessKey,
                                     String secretKey,
                                     Map<String, String> bizParams) {
        Map<String, String> allParams = new TreeMap<>();
        if (bizParams != null) {
            for (Map.Entry<String, String> e : bizParams.entrySet()) {
                if (e.getValue() != null) {
                    allParams.put(e.getKey(), e.getValue());
                }
            }
        }
        allParams.put("AccessKeyId", accessKey);
        allParams.put("SignatureMethod", "HmacSHA256");
        allParams.put("SignatureVersion", "2");
        allParams.put("Timestamp", utcTimestamp());

        String sortedQuery = encodeQuery(allParams);
        String payload = method.toUpperCase() + "\n"
                + host.toLowerCase() + "\n"
                + path + "\n"
                + sortedQuery;

        String signature;
        try {
            signature = Base64.getEncoder().encodeToString(
                    SignUtils.hmacSHA256(secretKey.getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign HTX request", e);
        }

        Map<String, String> withSig = new LinkedHashMap<>();
        // preserve alphabetical order for the request URL as well
        withSig.putAll(allParams);
        withSig.put("Signature", signature);
        return encodeQuery(withSig);
    }

    private static String encodeQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(urlEncode(e.getKey()));
            sb.append('=');
            sb.append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        // HTX expects RFC 3986 encoding: space -> %20, ~ untouched.
        String encoded = URLEncoder.encode(s, StandardCharsets.UTF_8);
        return encoded.replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
}
