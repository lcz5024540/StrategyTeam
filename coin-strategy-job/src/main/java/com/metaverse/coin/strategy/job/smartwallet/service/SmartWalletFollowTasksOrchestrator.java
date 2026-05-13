package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.execution.application.ExecutionApplicationService;
import com.metaverse.coin.strategy.job.follow.application.FollowTaskApplicationService;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.FollowTradeAttributionView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletBlockerStatView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowCoverageReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowTaskView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayBacktestRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayBacktestResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayConcentrationSummaryResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletRiskSimulationRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletRiskSimulationResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把原 SmartWalletController 中 follow-tasks/follow-trades 各端点的 HTTP 适配代码沉到 job 内部，
 * 让 {@code StrategyManualTriggerConsumer} 可以以稳定的服务接口调用。
 *
 * 业务核心仍在 {@link SmartWalletFollowTaskService}、{@link FollowTaskApplicationService}、
 * {@link SmartWalletReplayBacktestService} 等，本类只做 thin orchestrator：
 * <ol>
 *   <li>归一/补齐参数（preset / 默认值）</li>
 *   <li>转发到对应 service 方法</li>
 * </ol>
 */
@Service
public class SmartWalletFollowTasksOrchestrator {

    private final FollowTaskApplicationService followTaskApplicationService;
    private final SmartWalletFollowTaskService followTaskService;
    private final SmartWalletReplayBacktestService replayBacktestService;
    private final FollowTradeAttributionService followTradeAttributionService;
    private final PolymarketClobExecutionService clobExecutionService;
    private final ExecutionApplicationService executionApplicationService;
    private final SmartWalletProperties properties;

    public SmartWalletFollowTasksOrchestrator(FollowTaskApplicationService followTaskApplicationService,
                                              SmartWalletFollowTaskService followTaskService,
                                              SmartWalletReplayBacktestService replayBacktestService,
                                              FollowTradeAttributionService followTradeAttributionService,
                                              PolymarketClobExecutionService clobExecutionService,
                                              ExecutionApplicationService executionApplicationService,
                                              SmartWalletProperties properties) {
        this.followTaskApplicationService = followTaskApplicationService;
        this.followTaskService = followTaskService;
        this.replayBacktestService = replayBacktestService;
        this.followTradeAttributionService = followTradeAttributionService;
        this.clobExecutionService = clobExecutionService;
        this.executionApplicationService = executionApplicationService;
        this.properties = properties;
    }

    // ── A 类读接口 ────────────────────────────────────────────────────

    public List<SmartWalletFollowTaskView> listExecutable(Integer limit, Integer scanLimit) {
        return followTaskApplicationService.listExecutableTasks(safeLimit(limit, 50), scanLimit);
    }

    public List<SmartWalletFollowTaskView> listManualAllowlist(Integer limit, Boolean includeBlocked) {
        return followTaskService.listManualAllowlistTasks(safeLimit(limit, 50), includeBlocked);
    }

    public List<SmartWalletFollowTaskView> listOpportunities(Integer limit, Integer maxBlockers, Boolean includeExecutable) {
        return followTaskService.listOpportunityTasks(
                safeLimit(limit, 20),
                maxBlockers == null ? 3 : maxBlockers,
                Boolean.TRUE.equals(includeExecutable));
    }

    public List<SmartWalletFollowCoverageReportView> coverageReport(String walletAddress, Integer limit) {
        return followTaskService.followCoverageReports(walletAddress, safeLimit(limit, 5000));
    }

    public List<FollowTradeAttributionView> attribution(String groupBy, Integer limit) {
        return followTradeAttributionService.buildAttribution(
                groupBy == null || groupBy.isBlank() ? "EXIT_REASON" : groupBy,
                limit == null ? 500 : limit);
    }

    public List<SmartWalletFollowTaskView> listSingleWalletOverride(SmartWalletRiskSimulationRequest request,
                                                                    Integer limit,
                                                                    String preset) {
        if (request == null) request = new SmartWalletRiskSimulationRequest();
        applySingleWalletOverridePreset(request, preset);
        return followTaskService.listSingleWalletOverrideTasks(
                safeLimit(limit, 20),
                followTaskService.buildSimulationRisk(request),
                request.getTaskLimit(),
                preset);
    }

    public List<SmartWalletBlockerStatView> blockerStats(Integer taskLimit) {
        return followTaskApplicationService.blockerStats(safeLimit(taskLimit, 200));
    }

    // ── B 类写接口 ────────────────────────────────────────────────────

    public int reevaluateDeferred(Integer limit) {
        return followTaskService.reevaluateDeferredCandidates(limit == null ? 50 : limit);
    }

    public SmartWalletFollowTaskService.IntelligenceReplaySummary replayRecentIntelligence(Integer limit, boolean includeProcessed) {
        return followTaskService.replayRecentIntelligenceCandidates(limit == null ? 100 : limit, includeProcessed);
    }

