package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class PolymarketPythonSdkBridge {

    private final ObjectMapper objectMapper;

    public boolean isAvailable(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        String python = StringUtils.hasText(context.getPythonExecutable()) ? context.getPythonExecutable() : "python3";
        ProcessBuilder builder = new ProcessBuilder(
                python,
                "-c",
                "import importlib.util; raise SystemExit(0 if importlib.util.find_spec('py_clob_client') else 1)"
        );
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public Map<String, Object> deriveApiCreds(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        return invoke("derive-api-creds", Map.of(), context);
    }

    public Map<String, Object> placeOrder(ExecutionWalletContextLoader.ExecutionWalletContext context,
                                          PolymarketOrderRequestFactory.PreparedPolymarketOrder order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("host", context.getApiBaseUrl());
        payload.put("chainId", context.getChainId());
        payload.put("signatureType", context.getSignatureType());
        payload.put("orderType", context.getOrderType());
        payload.put("tokenId", order.getTokenId());
        payload.put("side", order.getSide());
        payload.put("price", order.getLimitPrice());
        payload.put("size", order.getSize());
        payload.put("amount", order.getStakeUsdc());
        payload.put("negRisk", order.isNegRisk());
        payload.put("signalId", order.getSignalId());
        payload.put("marketId", order.getMarketId());
        payload.put("question", order.getQuestion());
        return invoke("place-order", payload, context);
    }

    public Map<String, Object> getOrder(ExecutionWalletContextLoader.ExecutionWalletContext context, String orderId) {
        return invoke("get-order", Map.of("orderId", orderId), context);
    }

    public Map<String, Object> cancelOrder(ExecutionWalletContextLoader.ExecutionWalletContext context, String orderId) {
        return invoke("cancel-order", Map.of("orderId", orderId), context);
    }

    private Map<String, Object> invoke(String command,
                                       Map<String, Object> payload,
                                       ExecutionWalletContextLoader.ExecutionWalletContext context) {
        if (!"PYTHON".equalsIgnoreCase(context.getSdkMode())) {
            throw new IllegalStateException("Unsupported Polymarket CLOB sdk-mode: " + context.getSdkMode());
        }
        String python = StringUtils.hasText(context.getPythonExecutable()) ? context.getPythonExecutable() : "python3";
        String scriptPath = resolveScriptPath(context.getHelperScriptPath()).toString();
        ProcessBuilder builder = new ProcessBuilder(python, scriptPath, command);
        Map<String, String> env = builder.environment();
        env.put("POLY_CLOB_HOST", defaultString(context.getApiBaseUrl(), "https://clob.polymarket.com"));
        env.put("POLY_CHAIN_ID", String.valueOf(context.getChainId() == null ? 137 : context.getChainId()));
        env.put("POLY_PRIVATE_KEY", defaultString(context.getPrivateKey(), ""));
        env.put("POLY_ADDRESS", defaultString(context.getAddress(), ""));
        env.put("POLY_FUNDER", defaultString(context.getFunderAddress(), context.getAddress()));
        env.put("POLY_API_KEY", defaultString(context.getApiKey(), ""));
        env.put("POLY_API_SECRET", defaultString(context.getApiSecret(), ""));
        env.put("POLY_API_PASSPHRASE", defaultString(context.getApiPassphrase(), ""));
        env.put("POLY_DERIVE_API_CREDS_IF_MISSING", Boolean.toString(context.isDeriveApiCredsIfMissing()));
        env.put("POLY_SIGNATURE_TYPE", String.valueOf(context.getSignatureType() == null ? 0 : context.getSignatureType()));
        env.put("POLY_ORDER_TYPE", defaultString(context.getOrderType(), "GTC"));
        try {
            Process process = builder.start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(objectMapper.writeValueAsBytes(payload));
                stdin.flush();
            }
            boolean finished = process.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Polymarket Python SDK bridge timed out.");
            }
            String stdout = readFully(process.getInputStream());
            String stderr = readFully(process.getErrorStream());
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Polymarket Python SDK bridge failed: " + firstNonBlank(stderr, stdout));
            }
            if (!StringUtils.hasText(stdout)) {
                return Map.of();
            }
            return objectMapper.readValue(stdout, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to launch Polymarket Python SDK bridge. Check python executable and helper script path.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Polymarket Python SDK bridge.", e);
        }
    }

    private Path resolveScriptPath(String helperScriptPath) {
        String candidate = StringUtils.hasText(helperScriptPath) ? helperScriptPath.trim() : "scripts/polymarket_clob_client.py";
        Path path = Path.of(candidate);
        return path.isAbsolute() ? path : Path.of(System.getProperty("user.dir")).resolve(path).normalize();
    }

    private String readFully(java.io.InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        stream.transferTo(output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim() : defaultString(fallback, "unknown error");
    }
}
