package com.metaverse.coin.strategy.htx.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Configuration for the standalone HTX spot execution service.
 * <p>
 * Loaded from {@code application.yml} under prefix {@code htx}. See
 * {@code docs/architecture-refactor/HTX_SPOT_EXECUTION_SDK_V1.md} §5–§6.
 * <p>
 * Safety edges (aligned with design doc §P5):
 * <ul>
 *   <li>{@code htx.trade.enabled=false} (default) → all place-order calls only log; no REST hit.</li>
 *   <li>{@code htx.trade.enabled=true} → real API.</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "htx")
public class HtxTradeProperties {

    private Api api = new Api();
    private Trade trade = new Trade();
    private DepthGuard depthGuard = new DepthGuard();

    @Data
    public static class Api {
        private String accessKey;
        private String secretKey;
        private String baseUrl = "https://api.huobi.pro";
        private String wsUrl = "wss://api.huobi.pro/ws";
        /** HTX spot account id (long as string; retrieved once from listAccounts). */
        private String spotAccountId;
    }

    @Data
    public static class Trade {
        /**
         * Master switch. Default false → dry-run mode: {@link HtxTradingService} only prints intended orders.
         */
        private boolean enabled = false;
        /** Poll interval (ms) when waiting for a leg to fill. */
        private long fillPollIntervalMs = 200;
        /** Max time to wait for buy-leg fill before cancelling and aborting. */
        private long fillTimeoutMs = 5_000;
        /**
         * v1.0.2 hotfix #5: consecutive queryOrder failures (network / non-ok status) before
         * aborting the wait loop. Prevents blind cancel of a possibly-filled order when HTX
         * is returning 5xx or timing out.
         */
        private int queryFailureThreshold = 5;
    }

    @Data
    public static class DepthGuard {
        /** How many price levels to sum. HTX supports 1/5/10/20 for step0. */
        private int topN = 10;
        /**
         * Cumulative depth must be at least {@code qty × safetyRatio}. Default = 3.0
         * (i.e. depth is 3x the target size).
         */
        private BigDecimal safetyRatio = new BigDecimal("3.0");
        /**
         * Reject any pair whose notional value (qty × sellPrice) falls below this quote-asset amount.
         * Avoids sending dust-sized orders that would fail HTX's min-notional filter anyway.
         */
        private BigDecimal minQuoteNotional = new BigDecimal("20");
    }
}