    public SmartWalletRiskSimulationResult simulateRisk(SmartWalletRiskSimulationRequest request) {
        return followTaskApplicationService.simulateRisk(request);
    }

    public SmartWalletReplayBacktestResult replayBacktest(SmartWalletReplayBacktestRequest request) {
        return replayBacktestService.replay(request);
    }

    public SmartWalletReplayConcentrationSummaryResult replayConcentrationSummary(SmartWalletReplayBacktestRequest request) {
        return replayBacktestService.replayConcentrationSummary(request);
    }

    public int processPendingTasksNow() {
        return followTaskApplicationService.processPendingTasksNow();
    }

    public java.util.Map<String, Object> dryRunFollowTask(Long followTaskId) {
        return clobExecutionService.dryRunFollowTask(followTaskId);
    }

    public SmartWalletExecutionResult executeFollowTaskLive(Long followTaskId) {
        return executionApplicationService.executeFollowTask(followTaskId);
    }

    // ── 工具方法 ────────────────────────────────────────────────────

    private static Integer safeLimit(Integer raw, int defaultValue) {
        if (raw == null || raw <= 0) return defaultValue;
        return raw;
    }

    /**
     * 与原 SmartWalletController.applySingleWalletOverridePreset 行为一致；保留在 job 端以避免 web 依赖
     * SmartWalletProperties.SignalRisk。
     */
    private void applySingleWalletOverridePreset(SmartWalletRiskSimulationRequest request, String preset) {
        if (preset == null || preset.isBlank()) {
            return;
        }
        SmartWalletProperties.SignalRisk presetRisk = properties.getSignalRiskPresets().get(preset);
        if (presetRisk == null) {
            return;
        }
        if (request.getMaxSignalAgeSeconds() == null) {
            request.setMaxSignalAgeSeconds(presetRisk.getMaxSignalAgeSeconds());
        }
        if (request.getPolicyTag() == null) {
            request.setPolicyTag(presetRisk.getPolicyTag());
        }
        if (request.getMinDistinctWallets() == null) {
            request.setMinDistinctWallets(presetRisk.getMinDistinctWallets());
        }
        if (request.getMinDistinctEntities() == null) {
            request.setMinDistinctEntities(presetRisk.getMinDistinctEntities());
        }
        if (request.getMinConsensusScore() == null) {
            request.setMinConsensusScore(presetRisk.getMinConsensusScore());
        }
        if (request.getQualityWeightedConsensusEnabled() == null) {
            request.setQualityWeightedConsensusEnabled(presetRisk.isQualityWeightedConsensusEnabled());
        }
        if (request.getEnableSingleWalletOverrideCanary() == null) {
            request.setEnableSingleWalletOverrideCanary(presetRisk.isEnableSingleWalletOverrideCanary());
        }
        if (request.getSingleWalletOverrideCanaryRate() == null) {
            request.setSingleWalletOverrideCanaryRate(presetRisk.getSingleWalletOverrideCanaryRate());
        }
        if (request.getSingleWalletOverrideMinWalletScore() == null) {
            request.setSingleWalletOverrideMinWalletScore(presetRisk.getSingleWalletOverrideMinWalletScore());
        }
        if (request.getSingleWalletOverrideMinWinRate() == null) {
            request.setSingleWalletOverrideMinWinRate(presetRisk.getSingleWalletOverrideMinWinRate());
        }
        if (request.getSingleWalletOverrideMinTrades() == null) {
            request.setSingleWalletOverrideMinTrades(presetRisk.getSingleWalletOverrideMinTrades());
        }
        if (request.getSingleWalletOverrideMinRecentTrades7d() == null) {
            request.setSingleWalletOverrideMinRecentTrades7d(presetRisk.getSingleWalletOverrideMinRecentTrades7d());
        }
        if (request.getSingleWalletOverrideMinRecentWinRate7d() == null) {
            request.setSingleWalletOverrideMinRecentWinRate7d(presetRisk.getSingleWalletOverrideMinRecentWinRate7d());
        }
        if (request.getSingleWalletOverrideMinReplicabilityScore() == null) {
            request.setSingleWalletOverrideMinReplicabilityScore(presetRisk.getSingleWalletOverrideMinReplicabilityScore());
        }
        if (request.getSingleWalletOverrideMinQualityScore() == null) {
            request.setSingleWalletOverrideMinQualityScore(presetRisk.getSingleWalletOverrideMinQualityScore());
        }
        if (request.getSingleWalletOverrideMinTriggerSizeUsdc() == null) {
            request.setSingleWalletOverrideMinTriggerSizeUsdc(presetRisk.getSingleWalletOverrideMinTriggerSizeUsdc());
        }
        if (request.getSingleWalletOverrideMinRecommendedStakeUsdc() == null) {
            request.setSingleWalletOverrideMinRecommendedStakeUsdc(presetRisk.getSingleWalletOverrideMinRecommendedStakeUsdc());
        }
        if (request.getTaskLimit() == null) {
            request.setTaskLimit(120);
        }
    }

}
