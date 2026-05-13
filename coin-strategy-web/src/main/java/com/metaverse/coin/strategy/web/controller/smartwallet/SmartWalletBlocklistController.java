package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualBlocklistRequest;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualBlocklistView;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletBlocklistWriteService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Smart-wallet manual blocklist 写接口（A 类，纯 DB）。
 */
@RestController
@RequestMapping("/smart-wallet/blocklist")
public class SmartWalletBlocklistController {

    private final SmartWalletBlocklistWriteService service;

    public SmartWalletBlocklistController(SmartWalletBlocklistWriteService service) {
        this.service = service;
    }

    @PostMapping
    public SmartWalletManualBlocklistView upsert(@RequestBody SmartWalletManualBlocklistRequest request) {
        return service.upsert(request);
    }

    @PostMapping("/batch")
    public List<SmartWalletManualBlocklistView> upsertBatch(@RequestBody SmartWalletManualBlocklistRequest request) {
        return service.upsertBatch(request);
    }

    @PostMapping("/{walletAddress}")
    public SmartWalletManualBlocklistView upsertByWallet(@PathVariable String walletAddress,
                                                         @RequestBody(required = false) SmartWalletManualBlocklistRequest request) {
        return service.upsert(walletAddress, request);
    }

    @PostMapping("/{walletAddress}/disable")
    public SmartWalletManualBlocklistView disable(@PathVariable String walletAddress) {
        return service.disable(walletAddress);
    }
}
