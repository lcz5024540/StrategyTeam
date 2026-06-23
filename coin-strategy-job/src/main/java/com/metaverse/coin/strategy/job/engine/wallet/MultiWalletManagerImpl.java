package com.metaverse.coin.strategy.job.engine.wallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyWalletMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyWalletEntity;
import com.metaverse.coin.strategy.job.config.SecretDecryptor;
import com.metaverse.coin.strategy.job.engine.config.StrategyEngineProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MultiWalletManager 实现。
 *
 * <p>从 strategy_wallet 表加载钱包配置，缓存在内存中。
 * 支持手动刷新缓存。</p>
 *
 * <p><b>零侵入</b>：完全独立于现有的 ExecutionWalletContextLoader，
 * 不读取 SmartWalletProperties，不干扰跟单策略。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultiWalletManagerImpl implements MultiWalletManager {

    private static final String LOG_PREFIX = "[MULTI_WALLET]";

    private final StrategyWalletMapper walletMapper;
    private final SecretDecryptor secretDecryptor;
    private final StrategyEngineProperties engineProperties;

    private final Map<String, WalletContext> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refreshCache();
    }

    @Override
    public WalletContext getWalletContext(String walletId) {
        WalletContext ctx = cache.get(walletId);
        if (ctx == null) {
            log.warn("{} 钱包未找到: walletId={}", LOG_PREFIX, walletId);
        }
        return ctx;
    }

    @Override
    public List<WalletContext> getWalletsByStrategy(String strategyId) {
        return cache.values().stream()
                .filter(w -> strategyId.equals(w.getStrategyId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<WalletStatusView> listWallets() {
        return cache.values().stream()
                .map(w -> WalletStatusView.builder()
                        .walletId(w.getWalletId())
                        .strategyId(w.getStrategyId())
                        .address(w.getAddress())
                        .enabled(w.isEnabled())
                        .configured(w.isConfigured())
                        .capitalPoolUsdc(w.getCapitalPoolUsdc())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public void refreshCache() {
        try {
            List<StrategyWalletEntity> entities = walletMapper.selectList(
                    new LambdaQueryWrapper<StrategyWalletEntity>().orderByAsc(StrategyWalletEntity::getId));

            Map<String, WalletContext> newCache = new ConcurrentHashMap<>();
            for (StrategyWalletEntity entity : entities) {
                WalletContext ctx = toContext(entity);
                newCache.put(ctx.getWalletId(), ctx);
            }

            cache.clear();
            cache.putAll(newCache);

            log.info("{} 缓存刷新完成，加载 {} 个钱包: {}", LOG_PREFIX, cache.size(),
                    cache.values().stream()
                            .map(w -> String.format("%s(%s/%s)", w.getWalletId(), w.getStrategyId(), w.isEnabled() ? "ON" : "OFF"))
                            .collect(Collectors.joining(", ")));
        } catch (Exception e) {
            log.error("{} 缓存刷新失败: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }

    // ── 内部方法 ──────────────────────────────────────────────────────

    private WalletContext toContext(StrategyWalletEntity entity) {
        String address = trim(entity.getAddress());
        String privateKey = secretDecryptor.decryptIfEncrypted(trim(entity.getPrivateKey()));
        boolean configured = StringUtils.hasText(address) && StringUtils.hasText(privateKey);
        boolean hasApiCreds = configured
                && StringUtils.hasText(entity.getApiKey())
                && StringUtils.hasText(entity.getApiSecret())
                && StringUtils.hasText(entity.getApiPassphrase());

        return WalletContext.builder()
                .walletId(trim(entity.getWalletId()))
                .strategyId(trim(entity.getStrategyId()))
                .chain(entity.getChain() == null ? "polygon" : entity.getChain())
                .chainId(entity.getChainId() == null ? 137 : entity.getChainId())
                .address(address)
                .privateKey(privateKey)
                .funderAddress(trim(entity.getFunderAddress()))
                .apiBaseUrl(engineProperties.getApiBaseUrl())
                .apiKey(trim(entity.getApiKey()))
                .apiSecret(secretDecryptor.decryptIfEncrypted(trim(entity.getApiSecret())))
                .apiPassphrase(trim(entity.getApiPassphrase()))
                .enabled(Boolean.TRUE.equals(entity.getEnabled()))
                .capitalPoolUsdc(entity.getCapitalPoolUsdc())
                .configured(configured)
                .apiCredentialsPresent(hasApiCreds)
                .build();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
