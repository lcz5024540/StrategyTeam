package com.metaverse.coin.strategy.web.controller.subscription;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/subscription/api")
public class SubscriptionController {

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return Map.of(
                "status", "PLANNED",
                "message", "订阅与跟投管理接口边界已预留，后续将补订阅列表、暂停恢复、风控配置等能力。"
        );
    }
}
