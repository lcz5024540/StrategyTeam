package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionWalletStatus;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfile;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletScanResponse;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SmartWalletFollowService {

    private final SmartWalletProperties properties;
    private final SmartWalletRegistryService walletRegistryService;
    private final SmartWalletProfileStoreService profileStoreService;
    private final PredictionMarketOpportunityService opportunityService;
    private final SmartWalletScoringService scoringService;
    private final SmartWalletLeaderboardStateService leaderboardStateService;
    private final PolymarketExecutionGateway executionGateway;
    private final ExecutionWalletContextLoader executionWalletContextLoader;

    public SmartWalletFollowService(SmartWalletProperties properties,
                                    SmartWalletRegistryService walletRegistryService,
                                    SmartWalletProfileStoreService profileStoreService,
                                    PredictionMarketOpportunityService opportunityService,
                                    SmartWalletScoringService scoringService,
                                    SmartWalletLeaderboardStateService leaderboardStateService,
                                    PolymarketExecutionGateway executionGateway,
                                    ExecutionWalletContextLoader executionWalletContextLoader) {
        this.properties = properties;
        this.walletRegistryService = walletRegistryService;
        this.profileStoreService = profileStoreService;
        this.opportunityService = opportunityService;
        this.scoringService = scoringService;
        this.leaderboardStateService = leaderboardStateService;
        this.executionGateway = executionGateway;
        this.executionWalletContextLoader = executionWalletContextLoader;
    }

    public SmartWalletScanResponse scan(BigDecimal bankrollUsdc) {
        BigDecimal normalizedBankroll = bankrollUsdc == null ? BigDecimal.ZERO : bankrollUsdc.max(BigDecimal.ZERO);
        List<SmartWalletSignal> accepted = new ArrayList<>();
        List<SmartWalletSignal> rejected = new ArrayList<>();

        for (SmartWalletProfile wallet : listWalletsForScan()) {
            BigDecimal walletScore = leaderboardStateService.adjustWalletScore(wallet.getAddress(), scoringService.score(wallet));
            for (PredictionMarketOpportunity market : opportunityService.list()) {
                SmartWalletSignal signal = buildSignal(wallet, market, walletScore, normalizedBankroll);
                if (signal.isExecutable()) {
                    accepted.add(signal);
                } else {
                    rejected.add(signal);
                }
            }
        }

        Comparator<SmartWalletSignal> byScore = Comparator.comparing(SmartWalletSignal::getSignalScore).reversed();
        accepted.sort(byScore);
        rejected.sort(byScore);

        return SmartWalletScanResponse.builder()
                .autoExecutionEnabled(properties.isAutoExecutionEnabled())
                .bankrollUsdc(normalizedBankroll)
                .executableSignals(accepted)
                .rejectedSignals(rejected)
                .notes(buildNotes())
                .build();
    }

    public SmartWalletExecutionResult executeSignal(String signalId, BigDecimal bankrollUsdc, ExecutionMode mode) {
        List<SmartWalletSignal> signals = scan(bankrollUsdc).getExecutableSignals();
        SmartWalletSignal signal = signals.stream()
                .filter(item -> item.getSignalId().equals(signalId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Signal not found or not executable: " + signalId));

        ExecutionMode normalizedMode = mode == null ? ExecutionMode.PAPER : mode;
        if (normalizedMode == ExecutionMode.LIVE && !properties.isAutoExecutionEnabled()) {
            return SmartWalletExecutionResult.builder()
                    .signalId(signalId)
                    .mode(normalizedMode)
                    .success(false)
                    .message("LIVE mode is disabled. Set smart-wallet.auto-execution-enabled=true after checking /strategy/api/smart-wallet/execution-wallet/status.")
                    .build();
        }
        return executionGateway.execute(signal, normalizedMode);
    }

    public List<SmartWalletProfile> listWalletsForScan() {
        Map<String, SmartWalletProfile> combined = new LinkedHashMap<>();
        for (SmartWalletProfileEntity entity : profileStoreService.listWatchEnabled()) {
            SmartWalletProfile runtimeWallet = toRuntimeWallet(entity);
            combined.put(normalizeAddress(runtimeWallet.getAddress()), runtimeWallet);
        }
        for (SmartWalletProfile wallet : walletRegistryService.list()) {
            combined.putIfAbsent(normalizeAddress(wallet.getAddress()), wallet);
        }
        return new ArrayList<>(combined.values());
    }

    public ExecutionWalletStatus executionWalletStatus() {
        return executionWalletContextLoader.getStatus();
    }

    private SmartWalletSignal buildSignal(SmartWalletProfile wallet,
                                          PredictionMarketOpportunity market,
                                          BigDecimal walletScore,
                                          BigDecimal bankrollUsdc) {
        List<String> reasons = new ArrayList<>();
        boolean executable = true;
        BigDecimal walletWinRate = safe(wallet.getWinRate());
        BigDecimal marketLiquidity = safe(market.getLiquidityUsdc());
        BigDecimal marketConviction = safe(market.getConviction());
        BigDecimal marketPrice = safe(market.getPrice());

        if (walletWinRate.compareTo(properties.getMinWinRate()) < 0) {
            executable = false;
            reasons.add("Wallet win rate below threshold");
        } else {
            reasons.add("Wallet win rate passes threshold");
        }

        if (walletScore.compareTo(properties.getMinWalletScore()) < 0) {
            executable = false;
            reasons.add("Wallet quality score below threshold");
        } else {
            reasons.add("Wallet quality score is strong");
        }

        if (marketLiquidity.compareTo(properties.getMinMarketLiquidity()) < 0) {
            executable = false;
            reasons.add("Market liquidity below threshold");
        } else {
            reasons.add("Market liquidity is sufficient");
        }

        List<String> walletTags = normalize(wallet.getTags());
        List<String> marketTags = normalize(market.getTags());
        boolean tagMatched = walletTags.stream().anyMatch(marketTags::contains);
        boolean shouldApplyTagGate = properties.isRequireTagMatch()
                && !walletTags.isEmpty()
                && !marketTags.isEmpty();
        if (shouldApplyTagGate && !tagMatched) {
            executable = false;
            reasons.add("Wallet tags do not match market tags");
        } else if (tagMatched) {
            reasons.add("Wallet expertise matches market tags");
        } else if (properties.isRequireTagMatch() && walletTags.isEmpty()) {
            reasons.add("Wallet tags are unavailable, so tag matching was skipped");
        }

        BigDecimal marketEdge = marketConviction.subtract(marketPrice);
        if (marketEdge.compareTo(new BigDecimal("0.05")) < 0) {
            executable = false;
            reasons.add("Market edge is too thin after price impact buffer");
        } else {
            reasons.add("Market edge is above minimum buffer");
        }

        BigDecimal signalScore = walletScore.multiply(new BigDecimal("0.65"))
                .add(marketConviction.multiply(new BigDecimal("0.35")))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal recommendedStake = bankrollUsdc.multiply(properties.getBankrollUtilizationRate())
                .multiply(signalScore)
                .min(properties.getMaxStakeUsdc())
                .setScale(2, RoundingMode.HALF_UP);

        if (recommendedStake.compareTo(BigDecimal.ZERO) <= 0) {
            executable = false;
            reasons.add("Bankroll is zero or too small for staking");
        }

        return SmartWalletSignal.builder()
                .signalId(buildSignalId(wallet.getAddress(), market.getMarketId()))
                .wallet(wallet)
                .market(market)
                .followOutcome(normalizeOutcome(wallet.getPreferredOutcome(), market.getOutcome()))
                .walletScore(walletScore)
                .signalScore(signalScore)
                .recommendedStakeUsdc(recommendedStake)
                .executable(executable)
                .reasons(reasons)
                .build();
    }

    private String buildSignalId(String walletAddress, String marketId) {
        String seed = walletAddress + "-" + marketId;
        return UUID.nameUUIDFromBytes(seed.getBytes()).toString();
    }

    private String normalizeOutcome(String walletPreferredOutcome, String marketOutcome) {
        String preferred = walletPreferredOutcome == null ? "" : walletPreferredOutcome.trim().toUpperCase(Locale.ROOT);
        String market = marketOutcome == null ? "" : marketOutcome.trim().toUpperCase(Locale.ROOT);
        if ("YES".equals(preferred) || "NO".equals(preferred)) {
            return preferred;
        }
        return market.isEmpty() ? "YES" : market;
    }

    private List<String> normalize(List<String> tags) {
        if (tags == null) {
            return Collections.emptyList();
        }
        return tags.stream()
                .filter(tag -> tag != null && !tag.trim().isEmpty())
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    private List<String> buildNotes() {
        List<String> notes = new ArrayList<>();
        notes.add("This service ranks wallets and markets; it cannot guarantee win rate.");
        notes.add("LIVE execution requires execution-wallet config and the official Polymarket Python SDK bridge.");
        notes.add("Recommended stake is capped by bankroll utilization and max-stake-usdc.");
        return notes;
    }

    private SmartWalletProfile toRuntimeWallet(SmartWalletProfileEntity entity) {
        return SmartWalletProfile.builder()
                .address(entity.getWalletAddress())
                .chain(entity.getChain())
                .alias(entity.getAlias())
                .winRate(entity.getWinRate())
                .recentWinRate7d(entity.getRecentWinRate7d())
                .recentWinRate30d(entity.getRecentWinRate30d())
                .realizedPnlUsdc(entity.getRealizedPnlUsdc())
                .totalTrades(entity.getTotalTrades())
                .recentTradeCount7d(entity.getRecentTradeCount7d())
                .recentTradeCount30d(entity.getRecentTradeCount30d())
                .observeCooldownUntil(entity.getObserveCooldownUntil())
                .observeCooldownReason(entity.getObserveCooldownReason())
                .averageSizeUsdc(entity.getAverageSizeUsdc())
                .averageRoiRate(entity.getAverageRoiRate())
                .maxDrawdownRate(entity.getMaxDrawdownRate())
                .preferredOutcome("YES")
                .tags(List.of())
                .build();
    }

    private String normalizeAddress(String address) {
        if (!StringUtils.hasText(address)) {
            return "";
        }
        return address.trim().toLowerCase(Locale.ROOT);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
