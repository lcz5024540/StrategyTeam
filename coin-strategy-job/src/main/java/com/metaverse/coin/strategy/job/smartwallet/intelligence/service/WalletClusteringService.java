package com.metaverse.coin.strategy.job.smartwallet.intelligence.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.WalletEntityMapMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WalletEntityMapEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 钱包聚类服务（Phase 1：仅实现入金来源聚类）
 *
 * 职责：
 * 1. 对单个钱包尝试进行聚类（查找其入金来源，与已有实体关联）
 * 2. 写入或更新 wallet_entity_map 表
 *
 * 并发幂等优化：
 * - 每个钱包地址独立锁（ConcurrentHashMap 按地址分段），不同地址并发互不阻塞
 * - DB 层 DuplicateKeyException 兜底，防止极端情况下的重复写入
 * - clusterBatch 改用有界线程池并行处理，提升批量吞吐
 *
 * Phase 2 将扩展：BehaviorSimilarityClusterer
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletClusteringService {

    private final WalletEntityMapMapper entityMapMapper;
    private final FundingSourceClusterer fundingSourceClusterer;
    private final BehaviorSimilarityClusterer behaviorSimilarityClusterer;
    private final SmartWalletProperties properties;

    /** 入金来源聚类的置信度（高置信度，因为共同入金地址是强关联信号） */
    private static final BigDecimal FUNDING_SOURCE_CONFIDENCE = new BigDecimal("0.90");

    /**
     * 每个钱包地址独立的 in-process 锁对象。
     * 相同地址的并发 clusterWallet 调用会串行化；不同地址互不影响。
     * 内存上限由实际监控钱包数量决定，规模可控。
     */
    private final ConcurrentHashMap<String, Object> clusterLocks = new ConcurrentHashMap<>();

    /**
     * 批量聚类线程池（有界，防止资源耗尽）
     * 默认 4 线程；外部 API 调用是 IO 密集型，可适当增大
     */
    private final ExecutorService clusterExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() * 2)));

    @PreDestroy
    public void shutdown() {
        clusterExecutor.shutdown();
        try {
            if (!clusterExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                clusterExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            clusterExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 对指定钱包执行聚类尝试（线程安全、幂等）
     *
     * 同一地址并发调用时，后者会等待前者完成，若前者已写入则直接跳过。
     *
     * @param walletAddress 钱包地址（大小写不敏感）
     */
    public void clusterWallet(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return;
        }
        String normalized = walletAddress.toLowerCase().trim();

        // 获取（或创建）该地址专属锁对象，不同地址使用不同锁，互不阻塞
        Object lock = clusterLocks.computeIfAbsent(normalized, k -> new Object());
        synchronized (lock) {
            doClusterWallet(normalized);
        }
    }

    /**
     * 实际聚类逻辑（调用前已加锁）
     */
    private void doClusterWallet(String normalized) {
        // 已有记录则跳过（双重检查：锁内再查一次，避免并发两次进入的情况）
        Long existing = entityMapMapper.selectCount(new LambdaQueryWrapper<WalletEntityMapEntity>()
                .eq(WalletEntityMapEntity::getWalletAddress, normalized));
        if (existing != null && existing > 0) {
            return;
        }

        String polygonscanApiKey = properties.getIntelligence().getPolygonscanApiKey();
        Optional<String> fundingSource = fundingSourceClusterer.findFundingSource(normalized, polygonscanApiKey);

        if (fundingSource.isPresent() && tryClusterByFundingSource(normalized, fundingSource.get())) {
            return;
        }

        // 备选：行为相似度聚类（中置信）
        Optional<String> similarEntityId = behaviorSimilarityClusterer.findMostSimilarEntityId(normalized);
        if (similarEntityId.isPresent()) {
            tryClusterByBehaviorSimilarity(normalized, similarEntityId.get());
        } else {
            // 无法可信聚类：自身即实体，但保留 funding source 线索供后续人工/离线分析使用
            insertSelfEntity(normalized, fundingSource.orElse(null));
        }
    }

    /**
     * 根据共同入金来源地址进行聚类
     * 如果已有其他钱包使用相同入金地址，则归入同一实体
     * 否则创建新实体
     */
    private boolean tryClusterByFundingSource(String walletAddress, String fundingSource) {
        List<WalletEntityMapEntity> peers = entityMapMapper.selectList(new LambdaQueryWrapper<WalletEntityMapEntity>()
                .eq(WalletEntityMapEntity::getFundingSourceAddress, fundingSource));
        if (peers.isEmpty()) {
            return false;
        }
        if (peers.size() > 1) {
            log.info("[WalletClusteringService] Skip funding-source merge for wallet={} because source={} is already shared by {} wallets.",
                    walletAddress, fundingSource, peers.size());
            return false;
        }

        WalletEntityMapEntity peer = peers.get(0);
        BigDecimal similarity = behaviorSimilarityClusterer.computeSimilarity(walletAddress, peer.getWalletAddress());
        if (similarity.doubleValue() < behaviorSimilarityClusterer.similarityThreshold()) {
            log.info("[WalletClusteringService] Skip funding-source merge for wallet={} because similarity={} below threshold={} with peer={}.",
                    walletAddress, similarity, behaviorSimilarityClusterer.similarityThreshold(), peer.getWalletAddress());
            return false;
        }

        Date now = new Date();
        WalletEntityMapEntity entity = new WalletEntityMapEntity()
                .setWalletAddress(walletAddress)
                .setEntityId(peer.getEntityId())
                .setClusterMethod("FUNDING_SOURCE")
                .setClusterConfidence(FUNDING_SOURCE_CONFIDENCE)
                .setFundingSourceAddress(fundingSource)
                .setBehaviorSimScore(similarity)
                .setIsPrimaryWallet(false)
                .setVerified(false)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        safeInsert(entity, walletAddress, "FUNDING_SOURCE");
        log.info("[WalletClusteringService] Clustered wallet={} into existing entity={} via funding source={} after behavior confirmation={}.",
                walletAddress, peer.getEntityId(), fundingSource, similarity);
        return true;
    }

    private void tryClusterByBehaviorSimilarity(String walletAddress, String targetEntityId) {
        Date now = new Date();
        WalletEntityMapEntity entity = new WalletEntityMapEntity()
                .setWalletAddress(walletAddress)
                .setEntityId(targetEntityId)
                .setClusterMethod("BEHAVIOR_SIM")
                .setClusterConfidence(behaviorSimilarityClusterer.getClusterConfidence())
                .setIsPrimaryWallet(false)
                .setVerified(false)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        safeInsert(entity, walletAddress, "BEHAVIOR_SIM");
        log.info("[WalletClusteringService] Clustered wallet={} into entity={} via behavior similarity.",
                walletAddress, targetEntityId);
    }

    /**
     * 将钱包注册为独立实体（entity_id = wallet_address，confidence = 0）
     */
    private void insertSelfEntity(String walletAddress, String fundingSource) {
        Date now = new Date();
        WalletEntityMapEntity entity = new WalletEntityMapEntity()
                .setWalletAddress(walletAddress)
                .setEntityId(walletAddress)  // 自身即实体
                .setClusterMethod("SELF")
                .setClusterConfidence(BigDecimal.ZERO)
                .setFundingSourceAddress(fundingSource)
                .setIsPrimaryWallet(true)
                .setVerified(false)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        safeInsert(entity, walletAddress, "SELF");
    }

    /**
     * 安全插入：捕获唯一键冲突（极端并发或外部重复调用的兜底防护）
     */
    private void safeInsert(WalletEntityMapEntity entity, String walletAddress, String method) {
        try {
            entityMapMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            log.debug("[WalletClusteringService] Duplicate key on {} insert for wallet={}, treating as idempotent success.",
                    method, walletAddress);
        }
    }

    /**
     * 批量对 watchlist 中所有未聚类的钱包进行聚类（并行执行）
     * 供定时任务调用
     *
     * @param walletAddresses 钱包地址列表
     * @return 处理数量
     */
    public int clusterBatch(List<String> walletAddresses) {
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            return 0;
        }
        AtomicInteger count = new AtomicInteger();
        List<CompletableFuture<Void>> futures = walletAddresses.stream()
                .map(addr -> CompletableFuture.runAsync(() -> {
                    try {
                        clusterWallet(addr);
                        count.incrementAndGet();
                    } catch (Exception e) {
                        log.warn("[WalletClusteringService] Batch clustering failed for wallet={}, error={}", addr, e.getMessage());
                    }
                }, clusterExecutor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.warn("[WalletClusteringService] Batch clustering timed out after 10 minutes. processed so far={}", count.get());
            futures.forEach(f -> f.cancel(true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("[WalletClusteringService] Batch clustering encountered unexpected error: {}", e.getMessage());
        }
        return count.get();
    }
}
