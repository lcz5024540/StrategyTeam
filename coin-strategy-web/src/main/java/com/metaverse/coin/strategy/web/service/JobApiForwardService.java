package com.metaverse.coin.strategy.web.service;

import com.metaverse.coin.strategy.web.config.DashboardProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

@Service
public class JobApiForwardService {

    private static final Set<String> REQUEST_HEADERS_TO_SKIP = Set.of(
            "connection",
            "content-length",
            "host",
            "transfer-encoding"
    );

    private static final Set<String> RESPONSE_HEADERS_TO_SKIP = Set.of(
            "connection",
            "content-length",
            "transfer-encoding"
    );

    private final DashboardProperties properties;
    private final HttpClient httpClient;

    public JobApiForwardService(DashboardProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public ResponseEntity<byte[]> forward(HttpServletRequest request,
                                          byte[] body) throws IOException, InterruptedException {
        HttpRequest proxied = buildRequest(request, body == null ? new byte[0] : body);
        HttpResponse<byte[]> response = httpClient.send(proxied, HttpResponse.BodyHandlers.ofByteArray());

        HttpHeaders headers = new HttpHeaders();
        response.headers().map().forEach((name, values) -> {
            if (RESPONSE_HEADERS_TO_SKIP.contains(name.toLowerCase())) {
                return;
            }
            headers.put(name, values);
        });
        return ResponseEntity.status(response.statusCode())
                .headers(headers)
                .body(response.body());
    }

    private HttpRequest buildRequest(HttpServletRequest request, byte[] body) {
        String forwardPath = request.getRequestURI().substring(request.getContextPath().length());
        String query = request.getQueryString();
        String target = normalizeBaseUrl(properties.getJobBaseUrl())
                + request.getContextPath()
                + forwardPath
                + (query == null || query.isBlank() ? "" : "?" + query);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .timeout(Duration.ofMinutes(2));

        copyRequestHeaders(request, builder);

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        if (requiresBody(method)) {
            builder.method(method.name(), HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            builder.method(method.name(), HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        Enumeration<String> names = request.getHeaderNames();
        List<String> headerNames = names == null ? Collections.emptyList() : Collections.list(names);
        for (String headerName : headerNames) {
            if (REQUEST_HEADERS_TO_SKIP.contains(headerName.toLowerCase())) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(headerName);
            if (values == null) {
                continue;
            }
            for (String value : Collections.list(values)) {
                builder.header(headerName, value);
            }
        }
    }

    private boolean requiresBody(HttpMethod method) {
        return HttpMethod.POST.equals(method)
                || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method)
                || HttpMethod.DELETE.equals(method);
    }

    private String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "http://127.0.0.1:8081";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
