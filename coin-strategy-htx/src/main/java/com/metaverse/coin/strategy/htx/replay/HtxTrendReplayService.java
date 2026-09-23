package com.metaverse.coin.strategy.htx.replay;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendCandidateSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendReplayCandleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendReplayRunEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendReplayTradeEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendCandidateSignalMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendFactorSnapshotMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPositionMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendReplayCandleMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendReplayRunMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendReplayTradeMapper;
import com.metaverse.coin.strategy.htx.service.HtxTrendExitRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Creates reproducible historical replays from durable candidate/position evidence.
 * Public HTX K-lines are frozen before any result is calculated; this service never submits or cancels an order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(HtxUsdtSwapApiService.class)
public class HtxTrendReplayService {

    static final String ENGINE_VERSION = "htx-trend-exit-replay-v1";
    private static final List<String> EXIT_PERIODS = List.of("30min", "60min", "4hour");
    private static final int HTX_HISTORY_LIMIT = 2_000;
    /** Dashboard and HTX operations use China Standard Time; request timestamps are intentionally local to it. */
    private static final ZoneId REPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    private final HtxUsdtSwapApiService api;
    private final HtxTrendProperties properties;
    private final HtxTrendCandidateSignalMapper candidateMapper;
    private final HtxTrendFactorSnapshotMapper factorSnapshotMapper;
    private final HtxTrendPositionMapper positionMapper;
    private final HtxTrendReplayRunMapper replayRunMapper;
    private final HtxTrendReplayCandleMapper replayCandleMapper;
    private final HtxTrendReplayTradeMapper replayTradeMapper;
    private final HtxTrendExitReplayEngine exitReplayEngine = new HtxTrendExitReplayEngine();

    /**
     * Replays only candidates that the historical model layer passed. Missing final gate, position, fee, or K-line
     * evidence is persisted as unavailable rather than transformed into a synthetic trade.
     */
    public ReplaySummary replay(LocalDateTime startAt, LocalDateTime endAt) {
        validateWindow(startAt, endAt);
        String replayRunId = "replay:" + UUID.randomUUID();
        long startBarTime = startAt.atZone(REPLAY_ZONE).toEpochSecond();
        long endBarTime = endAt.atZone(REPLAY_ZONE).toEpochSecond();
        HtxTrendReplayRunEntity run = new HtxTrendReplayRunEntity();
        run.setReplayRunId(replayRunId);
        run.setEngineVersion(ENGINE_VERSION);
        run.setInputStartAt(startAt);
        run.setInputEndAt(endAt);
        run.setPeriods(String.join(",", EXIT_PERIODS));
        run.setStatus("FREEZING_INPUT");
        run.setInputIntegrity("PENDING");
        run.setCandidateCount(0L);
        run.setTradeCount(0L);
        run.setComparableTradeCount(0L);
        run.setMessage("freezing public HTX candles before replay");
        replayRunMapper.insert(run);

        List<HtxTrendCandidateSignalEntity> candidates = candidateMapper.findReplayCandidatesBetween(startBarTime, endBarTime);
        freezeInputs(replayRunId, candidates, startBarTime, endBarTime);
        List<HtxTrendReplayTradeEntity> trades = new ArrayList<>();
        for (HtxTrendCandidateSignalEntity candidate : candidates) {
            HtxTrendReplayTradeEntity trade = replayCandidate(replayRunId, candidate);
            replayTradeMapper.insert(trade);
            trades.add(trade);
        }
        ReplaySummary summary = summarize(replayRunId, candidates.size(), trades);
        replayRunMapper.complete(replayRunId, "COMPLETED", summary.inputIntegrity(), summary.candidateCount(),
                summary.tradeCount(), summary.comparableTradeCount(), summary.netProfit(), summary.profitFactor(),
                summary.message());
        return summary;
    }

