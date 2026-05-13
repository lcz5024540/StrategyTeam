package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.metaverse.coin.strategy.web.service.JobApiForwardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SmartWallet GET 接口的兜底代理：把<strong>暂未在 web 本地落地</strong>的查询请求转发到 job 服务。
 *
 * <p>Spring 路由中，{@link SmartWalletReadController} 等具体路径会优先命中本地实现；
 * 只有未被任何具体 controller 覆盖的 GET 才会落到本类的 {@code /smart-wallet/**} 通配上。
 * 设计目标是<strong>逐步把查询接口从 job 下沉到 web</strong>，所以每完成一组路径的本地化，
 * 就把对应 Ant 模式加入 {@link #MIGRATED_PATH_PATTERNS}。命中迁移清单的请求一律返回 404，
 * 避免本地实现被误删时静默回退到 job 而难以发现。</p>
 *
 * <p>未迁移的查询（仍走 job）请<strong>不要</strong>加进 {@code MIGRATED_PATH_PATTERNS}。</p>
 */
@RestController
public class SmartWalletReadProxyController {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 已在 web 本地实现的 GET 路径（Ant 模式，path-variable 用 {@code *}）。
     * <p>分组对应 web 的各个 controller：</p>
     * <ul>
     *   <li>SmartWalletReadController（健康、总览、订单、持仓、报表、配置文件、结算/索取查询、CSV 导出）</li>
     *   <li>SmartWalletFollowTasksController（follow-tasks 系列 + follow-trades/attribution）</li>
     *   <li>SmartWalletSettlementController（settlement 命令的 trigger 状态）</li>
     *   <li>SmartWalletClaimController（claim 命令的 trigger 状态）</li>
     *   <li>SmartWalletProfileWriteController（profiles 异步命令 trigger 状态）</li>
     *   <li>SmartWalletAccountingController（accounting/repair 命令 trigger 状态）</li>
     *   <li>SmartWalletAdminController（admin 命令 trigger 状态）</li>
     * </ul>
     */
    private static final List<String> MIGRATED_PATH_PATTERNS = List.of(
            // —— 健康/总览 ——
            "/smart-wallet/health",
            "/smart-wallet/overview",

            // —— Leader 闭环胜率（A 批） ——
            "/smart-wallet/leader-outcomes/summary",
            "/smart-wallet/leader-outcomes/follow-coverage",

            // —— 钱包特征日表（H 批） ——
            "/smart-wallet/wallet-features/daily",

            // —— 发现覆盖率（Phase 3.1） ——
            "/smart-wallet/discovery/session-coverage",

            // —— 市场监控（Phase 3.2） ——
            "/smart-wallet/reports/market-monitoring",
            "/smart-wallet/reports/market-monitoring/page",

            // —— Watchlist（Phase 4.1，E 批前半） ——
            "/smart-wallet/watchlist",
            "/smart-wallet/watchlist/candidates",
            "/smart-wallet/watchlist/executable",
            "/smart-wallet/watchlist/warm",

            // —— Watchlist archetypes/behavior（Phase 4.2，E 批后半） ——
            "/smart-wallet/watchlist/archetypes",
            "/smart-wallet/watchlist/archetypes/export",
            "/smart-wallet/watchlist/behavior-profiles",

            // —— Watchlist tier-diagnostics（Phase 4.3） ——
            "/smart-wallet/watchlist/tier-diagnostics",
            "/smart-wallet/watchlist/tier-diagnostics/summary",

            // —— B 批 profiles 深度查询（Phase 4.4） ——
            "/smart-wallet/profiles/*/order-analysis",
            "/smart-wallet/profiles/*/order-stats",
            "/smart-wallet/profiles/*/diagnosis",

            // —— B 批 INTERNAL_ONLY（前端不调用，下线对外暴露；运维内网仍可 curl job:8081） ——
            "/smart-wallet/profiles/truth-audit",
            "/smart-wallet/profiles/overrides/candidates",

            // —— C 批异常诊断（Phase 4.5，全部 INTERNAL_ONLY）
            // 前端 0 调用；底层依赖 AbnormalMarketReviewService + PolymarketMarketMetadataService
            // （外部 API）+ job 进程内 stats，本质是运维按 id 诊断单笔订单/仓位/市场数据流；
            // web 不再暴露，运维内网仍可 curl job:8081 ——
            "/smart-wallet/orders/diagnostics",
            "/smart-wallet/positions/market-diagnostics",
            "/smart-wallet/polymarket/market-data/diagnostics",

            // —— 订单 / 交易 / 持仓 ——
            "/smart-wallet/orders",
            "/smart-wallet/orders/page",
            "/smart-wallet/orders/export",
            "/smart-wallet/trade-orders/page",
            "/smart-wallet/trade-orders/pnl-category-attribution",
            "/smart-wallet/trades/page",
            "/smart-wallet/fills",
            "/smart-wallet/fills/export",
            "/smart-wallet/positions/live",
            "/smart-wallet/positions/live/export",
            "/smart-wallet/positions/next-settlements",
            "/smart-wallet/positions/next-settlements/export",
            "/smart-wallet/positions/history",
            "/smart-wallet/positions/history/export",

            // —— 报表 / PnL / 资金 ——
            "/smart-wallet/pnl/summary",
            "/smart-wallet/pnl/timeline",
            "/smart-wallet/reports/wallets",
            "/smart-wallet/reports/markets",
            "/smart-wallet/reports/daily",
            "/smart-wallet/reports/conflicting-markets",
            "/smart-wallet/reports/performance",
            "/smart-wallet/reports/follow-addresses/daily",
            "/smart-wallet/reports/follow-addresses/daily/export",
            "/smart-wallet/capacity/summary",
            "/smart-wallet/cash-ledger",

            // —— Profiles ——
            "/smart-wallet/profiles",
            "/smart-wallet/profiles/screener",
            "/smart-wallet/profiles/overrides",
            "/smart-wallet/profiles/overrides/summary",
            "/smart-wallet/profiles/overrides/export",

            // —— Allowlist / Blocklist ——
            "/smart-wallet/allowlist",
            "/smart-wallet/allowlist/summary",
            "/smart-wallet/blocklist",
            "/smart-wallet/blocklist/summary",

            // —— Polymarket 市场链接 ——
            "/smart-wallet/polymarket/markets/*/link",

            // —— Settlement 查询 + 命令 trigger 状态 ——
            "/smart-wallet/settlement/status",
            "/smart-wallet/settlement/runs",
            "/smart-wallet/settlement/retries",
            "/smart-wallet/settlement/retries/report",
            "/smart-wallet/settlement/sync/*",
            "/smart-wallet/settlement/review-paper-history/*",
            "/smart-wallet/settlement/retries/process/*",
            "/smart-wallet/settlement/manual-confirm-local/*",

            // —— Claim 查询 + 命令 trigger 状态 ——
            "/smart-wallet/claim/status",
            "/smart-wallet/claim/runs",
            "/smart-wallet/claim/retries",
            "/smart-wallet/claim/retries/report",
            "/smart-wallet/claim/process/*",
            "/smart-wallet/claim/retries/process/*",

            // —— Follow tasks 查询 + 命令 trigger 状态 ——
            "/smart-wallet/follow-tasks",
            "/smart-wallet/follow-tasks/blocker-stats",
            "/smart-wallet/follow-tasks/coverage-report",
            "/smart-wallet/follow-tasks/executable",
            "/smart-wallet/follow-tasks/manual-allowlist",
            "/smart-wallet/follow-tasks/opportunities",
            "/smart-wallet/follow-tasks/single-wallet-override",
            "/smart-wallet/follow-tasks/process/*",
            "/smart-wallet/follow-trades/attribution",

            // —— Profiles 异步命令 trigger 状态 ——
            "/smart-wallet/profiles/sync-all-positions/*",
            "/smart-wallet/profiles/metrics/rebuild/*",

            // —— Accounting 命令 trigger 状态 ——
            "/smart-wallet/accounting/backfill/*",
            "/smart-wallet/accounting/backfill-market-end-at/*",
            "/smart-wallet/accounting/backfill-token-links/*",
            "/smart-wallet/accounting/repair-market-ids/by-positions/*",
            "/smart-wallet/accounting/repair-premature-settlements/*",
            "/smart-wallet/accounting/repair-premature-settlements/by-ids/*",

            // —— Admin 命令 trigger 状态 ——
            "/smart-wallet/admin/reset-data/*",
            "/smart-wallet/admin/recover-state/*"
    );

    private final JobApiForwardService jobApiForwardService;

    public SmartWalletReadProxyController(JobApiForwardService jobApiForwardService) {
        this.jobApiForwardService = jobApiForwardService;
    }

    @GetMapping("/smart-wallet/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException, InterruptedException {
        String relativePath = relativePath(request);
        if (isMigrated(relativePath)) {
            String body = "{\"error\":\"endpoint migrated to web; expected local handler is missing\","
                    + "\"path\":\"" + relativePath + "\"}";
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.getBytes(StandardCharsets.UTF_8));
        }
        return jobApiForwardService.forward(request, new byte[0]);
    }

    private boolean isMigrated(String relativePath) {
        for (String pattern : MIGRATED_PATH_PATTERNS) {
            if (PATH_MATCHER.match(pattern, relativePath)) {
                return true;
            }
        }
        return false;
    }

    private String relativePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) {
            return uri;
        }
        if (uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
