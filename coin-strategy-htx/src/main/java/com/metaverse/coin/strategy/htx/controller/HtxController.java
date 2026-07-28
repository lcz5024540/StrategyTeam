package com.metaverse.coin.strategy.htx.controller;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.htx.service.HtxLockedPairRequest;
import com.metaverse.coin.strategy.htx.service.HtxLockedPairResult;
import com.metaverse.coin.strategy.htx.service.HtxTradingService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP triggers for HTX spot execution. Only mounted when the HTX SDK is wired
 * ({@link HtxApiService} bean exists — i.e. AK/SK are set in {@code application.yml}).
 * <p>
 * Endpoint intent is <b>operational</b>: verify credentials, inspect depth, and fire a locked
 * pair for smoke tests. Strategies should call {@link HtxTradingService#submitLockedPair}
 * directly rather than round-trip through HTTP.
 */
@RestController
@RequestMapping("/htx")
@RequiredArgsConstructor
@ConditionalOnBean(HtxApiService.class)
public class HtxController {

    private final HtxApiService htxApiService;
    private final HtxTradingService tradingService;

    /**
     * List HTX spot / margin / otc accounts. Use once to grab your {@code spotAccountId}
     * and write it back into {@code htx.api.spot-account-id}.
     */
    @GetMapping("/accounts")
    public HtxRestResponse<List<HtxAccount>> listAccounts() {
        return htxApiService.getAccountService().listAccounts();
    }

    /**
     * Fetch a step0 depth snapshot for a symbol. Useful for confirming that the guard would pass
     * before firing a locked pair.
     *
     * @param symbol e.g. {@code btcusdt}
     * @param depth  1/5/10/20
     */
    @GetMapping("/spot/depth")
    public HtxRestResponse<HtxDepth> depth(@RequestParam String symbol,
                                           @RequestParam(defaultValue = "10") int depth) {
        return htxApiService.getMarketDataService().depth(symbol, depth);
    }

    /**
     * Submit a locked buy+sell pair.
     * <p>
     * Behavior depends on {@code htx.trade.enabled}: false → dry-run (log only); true → real orders.
     */
    @PostMapping("/spot/locked-pair")
    public HtxLockedPairResult submitLockedPair(@RequestBody HtxLockedPairRequest request) {
        return tradingService.submitLockedPair(request);
    }
}