    private void freezeInputs(String replayRunId, List<HtxTrendCandidateSignalEntity> candidates,
                              long startBarTime, long endBarTime) {
        HtxUsdtSwapService swap = api.getSwapService();
        Map<String, LinkedHashSet<String>> requests = new LinkedHashMap<>();
        for (HtxTrendCandidateSignalEntity candidate : candidates) {
            if (blank(candidate.getContractCode())) {
                continue;
            }
            LinkedHashSet<String> periods = requests.computeIfAbsent(candidate.getContractCode(), ignored -> new LinkedHashSet<>());
            periods.add(candidate.getPeriod());
            periods.addAll(EXIT_PERIODS);
        }
        for (Map.Entry<String, LinkedHashSet<String>> request : requests.entrySet()) {
            for (String period : request.getValue()) {
                if (blank(period)) {
                    continue;
                }
                HtxRestResponse<List<HtxSwapKline>> response = swap.historyKline(request.getKey(), period, HTX_HISTORY_LIMIT);
                if (response == null || !response.ok() || response.payload() == null) {
                    log.warn("[HTX-TREND-REPLAY] unable to freeze candles replayRunId={} contract={} period={}",
                            replayRunId, request.getKey(), period);
                    continue;
                }
                long warmupStart = Math.max(0L, startBarTime - periodSeconds(period) * 64L);
                Map<Long, HtxSwapKline> unique = response.payload().stream()
                        .filter(candle -> candle != null && candle.getId() != null)
                        .filter(candle -> candle.getId() >= warmupStart && candle.getId() <= endBarTime)
                        .collect(Collectors.toMap(HtxSwapKline::getId, candle -> candle, (left, right) -> right,
                                LinkedHashMap::new));
                for (HtxSwapKline candle : unique.values()) {
                    if (!positive(candle.getOpen()) || !positive(candle.getHigh()) || !positive(candle.getLow())
                            || !positive(candle.getClose())) {
                        continue;
                    }
                    HtxTrendReplayCandleEntity frozen = new HtxTrendReplayCandleEntity();
                    frozen.setReplayRunId(replayRunId);
                    frozen.setContractCode(request.getKey());
                    frozen.setPeriod(period);
                    frozen.setBarTime(candle.getId());
                    frozen.setOpenPrice(candle.getOpen());
                    frozen.setHighPrice(candle.getHigh());
                    frozen.setLowPrice(candle.getLow());
                    frozen.setClosePrice(candle.getClose());
                    frozen.setVolume(candle.getVol());
                    frozen.setTurnover(candle.getTradeTurnover());
                    frozen.setTradeCount(candle.getCount());
                    replayCandleMapper.insert(frozen);
                }
            }
        }
    }

    private HtxTrendReplayTradeEntity replayCandidate(String replayRunId, HtxTrendCandidateSignalEntity candidate) {
        HtxTrendReplayTradeEntity trade = baseTrade(replayRunId, candidate);
        if (blank(candidate.getRunId())) {
            unavailable(trade, "candidate has no stable run_id; cannot join execution evidence");
            return trade;
        }
        if (!"PASSED".equals(candidate.getExecutionGateResult())) {
            unavailable(trade, "candidate never passed final execution gate result=" + candidate.getExecutionGateResult());
            return trade;
        }
        if (candidate.getFactorSnapshotId() == null || factorSnapshotMapper.selectById(candidate.getFactorSnapshotId()) == null) {
            unavailable(trade, "immutable entry factor snapshot unavailable");
            return trade;
        }
        HtxTrendPositionEntity position = positionMapper.findByRunId(candidate.getRunId());
        if (position == null) {
            unavailable(trade, "final gate passed but no durable position exists for run_id");
            return trade;
        }
        trade.setSourcePositionId(position.getId());
        trade.setEntryPrice(firstPositive(position.getEntryAvgPrice(), candidate.getClosePrice()));
        trade.setInitialContracts(filledVolume(position));
        BigDecimal feeRate = resolveFeeRate(candidate, position);
        List<HtxTrendReplayCandleEntity> entryCandles = replayCandleMapper.findByReplayRun(replayRunId,
                candidate.getContractCode(), candidate.getPeriod());
        Map<Long, Integer> opposition = oppositionByEntryBar(replayRunId, candidate.getContractCode(), position.getSide(), entryCandles);
        HtxTrendExitReplayEngine.Input input = new HtxTrendExitReplayEngine.Input(position.getSide(), candidate.getBarTime(),
                firstPositive(position.getEntryAvgPrice(), candidate.getClosePrice()),
                firstPositive(position.getStopPrice(), position.getInvalidationPrice(), candidate.getInvalidationPrice()),
                structuralTarget(position, candidate), candidate.getAtr(), position.getContractSize(), filledVolume(position),
                position.getEntryFee(), feeRate, properties.getPartialTakeProfitFirstRate(), properties.getPartialTakeProfitSecondRate());
        HtxTrendExitReplayEngine.Outcome outcome = exitReplayEngine.replay(input, toEngineCandles(entryCandles), opposition);
        trade.setExitBarTime(outcome.exitBarTime());
        trade.setExitPrice(outcome.exitPrice());
        trade.setRemainingContracts(outcome.remainingContracts());
        trade.setMfe(outcome.mfe());
        trade.setMae(outcome.mae());
        trade.setEntryFee(outcome.entryFee());
        trade.setCloseFee(outcome.closeFee());
        trade.setNetProfit(outcome.netProfit());
        trade.setOutcomeState(outcome.outcomeState());
        trade.setExitReason(outcome.exitReason());
        trade.setDataIntegrity(outcome.dataIntegrity());
        trade.setAmbiguityReason(outcome.ambiguityReason());
        trade.setMessage(outcome.message());
        return trade;
    }

