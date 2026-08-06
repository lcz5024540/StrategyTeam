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

    private Reconciler reconciler = new Reconciler();

    private Risk risk = new Risk();

    /**
     * 交易额度风控（上限）。权威值来自配置文件 {@code application.yml} 的 {@code htx.risk.*}，
     * 可用环境变量覆盖，后续可平滑迁移到数据库。此处字段仅声明与兜底默认，不含业务硬编码语义。
     */
    @Data
    public static class Risk {
        /**
         * 单笔交易 quote 名义上限（USDT）。校验时取买/卖两腿名义的较大者。
         * {@code <= 0} 表示不限制。默认 50。
         */
        private BigDecimal maxSingleQuoteNotional = new BigDecimal("50");
        /**
         * 当日累计 quote 名义上限（USDT）。累计口径 = 当日非 dry-run 已提交对的买腿名义之和 + 本单买腿名义。
         * {@code <= 0} 表示不限制。默认 100000。
         */
        private BigDecimal maxDailyQuoteNotional = new BigDecimal("100000");
    }

    @Data
    public static class Reconciler {
        /** 是否启用定时对账器（裸露自动市价平仓）。默认 true。 */
        private boolean enabled = true;
        /** 对账扫描间隔（ms）。默认 30s。 */
        private long intervalMs = 30_000;
        /** 单条裸露自动平仓的最大重试次数，超限转 MANUAL 并告警。 */
        private int maxFlattenRetries = 3;
    }

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
        /**
         * enabled=true 时，启动即校验 spot-account-id 非空。缺失则视为配置错误。
         * 默认 true（实盘必须有账户 id）。
         */
        private boolean requireSpotAccountIdWhenEnabled = true;
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
