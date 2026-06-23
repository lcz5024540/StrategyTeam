package com.metaverse.coin.strategy.job.smartwallet.controller;

import com.metaverse.coin.strategy.job.smartwallet.model.TraceTimelineView;
import com.metaverse.coin.strategy.job.smartwallet.service.TraceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跟单链路 trace_id 查询 (W1-T3).
 *
 * 示例:
 *   GET /strategy/api/smart-wallet/trace/tev_000000123456
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md.
 */
@Slf4j
@RestController
@RequestMapping("/smart-wallet/trace")
@RequiredArgsConstructor
@Tag(name = "Trace Query", description = "跟单链路 trace_id 反查 (W1-T3)")
public class TraceQueryController {

    private final TraceQueryService traceQueryService;

    @Operation(summary = "按 trace_id 反查全链路",
               description = "返回该 trace 对应的 trade_event / follow_task / order / trade_order / position 时间线与关键耗时, 用于线上问题定位与 Live↔Replay diff 报表的逐 trace 详情.")
    @GetMapping("/{traceId}")
    public TraceTimelineView trace(@PathVariable("traceId") String traceId) {
        return traceQueryService.queryByTraceId(traceId);
    }
}
