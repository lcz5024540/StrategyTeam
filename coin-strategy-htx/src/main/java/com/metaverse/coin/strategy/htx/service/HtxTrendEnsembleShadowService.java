package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendCandidateSignalRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendCandidateSignalEntity;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shadow-only multi-asset trend observer. It creates candidate signal rows and never places orders.
 */
@Slf4j
@Service
@ConditionalOnBean(HtxUsdtSwapApiService.class)
@ConditionalOnProperty(prefix = "htx.trend.ensemble-shadow", name = "enabled", havingValue = "true")
public class HtxTrendEnsembleShadowService {

    private final HtxUsdtSwapService swap;
    private final HtxTrendEnsembleShadowProperties properties;
    private final HtxTrendCandidateSignalRecorder recorder;
    private final HtxTrendEnsembleSignalCalculator calculator;

    @Autowired
    public HtxTrendEnsembleShadowService(HtxUsdtSwapApiService api,
                                         HtxTrendEnsembleShadowProperties properties,
                                         HtxTrendCandidateSignalRecorder recorder) {
        this(api.getSwapService(), properties, recorder, new HtxTrendEnsembleSignalCalculator());
    }

    HtxTrendEnsembleShadowService(HtxUsdtSwapService swap,
                                  HtxTrendEnsembleShadowProperties properties,
                                  HtxTrendCandidateSignalRecorder recorder,
                                  HtxTrendEnsembleSignalCalculator calculator) {
        this.swap = swap;
        this.properties = properties;
        this.recorder = recorder;
        this.calculator = calculator;
    }

    public RunResult runOnce() {
        int recorded = 0;
        List<String> failures = new ArrayList<>();
        long now = Instant.now().getEpochSecond();
        for (String contractCode : normalizedContracts(properties.getContractCodes())) {
            for (String period : normalizedPeriods(properties.getPeriods())) {
                try {
                    TrendCandidateSignal signal = loadSignal(contractCode, period, now);
                    boolean stored = recorder.record(toEntity(contractCode, period, signal));
                    if (stored) {
                        recorded++;
                    } else {
                        failures.add(contractCode + "/" + period + ": persistence failed");
                    }
                    log.info("[HTX-TREND-ENSEMBLE] shadow=true stored={} contract={} period={} direction={} score={} regime={} reason={}",
                            stored, contractCode, period, signal.direction(), signal.signalScore(), signal.regime(),
                            signal.entryReason());
                } catch (RuntimeException ex) {
                    String failure = contractCode + "/" + period + ": " + ex.getMessage();
                    failures.add(failure);
                    log.warn("[HTX-TREND-ENSEMBLE] candidate run failed {}", failure, ex);
                }
            }
        }
        return new RunResult(recorded, failures.size(), failures);
    }

    private TrendCandidateSignal loadSignal(String contractCode, String period, long nowEpochSeconds) {
        HtxRestResponse<List<HtxSwapKline>> response = swap.historyKline(contractCode, period, properties.getKlineSize());
        if (response == null || !response.ok() || response.payload() == null) {
            return TrendCandidateSignal.skip(period, "kline unavailable: " + error(response));
        }
        return calculator.evaluate(response.payload(), properties, period, nowEpochSeconds);
    }

    private HtxTrendCandidateSignalEntity toEntity(String contractCode, String period, TrendCandidateSignal signal) {
        HtxTrendCandidateSignalEntity entity = new HtxTrendCandidateSignalEntity();
        entity.setContractCode(contractCode);
        entity.setPeriod(period);
        entity.setBarTime(signal.barTime());
        entity.setDirection(signal.direction().name());
        entity.setRegime(signal.regime().name());
        entity.setSignalScore(signal.signalScore());
        entity.setLongScore(signal.longScore());
        entity.setShortScore(signal.shortScore());
        entity.setScoreGap(signal.scoreGap());
        entity.setClosePrice(signal.closePrice());
        entity.setEmaFast(signal.emaFast());
        entity.setEmaMedium(signal.emaMedium());
        entity.setEmaSlow(signal.emaSlow());
        entity.setBreakoutHigh(signal.breakoutHigh());
        entity.setBreakoutLow(signal.breakoutLow());
        entity.setAtr(signal.atr());
        entity.setAtrPct(signal.atrPct());
        entity.setAdx(signal.adx());
        entity.setMomentumPct(signal.momentumPct());
        entity.setVolumeRatio(signal.volumeRatio());
        entity.setEntryReason(clip(signal.entryReason()));
        entity.setInvalidationReason(clip(signal.invalidationReason()));
        entity.setShadow(true);
        entity.setModelVersion(properties.getModelVersion());
        return entity;
    }

    private static List<String> normalizedContracts(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static List<String> normalizedPeriods(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static String clip(String value) {
        if (value == null || value.length() <= 512) {
            return value;
        }
        return value.substring(0, 512);
    }

    private static String error(HtxRestResponse<?> response) {
        if (response == null) {
            return "null response";
        }
        return "status=" + response.getStatus() + " errCode=" + response.getErrCode() + " errMsg=" + response.getErrMsg();
    }

    public record RunResult(int recorded, int failed, List<String> failures) {
    }
}
