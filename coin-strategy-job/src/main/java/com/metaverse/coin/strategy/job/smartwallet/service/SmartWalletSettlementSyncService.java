package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSettlementRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSettlementRunMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSettlementRetryEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSettlementRunEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRetryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRetryCandidate;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRetryReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRunView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncFailure;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncStatusView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletSettlementSyncService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final SmartWalletProperties properties;
    private final SmartWalletAccountingService accountingService;
    private final SmartWalletSettlementRunMapper settlementRunMapper;
    private final SmartWalletSettlementRetryMapper settlementRetryMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger invocationCount = new AtomicInteger(0);

    private volatile Date lastRunAt;
    private volatile SettlementSyncSummary lastSummary;

    @PostConstruct
    public void ensureSettlementTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `smart_wallet_settlement_run` (
                    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                    `trigger_type` VARCHAR(16) NOT NULL,
                    `position_limit` INT NOT NULL DEFAULT 0,
                    `market_limit` INT NOT NULL DEFAULT 0,
                    `refresh_cache` TINYINT(1) NOT NULL DEFAULT 0,
                    `status` VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
                    `scanned_positions` INT NOT NULL DEFAULT 0,
                    `distinct_markets` INT NOT NULL DEFAULT 0,
                    `fetched_markets` INT NOT NULL DEFAULT 0,
                    `resolved_positions` INT NOT NULL DEFAULT 0,
                    `unresolved_markets` INT NOT NULL DEFAULT 0,
                    `skipped_future_markets` INT NOT NULL DEFAULT 0,
                    `failed_markets` INT NOT NULL DEFAULT 0,
                    `notes` TEXT DEFAULT NULL,
                    `error_message` VARCHAR(512) DEFAULT NULL,
                    `started_at` TIMESTAMP NOT NULL,
                    `finished_at` TIMESTAMP NULL,
                    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX `idx_settlement_run_started` (`started_at`),
                    INDEX `idx_settlement_run_status` (`status`, `started_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `smart_wallet_settlement_retry` (
                    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                    `market_id` VARCHAR(128) NOT NULL,
                    `question` VARCHAR(512) DEFAULT NULL,
                    `latest_run_id` BIGINT UNSIGNED DEFAULT NULL,
                    `failure_count` INT NOT NULL DEFAULT 1,
                    `last_error_message` VARCHAR(512) DEFAULT NULL,
                    `last_failed_at` TIMESTAMP NOT NULL,
                    `next_retry_at` TIMESTAMP NOT NULL,
                    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY `uk_settlement_retry_market` (`market_id`),
                    INDEX `idx_settlement_retry_status` (`status`, `next_retry_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Scheduled(fixedDelayString = "${smart-wallet.settlement-sync.fixed-delay-ms:300000}")
    public void scheduledSyncSettlements() {
        if (!properties.isEnabled() || !properties.getSettlementSync().isEnabled()) {
            return;
        }
        SettlementSyncSummary summary = runLocked(
                properties.getSettlementSync().getPositionLimit(),
                properties.getSettlementSync().getMarketLimit(),
                shouldRefreshCacheForNextRun(),
                true,
                "SCHEDULED"
        );
        if (summary != null) {
            log.info("Scheduled settlement sync finished: scanned={}, markets={}, resolved={}, oraclePending={}, failed={}",
                    summary.getScannedPositions(),
                    summary.getDistinctMarkets(),
                    summary.getResolvedPositions(),
                    summary.getOraclePendingMarkets(),
                    summary.getFailedMarkets());
        }
    }

    public SettlementSyncSummary runNow() {
        SmartWalletProperties.SettlementSync config = properties.getSettlementSync();
        return runLocked(config.getPositionLimit(), config.getMarketLimit(), shouldRefreshCacheForNextRun(), false, "MANUAL");
    }

    public SettlementSyncSummary runNow(int positionLimit, int marketLimit, boolean refreshCache) {
        return runLocked(positionLimit, marketLimit, refreshCache, false, "MANUAL");
    }

    public SettlementSyncSummary processRetriesNow(Integer limit, boolean refreshCache) {
        SmartWalletProperties.SettlementSync config = properties.getSettlementSync();
        int batchLimit = Math.max(limit == null ? config.getRetryBatchLimit() : limit, 1);
        List<SmartWalletSettlementRetryEntity> dueRetries = settlementRetryMapper.selectList(new LambdaQueryWrapper<SmartWalletSettlementRetryEntity>()
                .in(SmartWalletSettlementRetryEntity::getStatus, List.of("PENDING", "RETRYING"))
                .le(SmartWalletSettlementRetryEntity::getNextRetryAt, new Date())
                .orderByAsc(SmartWalletSettlementRetryEntity::getNextRetryAt)
                .last("limit " + batchLimit));
        if (dueRetries.isEmpty()) {
            return SettlementSyncSummary.builder()
                    .scannedPositions(0)
                    .distinctMarkets(0)
                    .fetchedMarkets(0)
                    .resolvedPositions(0)
                    .unresolvedMarkets(0)
                    .oraclePendingMarkets(0)
                    .skippedFutureMarkets(0)
                    .failedMarkets(0)
                    .notes(List.of("No due settlement retries."))
                    .build();
        }

        for (SmartWalletSettlementRetryEntity retry : dueRetries) {
            retry.setStatus("RETRYING").setUpdatedAt(new Date());
            settlementRetryMapper.updateById(retry);
        }

        List<String> marketIds = dueRetries.stream().map(SmartWalletSettlementRetryEntity::getMarketId).toList();
        SettlementSyncSummary summary = runRetryLocked(marketIds, refreshCache, batchLimit);
        return summary;
    }

    public SettlementSyncStatusView status() {
        SmartWalletProperties.SettlementSync config = properties.getSettlementSync();
        int runs = invocationCount.get();
        boolean nextRefresh = config.isRefreshCacheOnEachRun()
                || (config.getRefreshCacheEveryRuns() != null
                && config.getRefreshCacheEveryRuns() > 0
                && (runs + 1) % config.getRefreshCacheEveryRuns() == 0);
        return SettlementSyncStatusView.builder()
                .enabled(properties.isEnabled() && config.isEnabled())
                .running(running.get())
                .lastRunAt(lastRunAt)
                .invocationCount(runs)
                .nextRunRefreshesCache(nextRefresh)
                .lastSummary(lastSummary)
                .build();
    }

    public List<SettlementRunView> listRuns(int limit) {
        List<SmartWalletSettlementRunEntity> runs = settlementRunMapper.selectList(new LambdaQueryWrapper<SmartWalletSettlementRunEntity>()
                .orderByDesc(SmartWalletSettlementRunEntity::getStartedAt)
                .last("limit " + Math.max(limit, 1)));
        List<SettlementRunView> views = new ArrayList<>();
        for (SmartWalletSettlementRunEntity run : runs) {
            views.add(toRunView(run));
        }
        return views;
    }

    public List<SettlementRetryView> listRetries(String status, int limit) {
        List<SmartWalletSettlementRetryEntity> retries = settlementRetryMapper.selectList(new LambdaQueryWrapper<SmartWalletSettlementRetryEntity>()
                .eq(StringUtils.hasText(status), SmartWalletSettlementRetryEntity::getStatus, status)
                .orderByAsc(SmartWalletSettlementRetryEntity::getNextRetryAt)
                .last("limit " + Math.max(limit, 1)));
        List<SettlementRetryView> views = new ArrayList<>();
        for (SmartWalletSettlementRetryEntity retry : retries) {
            views.add(toRetryView(retry));
        }
        return views;
    }

    public SettlementRetryReportView retryReport() {
        List<SmartWalletSettlementRetryEntity> retries = settlementRetryMapper.selectList(new LambdaQueryWrapper<>());
        int pending = 0;
        int retrying = 0;
        int resolved = 0;
        int abandoned = 0;
        int due = 0;
        int maxFailure = 0;
        Date latestFailedAt = null;
        Date now = new Date();
        for (SmartWalletSettlementRetryEntity retry : retries) {
            String status = retry.getStatus();
            if ("PENDING".equalsIgnoreCase(status)) {
                pending++;
            } else if ("RETRYING".equalsIgnoreCase(status)) {
                retrying++;
            } else if ("RESOLVED".equalsIgnoreCase(status)) {
                resolved++;
            } else if ("ABANDONED".equalsIgnoreCase(status)) {
                abandoned++;
            }
            if (retry.getNextRetryAt() != null
                    && !retry.getNextRetryAt().after(now)
                    && ("PENDING".equalsIgnoreCase(status) || "RETRYING".equalsIgnoreCase(status))) {
                due++;
            }
            maxFailure = Math.max(maxFailure, retry.getFailureCount() == null ? 0 : retry.getFailureCount());
            if (retry.getLastFailedAt() != null && (latestFailedAt == null || retry.getLastFailedAt().after(latestFailedAt))) {
                latestFailedAt = retry.getLastFailedAt();
            }
        }
        return SettlementRetryReportView.builder()
                .totalItems(retries.size())
                .pendingItems(pending)
                .retryingItems(retrying)
                .resolvedItems(resolved)
                .abandonedItems(abandoned)
                .dueItems(due)
                .maxFailureCount(maxFailure)
                .latestFailedAt(latestFailedAt)
                .build();
    }

    private boolean shouldRefreshCacheForNextRun() {
        SmartWalletProperties.SettlementSync config = properties.getSettlementSync();
        int nextRun = invocationCount.get() + 1;
        return config.isRefreshCacheOnEachRun()
                || (config.getRefreshCacheEveryRuns() != null
                && config.getRefreshCacheEveryRuns() > 0
                && nextRun % config.getRefreshCacheEveryRuns() == 0);
    }

    private SettlementSyncSummary runLocked(int positionLimit, int marketLimit, boolean refreshCache, boolean scheduled, String triggerType) {
        if (!running.compareAndSet(false, true)) {
            if (scheduled) {
                log.info("Skip scheduled settlement sync because a previous run is still in progress.");
                return null;
            }
            throw new IllegalStateException("Settlement sync is already running.");
        }
        SmartWalletSettlementRunEntity run = createRun(triggerType, positionLimit, marketLimit, refreshCache);
        try {
            SettlementSyncSummary summary = accountingService.syncSettlements(positionLimit, marketLimit, refreshCache);
            completeRun(run, summary, "SUCCESS", null);
            return summary;
        } catch (RuntimeException e) {
            failRun(run, e);
            throw e;
        } finally {
            running.set(false);
        }
    }

    private SettlementSyncSummary runRetryLocked(List<String> marketIds, boolean refreshCache, int marketLimit) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Settlement sync is already running.");
        }
        SmartWalletSettlementRunEntity run = createRun("RETRY", marketIds.size(), marketLimit, refreshCache);
        try {
            SettlementSyncSummary summary = accountingService.syncSettlementsForMarkets(marketIds, refreshCache);
            completeRun(run, summary, "SUCCESS", null);
            reconcileProcessedRetryQueue(run.getId(), marketIds, summary);
            return summary;
        } catch (RuntimeException e) {
            failRun(run, e);
            throw e;
        } finally {
            running.set(false);
        }
    }

    private SmartWalletSettlementRunEntity createRun(String triggerType, int positionLimit, int marketLimit, boolean refreshCache) {
        Date now = new Date();
        SmartWalletSettlementRunEntity run = new SmartWalletSettlementRunEntity()
                .setTriggerType(triggerType)
                .setPositionLimit(positionLimit)
                .setMarketLimit(marketLimit)
                .setRefreshCache(refreshCache)
                .setStatus("RUNNING")
                .setStartedAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        settlementRunMapper.insert(run);
        return run;
    }

    private void completeRun(SmartWalletSettlementRunEntity run,
                             SettlementSyncSummary summary,
                             String status,
                             String errorMessage) {
        invocationCount.incrementAndGet();
        lastRunAt = new Date();
        lastSummary = summary;

        run.setStatus(status)
                .setScannedPositions(summary.getScannedPositions())
                .setDistinctMarkets(summary.getDistinctMarkets())
                .setFetchedMarkets(summary.getFetchedMarkets())
                .setResolvedPositions(summary.getResolvedPositions())
                .setUnresolvedMarkets(summary.getUnresolvedMarkets())
                .setSkippedFutureMarkets(summary.getSkippedFutureMarkets())
                .setFailedMarkets(summary.getFailedMarkets())
                .setNotes(writeNotes(summary.getNotes()))
                .setErrorMessage(errorMessage)
                .setFinishedAt(lastRunAt)
                .setUpdatedAt(lastRunAt);
        settlementRunMapper.updateById(run);
        reconcileRetryQueue(run.getId(), summary);
        resolveRecoveredRetries(run.getId(), summary);
    }

    private void failRun(SmartWalletSettlementRunEntity run, RuntimeException e) {
        Date now = new Date();
        run.setStatus("FAILED")
                .setErrorMessage(abbreviate(e.getMessage(), 500))
                .setFinishedAt(now)
                .setUpdatedAt(now);
        settlementRunMapper.updateById(run);
        lastRunAt = now;
    }

    private void reconcileRetryQueue(Long runId, SettlementSyncSummary summary) {
        List<SettlementSyncFailure> failures = summary.getFailures() == null ? Collections.emptyList() : summary.getFailures();
        for (SettlementSyncFailure failure : failures) {
            upsertRetry(runId, failure);
        }
        List<SettlementRetryCandidate> retryCandidates = summary.getRetryCandidates() == null
                ? Collections.emptyList()
                : summary.getRetryCandidates();
        for (SettlementRetryCandidate retryCandidate : retryCandidates) {
            upsertRetry(runId, retryCandidate);
        }
    }

    private void reconcileProcessedRetryQueue(Long runId, List<String> marketIds, SettlementSyncSummary summary) {
        List<String> failedMarketIds = summary.getFailures() == null
                ? Collections.emptyList()
                : summary.getFailures().stream()
                .map(SettlementSyncFailure::getMarketId)
                .filter(StringUtils::hasText)
                .toList();
        List<String> pendingMarketIds = summary.getRetryCandidates() == null
                ? Collections.emptyList()
                : summary.getRetryCandidates().stream()
                .map(SettlementRetryCandidate::getMarketId)
                .filter(StringUtils::hasText)
                .toList();
        Date now = new Date();
        for (String marketId : marketIds) {
            SmartWalletSettlementRetryEntity retry = settlementRetryMapper.selectOne(new LambdaQueryWrapper<SmartWalletSettlementRetryEntity>()
                    .eq(SmartWalletSettlementRetryEntity::getMarketId, marketId)
                    .last("limit 1"));
            if (retry == null) {
                continue;
            }
            if (failedMarketIds.contains(marketId)) {
                continue;
            }
            if (pendingMarketIds.contains(marketId)) {
                continue;
            }
            retry.setLatestRunId(runId)
                    .setStatus("RESOLVED")
                    .setUpdatedAt(now)
                    .setNextRetryAt(now);
            settlementRetryMapper.updateById(retry);
        }
    }

    private void resolveRecoveredRetries(Long runId, SettlementSyncSummary summary) {
        List<String> processedMarkets = summary.getProcessedMarketIds() == null
                ? Collections.emptyList()
                : summary.getProcessedMarketIds().stream().filter(StringUtils::hasText).distinct().toList();
        if (processedMarkets.isEmpty()) {
            return;
        }
        List<String> failedMarketIds = summary.getFailures() == null
                ? Collections.emptyList()
                : summary.getFailures().stream()
                .map(SettlementSyncFailure::getMarketId)
                .filter(StringUtils::hasText)
                .toList();
        List<String> pendingMarketIds = summary.getRetryCandidates() == null
                ? Collections.emptyList()
                : summary.getRetryCandidates().stream()
                .map(SettlementRetryCandidate::getMarketId)
                .filter(StringUtils::hasText)
                .toList();
        Date now = new Date();
        List<SmartWalletSettlementRetryEntity> retries = settlementRetryMapper.selectList(new LambdaQueryWrapper<SmartWalletSettlementRetryEntity>()
                .in(SmartWalletSettlementRetryEntity::getMarketId, processedMarkets)
                .in(SmartWalletSettlementRetryEntity::getStatus, List.of("PENDING", "RETRYING")));
        for (SmartWalletSettlementRetryEntity retry : retries) {
            if (failedMarketIds.contains(retry.getMarketId())) {
                continue;
            }
            if (pendingMarketIds.contains(retry.getMarketId())) {
                continue;
            }
            retry.setLatestRunId(runId)
                    .setStatus("RESOLVED")
                    .setUpdatedAt(now)
                    .setNextRetryAt(now);
            settlementRetryMapper.updateById(retry);
        }
    }

    private void upsertRetry(Long runId, SettlementSyncFailure failure) {
        if (!StringUtils.hasText(failure.getMarketId())) {
            return;
        }
        Date now = new Date();
        SmartWalletProperties.SettlementSync config = properties.getSettlementSync();
        SmartWalletSettlementRetryEntity retry = settlementRetryMapper.selectOne(new LambdaQueryWrapper<SmartWalletSettlementRetryEntity>()
                .eq(SmartWalletSettlementRetryEntity::getMarketId, failure.getMarketId())
                .last("limit 1"));
        int failureCount = retry == null ? 1 : retry.getFailureCount() + 1;
        String nextStatus = failureCount >= Math.max(config.getMaxRetryFailures(), 1) ? "ABANDONED" : "PENDING";
        Date nextRetryAt = nextStatus.equals("ABANDONED") ? now : Date.from(Instant.now().plus(backoffMinutes(failureCount), ChronoUnit.MINUTES));
        if (retry == null) {
            retry = new SmartWalletSettlementRetryEntity()
                    .setMarketId(failure.getMarketId())
                    .setCreatedAt(now);
        }
        retry.setQuestion(failure.getQuestion())
                .setLatestRunId(runId)
                .setFailureCount(failureCount)
                .setLastErrorMessage(abbreviate(failure.getErrorMessage(), 500))
                .setLastFailedAt(now)
                .setNextRetryAt(nextRetryAt)
                .setStatus(nextStatus)
                .setUpdatedAt(now);
        if (retry.getId() == null) {
            settlementRetryMapper.insert(retry);
        } else {
            settlementRetryMapper.updateById(retry);
        }
    }

    private void upsertRetry(Long runId, SettlementRetryCandidate retryCandidate) {
        if (retryCandidate == null || !StringUtils.hasText(retryCandidate.getMarketId())) {
            return;
        }
        Date now = new Date();
        SmartWalletProperties.SettlementSync config = properties.getSettlementSync();
        SmartWalletSettlementRetryEntity retry = settlementRetryMapper.selectOne(new LambdaQueryWrapper<SmartWalletSettlementRetryEntity>()
                .eq(SmartWalletSettlementRetryEntity::getMarketId, retryCandidate.getMarketId())
                .last("limit 1"));
        int failureCount = retry == null || retry.getFailureCount() == null ? 0 : retry.getFailureCount();
        Date nextRetryAt = Date.from(Instant.now().plus(Math.max(config.getRetryBackoffMinutes(), 1), ChronoUnit.MINUTES));
        if (retry == null) {
            retry = new SmartWalletSettlementRetryEntity()
                    .setMarketId(retryCandidate.getMarketId())
                    .setCreatedAt(now);
        }
        retry.setQuestion(retryCandidate.getQuestion())
                .setLatestRunId(runId)
                .setFailureCount(failureCount)
                .setLastErrorMessage(abbreviate(retryCandidate.getRetryMessage(), 500))
                .setLastFailedAt(now)
                .setNextRetryAt(nextRetryAt)
                .setStatus("PENDING")
                .setUpdatedAt(now);
        if (retry.getId() == null) {
            settlementRetryMapper.insert(retry);
        } else {
            settlementRetryMapper.updateById(retry);
        }
    }

    private long backoffMinutes(int failureCount) {
        int base = Math.max(properties.getSettlementSync().getRetryBackoffMinutes(), 1);
        long factor = 1L << Math.min(Math.max(failureCount - 1, 0), 4);
        return base * factor;
    }

    private String abbreviate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(maxLength - 3, 0)) + "...";
    }

    private SettlementRunView toRunView(SmartWalletSettlementRunEntity run) {
        return SettlementRunView.builder()
                .id(run.getId())
                .triggerType(run.getTriggerType())
                .positionLimit(run.getPositionLimit())
                .marketLimit(run.getMarketLimit())
                .refreshCache(run.getRefreshCache())
                .status(run.getStatus())
                .scannedPositions(run.getScannedPositions())
                .distinctMarkets(run.getDistinctMarkets())
                .fetchedMarkets(run.getFetchedMarkets())
                .resolvedPositions(run.getResolvedPositions())
                .unresolvedMarkets(run.getUnresolvedMarkets())
                .skippedFutureMarkets(run.getSkippedFutureMarkets())
                .failedMarkets(run.getFailedMarkets())
                .notes(readNotes(run.getNotes()))
                .errorMessage(run.getErrorMessage())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .build();
    }

    private SettlementRetryView toRetryView(SmartWalletSettlementRetryEntity retry) {
        return SettlementRetryView.builder()
                .id(retry.getId())
                .marketId(retry.getMarketId())
                .question(retry.getQuestion())
                .latestRunId(retry.getLatestRunId())
                .failureCount(retry.getFailureCount())
                .lastErrorMessage(retry.getLastErrorMessage())
                .lastFailedAt(retry.getLastFailedAt())
                .nextRetryAt(retry.getNextRetryAt())
                .status(retry.getStatus())
                .build();
    }

    private String writeNotes(List<String> notes) {
        try {
            return objectMapper.writeValueAsString(notes == null ? Collections.emptyList() : notes);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readNotes(String notes) {
        if (!StringUtils.hasText(notes)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(notes, STRING_LIST);
        } catch (Exception e) {
            return List.of(notes);
        }
    }
}
