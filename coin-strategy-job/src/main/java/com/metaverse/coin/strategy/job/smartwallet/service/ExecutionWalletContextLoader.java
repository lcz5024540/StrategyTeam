package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.config.SecretDecryptor;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionWalletStatus;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExecutionWalletContextLoader {

    private final SmartWalletProperties properties;
    private final PolymarketClobClientRouter clobClientRouter;
    private final SecretDecryptor secretDecryptor;

    public ExecutionWalletContext requireLiveContext() {
        ExecutionWalletContext context = load();
        if (!context.isEnabled()) {
            throw new IllegalStateException("Execution wallet is disabled. Set smart-wallet.execution-wallet.enabled=true.");
        }
        if (!context.isConfigured()) {
            throw new IllegalStateException("Execution wallet is missing address or private key.");
        }
        if (!context.isApiCredentialsPresent() && !context.isDeriveApiCredsIfMissing()) {
            throw new IllegalStateException("Execution wallet is missing Polymarket CLOB API credentials.");
        }
        return context;
    }

    public ExecutionWalletContext load() {
        SmartWalletProperties.ExecutionWallet config = properties.getExecutionWallet();
        boolean configured = StringUtils.hasText(config.getAddress()) && StringUtils.hasText(config.getPrivateKey());
        boolean hasApiCredentials = configured
                && StringUtils.hasText(config.getApiKey())
                && StringUtils.hasText(config.getApiSecret())
                && StringUtils.hasText(config.getApiPassphrase());
        boolean bridgeReady = clobClientRouter.isAvailable(ExecutionWalletContext.builder()
                .sdkMode(trim(config.getSdkMode()))
                .pythonExecutable(trim(config.getPythonExecutable()))
                .helperScriptPath(trim(config.getHelperScriptPath()))
                .apiBaseUrl(trim(config.getApiBaseUrl()))
                .build());
        boolean liveReady = config.isEnabled()
                && configured
                && bridgeReady
                && (hasApiCredentials || config.isDeriveApiCredsIfMissing());
        return ExecutionWalletContext.builder()
                .enabled(config.isEnabled())
                .configured(configured)
                .apiCredentialsPresent(hasApiCredentials)
                .liveReady(liveReady)
                .chain(config.getChain())
                .chainId(config.getChainId())
                .address(trim(config.getAddress()))
                .privateKey(decrypt(trim(config.getPrivateKey())))
                .funderAddress(trim(config.getFunderAddress()))
                .apiBaseUrl(trim(config.getApiBaseUrl()))
                .apiKey(trim(config.getApiKey()))
                .apiSecret(decrypt(trim(config.getApiSecret())))
                .apiPassphrase(trim(config.getApiPassphrase()))
                .deriveApiCredsIfMissing(config.isDeriveApiCredsIfMissing())
                .sdkMode(trim(config.getSdkMode()))
                .pythonExecutable(trim(config.getPythonExecutable()))
                .helperScriptPath(trim(config.getHelperScriptPath()))
                .pythonTimeoutSeconds(config.getPythonTimeoutSeconds())
                .signatureType(config.getSignatureType())
                .orderType(trim(config.getOrderType()))
                .minOrderUsdc(safe(config.getMinOrderUsdc(), BigDecimal.ONE))
                .maxOrderUsdc(safe(config.getMaxOrderUsdc(), new BigDecimal("150")))
                .priceSlippageRate(safe(config.getPriceSlippageRate(), new BigDecimal("0.02")))
                .estimatedFeeRate(safe(config.getEstimatedFeeRate(), new BigDecimal("0.0015")))
                .allowYes(Boolean.TRUE.equals(config.getAllowYes()))
                .allowNo(Boolean.TRUE.equals(config.getAllowNo()))
                // Fix-C: 动态滑点
                .enableDynamicSlippage(config.isEnableDynamicSlippage())
                .highLiquiditySlippageRate(safe(config.getHighLiquiditySlippageRate(), new BigDecimal("0.01")))
                .mediumLiquiditySlippageRate(safe(config.getMediumLiquiditySlippageRate(), new BigDecimal("0.02")))
                .lowLiquiditySlippageRate(safe(config.getLowLiquiditySlippageRate(), new BigDecimal("0.03")))
                .highLiquidityThresholdUsdc(safe(config.getHighLiquidityThresholdUsdc(), new BigDecimal("5000")))
                .lowLiquidityThresholdUsdc(safe(config.getLowLiquidityThresholdUsdc(), new BigDecimal("1000")))
                // Fix-B3: Maker 策略
                .orderStrategy(config.getOrderStrategy() == null ? "TAKER" : config.getOrderStrategy().trim().toUpperCase())
                .makerStrategyMinDepthUsdc(safe(config.getMakerStrategyMinDepthUsdc(), new BigDecimal("5000")))
                .makerStrategyMaxSpreadBps(safe(config.getMakerStrategyMaxSpreadBps(), new BigDecimal("200")))
                .build();
    }

    public ExecutionWalletStatus getStatus() {
        ExecutionWalletContext context = load();
        List<String> notes = new ArrayList<>();
        if (!context.isEnabled()) {
            notes.add("Execution wallet is disabled.");
        }
        if (!context.isConfigured()) {
            notes.add("Configure wallet address and private key before enabling LIVE follow.");
        }
        if (context.isConfigured() && !context.isApiCredentialsPresent() && !context.isDeriveApiCredsIfMissing()) {
            notes.add("Wallet is loaded, but Polymarket CLOB API credentials are still missing.");
        }
        if (context.isConfigured() && !context.isApiCredentialsPresent() && context.isDeriveApiCredsIfMissing()) {
            notes.add("Wallet is configured without API creds; LIVE mode will try deriving CLOB API credentials through the official SDK.");
        }
        if (context.isLiveReady()) {
            notes.add("Wallet is ready for LIVE order routing.");
        }
        notes.add("Current CLOB bridge mode: " + (context.getSdkMode() == null ? "JAVA" : context.getSdkMode()) + ".");
        if (!clobClientRouter.isAvailable(context)) {
            notes.add("Python bridge is configured, but `py-clob-client` is not installed in the selected Python environment.");
        }
        notes.add("Private key is read from configuration but never exposed by the status endpoint.");
        return ExecutionWalletStatus.builder()
                .enabled(context.isEnabled())
                .configured(context.isConfigured())
                .liveReady(context.isLiveReady())
                .chain(context.getChain())
                .address(mask(context.getAddress()))
                .funderAddress(mask(context.getFunderAddress()))
                .minOrderUsdc(context.getMinOrderUsdc())
                .maxOrderUsdc(context.getMaxOrderUsdc())
                .priceSlippageRate(context.getPriceSlippageRate())
                .notes(notes)
                .build();
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String decrypt(String value) {
        return secretDecryptor.decryptIfEncrypted(value);
    }

    private BigDecimal safe(BigDecimal value, BigDecimal defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String mask(String value) {
        if (!StringUtils.hasText(value) || value.length() < 10) {
            return value;
        }
        return value.substring(0, 6) + "..." + value.substring(value.length() - 4);
    }

    @Data
    @Builder
    public static class ExecutionWalletContext {
        private boolean enabled;
        private boolean configured;
        private boolean apiCredentialsPresent;
        private boolean liveReady;
        private String chain;
        private Integer chainId;
        private String address;
        private String privateKey;
        private String funderAddress;
        private String apiBaseUrl;
        private String apiKey;
        private String apiSecret;
        private String apiPassphrase;
        private boolean deriveApiCredsIfMissing;
        private String sdkMode;
        private String pythonExecutable;
        private String helperScriptPath;
        private Integer pythonTimeoutSeconds;
        private Integer signatureType;
        private String orderType;
        private BigDecimal minOrderUsdc;
        private BigDecimal maxOrderUsdc;
        private BigDecimal priceSlippageRate;
        private BigDecimal estimatedFeeRate;
        private boolean allowYes;
        private boolean allowNo;

        // ── Fix-C: 动态滑点 ──
        private boolean enableDynamicSlippage;
        private BigDecimal highLiquiditySlippageRate;
        private BigDecimal mediumLiquiditySlippageRate;
        private BigDecimal lowLiquiditySlippageRate;
        private BigDecimal highLiquidityThresholdUsdc;
        private BigDecimal lowLiquidityThresholdUsdc;

        // ── Fix-B3: Maker 策略 ──
        private String orderStrategy;
        private BigDecimal makerStrategyMinDepthUsdc;
        private BigDecimal makerStrategyMaxSpreadBps;
    }
}
