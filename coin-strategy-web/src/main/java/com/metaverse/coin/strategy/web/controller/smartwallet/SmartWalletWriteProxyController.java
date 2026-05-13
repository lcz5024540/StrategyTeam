package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.metaverse.coin.strategy.web.service.JobApiForwardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SmartWallet POST/PUT/PATCH/DELETE 兜底代理。
 *
 * <p>Spring 路由优先匹配具体路径——web 已实现的命令模式 controllers（settlement、claim、
 * admin、profile-write、accounting、follow-tasks、allowlist、blocklist、trade-events、
 * trade-orders、wallet-features、clob-write、polymarket-write、polymarket-write 等）会
 * 抢先命中。落到本 catch-all 的是"web 未实现的写入路径"，目前还会被透传到 job:8081。</p>
 *
 * <p>Priority 4 阶段：对"前端不调用 + 应保留为 job 内网能力"的写入路径加入
 * {@link #INTERNAL_ONLY_PATH_PATTERNS} 禁单，命中即 404。job:8081 内网仍可调用，
 * 不影响运维场景；前端永远不会触达。</p>
 */
@RestController
public class SmartWalletWriteProxyController {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 仅 job:8081 内网可调用的写入路径——前端不应触达。
     *
     * <p>分类说明：</p>
     * <ul>
     *   <li>测试/调试触发：{@code /polymarket/market-data/mock-event}</li>
     *   <li>job 进程内存写入（不能本地化）：{@code /polymarket/metrics}、
     *       {@code /polymarket/events}、{@code /live-validation/process}、
     *       {@code /wallets}、{@code /markets}、{@code /watchlist/behavior-profiles/refresh}</li>
     *   <li>手动运维触发（@Scheduled 已有定时驱动，不需 HTTP）：{@code /scan}、
     *       {@code /execute/&#42;}、{@code /reports/daily/rebuild}、{@code /markets/manual-review}</li>
     *   <li>外部 API 透传（不能本地化）：{@code /clob/dry-run}、{@code /clob/orders/&#42;/cancel}、
     *       {@code /execution-wallet/derive-api-creds}</li>
     *   <li>缓存运维：{@code /concentration/&#42;/invalidate}</li>
     * </ul>
     *
     * <p>下方<strong>未列入</strong>的端点（如 {@code /config/&#42;} POST 前端在用）继续走 proxy
     * 透传到 job——视业务方后续决策再决定是否一并下线对外暴露。</p>
     */
    private static final List<String> INTERNAL_ONLY_PATH_PATTERNS = List.of(
            // —— 测试/调试 ——
            "/smart-wallet/polymarket/market-data/mock-event",

            // —— job 进程内存写入 / 内省 ——
            "/smart-wallet/polymarket/metrics",
            "/smart-wallet/polymarket/events",
            "/smart-wallet/live-validation/process",
            "/smart-wallet/wallets",
            "/smart-wallet/markets",
            "/smart-wallet/watchlist/behavior-profiles/refresh",

            // —— 手动运维触发（已有 @Scheduled）——
            "/smart-wallet/scan",
            "/smart-wallet/execute/*",
            "/smart-wallet/reports/daily/rebuild",
            "/smart-wallet/markets/manual-review",

            // —— 外部 API（Polymarket CLOB） ——
            "/smart-wallet/clob/dry-run",
            "/smart-wallet/clob/orders/*/cancel",
            "/smart-wallet/execution-wallet/derive-api-creds",

            // —— 缓存运维 ——
            "/smart-wallet/concentration/*/invalidate",

            // —— 配置写入（方案 A：业务接受前端不再修改配置，仅 job:8081 内网可改）
            // 对应前端 config.api.ts 已移除的 POST 调用 ——
            "/smart-wallet/config/baseline-model",
            "/smart-wallet/config/runtime",
            "/smart-wallet/config/runtime/reset"
    );

    private final JobApiForwardService jobApiForwardService;

    public SmartWalletWriteProxyController(JobApiForwardService jobApiForwardService) {
        this.jobApiForwardService = jobApiForwardService;
    }

    @RequestMapping(
            path = "/smart-wallet/**",
            method = {
                    org.springframework.web.bind.annotation.RequestMethod.POST,
                    org.springframework.web.bind.annotation.RequestMethod.PUT,
                    org.springframework.web.bind.annotation.RequestMethod.PATCH,
                    org.springframework.web.bind.annotation.RequestMethod.DELETE
            }
    )
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) throws IOException, InterruptedException {
        String relativePath = relativePath(request);
        if (isInternalOnly(relativePath)) {
            String reply = "{\"error\":\"endpoint reserved for job internal-only; not exposed via web\","
                    + "\"path\":\"" + relativePath + "\"}";
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(reply.getBytes(StandardCharsets.UTF_8));
        }
        return jobApiForwardService.forward(request, body);
    }

    private boolean isInternalOnly(String relativePath) {
        for (String pattern : INTERNAL_ONLY_PATH_PATTERNS) {
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
