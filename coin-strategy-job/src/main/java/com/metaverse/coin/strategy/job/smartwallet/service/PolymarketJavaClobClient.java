package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class PolymarketJavaClobClient implements PolymarketClobClient {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final String AUTH_DOMAIN_NAME = "ClobAuthDomain";
    private static final String AUTH_VERSION = "1";
    private static final String AUTH_MESSAGE = "This message attests that I control the given wallet";
    private static final String ORDER_DOMAIN_NAME = "Polymarket CTF Exchange";
    private static final String ORDER_VERSION = "1";
    private static final byte[] AUTH_DOMAIN_TYPE_HASH = keccak("EIP712Domain(string name,string version,uint256 chainId)");
    private static final byte[] ORDER_DOMAIN_TYPE_HASH = keccak("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)");
    private static final byte[] AUTH_TYPE_HASH = keccak("ClobAuth(address address,string timestamp,uint256 nonce,string message)");
    private static final byte[] ORDER_TYPE_HASH = keccak("Order(uint256 salt,address maker,address signer,address taker,uint256 tokenId,uint256 makerAmount,uint256 takerAmount,uint256 expiration,uint256 nonce,uint256 feeRateBps,uint8 side,uint8 signatureType)");
    private static final ECDomainParameters CURVE;
    private static final BigInteger HALF_CURVE_ORDER;
    private static final BigInteger FIELD_PRIME;
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        var params = SECNamedCurves.getByName("secp256k1");
        CURVE = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
        HALF_CURVE_ORDER = params.getN().shiftRight(1);
        FIELD_PRIME = params.getCurve().getField().getCharacteristic();
    }

    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .protocols(List.of(Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    @Override
    public boolean supports(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        return context == null || !StringUtils.hasText(context.getSdkMode()) || "JAVA".equalsIgnoreCase(context.getSdkMode());
    }

    @Override
    public boolean isAvailable(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        return true;
    }

    @Override
    public Map<String, Object> deriveApiCreds(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        log.info("Polymarket Java CLOB invoke operation=derive-api-creds address={} chainId={}",
                context.getAddress(), context.getChainId());
        Map<String, String> headers = createLevel1Headers(context, 0);
        try {
            Map<String, Object> created = executeJson("POST", context, "/auth/api-key", headers, null);
            return normalizeApiCreds(created, false);
        } catch (IllegalStateException ex) {
            log.warn("Polymarket Java CLOB create api-key failed, trying derive fallback: {}", ex.getMessage());
            Map<String, Object> derived = executeJson("GET", context, "/auth/derive-api-key", headers, null);
            return normalizeApiCreds(derived, true);
        }
    }

    @Override
    public Map<String, Object> placeOrder(ExecutionWalletContextLoader.ExecutionWalletContext context,
                                          PolymarketOrderRequestFactory.PreparedPolymarketOrder order) {
        log.info("Polymarket Java CLOB invoke operation=place-order marketId={} tokenId={} side={} orderType={}",
                order.getMarketId(), order.getTokenId(), order.getSide(), context.getOrderType());
        ApiCreds creds = ensureApiCreds(context);
        SignedOrder signedOrder = signOrder(context, order);

        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("order", signedOrder.payload());
        body.put("owner", context.getAddress());
        body.put("orderType", normalizeOrderType(context.getOrderType()));
        body.put("postOnly", false);

        String serializedBody = serialize(body);
        Map<String, String> headers = createLevel2Headers(context, creds, "POST", "/order", serializedBody);
        Map<String, Object> response = executeJson("POST", context, "/order", headers, serializedBody);

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderID", firstNonBlank(response.get("orderID"), response.get("orderId"), response.get("id")));
        result.put("response", response);
        if (creds.derived()) {
            result.put("derivedApiCreds", true);
            result.put("apiKey", creds.apiKey());
            result.put("apiSecret", creds.apiSecret());
            result.put("apiPassphrase", creds.apiPassphrase());
        } else {
            result.put("derivedApiCreds", false);
        }
        return result;
    }

    @Override
    public Map<String, Object> getOrder(ExecutionWalletContextLoader.ExecutionWalletContext context, String orderId) {
        log.info("Polymarket Java CLOB invoke operation=get-order orderId={}", orderId);
        ApiCreds creds = ensureApiCreds(context);
        String path = "/data/order/" + orderId;
        Map<String, String> headers = createLevel2Headers(context, creds, "GET", path, null);
        return Map.of(
                "success", true,
                "response", executeJson("GET", context, path, headers, null)
        );
    }

    @Override
    public Map<String, Object> cancelOrder(ExecutionWalletContextLoader.ExecutionWalletContext context, String orderId) {
        log.info("Polymarket Java CLOB invoke operation=cancel-order orderId={}", orderId);
        ApiCreds creds = ensureApiCreds(context);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("orderID", orderId);
        String serializedBody = serialize(body);
        Map<String, String> headers = createLevel2Headers(context, creds, "DELETE", "/order", serializedBody);
        return Map.of(
                "success", true,
                "response", executeJson("DELETE", context, "/order", headers, serializedBody)
        );
    }

    private ApiCreds ensureApiCreds(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        if (StringUtils.hasText(context.getApiKey())
                && StringUtils.hasText(context.getApiSecret())
                && StringUtils.hasText(context.getApiPassphrase())) {
            return new ApiCreds(context.getApiKey(), context.getApiSecret(), context.getApiPassphrase(), false);
        }
        if (!context.isDeriveApiCredsIfMissing()) {
            throw new IllegalStateException("Missing Polymarket API credentials and derive mode is disabled.");
        }
        Map<String, Object> derived = deriveApiCreds(context);
        return new ApiCreds(
                stringValue(derived.get("apiKey")),
                stringValue(derived.get("apiSecret")),
                stringValue(derived.get("apiPassphrase")),
                true
        );
    }

    private SignedOrder signOrder(ExecutionWalletContextLoader.ExecutionWalletContext context,
                                  PolymarketOrderRequestFactory.PreparedPolymarketOrder order) {
        String normalizedSide = normalizeSide(order.getSide());
        String orderType = normalizeOrderType(context.getOrderType());
        RoundConfig roundConfig = RoundConfig.of(order.getTickSize());
        OrderAmounts amounts = "FOK".equalsIgnoreCase(orderType)
                ? marketOrderAmounts(normalizedSide, order.getStakeUsdc(), order.getLimitPrice(), roundConfig)
                : limitOrderAmounts(normalizedSide, order.getSize(), order.getLimitPrice(), roundConfig);

        int feeRateBps = fetchFeeRate(context, order.getTokenId());
        long salt = Math.abs(RANDOM.nextLong());
        String maker = StringUtils.hasText(context.getFunderAddress()) ? context.getFunderAddress() : context.getAddress();
        String signer = context.getAddress();
        String taker = ZERO_ADDRESS;
        String expiration = "0";
        String nonce = "0";
        int sideValue = "SELL".equalsIgnoreCase(normalizedSide) ? 1 : 0;
        int signatureType = context.getSignatureType() == null ? 0 : context.getSignatureType();

        LinkedHashMap<String, Object> orderPayload = new LinkedHashMap<>();
        orderPayload.put("salt", String.valueOf(salt));
        orderPayload.put("maker", checksumAddress(maker));
        orderPayload.put("signer", checksumAddress(signer));
        orderPayload.put("taker", ZERO_ADDRESS);
        orderPayload.put("tokenId", order.getTokenId());
        orderPayload.put("makerAmount", amounts.makerAmount().toString());
        orderPayload.put("takerAmount", amounts.takerAmount().toString());
        orderPayload.put("expiration", expiration);
        orderPayload.put("nonce", nonce);
        orderPayload.put("feeRateBps", String.valueOf(feeRateBps));
        orderPayload.put("side", normalizedSide);
        orderPayload.put("signatureType", signatureType);

        byte[] orderHash = typedDataHash(
                orderDomainSeparator(context.getChainId(), exchangeAddress(context.getChainId(), order.isNegRisk())),
                orderStructHash(
                        salt,
                        checksumAddress(maker),
                        checksumAddress(signer),
                        ZERO_ADDRESS,
                        new BigInteger(order.getTokenId()),
                        amounts.makerAmount(),
                        amounts.takerAmount(),
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.valueOf(feeRateBps),
                        sideValue,
                        signatureType
                )
        );
        orderPayload.put("signature", signHashHex(stripHexPrefix(context.getPrivateKey()), checksumAddress(signer), orderHash));
        return new SignedOrder(orderPayload);
    }

    private int fetchFeeRate(ExecutionWalletContextLoader.ExecutionWalletContext context, String tokenId) {
        Map<String, Object> response = executeJson("GET", context, "/fee-rate",
                Map.of("Accept", "application/json", "User-Agent", "CoinStrategy/1.0"),
                null,
                Map.of("token_id", tokenId));
        Object baseFee = response.get("base_fee");
        if (baseFee == null) {
            return 0;
        }
        return new BigDecimal(String.valueOf(baseFee)).setScale(0, RoundingMode.DOWN).intValue();
    }

    private Map<String, Object> normalizeApiCreds(Map<String, Object> raw, boolean derived) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("derived", derived);
        result.put("apiKey", firstNonBlank(raw.get("apiKey"), raw.get("api_key")));
        result.put("apiSecret", firstNonBlank(raw.get("secret"), raw.get("apiSecret"), raw.get("api_secret")));
        result.put("apiPassphrase", firstNonBlank(raw.get("passphrase"), raw.get("apiPassphrase"), raw.get("api_passphrase")));
        return result;
    }

    private Map<String, Object> executeJson(String method,
                                            ExecutionWalletContextLoader.ExecutionWalletContext context,
                                            String path,
                                            Map<String, String> headers,
                                            String serializedBody) {
        return executeJson(method, context, path, headers, serializedBody, Map.of());
    }

    private Map<String, Object> executeJson(String method,
                                            ExecutionWalletContextLoader.ExecutionWalletContext context,
                                            String path,
                                            Map<String, String> headers,
                                            String serializedBody,
                                            Map<String, String> queryParams) {
        try {
            HttpUrl.Builder urlBuilder = HttpUrl.parse(normalizeBaseUrl(context.getApiBaseUrl()) + path).newBuilder();
            queryParams.forEach(urlBuilder::addQueryParameter);
            Request.Builder requestBuilder = new Request.Builder().url(urlBuilder.build());
            headers.forEach(requestBuilder::header);
            RequestBody requestBody = serializedBody == null ? null : RequestBody.create(serializedBody, JSON);
            switch (method.toUpperCase(Locale.ROOT)) {
                case "GET" -> requestBuilder.get();
                case "POST" -> requestBuilder.post(requestBody == null ? RequestBody.create(new byte[0], JSON) : requestBody);
                case "DELETE" -> requestBuilder.delete(requestBody == null ? RequestBody.create(new byte[0], JSON) : requestBody);
                default -> throw new IllegalArgumentException("Unsupported method: " + method);
            }
            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("Polymarket CLOB request failed path=" + path
                            + " status=" + response.code() + " body=" + responseBody);
                }
                if (!StringUtils.hasText(responseBody)) {
                    return Map.of();
                }
                return objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to request Polymarket CLOB path=" + path, e);
        }
    }

    private Map<String, String> createLevel1Headers(ExecutionWalletContextLoader.ExecutionWalletContext context, int nonce) {
        long timestamp = Instant.now().getEpochSecond();
        byte[] authHash = typedDataHash(
                authDomainSeparator(context.getChainId()),
                authStructHash(checksumAddress(context.getAddress()), String.valueOf(timestamp), nonce, AUTH_MESSAGE)
        );
        String signature = signHashHex(stripHexPrefix(context.getPrivateKey()), checksumAddress(context.getAddress()), authHash);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "CoinStrategy/1.0");
        headers.put("POLY_ADDRESS", checksumAddress(context.getAddress()));
        headers.put("POLY_SIGNATURE", signature);
        headers.put("POLY_TIMESTAMP", String.valueOf(timestamp));
        headers.put("POLY_NONCE", String.valueOf(nonce));
        return headers;
    }

    private Map<String, String> createLevel2Headers(ExecutionWalletContextLoader.ExecutionWalletContext context,
                                                    ApiCreds creds,
                                                    String method,
                                                    String requestPath,
                                                    String serializedBody) {
        long timestamp = Instant.now().getEpochSecond();
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "CoinStrategy/1.0");
        headers.put("POLY_ADDRESS", checksumAddress(context.getAddress()));
        headers.put("POLY_SIGNATURE", buildHmac(creds.apiSecret(), String.valueOf(timestamp), method, requestPath, serializedBody));
        headers.put("POLY_TIMESTAMP", String.valueOf(timestamp));
        headers.put("POLY_API_KEY", creds.apiKey());
        headers.put("POLY_PASSPHRASE", creds.apiPassphrase());
        return headers;
    }

    private OrderAmounts limitOrderAmounts(String side, BigDecimal size, BigDecimal price, RoundConfig roundConfig) {
        BigDecimal rawPrice = roundNormal(price, roundConfig.priceDigits());
        if ("BUY".equalsIgnoreCase(side)) {
            BigDecimal rawTakerAmount = roundDown(size, roundConfig.sizeDigits());
            BigDecimal rawMakerAmount = rawTakerAmount.multiply(rawPrice);
            if (decimalPlaces(rawMakerAmount) > roundConfig.amountDigits()) {
                rawMakerAmount = roundUp(rawMakerAmount, roundConfig.amountDigits() + 4);
                if (decimalPlaces(rawMakerAmount) > roundConfig.amountDigits()) {
                    rawMakerAmount = roundDown(rawMakerAmount, roundConfig.amountDigits());
                }
            }
            return new OrderAmounts(toTokenDecimals(rawMakerAmount), toTokenDecimals(rawTakerAmount));
        }
        BigDecimal rawMakerAmount = roundDown(size, roundConfig.sizeDigits());
        BigDecimal rawTakerAmount = rawMakerAmount.multiply(rawPrice);
        if (decimalPlaces(rawTakerAmount) > roundConfig.amountDigits()) {
            rawTakerAmount = roundUp(rawTakerAmount, roundConfig.amountDigits() + 4);
            if (decimalPlaces(rawTakerAmount) > roundConfig.amountDigits()) {
                rawTakerAmount = roundDown(rawTakerAmount, roundConfig.amountDigits());
            }
        }
        return new OrderAmounts(toTokenDecimals(rawMakerAmount), toTokenDecimals(rawTakerAmount));
    }

    private OrderAmounts marketOrderAmounts(String side, BigDecimal amount, BigDecimal price, RoundConfig roundConfig) {
        BigDecimal rawPrice = roundNormal(price, roundConfig.priceDigits());
        if ("BUY".equalsIgnoreCase(side)) {
            BigDecimal rawMakerAmount = roundDown(amount, roundConfig.sizeDigits());
            BigDecimal rawTakerAmount = rawMakerAmount.divide(rawPrice, roundConfig.amountDigits() + 6, RoundingMode.HALF_UP);
            if (decimalPlaces(rawTakerAmount) > roundConfig.amountDigits()) {
                rawTakerAmount = roundUp(rawTakerAmount, roundConfig.amountDigits() + 4);
                if (decimalPlaces(rawTakerAmount) > roundConfig.amountDigits()) {
                    rawTakerAmount = roundDown(rawTakerAmount, roundConfig.amountDigits());
                }
            }
            return new OrderAmounts(toTokenDecimals(rawMakerAmount), toTokenDecimals(rawTakerAmount));
        }
        BigDecimal rawMakerAmount = roundDown(amount, roundConfig.sizeDigits());
        BigDecimal rawTakerAmount = rawMakerAmount.multiply(rawPrice);
        if (decimalPlaces(rawTakerAmount) > roundConfig.amountDigits()) {
            rawTakerAmount = roundUp(rawTakerAmount, roundConfig.amountDigits() + 4);
            if (decimalPlaces(rawTakerAmount) > roundConfig.amountDigits()) {
                rawTakerAmount = roundDown(rawTakerAmount, roundConfig.amountDigits());
            }
        }
        return new OrderAmounts(toTokenDecimals(rawMakerAmount), toTokenDecimals(rawTakerAmount));
    }

    private BigInteger toTokenDecimals(BigDecimal value) {
        BigDecimal scaled = value.multiply(new BigDecimal("1000000"));
        if (decimalPlaces(scaled) > 0) {
            scaled = roundNormal(scaled, 0);
        }
        return scaled.setScale(0, RoundingMode.DOWN).toBigIntegerExact();
    }

    private BigDecimal roundDown(BigDecimal value, int digits) {
        return value.setScale(digits, RoundingMode.DOWN);
    }

    private BigDecimal roundUp(BigDecimal value, int digits) {
        return value.setScale(digits, RoundingMode.UP);
    }

    private BigDecimal roundNormal(BigDecimal value, int digits) {
        return value.setScale(digits, RoundingMode.HALF_UP);
    }

    private int decimalPlaces(BigDecimal value) {
        return Math.max(value.stripTrailingZeros().scale(), 0);
    }

    private String normalizeOrderType(String orderType) {
        return StringUtils.hasText(orderType) ? orderType.trim().toUpperCase(Locale.ROOT) : "GTC";
    }

    private String normalizeSide(String side) {
        return "SELL".equalsIgnoreCase(side) ? "SELL" : "BUY";
    }

    private String normalizeBaseUrl(String baseUrl) {
        return StringUtils.hasText(baseUrl) ? baseUrl.replaceAll("/+$", "") : "https://clob.polymarket.com";
    }

    private String exchangeAddress(Integer chainId, boolean negRisk) {
        int resolvedChainId = chainId == null ? 137 : chainId;
        if (negRisk) {
            if (resolvedChainId == 137) {
                return "0xC5d563A36AE78145C45a50134d48A1215220f80a";
            }
            if (resolvedChainId == 80002) {
                return "0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296";
            }
        } else {
            if (resolvedChainId == 137) {
                return "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E";
            }
            if (resolvedChainId == 80002) {
                return "0xdFE02Eb6733538f8Ea35D585af8DE5958AD99E40";
            }
        }
        throw new IllegalStateException("Unsupported Polymarket chainId=" + resolvedChainId);
    }

    private String buildHmac(String apiSecret, String timestamp, String method, String requestPath, String body) {
        byte[] secretBytes = Base64.getUrlDecoder().decode(apiSecret);
        byte[] message = (timestamp + method.toUpperCase(Locale.ROOT) + requestPath + (body == null ? "" : body.replace('\'', '"')))
                .getBytes(StandardCharsets.UTF_8);
        javax.crypto.Mac mac;
        try {
            mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secretBytes, "HmacSHA256"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize HMAC signer.", e);
        }
        return Base64.getUrlEncoder().encodeToString(mac.doFinal(message));
    }

    private byte[] authDomainSeparator(Integer chainId) {
        byte[] encoded = concat(
                AUTH_DOMAIN_TYPE_HASH,
                keccakPadded(AUTH_DOMAIN_NAME),
                keccakPadded(AUTH_VERSION),
                uint256(chainId == null ? BigInteger.valueOf(137) : BigInteger.valueOf(chainId))
        );
        return keccak(encoded);
    }

    private byte[] orderDomainSeparator(Integer chainId, String verifyingContract) {
        byte[] encoded = concat(
                ORDER_DOMAIN_TYPE_HASH,
                keccakPadded(ORDER_DOMAIN_NAME),
                keccakPadded(ORDER_VERSION),
                uint256(chainId == null ? BigInteger.valueOf(137) : BigInteger.valueOf(chainId)),
                address(verifyingContract)
        );
        return keccak(encoded);
    }

    private byte[] authStructHash(String address, String timestamp, int nonce, String message) {
        return keccak(concat(
                AUTH_TYPE_HASH,
                address(address),
                keccakPadded(timestamp),
                uint256(BigInteger.valueOf(nonce)),
                keccakPadded(message)
        ));
    }

    private byte[] orderStructHash(long salt,
                                   String maker,
                                   String signer,
                                   String taker,
                                   BigInteger tokenId,
                                   BigInteger makerAmount,
                                   BigInteger takerAmount,
                                   BigInteger expiration,
                                   BigInteger nonce,
                                   BigInteger feeRateBps,
                                   int side,
                                   int signatureType) {
        return keccak(concat(
                ORDER_TYPE_HASH,
                uint256(BigInteger.valueOf(salt)),
                address(maker),
                address(signer),
                address(taker),
                uint256(tokenId),
                uint256(makerAmount),
                uint256(takerAmount),
                uint256(expiration),
                uint256(nonce),
                uint256(feeRateBps),
                uint8(side),
                uint8(signatureType)
        ));
    }

    private byte[] typedDataHash(byte[] domainSeparator, byte[] structHash) {
        return keccak(concat(new byte[]{0x19, 0x01}, domainSeparator, structHash));
    }

    private String signHashHex(String privateKeyHex, String expectedAddress, byte[] hash) {
        BigInteger privateKey = new BigInteger(stripHexPrefix(privateKeyHex), 16);
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, new ECPrivateKeyParameters(privateKey, CURVE));
        BigInteger[] signature = signer.generateSignature(hash);
        BigInteger r = signature[0];
        BigInteger s = signature[1];
        if (s.compareTo(HALF_CURVE_ORDER) > 0) {
            s = CURVE.getN().subtract(s);
        }
        int recId = findRecoveryId(r, s, hash, expectedAddress);
        if (recId < 0) {
            throw new IllegalStateException("Could not recover Ethereum signature id.");
        }
        byte[] out = new byte[65];
        System.arraycopy(toFixedLength(r, 32), 0, out, 0, 32);
        System.arraycopy(toFixedLength(s, 32), 0, out, 32, 32);
        out[64] = (byte) (recId + 27);
        return "0x" + hex(out);
    }

    private int findRecoveryId(BigInteger r, BigInteger s, byte[] hash, String expectedAddress) {
        for (int recId = 0; recId < 4; recId++) {
            ECPoint recovered = recoverPublicKey(recId, r, s, hash);
            if (recovered == null) {
                continue;
            }
            String recoveredAddress = addressFromPublicKey(recovered);
            if (stripHexPrefix(recoveredAddress).equalsIgnoreCase(stripHexPrefix(expectedAddress))) {
                return recId;
            }
        }
        return -1;
    }

    private ECPoint recoverPublicKey(int recId, BigInteger r, BigInteger s, byte[] message) {
        BigInteger n = CURVE.getN();
        BigInteger i = BigInteger.valueOf(recId / 2L);
        BigInteger x = r.add(i.multiply(n));
        if (x.compareTo(FIELD_PRIME) >= 0) {
            return null;
        }
        ECPoint rPoint = decompressKey(x, (recId & 1) == 1);
        if (!rPoint.multiply(n).isInfinity()) {
            return null;
        }
        BigInteger e = new BigInteger(1, message);
        BigInteger eInv = BigInteger.ZERO.subtract(e).mod(n);
        BigInteger rInv = r.modInverse(n);
        BigInteger srInv = s.multiply(rInv).mod(n);
        BigInteger eInvrInv = eInv.multiply(rInv).mod(n);
        return ECAlgorithms.sumOfTwoMultiplies(CURVE.getG(), eInvrInv, rPoint, srInv);
    }

    private ECPoint decompressKey(BigInteger xBN, boolean yBit) {
        byte[] compEnc = toFixedLength(xBN, 33);
        compEnc[0] = (byte) (yBit ? 0x03 : 0x02);
        return CURVE.getCurve().decodePoint(compEnc);
    }

    private String addressFromPublicKey(ECPoint publicKey) {
        byte[] encoded = publicKey.normalize().getEncoded(false);
        byte[] hash = keccak(slice(encoded, 1, encoded.length - 1));
        byte[] addressBytes = slice(hash, hash.length - 20, 20);
        return checksumAddress("0x" + hex(addressBytes));
    }

    private String checksumAddress(String address) {
        String clean = stripHexPrefix(address).toLowerCase(Locale.ROOT);
        byte[] hash = keccak(clean.getBytes(StandardCharsets.UTF_8));
        char[] out = clean.toCharArray();
        String hashHex = hex(hash);
        for (int i = 0; i < out.length; i++) {
            if (Character.digit(hashHex.charAt(i), 16) >= 8) {
                out[i] = Character.toUpperCase(out[i]);
            }
        }
        return "0x" + new String(out);
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Polymarket CLOB request.", e);
        }
    }

    private static String stripHexPrefix(String value) {
        if (value == null) {
            return null;
        }
        return value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        return null;
    }

    private static byte[] keccak(String value) {
        return keccak(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] keccak(byte[] input) {
        Keccak.Digest256 digest = new Keccak.Digest256();
        digest.update(input, 0, input.length);
        return digest.digest();
    }

    private static byte[] keccakPadded(String value) {
        return leftPad(keccak(value), 32);
    }

    private static byte[] uint256(BigInteger value) {
        return leftPad(stripLeadingZero(value.toByteArray()), 32);
    }

    private static byte[] uint8(int value) {
        return leftPad(new byte[]{(byte) value}, 32);
    }

    private static byte[] address(String address) {
        return leftPad(hexToBytes(stripHexPrefix(address)), 32);
    }

    private static byte[] leftPad(byte[] value, int size) {
        byte[] result = new byte[size];
        System.arraycopy(value, 0, result, size - value.length, value.length);
        return result;
    }

    private static byte[] stripLeadingZero(byte[] value) {
        if (value.length > 1 && value[0] == 0) {
            byte[] trimmed = new byte[value.length - 1];
            System.arraycopy(value, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return value;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static byte[] hexToBytes(String hex) {
        String clean = hex.length() % 2 == 0 ? hex : "0" + hex;
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int index = i * 2;
            out[i] = (byte) Integer.parseInt(clean.substring(index, index + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static byte[] toFixedLength(BigInteger value, int length) {
        return leftPad(stripLeadingZero(value.toByteArray()), length);
    }

    private static byte[] slice(byte[] bytes, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(bytes, offset, out, 0, length);
        return out;
    }

    private record ApiCreds(String apiKey, String apiSecret, String apiPassphrase, boolean derived) { }

    private record SignedOrder(Map<String, Object> payload) { }

    private record OrderAmounts(BigInteger makerAmount, BigInteger takerAmount) { }

    private record RoundConfig(int priceDigits, int sizeDigits, int amountDigits) {
        static RoundConfig of(BigDecimal tickSize) {
            String value = tickSize == null ? "0.01" : tickSize.stripTrailingZeros().toPlainString();
            return switch (value) {
                case "0.1" -> new RoundConfig(1, 2, 3);
                case "0.001" -> new RoundConfig(3, 2, 5);
                case "0.0001" -> new RoundConfig(4, 2, 6);
                default -> new RoundConfig(2, 2, 4);
            };
        }
    }
}
