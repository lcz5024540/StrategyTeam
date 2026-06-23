package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletManualAllowlistMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualAllowlistEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualAllowlistRequest;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualAllowlistView;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Manual allowlist 写接口（A 类纯 DB）。语义与 job 端 {@code SmartWalletManualAllowlistService} 保持一致。
 */
@Service
public class SmartWalletAllowlistWriteService {

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

    public SmartWalletAllowlistWriteService(SmartWalletManualAllowlistMapper allowlistMapper) {
        this.allowlistMapper = allowlistMapper;
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
        SmartWalletManualAllowlistRequest safe = request == null ? new SmartWalletManualAllowlistRequest() : request;
        entity.setStatus(normalizeStatus(safe.getStatus()))
                .setAllowedExecutionModes(normalizeModeWithDefault(safe.getAllowedExecutionModes()))
                .setStakeMultiplier(normalizeStakeMultiplier(safe.getStakeMultiplier()))
                .setMaxLiveOrdersPerDay(safe.getMaxLiveOrdersPerDay() == null
                        ? DEFAULT_MAX_LIVE_ORDERS_PER_DAY
                        : Math.max(safe.getMaxLiveOrdersPerDay(), 0))
                .setMaxLiveOrdersPerRun(safe.getMaxLiveOrdersPerRun() == null
                        ? DEFAULT_MAX_LIVE_ORDERS_PER_RUN
                        : Math.max(safe.getMaxLiveOrdersPerRun(), 0))
                .setExpiresAt(safe.getExpiresAt() == null ? defaultExpiresAt(now) : safe.getExpiresAt())
                .setReason(trimToNull(safe.getReason()))
                .setCreatedBy(trimToNull(safe.getCreatedBy()))
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

    private SmartWalletManualAllowlistView toView(SmartWalletManualAllowlistEntity e) {
        return new SmartWalletManualAllowlistView(e.getId(), e.getWalletAddress(), e.getSource(), e.getStatus(),
                e.getAllowedExecutionModes(), e.getStakeMultiplier(), e.getMaxLiveOrdersPerDay(),
                e.getMaxLiveOrdersPerRun(), e.getExpiresAt(), e.getReason(), e.getCreatedBy(),
                isActive(e), e.getCreatedAt(), e.getUpdatedAt());
    }

    private boolean isActive(SmartWalletManualAllowlistEntity e) {
        if (e == null || !STATUS_ACTIVE.equalsIgnoreCase(e.getStatus())) return false;
        return e.getExpiresAt() == null || e.getExpiresAt().after(new Date());
    }

    private String normalizeWallet(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String value) {
        String norm = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return STATUS_DISABLED.equals(norm) ? STATUS_DISABLED : STATUS_ACTIVE;
    }

    private String normalizeModeWithDefault(String value) {
        String norm = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : MODE_PAPER;
        return (MODE_LIVE.equals(norm) || MODE_BOTH.equals(norm)) ? norm : MODE_PAPER;
    }

    private BigDecimal normalizeStakeMultiplier(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) return DEFAULT_STAKE_MULTIPLIER;
        return value.min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private Date defaultExpiresAt(Date now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now == null ? new Date() : now);
        calendar.add(Calendar.DAY_OF_YEAR, DEFAULT_EXPIRY_DAYS);
        return calendar.getTime();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
