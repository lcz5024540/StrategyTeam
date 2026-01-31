package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.base.utils.SignUtils;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceAccountStatus;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceApiTradingStatus;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceApiKeyPermission;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceAssetDetail;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceCoinInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceDepositAddress;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceDepositHistory;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceSystemStatus;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceTradeFee;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceWithdrawHistory;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class BinanceWalletService {
    private final SpotClient spotClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String secretKey;
    private final String baseUrl;

    public BinanceWalletService(SpotClient spotClient) {
        this(spotClient, null, null, "https://api.binance.com");
    }

    public BinanceWalletService(SpotClient spotClient, String apiKey, String secretKey, String baseUrl) {
        this.spotClient = spotClient;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.binance.com";
        this.httpClient = new OkHttpClient();
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * System Status (System)
     * <br>
     * GET /sapi/v1/system/status
     * <br>
     * 系统状态
     */
    public BinanceSystemStatus systemStatus() {
        String json = spotClient.createWallet().systemStatus();
        try {
            return objectMapper.readValue(json, BinanceSystemStatus.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse system status", e);
            throw new RuntimeException("Failed to parse system status", e);
        }
    }

    /**
     * All Coins' Information (Capital)
     * <br>
     * GET /sapi/v1/capital/config/getall
     * <br>
     * 所有币种信息
     */
    public List<BinanceCoinInfo> getAllCoins() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = spotClient.createWallet().coinInfo(parameters);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceCoinInfo>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse coin info", e);
            throw new RuntimeException("Failed to parse coin info", e);
        }
    }

    /**
     * Deposit History (supporting network) (Capital)
     * <br>
     * GET /sapi/v1/capital/deposit/hisrec
     * <br>
     * 充值历史
     */
    public List<BinanceDepositHistory> depositHistory(String coin, Integer status, Long startTime, Long endTime, Integer offset, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (coin != null) parameters.put("coin", coin);
        if (status != null) parameters.put("status", status);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (offset != null) parameters.put("offset", offset);
        if (limit != null) parameters.put("limit", limit);
        String json = spotClient.createWallet().depositHistory(parameters);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceDepositHistory>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse deposit history", e);
            throw new RuntimeException("Failed to parse deposit history", e);
        }
    }

    /**
     * Withdraw (Capital)
     * <br>
     * POST /sapi/v1/capital/withdraw/apply
     * <br>
     * 提现
     * <br>
     * @param coin 币种
     * @param address 提现地址
     * @param amount 提现数量
     * @param network (可选) 网络
     * @param name (可选) 地址名称
     * @param walletType (可选) 0:现货钱包, 1:资金钱包
     * @return 提现 ID (JSON 字符串)
     */
    public String withdraw(String coin, String address, String amount, String network, String name, Integer walletType) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("coin", coin);
        parameters.put("address", address);
        parameters.put("amount", amount);
        if (network != null) parameters.put("network", network);
        if (name != null) parameters.put("name", name);
        if (walletType != null) parameters.put("walletType", walletType);
        return spotClient.createWallet().withdraw(parameters);
    }

    /**
     * Withdraw History (supporting network) (Capital)
     * <br>
     * GET /sapi/v1/capital/withdraw/history
     * <br>
     * 提现历史
     */
    public List<BinanceWithdrawHistory> withdrawHistory(String coin, Integer status, Long startTime, Long endTime, Integer offset, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (coin != null) parameters.put("coin", coin);
        if (status != null) parameters.put("status", status);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (offset != null) parameters.put("offset", offset);
        if (limit != null) parameters.put("limit", limit);
        String json = spotClient.createWallet().withdrawHistory(parameters);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceWithdrawHistory>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse withdraw history", e);
            throw new RuntimeException("Failed to parse withdraw history", e);
        }
    }

    /**
     * Asset Detail (Capital)
     * <br>
     * POST /sapi/v1/asset/assetDetail
     * <br>
     * 资产详情
     */
    public Map<String, BinanceAssetDetail> assetDetail(String asset) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (asset != null) parameters.put("asset", asset);
        String json = spotClient.createWallet().assetDetail(parameters);
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, BinanceAssetDetail>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse asset detail", e);
            throw new RuntimeException("Failed to parse asset detail", e);
        }
    }

    /**
     * Trade Fee (Wallet)
     * <br>
     * GET /sapi/v1/asset/tradeFee
     * <br>
     * 交易手续费
     */
    public List<BinanceTradeFee> tradeFee(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        String json = spotClient.createWallet().tradeFee(parameters);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceTradeFee>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse trade fee", e);
            throw new RuntimeException("Failed to parse trade fee", e);
        }
    }

    /**
     * Account Status (Capital)
     * <br>
     * GET /sapi/v1/account/status
     * <br>
     * 账户状态
     */
    public BinanceAccountStatus accountStatus() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = spotClient.createWallet().accountStatus(parameters);
        try {
            return objectMapper.readValue(json, BinanceAccountStatus.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse account status", e);
            throw new RuntimeException("Failed to parse account status", e);
        }
    }

    /**
     * API 交易状态 (API Trading Status)
     *
     * @see <a href="https://developers.binance.com/docs/wallet/capital/api-trading-status">API Trading Status</a>
     */
    public BinanceApiTradingStatus apiTradingStatus() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = spotClient.createWallet().apiTradingStatus(parameters);
        try {
            return objectMapper.readValue(json, BinanceApiTradingStatus.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse api trading status", e);
            throw new RuntimeException("Failed to parse api trading status", e);
        }
    }

    /**
     * 充值地址 (Deposit Address)
     *
     * @see <a href="https://developers.binance.com/docs/wallet/capital/deposit-address">Deposit Address</a>
     */
    public BinanceDepositAddress depositAddress(String coin, String network) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("coin", coin);
        if (network != null) parameters.put("network", network);
        String json = spotClient.createWallet().depositAddress(parameters);
        try {
            return objectMapper.readValue(json, BinanceDepositAddress.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse deposit address", e);
            throw new RuntimeException("Failed to parse deposit address", e);
        }
    }

    /**
     * Get API Key Permission (Wallet)
     * <br>
     * GET /sapi/v1/account/apiRestrictions
     * <br>
     * 获取 API Key 权限
     */
    public BinanceApiKeyPermission getApiKeyPermission() {
        if (apiKey == null || secretKey == null) {
            throw new RuntimeException("API Key and Secret Key are required for this operation");
        }

        long timestamp = System.currentTimeMillis();
        String queryString = "timestamp=" + timestamp;

        String signature;
        try {
            byte[] hmacBytes = SignUtils.hmacSHA256(secretKey.getBytes(), queryString.getBytes());
            signature = bytesToHex(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign request", e);
        }

        String fullUrl = baseUrl + "/sapi/v1/account/apiRestrictions?" + queryString + "&signature=" + signature;

        Request request = new Request.Builder()
                .url(fullUrl)
                .addHeader("X-MBX-APIKEY", apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String json = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("Failed to get API Key Permission: " + json);
            }
            return objectMapper.readValue(json, BinanceApiKeyPermission.class);
        } catch (IOException e) {
            log.error("Failed to get API Key Permission", e);
            throw new RuntimeException("Failed to get API Key Permission", e);
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
