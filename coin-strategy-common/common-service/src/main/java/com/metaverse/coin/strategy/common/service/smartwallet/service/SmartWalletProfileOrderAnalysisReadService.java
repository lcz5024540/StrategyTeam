package com.metaverse.coin.strategy.common.service.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderAnalysisMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletOrderAnalysisView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

/**
 * Phase 4.4 第一步：直接读 {@code smart_wallet_order_analysis} 表为单地址订单分析提供
 * web 端 read 路径。
 *
 * <p>表数据由 job 端 {@code SmartWalletOrderAnalysisService.analyzeAndPersist()} 按需写入
 * （查询触发 + 短时缓存）。web 读到的是延迟数据——业务可接受。</p>
 *
 * <p>语义差异：原 job 端 view 包含 {@code settlementAt}、{@code pendingSettlement}、
 * {@code earlySettlement} 三个动态字段，表中无对应列；本路径返回 null。</p>
 */
@Service
@RequiredArgsConstructor
public class SmartWalletProfileOrderAnalysisReadService {

    private final SmartWalletOrderAnalysisMapper orderAnalysisMapper;

    public List<WalletOrderAnalysisView> listByWalletAddress(String walletAddress,
                                                             String exitStyle,
                                                             Boolean isWin) {
        if (!StringUtils.hasText(walletAddress)) {
            return List.of();
        }
        LambdaQueryWrapper<SmartWalletOrderAnalysisEntity> wrapper = new LambdaQueryWrapper<SmartWalletOrderAnalysisEntity>()
                .eq(SmartWalletOrderAnalysisEntity::getWalletAddress, walletAddress.trim());
        if (StringUtils.hasText(exitStyle)) {
            wrapper.eq(SmartWalletOrderAnalysisEntity::getExitStyle, exitStyle.trim());
        }
        if (isWin != null) {
            wrapper.eq(SmartWalletOrderAnalysisEntity::getIsWin, isWin);
        }
        List<SmartWalletOrderAnalysisEntity> rows = orderAnalysisMapper.selectList(
                wrapper.orderByDesc(SmartWalletOrderAnalysisEntity::getClosedAt));
        return rows.stream()
                .sorted(Comparator.comparing(SmartWalletOrderAnalysisEntity::getClosedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toView)
                .toList();
    }

    private WalletOrderAnalysisView toView(SmartWalletOrderAnalysisEntity entity) {
        return WalletOrderAnalysisView.builder()
                .walletAddress(entity.getWalletAddress())
                .marketId(entity.getMarketId())
                .question(entity.getQuestion())
                .outcome(entity.getOutcome())
                .openedAt(entity.getOpenedAt())
                .closedAt(entity.getClosedAt())
                .settlementAt(null)         // entity 表无此字段（动态计算）
                .marketEndAt(entity.getMarketEndAt())
                .investedUsdc(entity.getInvestedUsdc())
                .realizedPnlUsdc(entity.getRealizedPnlUsdc())
                .roiRate(entity.getRoiRate())
                .holdMinutes(entity.getHoldMinutes())
                .exitStyle(entity.getExitStyle())
                .exitLeadMinutes(entity.getExitLeadMinutes())
                .isWin(entity.getIsWin())
                .sourceConfidence(entity.getSourceConfidence())
                .pendingSettlement(null)    // entity 表无此字段（动态计算）
                .earlySettlement(null)
                .build();
    }
}
