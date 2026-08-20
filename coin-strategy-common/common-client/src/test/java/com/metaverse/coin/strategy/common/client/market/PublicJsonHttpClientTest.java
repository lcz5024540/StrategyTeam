package com.metaverse.coin.strategy.common.client.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicJsonHttpClientTest {
    @Test
    void parsesSuccessfulJsonResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"rate\":\"0.001\"}"));
            server.start();
            PublicJsonHttpClient client = new PublicJsonHttpClient(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                    new ObjectMapper());

            assertEquals("0.001", client.get(server.url("/funding").uri()).path("rate").asText());
        }
    }

    @Test
    void rejectsNonSuccessfulResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429).setBody("rate limited"));
            server.start();
            PublicJsonHttpClient client = new PublicJsonHttpClient(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                    new ObjectMapper());

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> client.get(server.url("/funding").uri()));
            assertEquals("HTTP 429 from localhost", exception.getMessage());
        }
    }
}
