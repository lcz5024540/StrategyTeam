package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SmartWalletProfileStoreService {

    private static final String SOURCE = "POLYMARKET";

    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletScoringService scoringService;

    public SmartWalletProfileEntity upsertMetrics(PolymarketWalletMetricsRequest request) {
        SmartWalletProfileEntity entity = profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, request.getWalletAddress())
                .eq(SmartWalletProfileEntity::getChain, request.getChain())
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .last("limit 1"));

        if (entity == null) {
            entity = new SmartWalletProfileEntity()
                    .setWalletAddress(request.getWalletAddress())
                    .setChain(request.getChain())
                    .setSource(SOURCE)
                    .setCreatedAt(new Date());
            entity.setQualified(Boolean.FALSE);
            entity.setWatchEnabled(Boolean.FALSE);
        }

        entity.setAlias(request.getAlias())
                .setWinRate(safe(request.getWinRate()))
                .setRealizedPnlUsdc(safe(request.getRealizedPnlUsdc()))
                .setTotalTrades(request.getTotalTrades() == null ? 0 : request.getTotalTrades())
                .setAverageSizeUsdc(safe(request.getAverageSizeUsdc()))
                .setAverageRoiRate(safe(request.getAverageRoiRate()))
                .setMaxDrawdownRate(safe(request.getMaxDrawdownRate()))
                .setLastTradeAt(request.getLastTradeAt())
                .setWalletScore(scoringService.score(
                        safe(request.getWinRate()),
                        safe(request.getRealizedPnlUsdc()),
                        request.getTotalTrades() == null ? 0 : request.getTotalTrades(),
                        safe(request.getAverageRoiRate()),
                        safe(request.getMaxDrawdownRate())
                ))
                .setUpdatedAt(new Date());

        if (entity.getId() == null) {
            profileMapper.insert(entity);
        } else {
            profileMapper.updateById(entity);
        }
        return entity;
    }

    public List<SmartWalletProfileEntity> listAll() {
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .orderByDesc(SmartWalletProfileEntity::getWalletScore)
                .orderByDesc(SmartWalletProfileEntity::getRealizedPnlUsdc));
    }

    public List<SmartWalletProfileEntity> listWatchEnabled() {
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .eq(SmartWalletProfileEntity::getQualified, Boolean.TRUE)
                .eq(SmartWalletProfileEntity::getWatchEnabled, Boolean.TRUE)
                .orderByDesc(SmartWalletProfileEntity::getWalletScore));
    }

    public void update(SmartWalletProfileEntity entity) {
        entity.setUpdatedAt(new Date());
        profileMapper.updateById(entity);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
