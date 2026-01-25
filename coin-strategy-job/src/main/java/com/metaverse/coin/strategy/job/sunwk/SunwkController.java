package com.metaverse.coin.strategy.job.sunwk;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sunwk")
@RequiredArgsConstructor
public class SunwkController {

    private final SunwkHedgeStrategy sunwkHedgeStrategy;

    /**
     * 手动触发双向开仓
     * URL: POST /sunwk/open?contractCode=BTC-USDT&quantity=1
     */
    @PostMapping("/open")
    public String openDualPositions(@RequestParam String contractCode, @RequestParam String quantity) {
        sunwkHedgeStrategy.openDualPositions(contractCode, quantity);
        return "Open Dual Positions triggered for " + contractCode;
    }

    /**
     * 手动触发双向平仓
     * URL: POST /sunwk/close?contractCode=BTC-USDT&quantity=1
     */
    @PostMapping("/close")
    public String closeDualPositions(@RequestParam String contractCode, @RequestParam String quantity) {
        sunwkHedgeStrategy.closeDualPositions(contractCode, quantity);
        return "Close Dual Positions triggered for " + contractCode;
    }
}