    private Map<Long, Integer> oppositionByEntryBar(String replayRunId, String contractCode, String positionSide,
                                                     List<HtxTrendReplayCandleEntity> entryCandles) {
        Map<String, List<HtxTrendReplayCandleEntity>> byPeriod = new LinkedHashMap<>();
        for (String period : EXIT_PERIODS) {
            byPeriod.put(period, replayCandleMapper.findByReplayRun(replayRunId, contractCode, period));
        }
        return computeOppositionByEntryBar(byPeriod, positionSide, entryCandles, EXIT_PERIODS,
                properties.getMarketAnchorFastEmaPeriod(), properties.getMarketAnchorSlowEmaPeriod());
    }

    /**
     * Returns a value only when all required higher periods are complete as of the entry-period candle.
     * The integer is the count of periods whose confirmed direction opposes the replay position.
     */
    static Map<Long, Integer> computeOppositionByEntryBar(
            Map<String, List<HtxTrendReplayCandleEntity>> candlesByPeriod, String positionSide,
            List<HtxTrendReplayCandleEntity> entryCandles, List<String> periods, int fastEmaPeriod,
            int slowEmaPeriod) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        if (entryCandles == null || periods == null || periods.isEmpty()) {
            return result;
        }
        for (HtxTrendReplayCandleEntity entryCandle : entryCandles) {
            if (entryCandle == null || entryCandle.getBarTime() == null) {
                continue;
            }
            int opposite = 0;
            boolean complete = true;
            for (String period : periods) {
                String side = timeframeSide(candlesByPeriod == null ? null : candlesByPeriod.get(period),
                        entryCandle.getBarTime(), period, fastEmaPeriod, slowEmaPeriod);
                if ("UNKNOWN".equals(side)) {
                    complete = false;
                    break;
                }
                if ((HtxTrendExitRules.LONG.equals(positionSide) && "SHORT".equals(side))
                        || (HtxTrendExitRules.SHORT.equals(positionSide) && "LONG".equals(side))) {
                    opposite++;
                }
            }
            if (complete) {
                result.put(entryCandle.getBarTime(), opposite);
            }
        }
        return result;
    }

    private static String timeframeSide(List<HtxTrendReplayCandleEntity> all, long asOfBarTime, String period,
                                        int fastEmaPeriod, int slowEmaPeriod) {
        if (all == null) {
            return "UNKNOWN";
        }
        List<BigDecimal> closes = all.stream()
                .filter(candle -> candle.getBarTime() + periodSeconds(period) <= asOfBarTime)
                .sorted(Comparator.comparing(HtxTrendReplayCandleEntity::getBarTime))
                .map(HtxTrendReplayCandleEntity::getClosePrice).filter(HtxTrendReplayService::positive).toList();
        int slow = Math.max(2, slowEmaPeriod);
        if (closes.size() < slow + 2) {
            return "UNKNOWN";
        }
        BigDecimal latest = closes.get(closes.size() - 1);
        BigDecimal previous = closes.get(closes.size() - 2);
        BigDecimal fast = ema(closes, Math.max(1, fastEmaPeriod));
        BigDecimal slowValue = ema(closes, slow);
        if (latest.compareTo(previous) > 0 && fast.compareTo(slowValue) > 0 && latest.compareTo(fast) >= 0) return "LONG";
        if (latest.compareTo(previous) < 0 && fast.compareTo(slowValue) < 0 && latest.compareTo(fast) <= 0) return "SHORT";
        return "FLAT";
    }

    private static BigDecimal ema(List<BigDecimal> closes, int period) {
        BigDecimal multiplier = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1), 18, RoundingMode.HALF_UP);
        BigDecimal current = closes.get(0);
        for (int index = 1; index < closes.size(); index++) {
            current = closes.get(index).subtract(current).multiply(multiplier).add(current);
        }
        return current;
    }

    private BigDecimal resolveFeeRate(HtxTrendCandidateSignalEntity candidate, HtxTrendPositionEntity position) {
        if (positive(position.getEntryFeeRate())) {
            return position.getEntryFeeRate();
        }
        if (blank(candidate.getPolicyVersion())) {
            return null;
        }
        String prefix = candidate.getContractCode() + ":" + candidate.getPeriod() + ":" + candidate.getBarTime()
                + ":" + candidate.getPolicyVersion() + ":entry-cost-v1:" + candidate.getRunId();
        HtxTrendFactorSnapshotEntity snapshot = factorSnapshotMapper.findLatestBySnapshotPrefix(prefix);
        if (snapshot == null || blank(snapshot.getFactorsJson())) {
            return null;
        }
        try {
            JSONObject root = JSON.parseObject(snapshot.getFactorsJson());
            JSONObject account = root == null ? null : root.getJSONObject("account");
            return account == null ? null : account.getBigDecimal("closeTakerRate");
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static HtxTrendReplayTradeEntity baseTrade(String replayRunId, HtxTrendCandidateSignalEntity candidate) {
        HtxTrendReplayTradeEntity trade = new HtxTrendReplayTradeEntity();
        trade.setReplayRunId(replayRunId);
        trade.setSourceCandidateId(candidate.getId());
        trade.setSourceRunId(candidate.getRunId());
        trade.setContractCode(candidate.getContractCode());
        trade.setPeriod(candidate.getPeriod());
        trade.setDirection(candidate.getDirection());
        trade.setMarketRegime(candidate.getRegime());
        trade.setPrimaryModel(candidate.getPrimaryModel());
        trade.setEntryBarTime(candidate.getBarTime());
        return trade;
    }

    private static void unavailable(HtxTrendReplayTradeEntity trade, String message) {
        trade.setOutcomeState("UNAVAILABLE");
        trade.setExitReason("INSUFFICIENT_EVIDENCE");
        trade.setDataIntegrity("INSUFFICIENT_EVIDENCE");
        trade.setMessage(message);
    }

    private static List<HtxTrendExitReplayEngine.Candle> toEngineCandles(List<HtxTrendReplayCandleEntity> values) {
        return values.stream().map(value -> new HtxTrendExitReplayEngine.Candle(value.getBarTime(), value.getOpenPrice(),
                value.getHighPrice(), value.getLowPrice(), value.getClosePrice())).toList();
    }

    private static long filledVolume(HtxTrendPositionEntity position) {
        if (position.getEntryFilledContracts() != null && position.getEntryFilledContracts().signum() > 0) {
            return position.getEntryFilledContracts().setScale(0, RoundingMode.DOWN).longValue();
        }
        return position.getContractVolume() == null ? 0L : position.getContractVolume();
    }

    private static BigDecimal structuralTarget(HtxTrendPositionEntity position, HtxTrendCandidateSignalEntity candidate) {
        if (HtxTrendExitRules.LONG.equals(position.getSide())) {
            return firstPositive(position.getTargetZoneLow(), position.getTargetZoneHigh(), candidate.getTargetZoneLow(),
                    candidate.getTargetZoneHigh());
        }
        return firstPositive(position.getTargetZoneHigh(), position.getTargetZoneLow(), candidate.getTargetZoneHigh(),
                candidate.getTargetZoneLow());
    }

    private static BigDecimal firstPositive(BigDecimal... values) {
        for (BigDecimal value : values) if (positive(value)) return value;
        return null;
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static long periodSeconds(String period) {
        String value = period == null ? "" : period.trim().toLowerCase();
        if (value.endsWith("min")) return Long.parseLong(value.substring(0, value.length() - 3)) * 60L;
        if (value.endsWith("hour")) return Long.parseLong(value.substring(0, value.length() - 4)) * 3_600L;
        if (value.endsWith("day")) return Long.parseLong(value.substring(0, value.length() - 3)) * 86_400L;
        throw new IllegalArgumentException("unsupported HTX replay period: " + period);
    }

    private static void validateWindow(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("replay end must be after replay start");
        }
    }

    private static ReplaySummary summarize(String replayRunId, int candidateCount, List<HtxTrendReplayTradeEntity> trades) {
        List<HtxTrendReplayTradeEntity> comparable = trades.stream()
                .filter(trade -> "COMPLETE".equals(trade.getDataIntegrity()))
                .filter(trade -> trade.getNetProfit() != null).toList();
        BigDecimal net = comparable.stream().map(HtxTrendReplayTradeEntity::getNetProfit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossProfit = comparable.stream().map(HtxTrendReplayTradeEntity::getNetProfit)
                .filter(value -> value.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLoss = comparable.stream().map(HtxTrendReplayTradeEntity::getNetProfit)
                .filter(value -> value.signum() < 0).map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pf = grossLoss.signum() == 0 ? null : grossProfit.divide(grossLoss, 8, RoundingMode.HALF_UP);
        String integrity = comparable.size() == trades.size() ? "COMPLETE" : "PARTIAL";
        return new ReplaySummary(replayRunId, candidateCount, trades.size(), comparable.size(), net, pf, integrity,
                "frozen candle replay; unavailable samples retain their missing evidence reason");
    }

    public record ReplaySummary(String replayRunId, long candidateCount, long tradeCount, long comparableTradeCount,
                                BigDecimal netProfit, BigDecimal profitFactor, String inputIntegrity, String message) {
    }
}
