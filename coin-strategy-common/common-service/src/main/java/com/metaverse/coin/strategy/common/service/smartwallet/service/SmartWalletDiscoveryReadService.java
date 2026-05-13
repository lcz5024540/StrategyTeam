package com.metaverse.coin.strategy.common.service.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.model.DiscoverySessionCoverageBucketView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.DiscoverySessionCoverageView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 钱包发现"会话覆盖"只读视图。
 *
 * <p>Phase 3 Read/Write 拆分产物：
 * <ul>
 *   <li>job 端 {@code PolymarketWalletDiscoveryService.sessionCoverage()} 在 job 进程内服务定时发现写路径，不动；</li>
 *   <li>本类专供 web 服务的 GET 接口使用，纯 DB 读 + 内存聚合，跨进程行为一致。</li>
 * </ul>
 *
 * <p><strong>语义差异</strong>：job 端 {@code profileStoreService.listWatchEnabled()} 会把
 * "manual allowlist 运行时档案"合并进观察名单。这里为避免把整套 tier 推断 + manual allowlist 合并
 * 也下沉到 common，简化为直接读 {@code smart_wallet_profile.watchEnabled = TRUE}。
 * 对覆盖率报告这种粗粒度展示影响可接受。</p>
 */
@Service
@RequiredArgsConstructor
public class SmartWalletDiscoveryReadService {

    private static final String SOURCE = "POLYMARKET";
    private static final BigDecimal HALF = new BigDecimal("0.50");
    private static final List<String> ORDERED_SESSIONS = List.of("ASIA", "EU", "US", "MIXED", "UNKNOWN");

    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletProperties properties;

    public DiscoverySessionCoverageView sessionCoverage() {
        int lookbackDays = resolveSessionCoverageLookbackDays();
        Date since = new Date(System.currentTimeMillis() - lookbackDays * 24L * 3600_000L);

        List<SmartWalletProfileEntity> allProfiles = profileMapper.selectList(
                new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE));

        List<SmartWalletProfileEntity> recentProfiles = allProfiles.stream()
                .filter(item -> item.getLastDiscoveredAt() != null && !item.getLastDiscoveredAt().before(since))
                .toList();

        List<SmartWalletProfileEntity> recentWatchlist = allProfiles.stream()
                .filter(item -> Boolean.TRUE.equals(item.getWatchEnabled()))
                .filter(item -> item.getLastDiscoveredAt() != null && !item.getLastDiscoveredAt().before(since))
                .toList();

        return DiscoverySessionCoverageView.builder()
                .generatedAt(new Date())
                .lookbackDays(lookbackDays)
                .profileBuckets(bucketCoverage(recentProfiles))
                .watchlistBuckets(bucketCoverage(recentWatchlist))
                .build();
    }

    // ─────────────────────────────── 辅助 ───────────────────────────────

    private int resolveSessionCoverageLookbackDays() {
        Integer value = properties.getPolymarket().getSessionCoverageLookbackDays();
        return value == null ? 30 : Math.max(value, 1);
    }

    private List<DiscoverySessionCoverageBucketView> bucketCoverage(List<SmartWalletProfileEntity> profiles) {
        Map<String, List<SmartWalletProfileEntity>> grouped = profiles.stream()
                .collect(Collectors.groupingBy(item -> StringUtils.hasText(item.getDominantSession())
                        ? item.getDominantSession().trim().toUpperCase()
                        : "UNKNOWN", LinkedHashMap::new, Collectors.toList()));
        List<DiscoverySessionCoverageBucketView> result = new ArrayList<>();
        for (String session : ORDERED_SESSIONS) {
            List<SmartWalletProfileEntity> bucket = grouped.get(session);
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            result.add(DiscoverySessionCoverageBucketView.builder()
                    .dominantSession(session)
                    .walletCount(bucket.size())
                    .avgWinRate(avg(bucket.stream().map(SmartWalletProfileEntity::getWinRate).toList()))
                    .avgRecentWinRate30d(avg(bucket.stream().map(SmartWalletProfileEntity::getRecentWinRate30d).toList()))
                    .avgWalletScore(avg(bucket.stream().map(SmartWalletProfileEntity::getWalletScore).toList()))
                    .avgAsiaTradeRatio30d(avg(bucket.stream().map(SmartWalletProfileEntity::getAsiaTradeRatio30d).toList()))
                    .avgEuTradeRatio30d(avg(bucket.stream().map(SmartWalletProfileEntity::getEuTradeRatio30d).toList()))
                    .avgUsTradeRatio30d(avg(bucket.stream().map(SmartWalletProfileEntity::getUsTradeRatio30d).toList()))
                    .build());
        }
        return result;
    }

    private BigDecimal avg(List<BigDecimal> values) {
        List<BigDecimal> normalized = values.stream()
                .filter(item -> item != null)
                .toList();
        if (normalized.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : normalized) {
            total = total.add(value);
        }
        return total.divide(BigDecimal.valueOf(normalized.size()), 4, RoundingMode.HALF_UP);
    }
}
