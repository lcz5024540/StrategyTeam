package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletClaimRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletClaimRunMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletClaimRetryEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletClaimRunEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimFailure;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimResultView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimRetryReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimRetryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimRunView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimStatusView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimSummary;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletClaimService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final SmartWalletProperties properties;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletClaimRunMapper claimRunMapper;
    private final SmartWalletClaimRetryMapper claimRetryMapper;
    private final SmartWalletAccountingService accountingService;
    private final SmartWalletOnChainClaimGateway onChainClaimGateway;
    private final SmartWalletMarketInfoService marketInfoService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger invocationCount = new AtomicInteger(0);

    private volatile Date lastRunAt;
    private volatile SmartWalletClaimSummary lastSummary;

    @PostConstruct
    public void ensureClaimTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `smart_wallet_claim_run` (
                    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                    `trigger_type` VARCHAR(16) NOT NULL,
                    `position_limit` INT NOT NULL DEFAULT 0,
                    `status` VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
                    `scanned_positions` INT NOT NULL DEFAULT 0,
                    `attempted_claims` INT NOT NULL DEFAULT 0,
                    `claimed_positions` INT NOT NULL DEFAULT 0,
                    `waiting_confirmation_positions` INT NOT NULL DEFAULT 0,
                    `failed_positions` INT NOT NULL DEFAULT 0,
                    `skipped_positions` INT NOT NULL DEFAULT 0,
                    `notes` TEXT DEFAULT NULL,
                    `error_message` VARCHAR(512) DEFAULT NULL,
                    `started_at` TIMESTAMP NOT NULL,
                    `finished_at` TIMESTAMP NULL,
                    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX `idx_claim_run_started` (`started_at`),
                    INDEX `idx_claim_run_status` (`status`, `started_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `smart_wallet_claim_retry` (
                    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                    `position_id` BIGINT UNSIGNED NOT NULL,
                    `execution_address` VARCHAR(128) DEFAULT NULL,
                    `market_id` VARCHAR(128) DEFAULT NULL,
                    `question` VARCHAR(512) DEFAULT NULL,
                    `latest_run_id` BIGINT UNSIGNED DEFAULT NULL,
                    `failure_count` INT NOT NULL DEFAULT 1,
                    `last_error_message` VARCHAR(512) DEFAULT NULL,
                    `last_failed_at` TIMESTAMP NOT NULL,
                    `next_retry_at` TIMESTAMP NOT NULL,
                    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                    `tx_hash` VARCHAR(128) DEFAULT NULL,
                    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY `uk_claim_retry_position` (`position_id`),
                    INDEX `idx_claim_retry_status` (`status`, `next_retry_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Scheduled(fixedDelayString = "${smart-wallet.claim.fixed-delay-ms:60000}")
    public void scheduledProcessClaims() {
        if (!properties.isEnabled() || !properties.getClaim().isEnabled()) {
            return;
        }
        SmartWalletClaimSummary summary = runLocked(loadClaimablePositions(properties.getClaim().getPositionLimit()), "SCHEDULED");
        if (summary != null) {
            log.info("Scheduled claim processing finished: scanned={}, claimed={}, waitingConfirmation={}, failed={}",
                    summary.getScannedPositions(),
                    summary.getClaimedPositions(),
                    summary.getWaitingConfirmationPositions(),
                    summary.getFailedPositions());
        }
    }

    public SmartWalletClaimSummary processNow(Integer limit) {
        int batchLimit = Math.max(limit == null ? properties.getClaim().getPositionLimit() : limit, 1);
        return runLocked(loadClaimablePositions(batchLimit), "MANUAL");
    }

    public SmartWalletClaimSummary processRetriesNow(Integer limit) {
        SmartWalletProperties.Claim config = properties.getClaim();
        int batchLimit = Math.max(limit == null ? config.getRetryBatchLimit() : limit, 1);
        List<SmartWalletClaimRetryEntity> dueRetries = claimRetryMapper.selectList(new LambdaQueryWrapper<SmartWalletClaimRetryEntity>()
                .in(SmartWalletClaimRetryEntity::getStatus, List.of("PENDING", "RETRYING"))
                .le(SmartWalletClaimRetryEntity::getNextRetryAt, new Date())
                .orderByAsc(SmartWalletClaimRetryEntity::getNextRetryAt)
                .last("limit " + batchLimit));
        if (dueRetries.isEmpty()) {
            return SmartWalletClaimSummary.builder()
                    .scannedPositions(0)
                    .attemptedClaims(0)
                    .claimedPositions(0)
                    .waitingConfirmationPositions(0)
                    .failedPositions(0)
                    .skippedPositions(0)
                    .notes(List.of("No due claim retries."))
                    .build();
        }

        Date now = new Date();
        for (SmartWalletClaimRetryEntity retry : dueRetries) {
            retry.setStatus("RETRYING").setUpdatedAt(now);
            claimRetryMapper.updateById(retry);
        }

        List<Long> positionIds = dueRetries.stream().map(SmartWalletClaimRetryEntity::getPositionId).distinct().toList();
        List<SmartWalletPositionEntity> activePositions = positionMapper.selectBatchIds(positionIds).stream()
                .filter(position -> position != null && "RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus()))
                .toList();
        List<Long> activePositionIds = activePositions.stream().map(SmartWalletPositionEntity::getId).toList();
        for (SmartWalletClaimRetryEntity retry : dueRetries) {
            if (!activePositionIds.contains(retry.getPositionId())) {
                markRetryResolved(null, retry.getPositionId(), "Position is no longer RESOLVED_UNCLAIMED; closing retry entry.", retry.getTxHash());
            }
        }
        return runLocked(activePositions, "RETRY");
    }

    public SmartWalletClaimResultView claimPosition(Long positionId, boolean confirmExternalClaim) {
        SmartWalletClaimResultView result = accountingService.claimResolvedPosition(positionId, confirmExternalClaim);
        if ("CLAIMED".equalsIgnoreCase(result.getCurrentStatus())) {
            markRetryResolved(null, positionId, result.getMessage(), null);
        }
        return result;
    }

    public SmartWalletClaimStatusView status() {
        Date now = new Date();
        Date staleThreshold = Date.from(Instant.ofEpochMilli(now.getTime())
                .minus(properties.getClaim().getStaleResolvedUnclaimedHours() == null ? 12 : properties.getClaim().getStaleResolvedUnclaimedHours(), ChronoUnit.HOURS));
        int resolvedUnclaimedCount = safeCount(positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getStatus, "RESOLVED_UNCLAIMED")));
        int staleResolvedUnclaimedCount = safeCount(positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getStatus, "RESOLVED_UNCLAIMED")
                .lt(SmartWalletPositionEntity::getClosedAt, staleThreshold)));
        int dueRetryCount = safeCount(claimRetryMapper.selectCount(new LambdaQueryWrapper<SmartWalletClaimRetryEntity>()
                .in(SmartWalletClaimRetryEntity::getStatus, List.of("PENDING", "RETRYING"))
                .le(SmartWalletClaimRetryEntity::getNextRetryAt, now)));
        int waitingConfirmationRetryCount = safeCount(claimRetryMapper.selectCount(new LambdaQueryWrapper<SmartWalletClaimRetryEntity>()
                .eq(SmartWalletClaimRetryEntity::getStatus, "WAITING_CONFIRMATION")));
        List<String> anomalyNotes = new ArrayList<>();
        if (staleResolvedUnclaimedCount > 0) {
            anomalyNotes.add("stale_resolved_unclaimed=" + staleResolvedUnclaimedCount);
        }
        if (waitingConfirmationRetryCount > 0) {
            anomalyNotes.add("waiting_confirmation=" + waitingConfirmationRetryCount);
        }
        if (dueRetryCount > 0) {
            anomalyNotes.add("due_claim_retries=" + dueRetryCount);
        }
        return SmartWalletClaimStatusView.builder()
                .enabled(properties.isEnabled() && properties.getClaim().isEnabled())
                .running(running.get())
                .lastRunAt(lastRunAt)
                .invocationCount(invocationCount.get())
                .resolvedUnclaimedCount(resolvedUnclaimedCount)
                .dueRetryCount(dueRetryCount)
                .waitingConfirmationRetryCount(waitingConfirmationRetryCount)
                .staleResolvedUnclaimedCount(staleResolvedUnclaimedCount)
                .anomalyNotes(anomalyNotes)
                .lastSummary(lastSummary)
                .build();
    }

    public List<SmartWalletClaimRunView> listRuns(int limit) {
        List<SmartWalletClaimRunEntity> runs = claimRunMapper.selectList(new LambdaQueryWrapper<SmartWalletClaimRunEntity>()
                .orderByDesc(SmartWalletClaimRunEntity::getStartedAt)
                .last("limit " + Math.max(limit, 1)));
        List<SmartWalletClaimRunView> views = new ArrayList<>();
        for (SmartWalletClaimRunEntity run : runs) {
            views.add(toRunView(run));
        }
        return views;
    }

    public List<SmartWalletClaimRetryView> listRetries(String status, int limit) {
        List<SmartWalletClaimRetryEntity> retries = claimRetryMapper.selectList(new LambdaQueryWrapper<SmartWalletClaimRetryEntity>()
                .eq(StringUtils.hasText(status), SmartWalletClaimRetryEntity::getStatus, status)
                .orderByAsc(SmartWalletClaimRetryEntity::getNextRetryAt)
                .last("limit " + Math.max(limit, 1)));
        List<SmartWalletClaimRetryView> views = new ArrayList<>();
        for (SmartWalletClaimRetryEntity retry : retries) {
            views.add(toRetryView(retry));
        }
        return views;
    }

    public SmartWalletClaimRetryReportView retryReport() {
        List<SmartWalletClaimRetryEntity> retries = claimRetryMapper.selectList(new LambdaQueryWrapper<>());
        int pending = 0;
        int retrying = 0;
        int waitingConfirmation = 0;
        int resolved = 0;
        int abandoned = 0;
        int due = 0;
        int maxFailure = 0;
        Date latestFailedAt = null;
        Date now = new Date();
        for (SmartWalletClaimRetryEntity retry : retries) {
            String status = retry.getStatus();
            if ("PENDING".equalsIgnoreCase(status)) {
                pending++;
            } else if ("RETRYING".equalsIgnoreCase(status)) {
                retrying++;
            } else if ("WAITING_CONFIRMATION".equalsIgnoreCase(status)) {
                waitingConfirmation++;
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
        return SmartWalletClaimRetryReportView.builder()
                .totalItems(retries.size())
                .pendingItems(pending)
                .retryingItems(retrying)
                .waitingConfirmationItems(waitingConfirmation)
                .resolvedItems(resolved)
                .abandonedItems(abandoned)
                .dueItems(due)
                .maxFailureCount(maxFailure)
                .latestFailedAt(latestFailedAt)
                .build();
    }

    private List<SmartWalletPositionEntity> loadClaimablePositions(int limit) {
        return positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getStatus, "RESOLVED_UNCLAIMED")
                .orderByAsc(SmartWalletPositionEntity::getClosedAt)
                .orderByAsc(SmartWalletPositionEntity::getId)
                .last("limit " + Math.max(limit, 1)));
    }

    private int safeCount(Long count) {
        return count == null ? 0 : count.intValue();
    }

    private SmartWalletClaimSummary runLocked(List<SmartWalletPositionEntity> positions, String triggerType) {
        if (shouldSkipScheduledEmptyRun(positions, triggerType)) {
            log.debug("Skip scheduled claim processing because no RESOLVED_UNCLAIMED positions were found.");
            return null;
        }
        if (!running.compareAndSet(false, true)) {
            if ("SCHEDULED".equalsIgnoreCase(triggerType)) {
                log.info("Skip scheduled claim processing because a previous run is still in progress.");
                return null;
            }
            throw new IllegalStateException("Claim processing is already running.");
        }
        int positionLimit = positions == null ? 0 : positions.size();
        SmartWalletClaimRunEntity run = createRun(triggerType, positionLimit);
        try {
            SmartWalletClaimSummary summary = processPositions(run.getId(), positions == null ? List.of() : positions);
            completeRun(run, summary, "SUCCESS", null);
            return summary;
        } catch (RuntimeException e) {
            failRun(run, e);
            throw e;
        } finally {
            running.set(false);
        }
    }

    private SmartWalletClaimRunEntity createRun(String triggerType, int positionLimit) {
        Date now = new Date();
        SmartWalletClaimRunEntity run = new SmartWalletClaimRunEntity()
                .setTriggerType(triggerType)
                .setPositionLimit(positionLimit)
                .setStatus("RUNNING")
                .setStartedAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        claimRunMapper.insert(run);
        return run;
    }

    private SmartWalletClaimSummary processPositions(Long runId, List<SmartWalletPositionEntity> positions) {
        SmartWalletProperties.Claim config = properties.getClaim();
        Date now = new Date();
        // Populate all positions with market info to enable delegate getters
        marketInfoService.populatePositions(positions);
        Map<Long, SmartWalletClaimRetryEntity> retryByPosition = loadRetryMap(positions);
        int attempted = 0;
        int claimed = 0;
        int waitingConfirmation = 0;
        int failed = 0;
        int skipped = 0;
        List<String> notes = new ArrayList<>();
        List<SmartWalletClaimFailure> failures = new ArrayList<>();
        List<Long> processedPositionIds = new ArrayList<>();
        List<Long> claimedPositionIds = new ArrayList<>();

        for (SmartWalletPositionEntity position : positions) {
            if (position == null || !"RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())) {
                skipped++;
                continue;
            }
            boolean paper = isPaperExecutionAddress(position.getExecutionAddress());
            if (paper && !config.isPaperAutoClaimEnabled()) {
                skipped++;
                continue;
            }
            if (!paper && !config.isLiveAutoClaimEnabled()) {
                skipped++;
                continue;
            }

            SmartWalletClaimRetryEntity retry = retryByPosition.get(position.getId());
            if (retry != null) {
                if ("WAITING_CONFIRMATION".equalsIgnoreCase(retry.getStatus())
                        || "ABANDONED".equalsIgnoreCase(retry.getStatus())) {
                    skipped++;
                    continue;
                }
                if (retry.getNextRetryAt() != null
                        && retry.getNextRetryAt().after(now)
                        && ("PENDING".equalsIgnoreCase(retry.getStatus()) || "RETRYING".equalsIgnoreCase(retry.getStatus()))) {
                    skipped++;
                    continue;
                }
            }

            attempted++;
            processedPositionIds.add(position.getId());
            if (paper) {
                try {
                    accountingService.claimResolvedPosition(position.getId(), false);
                    markRetryResolved(runId, position.getId(), "Paper auto-claim completed.", null);
                    claimed++;
                    claimedPositionIds.add(position.getId());
                } catch (RuntimeException e) {
                    failed++;
                    failures.add(claimFailure(position, e.getMessage()));
                    upsertRetryFailure(runId, position, e.getMessage(), true, null);
                }
                continue;
            }

            SmartWalletOnChainClaimGateway.ClaimSubmission submission = onChainClaimGateway.submitClaim(position);
            if (submission == null) {
                failed++;
                String message = "Live claim gateway returned no result.";
                failures.add(claimFailure(position, message));
                upsertRetryFailure(runId, position, message, true, null);
                continue;
            }
            switch (submission.status()) {
                case CLAIMED -> {
                    accountingService.claimResolvedPosition(position.getId(), true);
                    markRetryResolved(runId, position.getId(), submission.message(), submission.txHash());
                    claimed++;
                    claimedPositionIds.add(position.getId());
                    addNote(notes, "Live claim completed for position " + position.getId() + ".");
                }
                case SUBMITTED, MANUAL_CONFIRM_REQUIRED -> {
                    upsertWaitingConfirmation(runId, position, submission.message(), submission.txHash());
                    waitingConfirmation++;
                    addNote(notes, "Live claim pending confirmation for position " + position.getId() + ".");
                }
                case PERMANENT_FAILURE -> {
                    failed++;
                    failures.add(claimFailure(position, submission.message()));
                    upsertRetryFailure(runId, position, submission.message(), false, submission.txHash());
                }
                case RETRYABLE_FAILURE -> {
                    failed++;
                    failures.add(claimFailure(position, submission.message()));
                    upsertRetryFailure(runId, position, submission.message(), true, submission.txHash());
                }
            }
        }

        if (notes.size() > 10) {
            notes = new ArrayList<>(notes.subList(0, 10));
        }
        return SmartWalletClaimSummary.builder()
                .scannedPositions(positions.size())
                .attemptedClaims(attempted)
                .claimedPositions(claimed)
                .waitingConfirmationPositions(waitingConfirmation)
                .failedPositions(failed)
                .skippedPositions(skipped)
                .notes(notes)
                .failures(failures)
                .processedPositionIds(processedPositionIds)
                .claimedPositionIds(claimedPositionIds)
                .build();
    }

    private Map<Long, SmartWalletClaimRetryEntity> loadRetryMap(List<SmartWalletPositionEntity> positions) {
        List<Long> positionIds = positions.stream()
                .map(SmartWalletPositionEntity::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (positionIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, SmartWalletClaimRetryEntity> result = new LinkedHashMap<>();
        List<SmartWalletClaimRetryEntity> retries = claimRetryMapper.selectList(new LambdaQueryWrapper<SmartWalletClaimRetryEntity>()
                .in(SmartWalletClaimRetryEntity::getPositionId, positionIds));
        for (SmartWalletClaimRetryEntity retry : retries) {
            result.put(retry.getPositionId(), retry);
        }
        return result;
    }

    private void completeRun(SmartWalletClaimRunEntity run,
                             SmartWalletClaimSummary summary,
                             String status,
                             String errorMessage) {
        invocationCount.incrementAndGet();
        lastRunAt = new Date();
        lastSummary = summary;
        run.setStatus(status)
                .setScannedPositions(summary.getScannedPositions())
                .setAttemptedClaims(summary.getAttemptedClaims())
                .setClaimedPositions(summary.getClaimedPositions())
                .setWaitingConfirmationPositions(summary.getWaitingConfirmationPositions())
                .setFailedPositions(summary.getFailedPositions())
                .setSkippedPositions(summary.getSkippedPositions())
                .setNotes(writeNotes(summary.getNotes()))
                .setErrorMessage(errorMessage)
                .setFinishedAt(lastRunAt)
                .setUpdatedAt(lastRunAt);
        claimRunMapper.updateById(run);
    }

    private void failRun(SmartWalletClaimRunEntity run, RuntimeException e) {
        Date now = new Date();
        run.setStatus("FAILED")
                .setErrorMessage(e.getMessage())
                .setFinishedAt(now)
                .setUpdatedAt(now);
        claimRunMapper.updateById(run);
        lastRunAt = now;
    }

    private void markRetryResolved(Long runId, Long positionId, String message, String txHash) {
        if (positionId == null) {
            return;
        }
        Date now = new Date();
        SmartWalletClaimRetryEntity retry = claimRetryMapper.selectOne(new LambdaQueryWrapper<SmartWalletClaimRetryEntity>()
                .eq(SmartWalletClaimRetryEntity::getPositionId, positionId)
                .last("limit 1"));
        if (retry == null) {
            return;
        }
        retry.setLatestRunId(runId)
                .setStatus("RESOLVED")
                .setLastErrorMessage(message)
                .setNextRetryAt(now)
                .setTxHash(StringUtils.hasText(txHash) ? txHash : retry.getTxHash())
                .setUpdatedAt(now);
        claimRetryMapper.updateById(retry);
    }

    private void upsertWaitingConfirmation(Long runId,
                                           SmartWalletPositionEntity position,
                                           String message,
                                           String txHash) {
        Date now = new Date();
        // Populate position with market info to access delegate getters
        marketInfoService.populate(position);
        SmartWalletClaimRetryEntity retry = claimRetryMapper.selectOne(new LambdaQueryWrapper<SmartWalletClaimRetryEntity>()
                .eq(SmartWalletClaimRetryEntity::getPositionId, position.getId())
                .last("limit 1"));
        if (retry == null) {
            retry = new SmartWalletClaimRetryEntity()
                    .setPositionId(position.getId())
                    .setCreatedAt(now)
                    .setFailureCount(0);
        }
        retry.setExecutionAddress(position.getExecutionAddress())
                .setMarketId(position.getMarketId())
                .setQuestion(position.getQuestion())
                .setLatestRunId(runId)
                .setLastErrorMessage(message)
                .setLastFailedAt(now)
                .setNextRetryAt(now)
                .setStatus("WAITING_CONFIRMATION")
                .setTxHash(txHash)
                .setUpdatedAt(now);
        if (retry.getId() == null) {
            claimRetryMapper.insert(retry);
        } else {
            claimRetryMapper.updateById(retry);
        }
    }

    private void upsertRetryFailure(Long runId,
                                    SmartWalletPositionEntity position,
                                    String message,
                                    boolean retryable,
                                    String txHash) {
        Date now = new Date();
        // Populate position with market info to access delegate getters
        marketInfoService.populate(position);
        SmartWalletProperties.Claim config = properties.getClaim();
        SmartWalletClaimRetryEntity retry = claimRetryMapper.selectOne(new LambdaQueryWrapper<SmartWalletClaimRetryEntity>()
                .eq(SmartWalletClaimRetryEntity::getPositionId, position.getId())
                .last("limit 1"));
        int failureCount = retry == null || retry.getFailureCount() == null ? 1 : retry.getFailureCount() + 1;
        String nextStatus = !retryable || failureCount >= Math.max(config.getMaxRetryFailures(), 1) ? "ABANDONED" : "PENDING";
        Date nextRetryAt = "ABANDONED".equalsIgnoreCase(nextStatus)
                ? now
                : Date.from(Instant.now().plus(backoffMinutes(failureCount), ChronoUnit.MINUTES));
        if (retry == null) {
            retry = new SmartWalletClaimRetryEntity()
                    .setPositionId(position.getId())
                    .setCreatedAt(now);
        }
        retry.setExecutionAddress(position.getExecutionAddress())
                .setMarketId(position.getMarketId())
                .setQuestion(position.getQuestion())
                .setLatestRunId(runId)
                .setFailureCount(failureCount)
                .setLastErrorMessage(message)
                .setLastFailedAt(now)
                .setNextRetryAt(nextRetryAt)
                .setStatus(nextStatus)
                .setTxHash(StringUtils.hasText(txHash) ? txHash : retry.getTxHash())
                .setUpdatedAt(now);
        if (retry.getId() == null) {
            claimRetryMapper.insert(retry);
        } else {
            claimRetryMapper.updateById(retry);
        }
    }

    private long backoffMinutes(int failureCount) {
        int base = Math.max(properties.getClaim().getRetryBackoffMinutes(), 1);
        long factor = 1L << Math.min(Math.max(failureCount - 1, 0), 4);
        return base * factor;
    }

    private boolean shouldSkipScheduledEmptyRun(List<SmartWalletPositionEntity> positions, String triggerType) {
        return "SCHEDULED".equalsIgnoreCase(triggerType) && (positions == null || positions.isEmpty());
    }

    private boolean isPaperExecutionAddress(String executionAddress) {
        return !StringUtils.hasText(executionAddress) || "paper-book".equalsIgnoreCase(executionAddress);
    }

    private SmartWalletClaimFailure claimFailure(SmartWalletPositionEntity position, String message) {
        return SmartWalletClaimFailure.builder()
                .positionId(position.getId())
                .executionAddress(position.getExecutionAddress())
                .marketId(position.getMarketId())
                .question(position.getQuestion())
                .errorMessage(message)
                .build();
    }

    private void addNote(List<String> notes, String note) {
        if (notes.size() < 10 && StringUtils.hasText(note)) {
            notes.add(note);
        }
    }

    private SmartWalletClaimRunView toRunView(SmartWalletClaimRunEntity run) {
        return SmartWalletClaimRunView.builder()
                .id(run.getId())
                .triggerType(run.getTriggerType())
                .positionLimit(run.getPositionLimit())
                .status(run.getStatus())
                .scannedPositions(run.getScannedPositions())
                .attemptedClaims(run.getAttemptedClaims())
                .claimedPositions(run.getClaimedPositions())
                .waitingConfirmationPositions(run.getWaitingConfirmationPositions())
                .failedPositions(run.getFailedPositions())
                .skippedPositions(run.getSkippedPositions())
                .notes(readNotes(run.getNotes()))
                .errorMessage(run.getErrorMessage())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .build();
    }

    private SmartWalletClaimRetryView toRetryView(SmartWalletClaimRetryEntity retry) {
        return SmartWalletClaimRetryView.builder()
                .id(retry.getId())
                .positionId(retry.getPositionId())
                .executionAddress(retry.getExecutionAddress())
                .marketId(retry.getMarketId())
                .question(retry.getQuestion())
                .latestRunId(retry.getLatestRunId())
                .failureCount(retry.getFailureCount())
                .lastErrorMessage(retry.getLastErrorMessage())
                .lastFailedAt(retry.getLastFailedAt())
                .nextRetryAt(retry.getNextRetryAt())
                .status(retry.getStatus())
                .txHash(retry.getTxHash())
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
