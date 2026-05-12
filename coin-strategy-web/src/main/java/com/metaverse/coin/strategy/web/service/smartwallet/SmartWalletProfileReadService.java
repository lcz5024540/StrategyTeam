package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletManualAllowlistMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletManualBlocklistMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualAllowlistEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualBlocklistEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualAllowlistSummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualAllowlistView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualBlocklistSummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualBlocklistView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletProfileOverrideSummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletProfileOverrideView;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class SmartWalletProfileReadService {

    private static final String SOURCE = "POLYMARKET_COPY";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletManualAllowlistMapper allowlistMapper;
    private final SmartWalletManualBlocklistMapper blocklistMapper;

    public SmartWalletProfileReadService(SmartWalletProfileMapper profileMapper,
                                          SmartWalletManualAllowlistMapper allowlistMapper,
                                          SmartWalletManualBlocklistMapper blocklistMapper) {
        this.profileMapper = profileMapper;
        this.allowlistMapper = allowlistMapper;
        this.blocklistMapper = blocklistMapper;
    }

    // ════════════════════════════════════════════════════════════════
    // Profiles
    // ════════════════════════════════════════════════════════════════

    public List<SmartWalletProfileEntity> profiles() {
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .orderByDesc(SmartWalletProfileEntity::getUpdatedAt));
    }

    // ════════════════════════════════════════════════════════════════
    // Profile Overrides
    // ════════════════════════════════════════════════════════════════

    public List<SmartWalletProfileOverrideView> listManualOverrides(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE)
                        .and(wrapper -> wrapper
                                .isNotNull(SmartWalletProfileEntity::getManualWatchEnabled)
                                .or()
                                .isNotNull(SmartWalletProfileEntity::getManualRecommendedExitLane)
                                .or()
                                .isNotNull(SmartWalletProfileEntity::getManualOverrideNote))
                        .orderByDesc(SmartWalletProfileEntity::getManualOverrideUpdatedAt)
                        .last("limit " + safeLimit))
                .stream()
                .map(this::toOverrideView)
                .toList();
    }

    public SmartWalletProfileOverrideSummaryView summarizeManualOverrides() {
        List<SmartWalletProfileEntity> rows = profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .and(wrapper -> wrapper
                        .isNotNull(SmartWalletProfileEntity::getManualWatchEnabled)
                        .or()
                        .isNotNull(SmartWalletProfileEntity::getManualRecommendedExitLane)
                        .or()
                        .isNotNull(SmartWalletProfileEntity::getManualOverrideNote)));

        int manualWatchEnabled = 0, manualWatchDisabled = 0, exitLaneSet = 0, recentlyUpdated = 0;
        long recentCutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        for (SmartWalletProfileEntity row : rows) {
            if (Boolean.TRUE.equals(row.getManualWatchEnabled())) manualWatchEnabled++;
            if (Boolean.FALSE.equals(row.getManualWatchEnabled())) manualWatchDisabled++;
            if (StringUtils.hasText(row.getManualRecommendedExitLane())) exitLaneSet++;
            if (row.getManualOverrideUpdatedAt() != null && row.getManualOverrideUpdatedAt().getTime() >= recentCutoff) {
                recentlyUpdated++;
            }
        }
        return new SmartWalletProfileOverrideSummaryView(rows.size(), manualWatchEnabled, manualWatchDisabled,
                exitLaneSet, recentlyUpdated);
    }

    // ════════════════════════════════════════════════════════════════
    // Manual Allowlist
    // ════════════════════════════════════════════════════════════════

    public List<SmartWalletManualAllowlistView> allowlist(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return allowlistMapper.selectList(new LambdaQueryWrapper<SmartWalletManualAllowlistEntity>()
                        .eq(SmartWalletManualAllowlistEntity::getSource, SOURCE)
                        .orderByDesc(SmartWalletManualAllowlistEntity::getUpdatedAt)
                        .last("limit " + safeLimit))
                .stream()
                .map(this::toAllowlistView)
                .toList();
    }

    public SmartWalletManualAllowlistSummaryView allowlistSummary() {
        List<SmartWalletManualAllowlistEntity> rows = allowlistMapper.selectList(
                new LambdaQueryWrapper<SmartWalletManualAllowlistEntity>()
                        .eq(SmartWalletManualAllowlistEntity::getSource, SOURCE));
        int active = 0, expired = 0, disabled = 0, paperOnly = 0, liveEnabled = 0;
        Date now = new Date();
        for (SmartWalletManualAllowlistEntity row : rows) {
            if (isAllowlistActive(row)) active++;
            if (row.getExpiresAt() != null && !row.getExpiresAt().after(now)) expired++;
            if (STATUS_DISABLED.equalsIgnoreCase(row.getStatus())) disabled++;
            String mode = normalizeAllowlistMode(row.getAllowedExecutionModes());
            if ("PAPER".equals(mode)) paperOnly++;
            if ("LIVE".equals(mode) || "BOTH".equals(mode)) liveEnabled++;
        }
        return new SmartWalletManualAllowlistSummaryView(rows.size(), active, expired, disabled, paperOnly, liveEnabled);
    }

    // ════════════════════════════════════════════════════════════════
    // Manual Blocklist
    // ════════════════════════════════════════════════════════════════

    public List<SmartWalletManualBlocklistView> blocklist(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return blocklistMapper.selectList(new LambdaQueryWrapper<SmartWalletManualBlocklistEntity>()
                        .eq(SmartWalletManualBlocklistEntity::getSource, SOURCE)
                        .orderByDesc(SmartWalletManualBlocklistEntity::getUpdatedAt)
                        .last("limit " + safeLimit))
                .stream()
                .map(this::toBlocklistView)
                .toList();
    }

    public SmartWalletManualBlocklistSummaryView blocklistSummary() {
        List<SmartWalletManualBlocklistEntity> rows = blocklistMapper.selectList(
                new LambdaQueryWrapper<SmartWalletManualBlocklistEntity>()
                        .eq(SmartWalletManualBlocklistEntity::getSource, SOURCE));
        int active = 0, disabled = 0;
        for (SmartWalletManualBlocklistEntity row : rows) {
            if (isBlocklistActive(row)) active++;
            if (STATUS_DISABLED.equalsIgnoreCase(row.getStatus())) disabled++;
        }
        return new SmartWalletManualBlocklistSummaryView(rows.size(), active, disabled);
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    private SmartWalletProfileOverrideView toOverrideView(SmartWalletProfileEntity p) {
        return new SmartWalletProfileOverrideView(p.getWalletAddress(), p.getAlias(), p.getQualified(),
                p.getWatchEnabled(), p.getManualWatchEnabled(), p.getManualRecommendedExitLane(),
                p.getManualOverrideNote(), p.getManualOverrideUpdatedAt());
    }

    private SmartWalletManualAllowlistView toAllowlistView(SmartWalletManualAllowlistEntity e) {
        return new SmartWalletManualAllowlistView(e.getId(), e.getWalletAddress(), e.getSource(), e.getStatus(),
                e.getAllowedExecutionModes(), e.getStakeMultiplier(), e.getMaxLiveOrdersPerDay(),
                e.getMaxLiveOrdersPerRun(), e.getExpiresAt(), e.getReason(), e.getCreatedBy(),
                isAllowlistActive(e), e.getCreatedAt(), e.getUpdatedAt());
    }

    private SmartWalletManualBlocklistView toBlocklistView(SmartWalletManualBlocklistEntity e) {
        return new SmartWalletManualBlocklistView(e.getId(), e.getWalletAddress(), e.getSource(), e.getStatus(),
                e.getReason(), e.getCreatedBy(), isBlocklistActive(e), e.getCreatedAt(), e.getUpdatedAt());
    }

    private boolean isAllowlistActive(SmartWalletManualAllowlistEntity e) {
        if (e == null || !STATUS_ACTIVE.equalsIgnoreCase(e.getStatus())) return false;
        return e.getExpiresAt() == null || e.getExpiresAt().after(new Date());
    }

    private boolean isBlocklistActive(SmartWalletManualBlocklistEntity e) {
        return e != null && STATUS_ACTIVE.equalsIgnoreCase(e.getStatus());
    }

    private String normalizeAllowlistMode(String mode) {
        if (!StringUtils.hasText(mode)) return "PAPER";
        return mode.trim().toUpperCase();
    }
}
