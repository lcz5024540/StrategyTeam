package com.metaverse.coin.strategy.web.controller.portfolio;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/portfolio/api")
public class PortfolioController {

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return Map.of(
                "status", "PLANNED",
                "message", "用户个人看板接口边界已预留，后续将补用户维度组合、持仓、订单与结算视图。"
        );
    }
}
