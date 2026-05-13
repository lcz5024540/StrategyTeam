package com.metaverse.coin.strategy.common.service.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletManualAllowlistMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualAllowlistEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletManualAllowlistRequest;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletManualAllowlistSummaryView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletManualAllowlistView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SmartWalletManualAllowlistService {

    public static final String SOURCE = "POLYMARKET";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String MODE_PAPER = "PAPER";
    public static final String MODE_LIVE = "LIVE";
    public static final String MODE_BOTH = "BOTH";

    private static final BigDecimal DEFAULT_STAKE_MULTIPLIER = new BigDecimal("0.50");
    private static final int DEFAULT_MAX_LIVE_ORDERS_PER_DAY = 5;
    private static final int DEFAULT_MAX_LIVE_ORDERS_PER_RUN = 2;
    private static final int DEFAULT_EXPIRY_DAYS = 30;

    private final SmartWalletManualAllowlistMapper allowlistMapper;
    private final SmartWalletOrderMapper orderMapper;

    public Optional<SmartWalletManualAllowlistEntity> findActive(String walletAddress) {
        String normalized = normalizeWallet(walletAddress);
        if (!StringUtils.hasText(normalized)) {
            return Optional.empty();
        }
        SmartWalletManualAllowlistEntity entity = allowlistMapper.selectOne(new LambdaQueryWrapper<SmartWalletManualAllowlistEntity>()
                .eq(SmartWalletManualAllowlistEntity::getWalletAddress, normalized)
                .eq(SmartWalletManualAllowlistEntity::getSource, SOURCE)
                .eq(SmartWalletManualAllowlistEntity::getStatus, STATUS_ACTIVE)
                .last("limit 1"));
        return isActive(entity) ? Optional.of(entity) : Optional.empty();
    }

    public boolean isActive(String walletAddress) {
        return findActive(walletAddress).isPresent();
    }

    public boolean allowsLive(String walletAddress) {
        return findActive(walletAddress)
                .map(this::allowsLive)
                .orElse(false);
    }

    public boolean allowsLive(SmartWalletManualAllowlistEntity entity) {
        String mode = normalizeMode(entity == null ? null : entity.getAllowedExecutionModes());
        return MODE_LIVE.equals(mode) || MODE_BOTH.equals(mode);
    }

    public BigDecimal stakeMultiplier(SmartWalletManualAllowlistEntity entity) {
        BigDecimal multiplier = entity == null ? null : entity.getStakeMultiplier();
        if (multiplier == null || multiplier.compareTo(BigDecimal.ZERO) <= 0) {
            return DEFAULT_STAKE_MULTIPLIER;
        }
        return multiplier.min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    public int maxLiveOrdersPerRun(SmartWalletManualAllowlistEntity entity) {
        return entity == null || entity.getMaxLiveOrdersPerRun() == null
                ? DEFAULT_MAX_LIVE_ORDERS_PER_RUN
                : Math.max(entity.getMaxLiveOrdersPerRun(), 0);
    }

    public boolean exceedsDailyLiveLimit(String walletAddress, SmartWalletManualAllowlistEntity entity) {
        int maxDaily = entity == null || entity.getMaxLiveOrdersPerDay() == null
                ? DEFAULT_MAX_LIVE_ORDERS_PER_DAY
                : Math.max(entity.getMaxLiveOrdersPerDay(), 0);
        if (maxDaily <= 0) {
            return true;
        }
        Date since = startOfToday();
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getWalletAddress, normalizeWallet(walletAddress))
                .eq(SmartWalletOrderEntity::getExecutionMode, MODE_LIVE)
                .ge(SmartWalletOrderEntity::getPlacedAt, since));
        return count != null && count >= maxDaily;
    }

    public List<SmartWalletManualAllowlistView> list(Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        return allowlistMapper.selectList(new LambdaQueryWrapper<SmartWalletManualAllowlistEntity>()
                        .eq(SmartWalletManualAllowlistEntity::getSource, SOURCE)
                        .orderByDesc(SmartWalletManualAllowlistEntity::getUpdatedAt)
                        .last("limit " + safeLimit))
                .stream()
                .map(this::toView)
                .toList();
    }

    public List<SmartWalletManualAllowlistEntity> listActiveEntities() {
        Date now = new Date();
        return allowlistMapper.selectList(new LambdaQueryWrapper<SmartWalletManualAllowlistEntity>()
                        .eq(SmartWalletManualAllowlistEntity::getSource, SOURCE)
                        .eq(SmartWalletManualAllowlistEntity::getStatus, STATUS_ACTIVE)
                        .and(wrapper -> wrapper
                                .isNull(SmartWalletManualAllowlistEntity::getExpiresAt)
                                .or()
                                .gt(SmartWalletManualAllowlistEntity::getExpiresAt, now))
                        .orderByDesc(SmartWalletManualAllowlistEntity::getUpdatedAt));
    }

    public SmartWalletManualAllowlistView upsert(SmartWalletManualAllowlistRequest request) {
        if (request == null || !StringUtils.hasText(request.getWalletAddress())) {
            throw new IllegalArgumentException("walletAddress is required");
        }
        return upsert(request.getWalletAddress(), request);
    }

    public SmartWalletManualAllowlistView upsert(String walletAddress, SmartWalletManualAllowlistRequest request) {
        String normalized = normalizeWallet(walletAddress);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("walletAddress is required");
        }
        Date now = new Date();
        SmartWalletManualAllowlistEntity entity = allowlistMapper.selectOne(new LambdaQueryWrapper<SmartWalletManualAllowlistEntity>()
                .eq(SmartWalletManualAllowlistEntity::getWalletAddress, normalized)
                .eq(SmartWalletManualAllowlistEntity::getSource, SOURCE)
                .last("limit 1"));
        if (entity == null) {
            entity = new SmartWalletManualAllowlistEntity()
                    .setWalletAddress(normalized)
                    .setSource(SOURCE)
                    .setCreatedAt(now);
        }
        SmartWalletManualAllowlistRequest safeRequest = request == null ? new SmartWalletManualAllowlistRequest() : request;
        entity.setStatus(normalizeStatus(safeRequest.getStatus()))
                .setAllowedExecutionModes(normalizeModeWithDefault(safeRequest.getAllowedExecutionModes()))
                .setStakeMultiplier(normalizeStakeMultiplier(safeRequest.getStakeMultiplier()))
                .setMaxLiveOrdersPerDay(safeRequest.getMaxLiveOrdersPerDay() == null
                        ? DEFAULT_MAX_LIVE_ORDERS_PER_DAY
                        : Math.max(safeRequest.getMaxLiveOrdersPerDay(), 0))
                .setMaxLiveOrdersPerRun(safeRequest.getMaxLiveOrdersPerRun() == null
                        ? DEFAULT_MAX_LIVE_ORDERS_PER_RUN
                        : Math.max(safeRequest.getMaxLiveOrdersPerRun(), 0))
                .setExpiresAt(safeRequest.getExpiresAt() == null ? defaultExpiresAt(now) : safeRequest.getExpiresAt())
                .setReason(trimToNull(safeRequest.getReason()))
                .setCreatedBy(trimToNull(safeRequest.getCreatedBy()))
                .setUpdatedAt(now);
        if (entity.getId() == null) {
            allowlistMapper.insert(entity);
        } else {
            allowlistMapper.updateById(entity);
        }
        return toView(entity);
    }

    public SmartWalletManualAllowlistView disable(String walletAddress) {
        String normalized = normalizeWallet(walletAddress);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("walletAddress is required");
        }
        SmartWalletManualAllowlistEntity entity = allowlistMapper.selectOne(new LambdaQueryWrapper<SmartWalletManualAllowlistEntity>()
                .eq(SmartWalletManualAllowlistEntity::getWalletAddress, normalized)
                .eq(SmartWalletManualAllowlistEntity::getSource, SOURCE)
                .last("limit 1"));
        if (entity == null) {
            throw new IllegalArgumentException("Manual allowlist entry not found: " + walletAddress);
        }
        entity.setStatus(STATUS_DISABLED).setUpdatedAt(new Date());
        allowlistMapper.updateById(entity);
        return toView(entity);
    }

    public int cleanupExpired() {
        Date now = new Date();
        List<SmartWalletManualAllowlistEntity> expired = allowlistMapper.selectList(new LambdaQueryWrapper<SmartWalletManualAllowlistEntity>()
                .eq(SmartWalletManualAllowlistEntity::getSource, SOURCE)
                .eq(SmartWalletManualAllowlistEntity::getStatus, STATUS_ACTIVE)
                .isNotNull(SmartWalletManualAllowlistEntity::getExpiresAt)
                .le(SmartWalletManualAllowlistEntity::getExpiresAt, now));
        int updated = 0;
        for (SmartWalletManualAllowlistEntity row : expired) {
            row.setStatus(STATUS_DISABLED).setUpdatedAt(now);
            allowlistMapper.updateById(row);
            updated++;
        }
        return updated;
    }

    public SmartWalletManualAllowlistSummaryView summary() {
        List<SmartWalletManualAllowlistEntity> rows = allowlistMapper.selectList(new LambdaQueryWrapper<SmartWalletManualAllowlistEntity>()
                .eq(SmartWalletManualAllowlistEntity::getSource, SOURCE));
        int active = 0;
        int expired = 0;
        int disabled = 0;
        int paperOnly = 0;
        int liveEnabled = 0;
        for (SmartWalletManualAllowlistEntity row : rows) {
            if (isActive(row)) {
                active++;
            }
            if (row.getExpiresAt() != null && !row.getExpiresAt().after(new Date())) {
                expired++;
            }
            if (STATUS_DISABLED.equalsIgnoreCase(row.getStatus())) {
                disabled++;
            }
            String mode = normalizeMode(row.getAllowedExecutionModes());
            if (MODE_PAPER.equals(mode)) {
                paperOnly++;
            }
            if (MODE_LIVE.equals(mode) || MODE_BOTH.equals(mode)) {
                liveEnabled++;
            }
        }
        return SmartWalletManualAllowlistSummaryView.builder()
                .total(rows.size())
                .active(active)
                .expired(expired)
                .disabled(disabled)
                .paperOnly(paperOnly)
                .liveEnabled(liveEnabled)
                .build();
    }

    public SmartWalletManualAllowlistView toView(SmartWalletManualAllowlistEntity entity) {
        if (entity == null) {
            return null;
        }
        return SmartWalletManualAllowlistView.builder()
                .id(entity.getId())
                .walletAddress(entity.getWalletAddress())
                .source(entity.getSource())
                .status(entity.getStatus())
                .allowedExecutionModes(entity.getAllowedExecutionModes())
                .stakeMultiplier(entity.getStakeMultiplier())
                .maxLiveOrdersPerDay(entity.getMaxLiveOrdersPerDay())
                .maxLiveOrdersPerRun(entity.getMaxLiveOrdersPerRun())
                .expiresAt(entity.getExpiresAt())
                .reason(entity.getReason())
                .createdBy(entity.getCreatedBy())
                .active(isActive(entity))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public boolean isActive(SmartWalletManualAllowlistEntity entity) {
        if (entity == null || !STATUS_ACTIVE.equalsIgnoreCase(entity.getStatus())) {
            return false;
        }
        return entity.getExpiresAt() == null || entity.getExpiresAt().after(new Date());
    }

    public String normalizeWallet(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return STATUS_DISABLED.equals(normalized) ? STATUS_DISABLED : STATUS_ACTIVE;
    }

    private String normalizeModeWithDefault(String value) {
        String normalized = normalizeMode(value);
        if (MODE_LIVE.equals(normalized) || MODE_BOTH.equals(normalized)) {
            return normalized;
        }
        return MODE_PAPER;
    }

    private String normalizeMode(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : MODE_PAPER;
    }

    private BigDecimal normalizeStakeMultiplier(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return DEFAULT_STAKE_MULTIPLIER;
        }
        return value.min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private Date defaultExpiresAt(Date now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now == null ? new Date() : now);
        calendar.add(Calendar.DAY_OF_YEAR, DEFAULT_EXPIRY_DAYS);
        return calendar.getTime();
    }

    private Date startOfToday() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
