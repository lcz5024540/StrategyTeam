package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletManualBlocklistMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualBlocklistEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualBlocklistRequest;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualBlocklistView;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Manual blocklist 写接口（A 类纯 DB）。
 */
@Service
public class SmartWalletBlocklistWriteService {

    public static final String SOURCE = "POLYMARKET";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String REASON_MANUAL_BLOCKLIST = "MANUAL_BLOCKLIST";

    private final SmartWalletManualBlocklistMapper blocklistMapper;

    public SmartWalletBlocklistWriteService(SmartWalletManualBlocklistMapper blocklistMapper) {
        this.blocklistMapper = blocklistMapper;
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
        SmartWalletManualBlocklistRequest safe = request == null ? new SmartWalletManualBlocklistRequest() : request;
        entity.setStatus(normalizeStatus(safe.getStatus()))
                .setReason(firstText(safe.getReason(), REASON_MANUAL_BLOCKLIST))
                .setCreatedBy(trimToNull(safe.getCreatedBy()))
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
            if (!StringUtils.hasText(walletAddress)) continue;
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

    private SmartWalletManualBlocklistView toView(SmartWalletManualBlocklistEntity e) {
        return new SmartWalletManualBlocklistView(e.getId(), e.getWalletAddress(), e.getSource(), e.getStatus(),
                e.getReason(), e.getCreatedBy(), isActive(e), e.getCreatedAt(), e.getUpdatedAt());
    }

    private boolean isActive(SmartWalletManualBlocklistEntity e) {
        return e != null && STATUS_ACTIVE.equalsIgnoreCase(e.getStatus());
    }

    private String normalizeWallet(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String value) {
        String norm = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return STATUS_DISABLED.equals(norm) ? STATUS_DISABLED : STATUS_ACTIVE;
    }

    private String firstText(String preferred, String fallback) {
        if (StringUtils.hasText(preferred)) return preferred.trim();
        return fallback;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
