package com.metaverse.coin.strategy.job.risk.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyRuntimeConfigMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyRuntimeConfigEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ResetStrategyRuntimeConfigRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyRuntimeConfigCatalogView;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyRuntimeConfigView;
import com.metaverse.coin.strategy.job.smartwallet.model.UpdateStrategyRuntimeConfigRequest;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyRuntimeConfigService {

    public static final String POLYMARKET_COPY = "POLYMARKET_COPY";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SmartWalletProperties properties;
    private final StrategyRuntimeConfigMapper mapper;
    private final ObjectMapper objectMapper;
    private final Map<String, RuntimeConfigDefinition> definitions = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        registerDefinitions();
        try {
            applyPersistedOverrides();
        } catch (Exception e) {
            log.warn("[StrategyRuntimeConfig] skip persisted overrides because runtime config table is unavailable: {}", e.getMessage());
        }
    }

    public StrategyRuntimeConfigCatalogView catalog(String strategyCode) {
        ensureSupported(strategyCode);
        return StrategyRuntimeConfigCatalogView.builder()
                .strategyCode(POLYMARKET_COPY)
                .groups(groupViews())
                .items(current(strategyCode))
                .build();
    }

    public List<StrategyRuntimeConfigView> current(String strategyCode) {
        ensureSupported(strategyCode);
        Map<String, StrategyRuntimeConfigEntity> persisted = persistedByKey();
        return definitions.values().stream()
                .map(definition -> toView(definition, persisted.get(definition.key())))
                .toList();
    }

    public List<StrategyRuntimeConfigView> update(String strategyCode, UpdateStrategyRuntimeConfigRequest request) {
        ensureSupported(strategyCode);
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            return current(strategyCode);
        }
        Map<String, StrategyRuntimeConfigEntity> persisted = persistedByKey();
        for (UpdateStrategyRuntimeConfigRequest.Item item : request.getItems()) {
            if (item == null || !StringUtils.hasText(item.getKey())) {
                continue;
            }
            RuntimeConfigDefinition definition = definitions.get(item.getKey());
            if (definition == null) {
                throw new IllegalArgumentException("Unsupported runtime config key: " + item.getKey());
            }
            if (!definition.editable()) {
                throw new IllegalArgumentException("Runtime config key is read-only: " + item.getKey());
            }
            String normalized = normalizeAndValidate(definition, item.getValue());
            apply(definition, normalized);
            upsert(definition, normalized, request.getUpdatedBy(), persisted.get(definition.key()));
        }
        return current(strategyCode);
    }

    public List<StrategyRuntimeConfigView> reset(String strategyCode, ResetStrategyRuntimeConfigRequest request) {
        ensureSupported(strategyCode);
        List<String> keys = request == null || request.getKeys() == null ? List.of() : request.getKeys();
        List<String> resetKeys = keys.isEmpty() ? new ArrayList<>(definitions.keySet()) : keys;
        for (String key : resetKeys) {
            RuntimeConfigDefinition definition = definitions.get(key);
            if (definition == null) {
                throw new IllegalArgumentException("Unsupported runtime config key: " + key);
            }
            apply(definition, definition.defaultValue());
            mapper.delete(new LambdaQueryWrapper<StrategyRuntimeConfigEntity>()
                    .eq(StrategyRuntimeConfigEntity::getStrategyCode, POLYMARKET_COPY)
                    .eq(StrategyRuntimeConfigEntity::getConfigKey, key));
        }
        return current(strategyCode);
    }

    public void persistBaselineConfig(String updatedBy) {
        for (RuntimeConfigDefinition definition : definitions.values()) {
            if (!definition.key().startsWith("smart-wallet.baseline-model.")
                    && !definition.key().startsWith("smart-wallet.model-calibration.")) {
                continue;
            }
            String value = definition.reader().apply(properties);
            upsert(definition, normalizeAndValidate(definition, value), updatedBy, persistedByKey().get(definition.key()));
        }
    }

    private void applyPersistedOverrides() {
        for (StrategyRuntimeConfigEntity entity : persistedByKey().values()) {
            RuntimeConfigDefinition definition = definitions.get(entity.getConfigKey());
            if (definition == null || !definition.editable()) {
                continue;
            }
            apply(definition, normalizeAndValidate(definition, entity.getConfigValue()));
        }
        log.info("[StrategyRuntimeConfig] applied persisted Polymarket runtime config overrides.");
    }

    private void upsert(RuntimeConfigDefinition definition, String value, String updatedBy, StrategyRuntimeConfigEntity existing) {
        Date now = new Date();
        StrategyRuntimeConfigEntity entity = existing == null ? new StrategyRuntimeConfigEntity() : existing;
        entity.setStrategyCode(POLYMARKET_COPY)
                .setConfigKey(definition.key())
                .setConfigValue(value)
                .setValueType(definition.type().name())
                .setGroupName(definition.group())
                .setEditable(definition.editable())
                .setDescription(definition.description())
                .setUpdatedBy(StringUtils.hasText(updatedBy) ? updatedBy.trim() : "dashboard")
                .setUpdatedAt(now);
        if (entity.getId() == null) {
            entity.setCreatedAt(now);
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
    }

    private StrategyRuntimeConfigView toView(RuntimeConfigDefinition definition, StrategyRuntimeConfigEntity persisted) {
        String persistedValue = persisted == null ? null : persisted.getConfigValue();
        String currentValue = definition.reader().apply(properties);
        String source = Objects.equals(currentValue, definition.defaultValue()) && persistedValue == null ? "YAML"
                : persistedValue == null ? "RUNTIME" : "DB";
        return StrategyRuntimeConfigView.builder()
                .strategyCode(POLYMARKET_COPY)
                .key(definition.key())
                .value(currentValue)
                .defaultValue(definition.defaultValue())
                .valueType(definition.type().name())
                .groupName(definition.group())
                .label(definition.label())
                .description(definition.description())
                .editable(definition.editable())
                .source(source)
                .updatedAt(format(persisted == null ? null : persisted.getUpdatedAt()))
                .build();
    }

    private Map<String, StrategyRuntimeConfigEntity> persistedByKey() {
        List<StrategyRuntimeConfigEntity> rows = mapper.selectList(new LambdaQueryWrapper<StrategyRuntimeConfigEntity>()
                .eq(StrategyRuntimeConfigEntity::getStrategyCode, POLYMARKET_COPY));
        Map<String, StrategyRuntimeConfigEntity> result = new LinkedHashMap<>();
        for (StrategyRuntimeConfigEntity row : rows) {
            result.put(row.getConfigKey(), row);
        }
        return result;
    }

    private void apply(RuntimeConfigDefinition definition, String value) {
        definition.writer().accept(properties, value);
    }

    private String normalizeAndValidate(RuntimeConfigDefinition definition, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (definition.type() != ValueType.STRING && !StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Runtime config value is required: " + definition.key());
        }
        switch (definition.type()) {
            case BOOLEAN -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new IllegalArgumentException("Boolean config must be true or false: " + definition.key());
                }
                return value.toLowerCase();
            }
            case INTEGER -> {
                long parsed = Long.parseLong(value);
                validateRange(definition, BigDecimal.valueOf(parsed));
                return Long.toString(parsed);
            }
            case DECIMAL -> {
                BigDecimal parsed = new BigDecimal(value);
                validateRange(definition, parsed);
                return parsed.stripTrailingZeros().toPlainString();
            }
            case STRING_LIST -> {
                List<String> values = parseStringList(value);
                if (values.isEmpty()) {
                    throw new IllegalArgumentException("String list config must contain at least one item: " + definition.key());
                }
                return String.join(",", values);
            }
            case JSON -> {
                try {
                    objectMapper.readTree(value);
                    return value;
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("JSON config is invalid: " + definition.key());
                }
            }
            case STRING -> {
                return value;
            }
            default -> throw new IllegalArgumentException("Unsupported value type: " + definition.type());
        }
    }

    private void validateRange(RuntimeConfigDefinition definition, BigDecimal value) {
        if (definition.min() != null && value.compareTo(definition.min()) < 0) {
            throw new IllegalArgumentException("Runtime config is below minimum: " + definition.key());
        }
        if (definition.max() != null && value.compareTo(definition.max()) > 0) {
            throw new IllegalArgumentException("Runtime config is above maximum: " + definition.key());
        }
    }

    private List<String> parseStringList(String value) {
        if (value.startsWith("[")) {
            try {
                return objectMapper.readValue(value, new TypeReference<List<String>>() {}).stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .toList();
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("String list JSON is invalid.");
            }
        }
        List<String> values = new ArrayList<>();
        for (String part : value.split(",")) {
            if (StringUtils.hasText(part)) {
                values.add(part.trim());
            }
        }
        return values;
    }

    private List<StrategyRuntimeConfigCatalogView.GroupView> groupViews() {
        return List.of(
                group("execution", "执行限额", "LIVE/PAPER 共同使用的交易金额、滑点和方向限制"),
                group("wallet-profile", "地址准入", "监控地址筛选、观察冷却和基础流动性门槛"),
                group("discovery", "地址发现", "排行榜发现、地址分析窗口和冷热分层参数"),
                group("entry-risk", "入场风控", "信号新鲜度、价格、共识、去重和仓位门控"),
                group("exit-risk", "退出风控", "止盈止损、时间窗口退出、资金回收和退出评分"),
                group("model", "模型参数", "baseline 概率/滑点模型与校准参数"),
                group("intelligence", "实体分析", "地址聚类、实体去重和跟单适配评分参数"),
                group("sync", "同步观测", "候选钱包、信号轮询与观察窗口的可调批量参数")
        );
    }

    private StrategyRuntimeConfigCatalogView.GroupView group(String name, String label, String description) {
        return StrategyRuntimeConfigCatalogView.GroupView.builder()
                .name(name)
                .label(label)
                .description(description)
                .build();
    }

    private void ensureSupported(String strategyCode) {
        String normalized = StringUtils.hasText(strategyCode) ? strategyCode.trim() : POLYMARKET_COPY;
        if (!POLYMARKET_COPY.equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException("Runtime config is currently supported only for " + POLYMARKET_COPY);
        }
    }

    private String format(Date date) {
        if (date == null) {
            return null;
        }
        return TIME_FORMATTER.format(date.toInstant().atZone(ZoneId.systemDefault()));
    }

    private void registerDefinitions() {
        if (!definitions.isEmpty()) {
            return;
        }
        SmartWalletProperties defaults = properties;

        bool("smart-wallet.auto-execution-enabled", "execution", "自动实盘执行", "启用后符合条件的信号可进入 LIVE 执行。", defaults.isAutoExecutionEnabled(), SmartWalletProperties::isAutoExecutionEnabled, SmartWalletProperties::setAutoExecutionEnabled);
        decimal("smart-wallet.max-stake-usdc", "execution", "单笔推荐仓位上限", "策略层单笔推荐 stake 上限。", defaults.getMaxStakeUsdc(), BigDecimal.ZERO, null, SmartWalletProperties::getMaxStakeUsdc, SmartWalletProperties::setMaxStakeUsdc);
        decimal("smart-wallet.bankroll-utilization-rate", "execution", "资金利用率", "按 bankroll 计算单笔仓位的比例。", defaults.getBankrollUtilizationRate(), BigDecimal.ZERO, BigDecimal.ONE, SmartWalletProperties::getBankrollUtilizationRate, SmartWalletProperties::setBankrollUtilizationRate);

        bool("smart-wallet.require-tag-match", "wallet-profile", "要求标签匹配", "地址准入时是否要求类别/标签匹配。", defaults.isRequireTagMatch(), SmartWalletProperties::isRequireTagMatch, SmartWalletProperties::setRequireTagMatch);
        decimal("smart-wallet.min-win-rate", "wallet-profile", "最低胜率", "进入监控地址池的最低历史胜率。", defaults.getMinWinRate(), BigDecimal.ZERO, BigDecimal.ONE, SmartWalletProperties::getMinWinRate, SmartWalletProperties::setMinWinRate);
        decimal("smart-wallet.min-wallet-score", "wallet-profile", "最低地址评分", "进入监控地址池的最低综合评分。", defaults.getMinWalletScore(), BigDecimal.ZERO, BigDecimal.ONE, SmartWalletProperties::getMinWalletScore, SmartWalletProperties::setMinWalletScore);
        integer("smart-wallet.min-total-trades", "wallet-profile", "最低样本交易数", "地址准入前要求的最低历史交易样本。", defaults.getMinTotalTrades(), BigDecimal.ONE, null, SmartWalletProperties::getMinTotalTrades, SmartWalletProperties::setMinTotalTrades);
        decimal("smart-wallet.min-realized-pnl-usdc", "wallet-profile", "最低已实现 PnL", "地址准入要求的最低已实现收益，可按策略阶段下调。", defaults.getMinRealizedPnlUsdc(), null, null, SmartWalletProperties::getMinRealizedPnlUsdc, SmartWalletProperties::setMinRealizedPnlUsdc);
        decimal("smart-wallet.max-drawdown-rate", "wallet-profile", "最大回撤率", "地址准入允许的最大历史回撤率。", defaults.getMaxDrawdownRate(), BigDecimal.ZERO, BigDecimal.ONE, SmartWalletProperties::getMaxDrawdownRate, SmartWalletProperties::setMaxDrawdownRate);
        bool("smart-wallet.observe-cooldown-enabled", "wallet-profile", "启用观察冷却", "近期表现恶化时先降级观察，而不是立即移除。", defaults.isObserveCooldownEnabled(), SmartWalletProperties::isObserveCooldownEnabled, SmartWalletProperties::setObserveCooldownEnabled);
        integer("smart-wallet.observe-cooldown-min-recent-trades-7d", "wallet-profile", "冷却最小 7d 样本", "近期胜率冷却判断所需的最小 7d 交易数。", defaults.getObserveCooldownMinRecentTrades7d(), BigDecimal.ZERO, null, SmartWalletProperties::getObserveCooldownMinRecentTrades7d, SmartWalletProperties::setObserveCooldownMinRecentTrades7d);
        decimal("smart-wallet.observe-cooldown-low-recent-win-rate-7d", "wallet-profile", "冷却低胜率阈值", "7d 胜率低于该值时触发观察冷却。", defaults.getObserveCooldownLowRecentWinRate7d(), BigDecimal.ZERO, BigDecimal.ONE, SmartWalletProperties::getObserveCooldownLowRecentWinRate7d, SmartWalletProperties::setObserveCooldownLowRecentWinRate7d);
        decimal("smart-wallet.observe-cooldown-max-recent30d-gap", "wallet-profile", "冷却 30d 差距阈值", "近期 30d 表现相对历史表现的最大允许差距。", defaults.getObserveCooldownMaxRecent30dGap(), BigDecimal.ZERO, BigDecimal.ONE, SmartWalletProperties::getObserveCooldownMaxRecent30dGap, SmartWalletProperties::setObserveCooldownMaxRecent30dGap);
        decimal("smart-wallet.observe-cooldown-max-historical7d-gap", "wallet-profile", "冷却历史/7d 差距阈值", "历史表现与 7d 表现的最大允许差距。", defaults.getObserveCooldownMaxHistorical7dGap(), BigDecimal.ZERO, BigDecimal.ONE, SmartWalletProperties::getObserveCooldownMaxHistorical7dGap, SmartWalletProperties::setObserveCooldownMaxHistorical7dGap);
        decimal("smart-wallet.observe-cooldown-recover-min-recent-win-rate-7d", "wallet-profile", "冷却恢复最低胜率", "观察冷却恢复 HOT/WARM 所需的最低 7d 胜率。", defaults.getObserveCooldownRecoverMinRecentWinRate7d(), BigDecimal.ZERO, BigDecimal.ONE, SmartWalletProperties::getObserveCooldownRecoverMinRecentWinRate7d, SmartWalletProperties::setObserveCooldownRecoverMinRecentWinRate7d);
        decimal("smart-wallet.observe-cooldown-recover-max-recent30d-gap", "wallet-profile", "冷却恢复最大差距", "观察冷却恢复时允许的最大近期表现差距。", defaults.getObserveCooldownRecoverMaxRecent30dGap(), BigDecimal.ZERO, BigDecimal.ONE, SmartWalletProperties::getObserveCooldownRecoverMaxRecent30dGap, SmartWalletProperties::setObserveCooldownRecoverMaxRecent30dGap);
        integer("smart-wallet.observe-cooldown-days", "wallet-profile", "观察冷却天数", "触发后保留观察状态的默认天数。", defaults.getObserveCooldownDays(), BigDecimal.ONE, null, SmartWalletProperties::getObserveCooldownDays, SmartWalletProperties::setObserveCooldownDays);
        decimal("smart-wallet.min-market-liquidity", "wallet-profile", "最低市场流动性", "忽略低于该流动性的市场信号。", defaults.getMinMarketLiquidity(), BigDecimal.ZERO, null, SmartWalletProperties::getMinMarketLiquidity, SmartWalletProperties::setMinMarketLiquidity);
        integer("smart-wallet.follow-delay-seconds", "wallet-profile", "跟随延迟秒数", "观察到源钱包交易后等待多少秒再尝试跟随。", defaults.getFollowDelaySeconds(), BigDecimal.ZERO, null, SmartWalletProperties::getFollowDelaySeconds, SmartWalletProperties::setFollowDelaySeconds);

        bool("smart-wallet.polymarket.auto-discover-enabled", "discovery", "启用自动发现", "定时从 Polymarket 排行榜发现候选地址。", defaults.getPolymarket().isAutoDiscoverEnabled(), p -> p.getPolymarket().isAutoDiscoverEnabled(), (p, v) -> p.getPolymarket().setAutoDiscoverEnabled(v));
        bool("smart-wallet.polymarket.auto-sync-events-enabled", "discovery", "启用事件同步", "定时同步监控地址的新交易事件。", defaults.getPolymarket().isAutoSyncEventsEnabled(), p -> p.getPolymarket().isAutoSyncEventsEnabled(), (p, v) -> p.getPolymarket().setAutoSyncEventsEnabled(v));
        string("smart-wallet.polymarket.leaderboard-category", "discovery", "排行榜分类", "Polymarket leaderboard category 过滤条件。", defaults.getPolymarket().getLeaderboardCategory(), p -> p.getPolymarket().getLeaderboardCategory(), (p, v) -> p.getPolymarket().setLeaderboardCategory(v));
        string("smart-wallet.polymarket.leaderboard-time-period", "discovery", "排行榜周期", "Polymarket leaderboard timePeriod 过滤条件。", defaults.getPolymarket().getLeaderboardTimePeriod(), p -> p.getPolymarket().getLeaderboardTimePeriod(), (p, v) -> p.getPolymarket().setLeaderboardTimePeriod(v));
        string("smart-wallet.polymarket.leaderboard-order-by", "discovery", "排行榜排序", "Polymarket leaderboard orderBy 过滤条件。", defaults.getPolymarket().getLeaderboardOrderBy(), p -> p.getPolymarket().getLeaderboardOrderBy(), (p, v) -> p.getPolymarket().setLeaderboardOrderBy(v));
        integer("smart-wallet.polymarket.leaderboard-limit", "discovery", "排行榜候选数量", "每次排行榜拉取的候选地址数量。", defaults.getPolymarket().getLeaderboardLimit(), BigDecimal.ONE, null, p -> p.getPolymarket().getLeaderboardLimit(), (p, v) -> p.getPolymarket().setLeaderboardLimit(v));
        integer("smart-wallet.polymarket.analyze-top-n", "discovery", "深度分析 Top N", "每轮对排行榜前 N 个地址做深度分析。", defaults.getPolymarket().getAnalyzeTopN(), BigDecimal.ONE, null, p -> p.getPolymarket().getAnalyzeTopN(), (p, v) -> p.getPolymarket().setAnalyzeTopN(v));
        integer("smart-wallet.polymarket.closed-position-limit", "discovery", "历史仓位拉取数量", "地址画像分析时拉取的 closed position 数量。", defaults.getPolymarket().getClosedPositionLimit(), BigDecimal.ONE, null, p -> p.getPolymarket().getClosedPositionLimit(), (p, v) -> p.getPolymarket().setClosedPositionLimit(v));
        integer("smart-wallet.polymarket.recent-trade-limit", "discovery", "近期交易拉取数量", "地址画像分析时拉取的 recent trade 数量。", defaults.getPolymarket().getRecentTradeLimit(), BigDecimal.ONE, null, p -> p.getPolymarket().getRecentTradeLimit(), (p, v) -> p.getPolymarket().setRecentTradeLimit(v));
        integer("smart-wallet.polymarket.user-trade-history-lookback-days", "discovery", "用户交易回看天数", "构建地址画像时保留的用户交易历史窗口。", defaults.getPolymarket().getUserTradeHistoryLookbackDays(), BigDecimal.ONE, null, p -> p.getPolymarket().getUserTradeHistoryLookbackDays(), (p, v) -> p.getPolymarket().setUserTradeHistoryLookbackDays(v));
        integer("smart-wallet.polymarket.discovery-profile-trade-limit", "discovery", "发现画像交易数量", "自动发现阶段用于补充画像的近期交易数量。", defaults.getPolymarket().getDiscoveryProfileTradeLimit(), BigDecimal.ONE, null, p -> p.getPolymarket().getDiscoveryProfileTradeLimit(), (p, v) -> p.getPolymarket().setDiscoveryProfileTradeLimit(v));
        integer("smart-wallet.polymarket.session-coverage-lookback-days", "discovery", "覆盖诊断回看天数", "发现任务 session coverage 诊断使用的回看窗口。", defaults.getPolymarket().getSessionCoverageLookbackDays(), BigDecimal.ONE, null, p -> p.getPolymarket().getSessionCoverageLookbackDays(), (p, v) -> p.getPolymarket().setSessionCoverageLookbackDays(v));
        integer("smart-wallet.polymarket.leaderboard-refresh-top-n", "discovery", "排行榜刷新 Top N", "增量排行榜刷新时每轮覆盖的地址数量。", defaults.getPolymarket().getLeaderboardRefreshTopN(), BigDecimal.ONE, null, p -> p.getPolymarket().getLeaderboardRefreshTopN(), (p, v) -> p.getPolymarket().setLeaderboardRefreshTopN(v));
        bool("smart-wallet.polymarket.hot-retention-enabled", "discovery", "启用 HOT 保留", "小幅分数波动时保留已有 HOT 地址，减少频繁进出。", defaults.getPolymarket().isHotRetentionEnabled(), p -> p.getPolymarket().isHotRetentionEnabled(), (p, v) -> p.getPolymarket().setHotRetentionEnabled(v));
        integer("smart-wallet.polymarket.hot-retention-hours", "discovery", "HOT 保留小时数", "触发保留后 HOT 状态最长保留时间。", defaults.getPolymarket().getHotRetentionHours(), BigDecimal.ONE, null, p -> p.getPolymarket().getHotRetentionHours(), (p, v) -> p.getPolymarket().setHotRetentionHours(v));
        decimal("smart-wallet.polymarket.score-hysteresis-buffer", "discovery", "评分滞后缓冲", "已有 HOT 地址降级时使用的评分缓冲。", defaults.getPolymarket().getScoreHysteresisBuffer(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getPolymarket().getScoreHysteresisBuffer(), (p, v) -> p.getPolymarket().setScoreHysteresisBuffer(v));
        decimal("smart-wallet.polymarket.win-rate-hysteresis-buffer", "discovery", "胜率滞后缓冲", "已有 HOT 地址降级时使用的胜率缓冲。", defaults.getPolymarket().getWinRateHysteresisBuffer(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getPolymarket().getWinRateHysteresisBuffer(), (p, v) -> p.getPolymarket().setWinRateHysteresisBuffer(v));
        integer("smart-wallet.polymarket.warm-wallet-inactivity-days", "discovery", "WARM 不活跃天数", "地址超过该天数无交易后进入 WARM。", defaults.getPolymarket().getWarmWalletInactivityDays(), BigDecimal.ONE, null, p -> p.getPolymarket().getWarmWalletInactivityDays(), (p, v) -> p.getPolymarket().setWarmWalletInactivityDays(v));
        integer("smart-wallet.polymarket.cold-wallet-inactivity-days", "discovery", "COLD 不活跃天数", "地址超过该天数无交易后进入 COLD。", defaults.getPolymarket().getColdWalletInactivityDays(), BigDecimal.ONE, null, p -> p.getPolymarket().getColdWalletInactivityDays(), (p, v) -> p.getPolymarket().setColdWalletInactivityDays(v));
        decimal("smart-wallet.polymarket.stale-address-weight-factor", "discovery", "陈旧地址评分折扣", "低活跃地址参与评分时的权重折扣。", defaults.getPolymarket().getStaleAddressWeightFactor(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getPolymarket().getStaleAddressWeightFactor(), (p, v) -> p.getPolymarket().setStaleAddressWeightFactor(v));

        decimal("smart-wallet.execution-wallet.max-order-usdc", "execution", "LIVE 单笔最大金额", "执行钱包单笔订单硬上限。", defaults.getExecutionWallet().getMaxOrderUsdc(), BigDecimal.ZERO, null, p -> p.getExecutionWallet().getMaxOrderUsdc(), (p, v) -> p.getExecutionWallet().setMaxOrderUsdc(v));
        decimal("smart-wallet.execution-wallet.min-order-usdc", "execution", "LIVE 单笔最小金额", "小于该金额的订单会被拒绝。", defaults.getExecutionWallet().getMinOrderUsdc(), BigDecimal.ZERO, null, p -> p.getExecutionWallet().getMinOrderUsdc(), (p, v) -> p.getExecutionWallet().setMinOrderUsdc(v));
        decimal("smart-wallet.execution-wallet.price-slippage-rate", "execution", "执行滑点缓冲", "准备下单价格时使用的价格缓冲。", defaults.getExecutionWallet().getPriceSlippageRate(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getExecutionWallet().getPriceSlippageRate(), (p, v) -> p.getExecutionWallet().setPriceSlippageRate(v));
        decimal("smart-wallet.execution-wallet.estimated-fee-rate", "execution", "预估手续费率", "离线 EV 标签使用的默认单边费率假设。", defaults.getExecutionWallet().getEstimatedFeeRate(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getExecutionWallet().getEstimatedFeeRate(), (p, v) -> p.getExecutionWallet().setEstimatedFeeRate(v));
        bool("smart-wallet.execution-wallet.allow-yes", "execution", "允许 YES 方向", "禁用后不会跟随 YES 买入。", defaults.getExecutionWallet().getAllowYes(), p -> p.getExecutionWallet().getAllowYes(), (p, v) -> p.getExecutionWallet().setAllowYes(v));
        bool("smart-wallet.execution-wallet.allow-no", "execution", "允许 NO 方向", "禁用后不会跟随 NO 买入。", defaults.getExecutionWallet().getAllowNo(), p -> p.getExecutionWallet().getAllowNo(), (p, v) -> p.getExecutionWallet().setAllowNo(v));

        integer("smart-wallet.signal-risk.max-signal-age-seconds", "entry-risk", "最大信号年龄", "超过该秒数的跟单信号不再入场。", defaults.getSignalRisk().getMaxSignalAgeSeconds(), BigDecimal.ONE, null, p -> p.getSignalRisk().getMaxSignalAgeSeconds(), (p, v) -> p.getSignalRisk().setMaxSignalAgeSeconds(v));
        decimal("smart-wallet.signal-risk.min-trigger-size-usdc", "entry-risk", "源钱包最小触发金额", "忽略低于该金额的源钱包交易。", defaults.getSignalRisk().getMinTriggerSizeUsdc(), BigDecimal.ZERO, null, p -> p.getSignalRisk().getMinTriggerSizeUsdc(), (p, v) -> p.getSignalRisk().setMinTriggerSizeUsdc(v));
        decimal("smart-wallet.signal-risk.max-stake-to-trigger-notional-ratio", "entry-risk", "源单金额缩放上限", "跟单金额不得超过源钱包本次成交名义本金的该比例。", defaults.getSignalRisk().getMaxStakeToTriggerNotionalRatio(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getSignalRisk().getMaxStakeToTriggerNotionalRatio(), (p, v) -> p.getSignalRisk().setMaxStakeToTriggerNotionalRatio(v));
        decimal("smart-wallet.signal-risk.max-single-order-capital-ratio", "entry-risk", "单笔资金占用上限", "跟单金额不得超过当前可用资金的该比例。", defaults.getSignalRisk().getMaxSingleOrderCapitalRatio(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getSignalRisk().getMaxSingleOrderCapitalRatio(), (p, v) -> p.getSignalRisk().setMaxSingleOrderCapitalRatio(v));
        bool("smart-wallet.signal-risk.require-leader-profitable-active-position", "entry-risk", "要求 Leader 当前持仓盈利", "入场前要求源钱包在同一市场同一方向仍持有盈利仓位。", defaults.getSignalRisk().isRequireLeaderProfitableActivePosition(), p -> p.getSignalRisk().isRequireLeaderProfitableActivePosition(), (p, v) -> p.getSignalRisk().setRequireLeaderProfitableActivePosition(v));
        decimal("smart-wallet.signal-risk.min-follow-price", "entry-risk", "最低跟随价格", "低于该价格不入场。", defaults.getSignalRisk().getMinFollowPrice(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getSignalRisk().getMinFollowPrice(), (p, v) -> p.getSignalRisk().setMinFollowPrice(v));
        decimal("smart-wallet.signal-risk.max-follow-price", "entry-risk", "最高跟随价格", "高于该价格不入场。", defaults.getSignalRisk().getMaxFollowPrice(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getSignalRisk().getMaxFollowPrice(), (p, v) -> p.getSignalRisk().setMaxFollowPrice(v));
        bool("smart-wallet.signal-risk.enable-probability-gate", "entry-risk", "启用概率门控", "使用校准后的 follow probability 过滤信号。", defaults.getSignalRisk().isEnableProbabilityGate(), p -> p.getSignalRisk().isEnableProbabilityGate(), (p, v) -> p.getSignalRisk().setEnableProbabilityGate(v));
        decimal("smart-wallet.signal-risk.min-calibrated-follow-probability", "entry-risk", "最低跟随概率", "概率门控开启时的最低阈值。", defaults.getSignalRisk().getMinCalibratedFollowProbability(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getSignalRisk().getMinCalibratedFollowProbability(), (p, v) -> p.getSignalRisk().setMinCalibratedFollowProbability(v));
        bool("smart-wallet.signal-risk.enable-slippage-gate", "entry-risk", "启用滑点门控", "预测滑点过高时拒绝入场。", defaults.getSignalRisk().isEnableSlippageGate(), p -> p.getSignalRisk().isEnableSlippageGate(), (p, v) -> p.getSignalRisk().setEnableSlippageGate(v));
        decimal("smart-wallet.signal-risk.max-expected-slippage-usdc", "entry-risk", "最大预期滑点", "滑点门控允许的最大预期 USDC 损耗。", defaults.getSignalRisk().getMaxExpectedSlippageUsdc(), BigDecimal.ZERO, null, p -> p.getSignalRisk().getMaxExpectedSlippageUsdc(), (p, v) -> p.getSignalRisk().setMaxExpectedSlippageUsdc(v));
        decimal("smart-wallet.signal-risk.max-slippage-spike-probability", "entry-risk", "最大滑点尖峰概率", "滑点尖峰概率上限。", defaults.getSignalRisk().getMaxSlippageSpikeProbability(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getSignalRisk().getMaxSlippageSpikeProbability(), (p, v) -> p.getSignalRisk().setMaxSlippageSpikeProbability(v));
        integer("smart-wallet.signal-risk.max-open-positions", "entry-risk", "最大持仓数", "组合层同时打开的最大仓位数。", defaults.getSignalRisk().getMaxOpenPositions(), BigDecimal.ONE, null, p -> p.getSignalRisk().getMaxOpenPositions(), (p, v) -> p.getSignalRisk().setMaxOpenPositions(v));
        decimal("smart-wallet.signal-risk.max-total-open-cost-usdc", "entry-risk", "最大持仓成本", "组合层打开仓位成本预算。", defaults.getSignalRisk().getMaxTotalOpenCostUsdc(), BigDecimal.ZERO, null, p -> p.getSignalRisk().getMaxTotalOpenCostUsdc(), (p, v) -> p.getSignalRisk().setMaxTotalOpenCostUsdc(v));
        integer("smart-wallet.signal-risk.max-hours-to-market-end", "entry-risk", "距离结算最大小时数", "超过该窗口的市场不入场。", defaults.getSignalRisk().getMaxHoursToMarketEnd(), BigDecimal.ONE, null, p -> p.getSignalRisk().getMaxHoursToMarketEnd(), (p, v) -> p.getSignalRisk().setMaxHoursToMarketEnd(v));
        bool("smart-wallet.signal-risk.enable-consensus-gate", "entry-risk", "启用共识门控", "要求多钱包/实体共识后才入场。", defaults.getSignalRisk().isEnableConsensusGate(), p -> p.getSignalRisk().isEnableConsensusGate(), (p, v) -> p.getSignalRisk().setEnableConsensusGate(v));
        string("smart-wallet.signal-risk.entry-strategy-route-mode", "entry-risk", "入场策略路由", "ANY 或 ALL。", defaults.getSignalRisk().getEntryStrategyRouteMode(), p -> p.getSignalRisk().getEntryStrategyRouteMode(), (p, v) -> p.getSignalRisk().setEntryStrategyRouteMode(v));
        stringList("smart-wallet.signal-risk.enabled-entry-strategies", "entry-risk", "启用入场策略", "逗号分隔的入场策略列表。", defaults.getSignalRisk().getEnabledEntryStrategies(), p -> p.getSignalRisk().getEnabledEntryStrategies(), (p, v) -> p.getSignalRisk().setEnabledEntryStrategies(parseListValue(v)));
        integer("smart-wallet.signal-risk.consensus-window-seconds", "entry-risk", "共识窗口秒数", "计算共识时回看的时间窗口。", defaults.getSignalRisk().getConsensusWindowSeconds(), BigDecimal.ONE, null, p -> p.getSignalRisk().getConsensusWindowSeconds(), (p, v) -> p.getSignalRisk().setConsensusWindowSeconds(v));
        integer("smart-wallet.signal-risk.min-distinct-entities", "entry-risk", "最小去重实体数", "共识门控要求的最小实体数。", defaults.getSignalRisk().getMinDistinctEntities(), BigDecimal.ONE, null, p -> p.getSignalRisk().getMinDistinctEntities(), (p, v) -> p.getSignalRisk().setMinDistinctEntities(v));
        decimal("smart-wallet.signal-risk.min-consensus-score", "entry-risk", "最低共识分", "共识门控最低分。", defaults.getSignalRisk().getMinConsensusScore(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getSignalRisk().getMinConsensusScore(), (p, v) -> p.getSignalRisk().setMinConsensusScore(v));
        bool("smart-wallet.signal-risk.enable-single-wallet-paper-canary", "entry-risk", "启用单钱包 paper canary", "允许优质单钱包进入受控 PAPER 验证通道。", defaults.getSignalRisk().isEnableSingleWalletPaperCanary(), p -> p.getSignalRisk().isEnableSingleWalletPaperCanary(), (p, v) -> p.getSignalRisk().setEnableSingleWalletPaperCanary(v));
        bool("smart-wallet.signal-risk.enable-concentration-gate", "entry-risk", "启用集中度门控", "根据持仓集中度控制入场或缩仓。", defaults.getSignalRisk().isEnableConcentrationGate(), p -> p.getSignalRisk().isEnableConcentrationGate(), (p, v) -> p.getSignalRisk().setEnableConcentrationGate(v));
        bool("smart-wallet.signal-risk.kelly-stake-enabled", "entry-risk", "启用 Kelly 仓位", "按概率和价格动态计算仓位。", defaults.getSignalRisk().isKellyStakeEnabled(), p -> p.getSignalRisk().isKellyStakeEnabled(), (p, v) -> p.getSignalRisk().setKellyStakeEnabled(v));
        decimal("smart-wallet.signal-risk.kelly-fraction", "entry-risk", "Kelly 折扣", "Kelly 仓位折扣因子。", defaults.getSignalRisk().getKellyFraction(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getSignalRisk().getKellyFraction(), (p, v) -> p.getSignalRisk().setKellyFraction(v));

        bool("smart-wallet.signal-risk.enable-auto-stop-loss", "exit-risk", "启用自动止损", "持仓 ROI 低于阈值时自动退出。", defaults.getSignalRisk().isEnableAutoStopLoss(), p -> p.getSignalRisk().isEnableAutoStopLoss(), (p, v) -> p.getSignalRisk().setEnableAutoStopLoss(v));
        decimal("smart-wallet.signal-risk.auto-stop-loss-threshold-rate", "exit-risk", "止损阈值", "正数，例如 0.35 表示 ROI <= -35% 触发。", defaults.getSignalRisk().getAutoStopLossThresholdRate(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getSignalRisk().getAutoStopLossThresholdRate(), (p, v) -> p.getSignalRisk().setAutoStopLossThresholdRate(v));
        bool("smart-wallet.signal-risk.enable-auto-take-profit", "exit-risk", "启用自动止盈", "持仓 ROI 高于阈值时自动退出。", defaults.getSignalRisk().isEnableAutoTakeProfit(), p -> p.getSignalRisk().isEnableAutoTakeProfit(), (p, v) -> p.getSignalRisk().setEnableAutoTakeProfit(v));
        decimal("smart-wallet.signal-risk.auto-take-profit-threshold-rate", "exit-risk", "止盈阈值", "ROI 达到该比例触发止盈。", defaults.getSignalRisk().getAutoTakeProfitThresholdRate(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getSignalRisk().getAutoTakeProfitThresholdRate(), (p, v) -> p.getSignalRisk().setAutoTakeProfitThresholdRate(v));
        bool("smart-wallet.signal-risk.enable-auto-time-window-exit", "exit-risk", "启用时间窗口退出", "临近市场结束时自动退出。", defaults.getSignalRisk().isEnableAutoTimeWindowExit(), p -> p.getSignalRisk().isEnableAutoTimeWindowExit(), (p, v) -> p.getSignalRisk().setEnableAutoTimeWindowExit(v));
        integer("smart-wallet.signal-risk.auto-time-window-exit-hours-before-end", "exit-risk", "结算前退出小时数", "距离市场结束多少小时开始强制退出。", defaults.getSignalRisk().getAutoTimeWindowExitHoursBeforeEnd(), BigDecimal.ZERO, null, p -> p.getSignalRisk().getAutoTimeWindowExitHoursBeforeEnd(), (p, v) -> p.getSignalRisk().setAutoTimeWindowExitHoursBeforeEnd(v));
        decimal("smart-wallet.signal-risk.auto-time-window-exit-min-roi-rate", "exit-risk", "时间窗口最低 ROI", "低于该 ROI 时不执行时间窗口退出，可为负数。", defaults.getSignalRisk().getAutoTimeWindowExitMinRoiRate(), new BigDecimal("-1"), BigDecimal.ONE, p -> p.getSignalRisk().getAutoTimeWindowExitMinRoiRate(), (p, v) -> p.getSignalRisk().setAutoTimeWindowExitMinRoiRate(v));
        bool("smart-wallet.signal-risk.time-aware-stop-loss-enabled", "exit-risk", "启用时间感知止损", "按市场剩余时间动态调整止损线。", defaults.getSignalRisk().isTimeAwareStopLossEnabled(), p -> p.getSignalRisk().isTimeAwareStopLossEnabled(), (p, v) -> p.getSignalRisk().setTimeAwareStopLossEnabled(v));
        json("smart-wallet.signal-risk.stop-loss-tiers", "exit-risk", "时间止损分段", "JSON 数组，元素包含 remainingHours 和 thresholdRate。", toJson(defaults.getSignalRisk().getStopLossTiers()), p -> toJson(p.getSignalRisk().getStopLossTiers()), (p, v) -> p.getSignalRisk().setStopLossTiers(parseStopLossTiers(v)));
        bool("smart-wallet.signal-risk.trend-stop-loss-enabled", "exit-risk", "启用趋势止损", "价格连续恶化时提前止损。", defaults.getSignalRisk().isTrendStopLossEnabled(), p -> p.getSignalRisk().isTrendStopLossEnabled(), (p, v) -> p.getSignalRisk().setTrendStopLossEnabled(v));
        string("smart-wallet.signal-risk.exit-signal-provider-mode", "exit-risk", "退出信号模式", "RULES 或 SCORING。", defaults.getSignalRisk().getExitSignalProviderMode(), p -> p.getSignalRisk().getExitSignalProviderMode(), (p, v) -> p.getSignalRisk().setExitSignalProviderMode(v));
        bool("smart-wallet.signal-risk.capital-recycle.enabled", "exit-risk", "启用资金回收", "长时间低效持仓主动退出。", defaults.getSignalRisk().getCapitalRecycle().isEnabled(), p -> p.getSignalRisk().getCapitalRecycle().isEnabled(), (p, v) -> p.getSignalRisk().getCapitalRecycle().setEnabled(v));
        bool("smart-wallet.signal-risk.capital-recycle.paper-enabled", "exit-risk", "启用 PAPER 资金回收", "关闭后 PAPER 资金回收只保留观察，不自动卖出。", defaults.getSignalRisk().getCapitalRecycle().isPaperEnabled(), p -> p.getSignalRisk().getCapitalRecycle().isPaperEnabled(), (p, v) -> p.getSignalRisk().getCapitalRecycle().setPaperEnabled(v));
        bool("smart-wallet.signal-risk.capital-recycle.live-enabled", "exit-risk", "启用 LIVE 资金回收", "控制 LIVE 仓位是否允许资金回收自动退出。", defaults.getSignalRisk().getCapitalRecycle().isLiveEnabled(), p -> p.getSignalRisk().getCapitalRecycle().isLiveEnabled(), (p, v) -> p.getSignalRisk().getCapitalRecycle().setLiveEnabled(v));
        integer("smart-wallet.signal-risk.capital-recycle.holding-period-hours", "exit-risk", "资金回收持仓小时", "持仓超过该小时数后进入回收候选。", defaults.getSignalRisk().getCapitalRecycle().getHoldingPeriodHours(), BigDecimal.ONE, null, p -> p.getSignalRisk().getCapitalRecycle().getHoldingPeriodHours(), (p, v) -> p.getSignalRisk().getCapitalRecycle().setHoldingPeriodHours(v));
        decimal("smart-wallet.signal-risk.capital-recycle.dead-zone-low-rate", "exit-risk", "资金回收死区下界", "ROI 低于此值不回收，留给止损处理。", defaults.getSignalRisk().getCapitalRecycle().getDeadZoneLowRate(), new BigDecimal("-1"), BigDecimal.ONE, p -> p.getSignalRisk().getCapitalRecycle().getDeadZoneLowRate(), (p, v) -> p.getSignalRisk().getCapitalRecycle().setDeadZoneLowRate(v));
        decimal("smart-wallet.signal-risk.capital-recycle.dead-zone-high-rate", "exit-risk", "资金回收死区上界", "ROI 高于此值不回收，留给止盈处理。", defaults.getSignalRisk().getCapitalRecycle().getDeadZoneHighRate(), new BigDecimal("-1"), BigDecimal.ONE, p -> p.getSignalRisk().getCapitalRecycle().getDeadZoneHighRate(), (p, v) -> p.getSignalRisk().getCapitalRecycle().setDeadZoneHighRate(v));

        decimal("smart-wallet.model-calibration.follow-probability-scale", "model", "跟随概率校准 scale", "baseline follow probability 的校准斜率。", defaults.getModelCalibration().getFollowProbabilityScale(), null, null, p -> p.getModelCalibration().getFollowProbabilityScale(), (p, v) -> p.getModelCalibration().setFollowProbabilityScale(v));
        decimal("smart-wallet.model-calibration.follow-probability-bias", "model", "跟随概率校准 bias", "baseline follow probability 的校准偏置。", defaults.getModelCalibration().getFollowProbabilityBias(), null, null, p -> p.getModelCalibration().getFollowProbabilityBias(), (p, v) -> p.getModelCalibration().setFollowProbabilityBias(v));
        decimal("smart-wallet.model-calibration.slippage-spike-scale", "model", "滑点尖峰校准 scale", "slippage spike probability 的校准斜率。", defaults.getModelCalibration().getSlippageSpikeScale(), null, null, p -> p.getModelCalibration().getSlippageSpikeScale(), (p, v) -> p.getModelCalibration().setSlippageSpikeScale(v));
        decimal("smart-wallet.model-calibration.slippage-spike-bias", "model", "滑点尖峰校准 bias", "slippage spike probability 的校准偏置。", defaults.getModelCalibration().getSlippageSpikeBias(), null, null, p -> p.getModelCalibration().getSlippageSpikeBias(), (p, v) -> p.getModelCalibration().setSlippageSpikeBias(v));
        decimal("smart-wallet.baseline-model.gross-edge-prior-weight", "model", "毛边际先验权重", "收益评分中 gross edge 先验的权重。", defaults.getBaselineModel().getGrossEdgePriorWeight(), null, null, p -> p.getBaselineModel().getGrossEdgePriorWeight(), (p, v) -> p.getBaselineModel().setGrossEdgePriorWeight(v));
        decimal("smart-wallet.baseline-model.follow-probability.intercept", "model", "概率模型截距", "follow probability baseline 截距。", defaults.getBaselineModel().getFollowProbability().getIntercept(), null, null, p -> p.getBaselineModel().getFollowProbability().getIntercept(), (p, v) -> p.getBaselineModel().getFollowProbability().setIntercept(v));
        decimal("smart-wallet.baseline-model.follow-probability.wallet-score-weight", "model", "钱包评分权重", "follow probability 钱包评分权重。", defaults.getBaselineModel().getFollowProbability().getWalletScoreWeight(), null, null, p -> p.getBaselineModel().getFollowProbability().getWalletScoreWeight(), (p, v) -> p.getBaselineModel().getFollowProbability().setWalletScoreWeight(v));
        decimal("smart-wallet.baseline-model.follow-probability.consensus-score-weight", "model", "共识评分权重", "follow probability 共识评分权重。", defaults.getBaselineModel().getFollowProbability().getConsensusScoreWeight(), null, null, p -> p.getBaselineModel().getFollowProbability().getConsensusScoreWeight(), (p, v) -> p.getBaselineModel().getFollowProbability().setConsensusScoreWeight(v));
        decimal("smart-wallet.baseline-model.slippage.spread-cost-weight", "model", "滑点 spread 权重", "滑点模型中 spread 成本权重。", defaults.getBaselineModel().getSlippage().getSpreadCostWeight(), null, null, p -> p.getBaselineModel().getSlippage().getSpreadCostWeight(), (p, v) -> p.getBaselineModel().getSlippage().setSpreadCostWeight(v));
        decimal("smart-wallet.baseline-model.slippage.impact-cost-weight", "model", "滑点 impact 权重", "滑点模型中 impact 成本权重。", defaults.getBaselineModel().getSlippage().getImpactCostWeight(), null, null, p -> p.getBaselineModel().getSlippage().getImpactCostWeight(), (p, v) -> p.getBaselineModel().getSlippage().setImpactCostWeight(v));
        decimal("smart-wallet.baseline-model.slippage-spike.intercept", "model", "滑点尖峰截距", "滑点尖峰模型截距。", defaults.getBaselineModel().getSlippageSpike().getIntercept(), null, null, p -> p.getBaselineModel().getSlippageSpike().getIntercept(), (p, v) -> p.getBaselineModel().getSlippageSpike().setIntercept(v));
        decimal("smart-wallet.baseline-model.slippage-spike.spike-threshold-bps", "model", "滑点尖峰阈值 bps", "超过该 bps 视为滑点尖峰样本。", defaults.getBaselineModel().getSlippageSpike().getSpikeThresholdBps(), BigDecimal.ZERO, null, p -> p.getBaselineModel().getSlippageSpike().getSpikeThresholdBps(), (p, v) -> p.getBaselineModel().getSlippageSpike().setSpikeThresholdBps(v));

        decimal("smart-wallet.intelligence.min-cluster-confidence-for-dedup", "intelligence", "实体去重最低置信度", "共识去重时将地址视为同一实体的最低聚类置信度。", defaults.getIntelligence().getMinClusterConfidenceForDedup(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getIntelligence().getMinClusterConfidenceForDedup(), (p, v) -> p.getIntelligence().setMinClusterConfidenceForDedup(v));
        decimal("smart-wallet.intelligence.behavior-similarity-threshold", "intelligence", "行为相似度阈值", "行为相似度高于该值的地址会被视为同一实体候选。", BigDecimal.valueOf(defaults.getIntelligence().getBehaviorSimilarityThreshold()), BigDecimal.ZERO, BigDecimal.ONE, p -> BigDecimal.valueOf(p.getIntelligence().getBehaviorSimilarityThreshold()), (p, v) -> p.getIntelligence().setBehaviorSimilarityThreshold(v.doubleValue()));
        integer("smart-wallet.intelligence.candidate-reevaluation-batch-size", "intelligence", "候选重评估批量", "每轮实体候选重评估处理的钱包数量。", defaults.getIntelligence().getCandidateReevaluationBatchSize(), BigDecimal.ONE, null, p -> p.getIntelligence().getCandidateReevaluationBatchSize(), (p, v) -> p.getIntelligence().setCandidateReevaluationBatchSize(v));
        decimal("smart-wallet.follow-fit-weights.leader-quality", "intelligence", "适配评分 Leader 权重", "followFitScore 中 leader quality 的权重。", defaults.getFollowFitWeights().getLeaderQuality(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getFollowFitWeights().getLeaderQuality(), (p, v) -> p.getFollowFitWeights().setLeaderQuality(v));
        decimal("smart-wallet.follow-fit-weights.followable-rate", "intelligence", "适配评分可跟随率权重", "followFitScore 中 followable rate 的权重。", defaults.getFollowFitWeights().getFollowableRate(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getFollowFitWeights().getFollowableRate(), (p, v) -> p.getFollowFitWeights().setFollowableRate(v));
        decimal("smart-wallet.follow-fit-weights.alpha-30s", "intelligence", "适配评分 30s alpha 权重", "保留用于回滚的短周期 alpha 权重，默认 0。", defaults.getFollowFitWeights().getAlpha30s(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getFollowFitWeights().getAlpha30s(), (p, v) -> p.getFollowFitWeights().setAlpha30s(v));
        decimal("smart-wallet.follow-fit-weights.alpha-5m", "intelligence", "适配评分 5m alpha 权重", "保留用于回滚的短周期 alpha 权重，默认 0。", defaults.getFollowFitWeights().getAlpha5m(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getFollowFitWeights().getAlpha5m(), (p, v) -> p.getFollowFitWeights().setAlpha5m(v));
        decimal("smart-wallet.follow-fit-weights.alpha-rule", "intelligence", "适配评分 alpha rule 权重", "followFitScore 中 alpha rule 的权重。", defaults.getFollowFitWeights().getAlphaRule(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getFollowFitWeights().getAlphaRule(), (p, v) -> p.getFollowFitWeights().setAlphaRule(v));
        decimal("smart-wallet.follow-fit-weights.win-rate30d", "intelligence", "适配评分 30d 胜率权重", "followFitScore 中 30d win rate 的权重。", defaults.getFollowFitWeights().getWinRate30d(), BigDecimal.ZERO, BigDecimal.ONE, p -> p.getFollowFitWeights().getWinRate30d(), (p, v) -> p.getFollowFitWeights().setWinRate30d(v));

        integer("smart-wallet.polymarket.watchlist-trade-sync-batch-size", "sync", "观察钱包同步批量", "每轮同步 watchlist trade 的钱包数。", defaults.getPolymarket().getWatchlistTradeSyncBatchSize(), BigDecimal.ONE, null, p -> p.getPolymarket().getWatchlistTradeSyncBatchSize(), (p, v) -> p.getPolymarket().setWatchlistTradeSyncBatchSize(v));
        integer("smart-wallet.polymarket.wallet-trade-sync-min-interval-ms", "sync", "单钱包同步最小间隔", "同一钱包两次 trade sync 的最小间隔。", defaults.getPolymarket().getWalletTradeSyncMinIntervalMs(), BigDecimal.ONE, null, p -> p.getPolymarket().getWalletTradeSyncMinIntervalMs(), (p, v) -> p.getPolymarket().setWalletTradeSyncMinIntervalMs(v.longValue()));
        integer("smart-wallet.polymarket.warm-metrics-refresh-batch-size", "sync", "WARM 指标刷新批量", "每轮刷新 WARM 钱包指标的数量。", defaults.getPolymarket().getWarmMetricsRefreshBatchSize(), BigDecimal.ONE, null, p -> p.getPolymarket().getWarmMetricsRefreshBatchSize(), (p, v) -> p.getPolymarket().setWarmMetricsRefreshBatchSize(v));
    }

    private static List<String> parseListValue(String value) {
        List<String> values = new ArrayList<>();
        for (String part : value.split(",")) {
            if (StringUtils.hasText(part)) {
                values.add(part.trim());
            }
        }
        return values;
    }

    private List<SmartWalletProperties.StopLossTier> parseStopLossTiers(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<SmartWalletProperties.StopLossTier>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid stop loss tiers JSON.");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize runtime config default.", e);
        }
    }

    private void bool(String key, String group, String label, String description, Boolean defaultValue,
                      Function<SmartWalletProperties, Boolean> reader,
                      BiConsumer<SmartWalletProperties, Boolean> writer) {
        add(new RuntimeConfigDefinition(key, ValueType.BOOLEAN, group, label, description, String.valueOf(Boolean.TRUE.equals(defaultValue)),
                true, null, null, p -> String.valueOf(Boolean.TRUE.equals(reader.apply(p))), (p, value) -> writer.accept(p, Boolean.parseBoolean(value))));
    }

    private void integer(String key, String group, String label, String description, Number defaultValue, BigDecimal min, BigDecimal max,
                         Function<SmartWalletProperties, ? extends Number> reader,
                         BiConsumer<SmartWalletProperties, Integer> writer) {
        add(new RuntimeConfigDefinition(key, ValueType.INTEGER, group, label, description, String.valueOf(defaultValue),
                true, min, max, p -> String.valueOf(reader.apply(p)), (p, value) -> writer.accept(p, Integer.parseInt(value))));
    }

    private void decimal(String key, String group, String label, String description, BigDecimal defaultValue, BigDecimal min, BigDecimal max,
                         Function<SmartWalletProperties, BigDecimal> reader,
                         BiConsumer<SmartWalletProperties, BigDecimal> writer) {
        add(new RuntimeConfigDefinition(key, ValueType.DECIMAL, group, label, description, value(defaultValue),
                true, min, max, p -> value(reader.apply(p)), (p, value) -> writer.accept(p, new BigDecimal(value))));
    }

    private void string(String key, String group, String label, String description, String defaultValue,
                        Function<SmartWalletProperties, String> reader,
                        BiConsumer<SmartWalletProperties, String> writer) {
        add(new RuntimeConfigDefinition(key, ValueType.STRING, group, label, description, defaultValue,
                true, null, null, p -> reader.apply(p), writer));
    }

    private void stringList(String key, String group, String label, String description, List<String> defaultValue,
                            Function<SmartWalletProperties, List<String>> reader,
                            BiConsumer<SmartWalletProperties, String> writer) {
        add(new RuntimeConfigDefinition(key, ValueType.STRING_LIST, group, label, description, String.join(",", defaultValue),
                true, null, null, p -> String.join(",", reader.apply(p)), writer));
    }

    private void json(String key, String group, String label, String description, String defaultValue,
                      Function<SmartWalletProperties, String> reader,
                      BiConsumer<SmartWalletProperties, String> writer) {
        add(new RuntimeConfigDefinition(key, ValueType.JSON, group, label, description, defaultValue,
                true, null, null, reader, writer));
    }

    private String value(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private void add(RuntimeConfigDefinition definition) {
        definitions.put(definition.key(), definition);
    }

    private enum ValueType {
        BOOLEAN,
        INTEGER,
        DECIMAL,
        STRING,
        STRING_LIST,
        JSON
    }

    private record RuntimeConfigDefinition(
            String key,
            ValueType type,
            String group,
            String label,
            String description,
            String defaultValue,
            boolean editable,
            BigDecimal min,
            BigDecimal max,
            Function<SmartWalletProperties, String> reader,
            BiConsumer<SmartWalletProperties, String> writer
    ) {
    }
}
