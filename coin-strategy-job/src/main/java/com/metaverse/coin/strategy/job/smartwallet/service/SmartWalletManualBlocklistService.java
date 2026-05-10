package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletLeaderTradeOutcomeMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletManualBlocklistMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletLeaderTradeOutcomeEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualBlocklistEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletManualBlocklistRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletManualBlocklistSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletManualBlocklistView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SmartWalletManualBlocklistService {

    public static final String SOURCE = "POLYMARKET";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String REASON_MANUAL_BLOCKLIST = "MANUAL_BLOCKLIST";
    public static final String REASON_INACTIVE_GT_35D_BLOCKLIST = "INACTIVE_GT_35D_BLOCKLIST";
    public static final long INACTIVE_BLOCKLIST_DAYS = 35L;

    private static final List<String> TRUSTED_CLOSED_STATUSES = List.of(
            LeaderTradeOutcomeRebuildService.STATUS_CLOSED_WIN,
            LeaderTradeOutcomeRebuildService.STATUS_CLOSED_LOSS,
            LeaderTradeOutcomeRebuildService.STATUS_CLOSED_BREAKEVEN);

    private final SmartWalletManualBlocklistMapper blocklistMapper;
    private final SmartWalletLeaderTradeOutcomeMapper leaderOutcomeMapper;

    public Optional<SmartWalletManualBlocklistEntity> findActive(String walletAddress) {
        String normalized = normalizeWallet(walletAddress);
        if (!StringUtils.hasText(normalized)) {
            return Optional.empty();
        }
        SmartWalletManualBlocklistEntity entity = blocklistMapper.selectOne(new LambdaQueryWrapper<SmartWalletManualBlocklistEntity>()
                .eq(SmartWalletManualBlocklistEntity::getWalletAddress, normalized)
                .eq(SmartWalletManualBlocklistEntity::getSource, SOURCE)
                .eq(SmartWalletManualBlocklistEntity::getStatus, STATUS_ACTIVE)
                .last("limit 1"));
        return isActive(entity) ? Optional.of(entity) : Optional.empty();
    }

    public boolean isBlocked(String walletAddress) {
        return findActive(walletAddress).isPresent();
    }

    public String activeReason(String walletAddress) {
        return findActive(walletAddress)
                .map(SmartWalletManualBlocklistEntity::getReason)
                .filter(StringUtils::hasText)
                .orElse(REASON_MANUAL_BLOCKLIST);
    }

    public List<SmartWalletManualBlocklistView> list(Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        return blocklistMapper.selectList(new LambdaQueryWrapper<SmartWalletManualBlocklistEntity>()
                        .eq(SmartWalletManualBlocklistEntity::getSource, SOURCE)
                        .orderByDesc(SmartWalletManualBlocklistEntity::getUpdatedAt)
                        .last("limit " + safeLimit))
                .stream()
                .map(this::toView)
                .toList();
    }

    public SmartWalletManualBlocklistView upsert(SmartWalletManualBlocklistRequest request) {
        if (request == null || !StringUtils.hasText(request.getWalletAddress())) {
            throw new IllegalArgumentException("walletAddress is required");
        }
        return upsert(request.getWalletAddress(), request);
    }

    public SmartWalletManualBlocklistView upsert(String walletAddress, SmartWalletManualBlocklistRequest request) {
        String normalized = normalizeWallet(walletAddress);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("walletAddress is required");
        }
        Date now = new Date();
        SmartWalletManualBlocklistEntity entity = blocklistMapper.selectOne(new LambdaQueryWrapper<SmartWalletManualBlocklistEntity>()
                .eq(SmartWalletManualBlocklistEntity::getWalletAddress, normalized)
                .eq(SmartWalletManualBlocklistEntity::getSource, SOURCE)
                .last("limit 1"));
        if (entity == null) {
            entity = new SmartWalletManualBlocklistEntity()
                    .setWalletAddress(normalized)
                    .setSource(SOURCE)
                    .setCreatedAt(now);
        }
        SmartWalletManualBlocklistRequest safeRequest = request == null ? new SmartWalletManualBlocklistRequest() : request;
        entity.setStatus(normalizeStatus(safeRequest.getStatus()))
                .setReason(firstText(safeRequest.getReason(), REASON_MANUAL_BLOCKLIST))
                .setCreatedBy(trimToNull(safeRequest.getCreatedBy()))
                .setUpdatedAt(now);
        if (entity.getId() == null) {
            blocklistMapper.insert(entity);
        } else {
            blocklistMapper.updateById(entity);
        }
        return toView(entity);
    }

    public List<SmartWalletManualBlocklistView> upsertBatch(SmartWalletManualBlocklistRequest request) {
        if (request == null || request.getWalletAddresses() == null || request.getWalletAddresses().isEmpty()) {
            throw new IllegalArgumentException("walletAddresses is required");
        }
        List<SmartWalletManualBlocklistView> views = new ArrayList<>();
        for (String walletAddress : request.getWalletAddresses()) {
            if (!StringUtils.hasText(walletAddress)) {
                continue;
            }
            SmartWalletManualBlocklistRequest row = new SmartWalletManualBlocklistRequest();
            row.setStatus(request.getStatus());
            row.setReason(request.getReason());
            row.setCreatedBy(request.getCreatedBy());
            views.add(upsert(walletAddress, row));
        }
        return views;
    }

    public SmartWalletManualBlocklistView disable(String walletAddress) {
        String normalized = normalizeWallet(walletAddress);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("walletAddress is required");
        }
        SmartWalletManualBlocklistEntity entity = blocklistMapper.selectOne(new LambdaQueryWrapper<SmartWalletManualBlocklistEntity>()
                .eq(SmartWalletManualBlocklistEntity::getWalletAddress, normalized)
                .eq(SmartWalletManualBlocklistEntity::getSource, SOURCE)
                .last("limit 1"));
        if (entity == null) {
            throw new IllegalArgumentException("Manual blocklist entry not found: " + walletAddress);
        }
        entity.setStatus(STATUS_DISABLED).setUpdatedAt(new Date());
        blocklistMapper.updateById(entity);
        return toView(entity);
    }

    public SmartWalletManualBlocklistSummaryView summary() {
        List<SmartWalletManualBlocklistEntity> rows = blocklistMapper.selectList(new LambdaQueryWrapper<SmartWalletManualBlocklistEntity>()
                .eq(SmartWalletManualBlocklistEntity::getSource, SOURCE));
        int active = 0;
        int disabled = 0;
        for (SmartWalletManualBlocklistEntity row : rows) {
            if (isActive(row)) {
                active++;
            }
            if (STATUS_DISABLED.equalsIgnoreCase(row.getStatus())) {
                disabled++;
            }
        }
        return SmartWalletManualBlocklistSummaryView.builder()
                .total(rows.size())
                .active(active)
                .disabled(disabled)
                .build();
    }

    public boolean shouldBlockForInactivity(SmartWalletProfileEntity profile) {
        if (profile == null || !StringUtils.hasText(profile.getWalletAddress())) {
            return false;
        }
        Date now = new Date();
        if (olderThan(profile.getLastTradeAt(), now, INACTIVE_BLOCKLIST_DAYS)) {
            return true;
        }
        int recentTrades30d = profile.getRecentTradeCount30d() == null ? 0 : profile.getRecentTradeCount30d();
        if (recentTrades30d != 0) {
            return false;
        }
        Date lastTrustedClosedAt = lastTrustedClosedAt(profile.getWalletAddress());
        return olderThan(lastTrustedClosedAt, now, INACTIVE_BLOCKLIST_DAYS);
    }

    public boolean blockForInactivityIfNeeded(SmartWalletProfileEntity profile) {
        if (!shouldBlockForInactivity(profile)) {
            return false;
        }
        SmartWalletManualBlocklistRequest request = new SmartWalletManualBlocklistRequest();
        request.setStatus(STATUS_ACTIVE);
        request.setReason(REASON_INACTIVE_GT_35D_BLOCKLIST);
        request.setCreatedBy("system-inactivity-gate");
        upsert(profile.getWalletAddress(), request);
        return true;
    }

    public SmartWalletManualBlocklistView toView(SmartWalletManualBlocklistEntity entity) {
        if (entity == null) {
            return null;
        }
        return SmartWalletManualBlocklistView.builder()
                .id(entity.getId())
                .walletAddress(entity.getWalletAddress())
                .source(entity.getSource())
                .status(entity.getStatus())
                .reason(entity.getReason())
                .createdBy(entity.getCreatedBy())
                .active(isActive(entity))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public boolean isActive(SmartWalletManualBlocklistEntity entity) {
        return entity != null && STATUS_ACTIVE.equalsIgnoreCase(entity.getStatus());
    }

    public String normalizeWallet(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Date lastTrustedClosedAt(String walletAddress) {
        String normalized = normalizeWallet(walletAddress);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        SmartWalletLeaderTradeOutcomeEntity row = leaderOutcomeMapper.selectOne(new LambdaQueryWrapper<SmartWalletLeaderTradeOutcomeEntity>()
                .eq(SmartWalletLeaderTradeOutcomeEntity::getWalletAddress, normalized)
                .in(SmartWalletLeaderTradeOutcomeEntity::getOutcomeStatus, TRUSTED_CLOSED_STATUSES)
                .ge(SmartWalletLeaderTradeOutcomeEntity::getSourceConfidence, new java.math.BigDecimal("0.5500"))
                .orderByDesc(SmartWalletLeaderTradeOutcomeEntity::getLastTradeAt)
                .last("limit 1"));
        return row == null ? null : row.getLastTradeAt();
    }

    private boolean olderThan(Date value, Date now, long days) {
        if (value == null) {
            return false;
        }
        long ageDays = TimeUnit.MILLISECONDS.toDays(Math.max(0L, now.getTime() - value.getTime()));
        return ageDays > days;
    }

    private String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return STATUS_DISABLED.equals(normalized) ? STATUS_DISABLED : STATUS_ACTIVE;
    }

    private String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
