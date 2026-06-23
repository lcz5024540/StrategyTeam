package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletClaimRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletClaimRunMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSettlementRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSettlementRunMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletClaimRetryEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletClaimRunEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSettlementRetryEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSettlementRunEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.SettlementRetryReportView;
import com.metaverse.coin.strategy.web.model.smartwallet.SettlementRetryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SettlementRunView;
import com.metaverse.coin.strategy.web.model.smartwallet.SettlementSyncStatusView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletClaimRetryReportView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletClaimRetryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletClaimRunView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletClaimStatusView;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class SmartWalletSettlementReadService {

    private final SmartWalletSettlementRunMapper settlementRunMapper;
    private final SmartWalletSettlementRetryMapper settlementRetryMapper;
    private final SmartWalletClaimRunMapper claimRunMapper;
    private final SmartWalletClaimRetryMapper claimRetryMapper;
    private final SmartWalletPositionMapper positionMapper;

    public SmartWalletSettlementReadService(SmartWalletSettlementRunMapper settlementRunMapper,
                                             SmartWalletSettlementRetryMapper settlementRetryMapper,
                                             SmartWalletClaimRunMapper claimRunMapper,
                                             SmartWalletClaimRetryMapper claimRetryMapper,
                                             SmartWalletPositionMapper positionMapper) {
        this.settlementRunMapper = settlementRunMapper;
        this.settlementRetryMapper = settlementRetryMapper;
        this.claimRunMapper = claimRunMapper;
        this.claimRetryMapper = claimRetryMapper;
        this.positionMapper = positionMapper;
    }

    // ════════════════════════════════════════════════════════════════
    // Settlement
    // ════════════════════════════════════════════════════════════════

    public SettlementSyncStatusView settlementStatus() {
        int pendingOracleCount = safeCount(positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getStatus, "SETTLEMENT_PENDING_ORACLE")));
        int resolvedUnclaimedCount = safeCount(positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getStatus, "RESOLVED_UNCLAIMED")));
        int activeRetryCount = safeCount(settlementRetryMapper.selectCount(new LambdaQueryWrapper<SmartWalletSettlementRetryEntity>()
                .in(SmartWalletSettlementRetryEntity::getStatus, List.of("PENDING", "RETRYING"))));
        // Recent runs in last 24h
        Date oneDayAgo = new Date(System.currentTimeMillis() - 86400_000L);
        int recentRunCount = safeCount(settlementRunMapper.selectCount(new LambdaQueryWrapper<SmartWalletSettlementRunEntity>()
                .ge(SmartWalletSettlementRunEntity::getStartedAt, oneDayAgo)));
        // Last run time
        SmartWalletSettlementRunEntity lastRun = settlementRunMapper.selectOne(new LambdaQueryWrapper<SmartWalletSettlementRunEntity>()
                .orderByDesc(SmartWalletSettlementRunEntity::getStartedAt)
                .last("limit 1"));
        Date lastRunAt = lastRun == null ? null : lastRun.getStartedAt();
        return new SettlementSyncStatusView(pendingOracleCount, resolvedUnclaimedCount, recentRunCount, activeRetryCount, lastRunAt);
    }

    public List<SettlementRunView> settlementRuns(int limit) {
        List<SmartWalletSettlementRunEntity> runs = settlementRunMapper.selectList(
                new LambdaQueryWrapper<SmartWalletSettlementRunEntity>()
                        .orderByDesc(SmartWalletSettlementRunEntity::getStartedAt)
                        .last("limit " + Math.max(limit, 1)));
        List<SettlementRunView> views = new ArrayList<>();
        for (SmartWalletSettlementRunEntity run : runs) {
            views.add(new SettlementRunView(run.getId(), run.getStatus(), run.getScannedPositions(),
                    run.getResolvedPositions(), run.getFailedMarkets(), run.getErrorMessage(),
                    run.getStartedAt(), run.getFinishedAt(), run.getCreatedAt()));
        }
        return views;
    }

    public List<SettlementRetryView> settlementRetries(String status, int limit) {
        List<SmartWalletSettlementRetryEntity> retries = settlementRetryMapper.selectList(
                new LambdaQueryWrapper<SmartWalletSettlementRetryEntity>()
                        .eq(StringUtils.hasText(status), SmartWalletSettlementRetryEntity::getStatus, status)
                        .orderByAsc(SmartWalletSettlementRetryEntity::getNextRetryAt)
                        .last("limit " + Math.max(limit, 1)));
        List<SettlementRetryView> views = new ArrayList<>();
        for (SmartWalletSettlementRetryEntity retry : retries) {
            views.add(new SettlementRetryView(retry.getId(), null, retry.getMarketId(), null,
                    retry.getStatus(), retry.getFailureCount(), retry.getLastErrorMessage(),
                    retry.getNextRetryAt(), retry.getCreatedAt(), retry.getUpdatedAt()));
        }
        return views;
    }

    public SettlementRetryReportView settlementRetryReport() {
        List<SmartWalletSettlementRetryEntity> retries = settlementRetryMapper.selectList(new LambdaQueryWrapper<>());
        int pending = 0, retrying = 0, resolved = 0, abandoned = 0;
        for (SmartWalletSettlementRetryEntity retry : retries) {
            String s = retry.getStatus();
            if ("PENDING".equalsIgnoreCase(s)) pending++;
            else if ("RETRYING".equalsIgnoreCase(s)) retrying++;
            else if ("RESOLVED".equalsIgnoreCase(s)) resolved++;
            else if ("ABANDONED".equalsIgnoreCase(s)) abandoned++;
        }
        return new SettlementRetryReportView(retries.size(), pending + retrying, resolved, 0, abandoned);
    }

    // ════════════════════════════════════════════════════════════════
    // Claim
    // ════════════════════════════════════════════════════════════════

    public SmartWalletClaimStatusView claimStatus() {
        int claimableCount = safeCount(positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getStatus, "RESOLVED_UNCLAIMED")));
        // Sum claimable proceeds
        List<SmartWalletPositionEntity> claimable = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getStatus, "RESOLVED_UNCLAIMED"));
        BigDecimal proceeds = BigDecimal.ZERO;
        for (SmartWalletPositionEntity p : claimable) {
            if (p.getClaimableProceedsUsdc() != null) {
                proceeds = proceeds.add(p.getClaimableProceedsUsdc());
            }
        }
        int activeRetryCount = safeCount(claimRetryMapper.selectCount(new LambdaQueryWrapper<SmartWalletClaimRetryEntity>()
                .in(SmartWalletClaimRetryEntity::getStatus, List.of("PENDING", "RETRYING", "WAITING_CONFIRMATION"))));
        Date oneDayAgo = new Date(System.currentTimeMillis() - 86400_000L);
        int recentRunCount = safeCount(claimRunMapper.selectCount(new LambdaQueryWrapper<SmartWalletClaimRunEntity>()
                .ge(SmartWalletClaimRunEntity::getStartedAt, oneDayAgo)));
        SmartWalletClaimRunEntity lastRun = claimRunMapper.selectOne(new LambdaQueryWrapper<SmartWalletClaimRunEntity>()
                .orderByDesc(SmartWalletClaimRunEntity::getStartedAt)
                .last("limit 1"));
        Date lastRunAt = lastRun == null ? null : lastRun.getStartedAt();
        return new SmartWalletClaimStatusView(claimableCount, proceeds, recentRunCount, activeRetryCount, lastRunAt);
    }

    public List<SmartWalletClaimRunView> claimRuns(int limit) {
        List<SmartWalletClaimRunEntity> runs = claimRunMapper.selectList(
                new LambdaQueryWrapper<SmartWalletClaimRunEntity>()
                        .orderByDesc(SmartWalletClaimRunEntity::getStartedAt)
                        .last("limit " + Math.max(limit, 1)));
        List<SmartWalletClaimRunView> views = new ArrayList<>();
        for (SmartWalletClaimRunEntity run : runs) {
            views.add(new SmartWalletClaimRunView(run.getId(), run.getStatus(), run.getScannedPositions(),
                    run.getClaimedPositions(), run.getFailedPositions(), run.getErrorMessage(),
                    run.getStartedAt(), run.getFinishedAt(), run.getCreatedAt()));
        }
        return views;
    }

    public List<SmartWalletClaimRetryView> claimRetries(String status, int limit) {
        List<SmartWalletClaimRetryEntity> retries = claimRetryMapper.selectList(
                new LambdaQueryWrapper<SmartWalletClaimRetryEntity>()
                        .eq(StringUtils.hasText(status), SmartWalletClaimRetryEntity::getStatus, status)
                        .orderByAsc(SmartWalletClaimRetryEntity::getNextRetryAt)
                        .last("limit " + Math.max(limit, 1)));
        List<SmartWalletClaimRetryView> views = new ArrayList<>();
        for (SmartWalletClaimRetryEntity retry : retries) {
            views.add(new SmartWalletClaimRetryView(retry.getId(), retry.getPositionId(), retry.getMarketId(),
                    null, retry.getStatus(), retry.getFailureCount(), retry.getLastErrorMessage(),
                    retry.getNextRetryAt(), retry.getCreatedAt(), retry.getUpdatedAt()));
        }
        return views;
    }

    public SmartWalletClaimRetryReportView claimRetryReport() {
        List<SmartWalletClaimRetryEntity> retries = claimRetryMapper.selectList(new LambdaQueryWrapper<>());
        int pending = 0, waiting = 0, resolved = 0, abandoned = 0;
        for (SmartWalletClaimRetryEntity retry : retries) {
            String s = retry.getStatus();
            if ("PENDING".equalsIgnoreCase(s) || "RETRYING".equalsIgnoreCase(s)) pending++;
            else if ("WAITING_CONFIRMATION".equalsIgnoreCase(s)) waiting++;
            else if ("RESOLVED".equalsIgnoreCase(s)) resolved++;
            else if ("ABANDONED".equalsIgnoreCase(s)) abandoned++;
        }
        return new SmartWalletClaimRetryReportView(retries.size(), pending + waiting, resolved, 0, abandoned);
    }

    // ════════════════════════════════════════════════════════════════
    private int safeCount(Long count) { return count == null ? 0 : count.intValue(); }
}
