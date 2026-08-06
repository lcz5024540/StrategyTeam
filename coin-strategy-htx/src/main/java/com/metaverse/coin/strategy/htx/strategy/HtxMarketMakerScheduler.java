package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 做市调度器：启动恢复 + 周期主循环。见 spec 2026-08-06-htx-market-maker-design.md §4/§6/§8。
 * <p>
 * 装配条件（两道并存）：
 * <ul>
 *   <li>{@code @ConditionalOnBean(HtxApiService.class)}——无凭证（如集成测试上下文）不装配，避免上下文加载失败；</li>
 *   <li>{@code @ConditionalOnProperty(...enabled=true)}——急停语义：{@code HTX_MM_ENABLED=false} 重启则本 bean 不加载，
 *       已 resting 挂单冻结不动（不砸盘），靠 buy-ttl 或人工了结（§8）。</li>
 * </ul>
 * 循环与恢复均吞 RuntimeException 只记日志：单轮/单 cell 失败不拖垮调度线程与启动流程。
 */
@Slf4j
@Component
@ConditionalOnBean(HtxApiService.class)
@ConditionalOnProperty(prefix = "htx.market-maker", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class HtxMarketMakerScheduler {

    private final MarketMakerService marketMakerService;

    /** 启动就绪后对齐库里所有 open cell 一次（幂等，clientOrderId 幂等防重复下单）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            marketMakerService.recoverOpenCells();
            log.info("MM startup recover done");
        } catch (RuntimeException ex) {
            log.error("MM startup recover failed", ex);
        }
    }

    /** 周期主循环：推进 open cell + 按白名单尝试开新 cell。 */
    @Scheduled(fixedDelayString = "${htx.market-maker.poll-interval-ms:10000}", initialDelay = 20000)
    public void scheduled() {
        try {
            marketMakerService.runOnce();
        } catch (RuntimeException ex) {
            log.error("MM scheduled runOnce failed", ex);
        }
    }
}
