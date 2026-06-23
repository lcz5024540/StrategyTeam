package com.metaverse.coin.strategy.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyManualTriggerMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerAction;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binance 单交易所资金费率套利触发接口。
 *
 * <p>web 端只负责接受 HTTP 请求并向 strategy_manual_trigger 写入一条
 * FUNDING_ARB_EXECUTE 命令；实际执行仍由 job 端的
 * {@code StrategyManualTriggerConsumer} 调用
 * {@code BinanceSingleExchangeFundingRateArbitrageService} 完成。</p>
 *
 * <p>为了保持前端的同步语义，这里在写入命令后短轮询命令表，
 * 命令变为 COMPLETED/FAILED 时返回 message；超时则返回提示信息。</p>
 */
@RestController
@RequestMapping("/binance/strategy/arbitrage")
public class BinanceArbitrageController {

    /** 单次请求最长等待时间（毫秒）。 */
    private static final long MAX_WAIT_MS = 60_000L;

    /** 轮询间隔（毫秒）。 */
    private static final long POLL_INTERVAL_MS = 250L;

    private final StrategyManualTriggerMapper triggerMapper;
    private final ObjectMapper objectMapper;

    public BinanceArbitrageController(StrategyManualTriggerMapper triggerMapper,
                                      ObjectMapper objectMapper) {
        this.triggerMapper = triggerMapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/execute", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> executeArbitrage(@RequestParam(required = false) String symbol,
                                                   @RequestParam(required = false) String symbols,
                                                   @RequestParam BigDecimal amount,
                                                   @RequestParam(defaultValue = "0.0001") BigDecimal minRate,
                                                   @RequestParam(defaultValue = "3") Integer leverage) throws Exception {
        String targetSymbols = symbols;
        if ((targetSymbols == null || targetSymbols.isBlank()) && symbol != null && !symbol.isBlank()) {
            targetSymbols = symbol;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbols", targetSymbols);
        payload.put("amount", amount);
        payload.put("minRate", minRate);
        payload.put("leverage", leverage);

        StrategyManualTriggerEntity trigger = new StrategyManualTriggerEntity()
                .setStrategyId(StrategyManualTriggerAction.GLOBAL_STRATEGY_ID)
                .setAction(StrategyManualTriggerAction.FUNDING_ARB_EXECUTE)
                .setStatus(StrategyManualTriggerStatus.PENDING)
                .setPayload(objectMapper.writeValueAsString(payload))
                .setRequestedAt(new Date());
        triggerMapper.insert(trigger);

        return ResponseEntity.ok(waitForResult(trigger.getId()));
    }

    private String waitForResult(Long triggerId) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < MAX_WAIT_MS) {
            StrategyManualTriggerEntity row = triggerMapper.selectById(triggerId);
            if (row == null) {
                return "命令记录丢失 (trigger=" + triggerId + ")";
            }
            String status = row.getStatus();
            if (StrategyManualTriggerStatus.COMPLETED.equals(status)
                    || StrategyManualTriggerStatus.FAILED.equals(status)) {
                String message = row.getMessage();
                return message == null ? "" : message;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return "执行超时（trigger=" + triggerId + "），后台仍可能在运行；可稍后查询命令状态。";
    }
}
