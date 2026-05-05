package com.metaverse.coin.strategy.job.strategy.controller;

import com.metaverse.coin.strategy.job.strategy.application.StrategyLogQueryService;
import com.metaverse.coin.strategy.job.strategy.application.StrategyFlowLogReader;
import com.metaverse.coin.strategy.job.strategy.model.StrategyLogEntryView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyLogFlowView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/strategy-platform/logs")
@Tag(name = "Strategy Logs", description = "平台级策略运行日志查询接口")
public class StrategyLogController {

    private final StrategyLogQueryService strategyLogQueryService;
    private final StrategyFlowLogReader strategyFlowLogReader;

    public StrategyLogController(StrategyLogQueryService strategyLogQueryService,
                                 StrategyFlowLogReader strategyFlowLogReader) {
        this.strategyLogQueryService = strategyLogQueryService;
        this.strategyFlowLogReader = strategyFlowLogReader;
    }

    @Operation(summary = "策略运行日志")
    @GetMapping
    public List<StrategyLogEntryView> logs(@RequestParam(defaultValue = "app") String source,
                                           @RequestParam(defaultValue = "ALL") String level,
                                           @RequestParam(defaultValue = "ALL") String strategy,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "200") Integer limit) {
        try {
            return strategyLogQueryService.query(source, level, strategy, q, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @Operation(summary = "策略运行流程日志")
    @GetMapping("/flows")
    public List<StrategyLogFlowView> flows(@RequestParam(defaultValue = "app") String source,
                                           @RequestParam(defaultValue = "POLYMARKET_COPY") String strategy,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String wallet,
                                           @RequestParam(required = false) String eventId,
                                           @RequestParam(required = false) String followTaskId,
                                           @RequestParam(required = false) String marketId,
                                           @RequestParam(defaultValue = "200") Integer limit) {
        try {
            return strategyFlowLogReader.queryFlows(source, strategy, q, wallet, eventId, followTaskId, marketId, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
