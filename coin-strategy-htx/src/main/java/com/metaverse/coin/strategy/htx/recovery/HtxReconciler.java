package com.metaverse.coin.strategy.htx.recovery;

import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxExposureEntity;
import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 定时对账器：检测 htx_exposure 中 OPEN 裸露头寸，市价平回中性。
 * base 多头 → sell-market；平仓单 write-ahead；超过重试上限转 MANUAL + 告警。
 * <p>
 * 仅当 {@link HtxApiService} bean 存在（AK/SK 已配置）时装配，与 {@code HtxTradingService} 同模式；
 * 无凭证时（如集成测试上下文）不创建本 bean，避免上下文加载失败。
 */
@Slf4j
@Component
@ConditionalOnBean(HtxApiService.class)
public class HtxReconciler {

    private final HtxTradeRecorder recorder;
    private final HtxApiService htxApiService;
    private final HtxTradeProperties props;

    public HtxReconciler(HtxTradeRecorder recorder, HtxApiService htxApiService, HtxTradeProperties props) {
        this.recorder = recorder;
        this.htxApiService = htxApiService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${htx.reconciler.interval-ms:30000}", initialDelay = 15000)
    public void scheduled() {
        if (!props.getReconciler().isEnabled()) {
            return;
        }
        reconcile();
    }

    public void reconcile() {
        if (!props.getTrade().isEnabled()) {
            return;
        }
        List<HtxExposureEntity> open = recorder.findOpenExposures();
        if (open == null || open.isEmpty()) {
            return;
        }
        log.warn("HTX reconciler: {} OPEN exposure(s)", open.size());
        for (HtxExposureEntity ex : open) {
            flattenOne(ex);
        }
    }

    private void flattenOne(HtxExposureEntity ex) {
        int retries = ex.getFlattenRetries() == null ? 0 : ex.getFlattenRetries();
        if (retries >= props.getReconciler().getMaxFlattenRetries()) {
            log.error("HTX reconciler ALERT: exposure id={} pair={} symbol={} exhausted {} retries -> MANUAL",
                    ex.getId(), ex.getPairId(), ex.getSymbol(), retries);
            recorder.markExposureManual(ex.getId(), "flatten retries exhausted: " + retries);
            return;
        }
        if (!HtxStatus.EXPOSED_SIDE_BASE_LONG.equals(ex.getExposedSide())) {
            log.error("HTX reconciler: unsupported exposed_side={} on id={}; MANUAL",
                    ex.getExposedSide(), ex.getId());
            recorder.markExposureManual(ex.getId(), "unsupported exposed_side: " + ex.getExposedSide());
            return;
        }
        BigDecimal amount = ex.getExposedAmount();
        try {
            recorder.insertFlattenIntent(ex.getPairId(), ex.getSymbol(), amount);
            String clientId = "flatten-" + ex.getId() + "-" + retries;
            HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                    props.getApi().getSpotAccountId(), ex.getSymbol(),
                    "sell-market", amount.toPlainString(), null, clientId);
            Long htxOrderId = parseOrderId(resp);
            if (htxOrderId != null) {
                recorder.markExposureFlattened(ex.getId(), htxOrderId);
                log.warn("HTX reconciler: exposure id={} flattened via sell-market htxId={}",
                        ex.getId(), htxOrderId);
            } else {
                recorder.incrementFlattenRetry(ex.getId());
                log.error("HTX reconciler: flatten REST failed for exposure id={} (retry {}/{})",
                        ex.getId(), retries + 1, props.getReconciler().getMaxFlattenRetries());
            }
        } catch (RuntimeException e) {
            recorder.incrementFlattenRetry(ex.getId());
            log.error("HTX reconciler: flatten exception for exposure id={}: {}", ex.getId(), e.getMessage());
        }
    }

    private Long parseOrderId(HtxRestResponse<String> resp) {
        if (resp == null || !resp.ok() || resp.getData() == null) {
            return null;
        }
        try {
            return Long.parseLong(resp.getData().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
