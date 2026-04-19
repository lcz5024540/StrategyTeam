package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.LiveValidationRunSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.LiveValidationStatusView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowTaskView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletLiveValidationService {

    private final SmartWalletProperties properties;
    private final SmartWalletFollowTaskService followTaskService;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletAccountingService accountingService;
    private final PolymarketExecutionGateway executionGateway;
    private final SmartWalletMarketInfoService marketInfoService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Date lastRunAt;
    private volatile LiveValidationRunSummary lastRunSummary;
    private volatile Date suspendedUntil;
    private volatile String suspensionReason;
    private volatile int consecutiveNetworkErrors;
    private final Map<String, Date> unsupportedMarkets = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${smart-wallet.live-validation.fixed-delay-ms:45000}")
    public void scheduledProcess() {
        if (!properties.getLiveValidation().isEnabled()) {
            return;
        }
        processNow();
    }

    public LiveValidationRunSummary processNow() {
        SmartWalletProperties.LiveValidation config = properties.getLiveValidation();
        if (!config.isEnabled()) {
            return LiveValidationRunSummary.builder()
                    .enabled(false)
                    .preset(config.getPreset())
                    .scannedCandidates(0)
                    .executedCount(0)
                    .skippedCount(0)
                    .notes(List.of("Live validation lane is disabled by configuration."))
                    .build();
        }
        if (isSuspended()) {
            return LiveValidationRunSummary.builder()
                    .enabled(true)
                    .preset(config.getPreset())
                    .scannedCandidates(0)
                    .executedCount(0)
                    .skippedCount(0)
                    .notes(List.of("Live validation lane is suspended until " + suspendedUntil + " because: " + suspensionReason))
                    .build();
        }
        if (!running.compareAndSet(false, true)) {
            return LiveValidationRunSummary.builder()
                    .enabled(config.isEnabled())
                    .preset(config.getPreset())
                    .scannedCandidates(0)
                    .executedCount(0)
                    .skippedCount(0)
                    .notes(List.of("Live validation run skipped because another run is already active."))
                    .build();
        }
        try {
            SmartWalletProperties.SignalRisk presetRisk = followTaskService.resolveSignalRiskPreset(config.getPreset());
            List<SmartWalletFollowTaskView> candidates = followTaskService.listSingleWalletOverrideTasks(
                    config.getCandidateScanLimit(),
                    presetRisk,
                    config.getCandidateScanLimit(),
                    config.getPreset());

            int executed = 0;
            int skipped = 0;
            List<String> notes = new ArrayList<>();
            for (SmartWalletFollowTaskView candidate : candidates) {
                if (candidate.getId() == null) {
                    skipped++;
                    continue;
                }
                SmartWalletFollowTaskEntity task = followTaskService.getTask(candidate.getId());
                if (task == null) {
                    skipped++;
                    continue;
                }
                if (!"SKIPPED".equalsIgnoreCase(task.getStatus())) {
                    skipped++;
                    continue;
                }
                if (isUnsupportedMarketCoolingDown(task.getMarketId())) {
                    skipped++;
                    notes.add("Skipped followTaskId=" + task.getId() + " because market " + task.getMarketId() + " is cooling down after an unsupported outcome/token mapping issue.");
                    continue;
                }
                if (Boolean.TRUE.equals(config.isSkipIfPaperPendingExists()) && hasPaperOrLiveOrder(task.getId())) {
                    skipped++;
                    continue;
                }
                if (executed >= safeMaxExecutions(config.getMaxExecutionsPerRun())) {
                    break;
                }
                try {
                    SmartWalletExecutionResult result = executeValidationTask(task, candidate.getAppliedPreset());
                    executed++;
                    consecutiveNetworkErrors = 0;
                    notes.add("Executed LIVE validation for followTaskId=" + task.getId() + " status=" + result.getStatus());
                } catch (Exception ex) {
                    skipped++;
                    if (handleUnsupportedTokenMapping(config, task, ex, notes)) {
                        continue;
                    }
                    if (handleOperationalValidationFailure(config, task, ex, notes)) {
                        if (isSuspended()) {
                            break;
                        }
                        continue;
                    }
                    applyCircuitBreakerIfNeeded(config, ex, notes);
                    notes.add("LIVE validation failed for followTaskId=" + task.getId() + ": " + ex.getMessage());
                    log.error("Failed LIVE validation for follow task {}", task.getId(), ex);
                    if (isSuspended()) {
                        break;
                    }
                }
            }
            lastRunAt = new Date();
            lastRunSummary = LiveValidationRunSummary.builder()
                    .enabled(config.isEnabled())
                    .preset(config.getPreset())
                    .scannedCandidates(candidates.size())
                    .executedCount(executed)
                    .skippedCount(skipped)
                    .notes(notes)
                    .build();
            return lastRunSummary;
        } finally {
            running.set(false);
        }
    }

    public LiveValidationStatusView status() {
        SmartWalletProperties.LiveValidation config = properties.getLiveValidation();
        return LiveValidationStatusView.builder()
                .enabled(config.isEnabled())
                .preset(config.getPreset())
                .candidateScanLimit(config.getCandidateScanLimit())
                .maxExecutionsPerRun(config.getMaxExecutionsPerRun())
                .skipIfPaperPendingExists(config.isSkipIfPaperPendingExists())
                .suspended(isSuspended())
                .suspendedUntil(suspendedUntil)
                .suspensionReason(suspensionReason)
                .running(running.get())
                .lastRunAt(lastRunAt)
                .lastRunSummary(lastRunSummary)
                .build();
    }

    private SmartWalletExecutionResult executeValidationTask(SmartWalletFollowTaskEntity task, String appliedPreset) {
        marketInfoService.populate(task);
        SignalRiskDecision decision = followTaskService.evaluateTask(task.getId(), followTaskService.resolveSignalRiskPreset(appliedPreset));
        if (!decision.isAccepted()) {
            throw new IllegalStateException("Task is no longer executable for LIVE validation.");
        }
        SmartWalletSignal signal = SmartWalletSignal.builder()
                .signalId("live-validation-" + task.getId())
                .market(PredictionMarketOpportunity.builder()
                        .marketId(task.getMarketId())
                        .question(task.getQuestion())
                        .outcome(task.getOutcome())
                        .price(task.getTriggerPrice())
                        .build())
                .followOutcome(task.getOutcome())
                .recommendedStakeUsdc(resolveStake(task, decision))
                .build();
        SmartWalletExecutionResult result = executionGateway.execute(signal, ExecutionMode.LIVE);
        SmartWalletFollowTaskEntity syntheticTask = new SmartWalletFollowTaskEntity()
                .setId(task.getId())
                .setWalletAddress(task.getWalletAddress())
                .setSourceEventId(task.getSourceEventId())
                .setMarketInfoId(task.getMarketInfoId())
                .setMarketInfo(task.getMarketInfo())
                .setSide(task.getSide())
                .setTriggerPrice(task.getTriggerPrice())
                .setTriggerSizeUsdc(task.getTriggerSizeUsdc())
                .setRecommendedStakeUsdc(resolveStake(task, decision))
                .setStatus(task.getStatus())
                .setExecutionMode("LIVE_VALIDATION")
                .setExecutedAt(new Date())
                .setCreatedAt(task.getCreatedAt())
                .setUpdatedAt(new Date());
        accountingService.recordExecution(syntheticTask, result);
        return result;
    }

    private boolean hasPaperOrLiveOrder(Long followTaskId) {
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getFollowTaskId, followTaskId)
                .in(SmartWalletOrderEntity::getExecutionMode, List.of("LIVE_VALIDATION", "LIVE", "PAPER")));
        return count != null && count > 0;
    }

    private BigDecimal resolveStake(SmartWalletFollowTaskEntity task, SignalRiskDecision decision) {
        if (decision != null && decision.getRecommendedStakeUsdc() != null && decision.getRecommendedStakeUsdc().compareTo(BigDecimal.ZERO) > 0) {
            return decision.getRecommendedStakeUsdc();
        }
        if (task.getRecommendedStakeUsdc() != null && task.getRecommendedStakeUsdc().compareTo(BigDecimal.ZERO) > 0) {
            return task.getRecommendedStakeUsdc();
        }
        return BigDecimal.ZERO;
    }

    private int safeMaxExecutions(Integer configured) {
        if (configured == null || configured <= 0) {
            return 1;
        }
        return Math.min(configured, 10);
    }

    private boolean isSuspended() {
        return suspendedUntil != null && suspendedUntil.after(new Date());
    }

    private boolean isUnsupportedMarketCoolingDown(String marketId) {
        if (marketId == null) {
            return false;
        }
        Date until = unsupportedMarkets.get(marketId);
        if (until == null) {
            return false;
        }
        if (until.after(new Date())) {
            return true;
        }
        unsupportedMarkets.remove(marketId);
        return false;
    }

    private boolean handleUnsupportedTokenMapping(SmartWalletProperties.LiveValidation config,
                                                  SmartWalletFollowTaskEntity task,
                                                  Exception ex,
                                                  List<String> notes) {
        String normalized = collectExceptionText(ex);
        if (!(ex instanceof IllegalStateException) || normalized.isBlank()) {
            return false;
        }
        if (!normalized.contains("token id not found")) {
            return false;
        }
        int cooldownMinutes = config.getUnsupportedMarketCooldownMinutes() == null
                || config.getUnsupportedMarketCooldownMinutes() <= 0
                ? 360
                : config.getUnsupportedMarketCooldownMinutes();
        unsupportedMarkets.put(task.getMarketId(), new Date(System.currentTimeMillis() + cooldownMinutes * 60_000L));
        notes.add("Skipped followTaskId=" + task.getId() + " because market outcome labels are not executable yet. Market cooldown applied for " + cooldownMinutes + " minutes.");
        log.warn("Skip LIVE validation for follow task {} because token mapping is unsupported for market {}: {}",
                task.getId(), task.getMarketId(), ex.getMessage());
        return true;
    }

    private boolean handleOperationalValidationFailure(SmartWalletProperties.LiveValidation config,
                                                       SmartWalletFollowTaskEntity task,
                                                       Exception ex,
                                                       List<String> notes) {
        String normalized = collectExceptionText(ex);
        if (!normalized.contains("request exception")
                && !normalized.contains("unexpected_eof_while_reading")
                && !normalized.contains("ssl")
                && !normalized.contains("sslhandshakeexception")
                && !normalized.contains("ssl peer shut down incorrectly")
                && !normalized.contains("remote host terminated the handshake")
                && !normalized.contains("connecterror")
                && !normalized.contains("failed to request polymarket api")
                && !normalized.contains("timed out")
                && !normalized.contains("timeout")
                && !normalized.contains("interrupted while waiting")
                && !normalized.contains("trading restricted in your region")
                && !normalized.contains("geoblock")) {
            return false;
        }
        applyCircuitBreakerIfNeeded(config, ex, notes);
        notes.add("Skipped followTaskId=" + task.getId() + " because LIVE validation hit an external Polymarket network or regional restriction.");
        log.warn("Skip LIVE validation for follow task {} due to external Polymarket connectivity/restriction issue: {}",
                task.getId(), ex.getMessage());
        return true;
    }

    private void applyCircuitBreakerIfNeeded(SmartWalletProperties.LiveValidation config,
                                             Exception ex,
                                             List<String> notes) {
        String normalized = collectExceptionText(ex);
        if (normalized.contains("trading restricted in your region") || normalized.contains("geoblock")) {
            suspendForMinutes(config.getGeoblockCooldownMinutes(), "Polymarket geoblock / regional restriction detected.");
            notes.add("Live validation suspended because Polymarket returned a geoblock response.");
            return;
        }
        if (normalized.contains("unexpected_eof_while_reading")
                || normalized.contains("request exception")
                || normalized.contains("ssl")
                || normalized.contains("sslhandshakeexception")
                || normalized.contains("ssl peer shut down incorrectly")
                || normalized.contains("remote host terminated the handshake")
                || normalized.contains("connecterror")) {
            consecutiveNetworkErrors++;
            if (consecutiveNetworkErrors >= safeThreshold(config.getNetworkErrorThreshold())) {
                suspendForMinutes(config.getNetworkCooldownMinutes(), "Repeated LIVE validation network/TLS failures.");
                notes.add("Live validation suspended because repeated TLS/network failures crossed threshold.");
            }
        }
    }

    private void suspendForMinutes(Integer minutes, String reason) {
        int safeMinutes = minutes == null || minutes <= 0 ? 15 : minutes;
        suspendedUntil = new Date(System.currentTimeMillis() + safeMinutes * 60_000L);
        suspensionReason = reason;
        consecutiveNetworkErrors = 0;
    }

    private int safeThreshold(Integer threshold) {
        if (threshold == null || threshold <= 0) {
            return 2;
        }
        return Math.min(threshold, 10);
    }

    private String collectExceptionText(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return builder.toString().toLowerCase();
    }
}
