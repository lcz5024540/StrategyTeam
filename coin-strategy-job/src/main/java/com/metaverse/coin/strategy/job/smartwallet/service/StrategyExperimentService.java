package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyExperimentMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyDecisionAuditMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyExperimentEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyDecisionAuditEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayBacktestRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayBacktestResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayStrategyMetrics;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayWindowResult;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyExperimentFoldResult;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyExperimentResult;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyExperimentSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StrategyExperimentService {

    public static final String STRATEGY_KEY = "POLYMARKET_COPY";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_REJECTED_DATA_QUALITY = "REJECTED_DATA_QUALITY";
    private static final BigDecimal MIN_FEATURE_COVERAGE = new BigDecimal("0.98");
    private static final BigDecimal MIN_LABEL_COVERAGE = new BigDecimal("0.95");

    private final StrategyExperimentMapper experimentMapper;
    private final StrategyDecisionAuditMapper decisionAuditMapper;
    private final SmartWalletReplayBacktestService replayService;
    private final ObjectMapper objectMapper;

    public StrategyExperimentResult execute(StrategyExperimentSpec spec) {
        validate(spec);
        String experimentId = UUID.randomUUID().toString();
        Date startedAt = new Date();
        StrategyExperimentEntity entity = new StrategyExperimentEntity()
                .setId(experimentId)
                .setStrategyKey(STRATEGY_KEY)
                .setDatasetVersion(spec.getDatasetVersion())
                .setFeatureVersion(spec.getFeatureVersion())
                .setModelVersion(spec.getModelVersion())
                .setChampionPolicyTag(spec.getChampionPolicy().getPolicyTag())
                .setCandidatePolicyJson(json(spec.getCandidatePolicy()))
                .setCostScenarioJson(json(spec.getCostScenario()))
                .setRandomSeed(spec.getRandomSeed())
                .setStatus("RUNNING")
                .setSpecJson(json(spec))
                .setCreatedAt(startedAt)
                .setStartedAt(startedAt);
        experimentMapper.insert(entity);

        List<StrategyExperimentFoldResult> folds = new ArrayList<>();
        List<String> gateFailures = new ArrayList<>();
        for (int index = 0; index < spec.getFoldCount(); index++) {
            int reverseIndex = spec.getFoldCount() - index - 1;
            Date testEnd = Date.from(spec.getAsOf().toInstant().minus((long) reverseIndex * spec.getTestDays(), ChronoUnit.DAYS));
            Date testStart = Date.from(testEnd.toInstant().minus(spec.getTestDays(), ChronoUnit.DAYS));
            Date validationEnd = Date.from(testStart.toInstant().minus(spec.getEmbargoDays(), ChronoUnit.DAYS));
            Date validationStart = Date.from(validationEnd.toInstant().minus(spec.getValidationDays(), ChronoUnit.DAYS));
            Date trainEnd = Date.from(validationStart.toInstant().minus(spec.getEmbargoDays(), ChronoUnit.DAYS));
            Date trainStart = Date.from(trainEnd.toInstant().minus(spec.getTrainDays(), ChronoUnit.DAYS));

            SmartWalletReplayBacktestRequest replayRequest = new SmartWalletReplayBacktestRequest();
            replayRequest.setWindowDays(List.of(spec.getTestDays()));
            replayRequest.setMaxEventsPerWindow(spec.getMaxEventsPerFold());
            replayRequest.setDatasetVersion(spec.getDatasetVersion());
            replayRequest.setStrictPointInTime(true);
            replayRequest.setRandomSeed(spec.getRandomSeed() + index);
            replayRequest.setAsOf(testEnd);
            SmartWalletReplayBacktestResult replay = replayService.replayWithPolicies(
                    replayRequest, spec.getChampionPolicy(), spec.getCandidatePolicy(),
                    (event, snapshot, lane, decision) -> decisionAuditMapper.insert(
                            new StrategyDecisionAuditEntity()
                                    .setExperimentId(experimentId)
                                    .setSourceEventId(event.getId())
                                    .setLane(lane)
                                    .setDecisionAt(event.getOpenedAt())
                                    .setFeatureSnapshotId(snapshot == null ? null : snapshot.getId())
                                    .setModelVersion(spec.getModelVersion())
                                    .setAccepted(decision.isAccepted())
                                    .setRecommendedStakeUsdc(decision.getRecommendedStakeUsdc())
                                    .setPWin(decision.getCalibratedFollowProbability())
                                    .setEstimatedNetEvUsdc(decision.getEstimatedNetProfitabilityUsdc())
                                    .setReasonJson(json(decision.getReasons()))
                                    .setCreatedAt(new Date())));
            SmartWalletReplayWindowResult window = replay.getWindows().get(0);
            collectDataQualityFailures(index, window, gateFailures);
            folds.add(StrategyExperimentFoldResult.builder()
                    .fold(index + 1)
                    .trainStart(trainStart)
                    .trainEnd(trainEnd)
                    .validationStart(validationStart)
                    .validationEnd(validationEnd)
                    .testStart(testStart)
                    .testEnd(testEnd)
                    .replay(window)
                    .build());
        }

        String status = gateFailures.isEmpty() ? STATUS_COMPLETED : STATUS_REJECTED_DATA_QUALITY;
        String resultHash = sha256(json(folds));
        StrategyExperimentResult result = StrategyExperimentResult.builder()
                .experimentId(experimentId)
                .status(status)
                .resultHash(resultHash)
                .generatedAt(new Date())
                .spec(spec)
                .gateFailures(gateFailures)
                .folds(folds)
                .build();
        entity.setStatus(status)
                .setResultJson(json(result))
                .setResultHash(resultHash)
                .setCompletedAt(new Date());
        experimentMapper.updateById(entity);
        return result;
    }

    public StrategyExperimentResult get(String experimentId) {
        StrategyExperimentEntity entity = experimentMapper.selectById(experimentId);
        if (entity == null || !StringUtils.hasText(entity.getResultJson())) {
            return null;
        }
        try {
            return objectMapper.readValue(entity.getResultJson(), StrategyExperimentResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid persisted experiment result " + experimentId, e);
        }
    }

    private void collectDataQualityFailures(int fold, SmartWalletReplayWindowResult window, List<String> failures) {
        if (window.getPointInTimeSnapshotCoverageRate() == null
                || window.getPointInTimeSnapshotCoverageRate().compareTo(MIN_FEATURE_COVERAGE) < 0) {
            failures.add("fold=" + (fold + 1) + ":POINT_IN_TIME_COVERAGE_BELOW_98_PERCENT");
        }
        checkLabelCoverage(fold, "champion", window.getBaseline(), failures);
        checkLabelCoverage(fold, "challenger", window.getCandidate(), failures);
    }

    private void checkLabelCoverage(int fold, String lane, SmartWalletReplayStrategyMetrics metrics, List<String> failures) {
        if (metrics.getAcceptedEvents() != null && metrics.getAcceptedEvents() > 0
                && (metrics.getLabelCoverageRate() == null
                || metrics.getLabelCoverageRate().compareTo(MIN_LABEL_COVERAGE) < 0)) {
            failures.add("fold=" + (fold + 1) + ":" + lane + ":LABEL_COVERAGE_BELOW_95_PERCENT");
        }
    }

    private void validate(StrategyExperimentSpec spec) {
        if (spec == null || spec.getAsOf() == null || !StringUtils.hasText(spec.getDatasetVersion())
                || !StringUtils.hasText(spec.getFeatureVersion()) || !StringUtils.hasText(spec.getModelVersion())
                || spec.getChampionPolicy() == null || spec.getCandidatePolicy() == null) {
            throw new IllegalArgumentException("dataset, feature, model, asOf, championPolicy and candidatePolicy are required");
        }
        if (spec.getTrainDays() == null || spec.getTrainDays() < 1
                || spec.getValidationDays() == null || spec.getValidationDays() < 1
                || spec.getTestDays() == null || spec.getTestDays() < 1
                || spec.getEmbargoDays() == null || spec.getEmbargoDays() < 7
                || spec.getFoldCount() == null || spec.getFoldCount() < 1 || spec.getFoldCount() > 24) {
            throw new IllegalArgumentException("invalid walk-forward window configuration");
        }
        if (!StringUtils.hasText(spec.getChampionPolicy().getPolicyTag())
                || !StringUtils.hasText(spec.getCandidatePolicy().getPolicyTag())) {
            throw new IllegalArgumentException("both policies require immutable policyTag values");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("experiment payload is not serializable", e);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
