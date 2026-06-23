package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualAllowlistRequest;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualAllowlistView;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletAllowlistWriteService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Smart-wallet manual allowlist 写接口（A 类，纯 DB）。
 */
@RestController
@RequestMapping("/smart-wallet/allowlist")
public class SmartWalletAllowlistController {

    private final SmartWalletAllowlistWriteService service;

    public SmartWalletAllowlistController(SmartWalletAllowlistWriteService service) {
        this.service = service;
    }

    @PostMapping
    public SmartWalletManualAllowlistView upsert(@RequestBody SmartWalletManualAllowlistRequest request) {
        return service.upsert(request);
    }

    @PostMapping("/{walletAddress}")
    public SmartWalletManualAllowlistView upsertByWallet(@PathVariable String walletAddress,
                                                         @RequestBody(required = false) SmartWalletManualAllowlistRequest request) {
        return service.upsert(walletAddress, request);
    }

    @PostMapping("/{walletAddress}/disable")
    public SmartWalletManualAllowlistView disable(@PathVariable String walletAddress) {
        return service.disable(walletAddress);
    }

    @PostMapping("/cleanup-expired")
    public Map<String, Object> cleanupExpired() {
        return Map.of("updated", service.cleanupExpired());
    }
}
