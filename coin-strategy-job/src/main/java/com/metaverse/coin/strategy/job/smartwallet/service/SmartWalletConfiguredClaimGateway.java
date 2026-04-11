package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SmartWalletConfiguredClaimGateway implements SmartWalletOnChainClaimGateway {

    private static final MediaType JSON = MediaType.get("application/json");

    private final SmartWalletProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    @Override
    public ClaimSubmission submitClaim(SmartWalletPositionEntity position) {
        String mode = properties.getClaim().getLiveGatewayMode();
        if (!StringUtils.hasText(mode) || "MANUAL_CONFIRM".equalsIgnoreCase(mode)) {
            return ClaimSubmission.manualConfirmRequired(
                    "Live claim is waiting for external on-chain confirmation because smart-wallet.claim.live-gateway-mode=MANUAL_CONFIRM."
            );
        }
        if ("SIMULATED_SUCCESS".equalsIgnoreCase(mode)) {
            String txHash = "simulated-claim-" + position.getId();
            return ClaimSubmission.claimed("Live claim completed through simulated gateway mode.", txHash);
        }
        if ("SIMULATED_SUBMITTED".equalsIgnoreCase(mode)) {
            String txHash = "submitted-claim-" + position.getId();
            return ClaimSubmission.submitted("Live claim was submitted and is waiting for confirmation.", txHash);
        }
        if ("RELAYER".equalsIgnoreCase(mode)) {
            return submitViaRelayer(position);
        }
        return ClaimSubmission.retryableFailure("Unsupported live claim gateway mode: " + mode);
    }

    private ClaimSubmission submitViaRelayer(SmartWalletPositionEntity position) {
        SmartWalletProperties.Claim config = properties.getClaim();
        if (position == null || !StringUtils.hasText(position.getMarketId())) {
            return ClaimSubmission.permanentFailure("Claim position is missing marketId / conditionId.");
        }
        if (position.getQuantity() == null || position.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return ClaimSubmission.permanentFailure("Claim position has no redeemable quantity.");
        }
        if (!StringUtils.hasText(config.getBuilderApiKey())) {
            return ClaimSubmission.permanentFailure("Claim relayer mode requires smart-wallet.claim.builder-api-key.");
        }
        String baseUrl = StringUtils.hasText(config.getRelayerBaseUrl())
                ? config.getRelayerBaseUrl().trim()
                : "https://relayer.polymarket.com";
        String url = baseUrl.endsWith("/") ? baseUrl + "redeem" : baseUrl + "/redeem";
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("conditionId", position.getMarketId().trim());
        payload.put("amounts", java.util.List.of(position.getQuantity().stripTrailingZeros()));
        try {
            String requestJson = objectMapper.writeValueAsString(payload);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Authorization", config.getBuilderApiKey().trim())
                    .post(RequestBody.create(requestJson, JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    return ClaimSubmission.retryableFailure("Claim relayer request failed status="
                            + response.code() + " body=" + responseBody);
                }
                Map<String, Object> decoded = decodeJson(responseBody);
                String txHash = firstNonBlank(
                        decoded.get("txHash"),
                        decoded.get("transactionHash"),
                        decoded.get("hash"),
                        decoded.get("tx_hash")
                );
                String status = firstNonBlank(
                        decoded.get("status"),
                        decoded.get("state"),
                        decoded.get("result")
                );
                if ("CLAIMED".equalsIgnoreCase(status) || "CONFIRMED".equalsIgnoreCase(status)) {
                    return ClaimSubmission.claimed("Live claim completed through Polymarket relayer.", txHash);
                }
                return ClaimSubmission.submitted("Live claim submitted through Polymarket relayer.", txHash);
            }
        } catch (Exception e) {
            return ClaimSubmission.retryableFailure("Claim relayer submission failed: " + e.getMessage());
        }
    }

    private Map<String, Object> decodeJson(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("rawBody", responseBody);
        }
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }
}
