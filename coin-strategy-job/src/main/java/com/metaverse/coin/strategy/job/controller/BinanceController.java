package com.metaverse.coin.strategy.job.controller;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo;
import com.metaverse.coin.strategy.job.service.BinanceTradingService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/binance")
@RequiredArgsConstructor
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
public class BinanceController {

    private final BinanceTradingService binanceTradingService;
    private final BinanceApiService binanceApiService;
     //================= SPOT =================

    @GetMapping("/getAccount")
    public BinanceAccountInfo getAccountInfo(){
        BinanceAccountInfo accountInfo = binanceApiService.getAccountInfo();
        return accountInfo;
    }

    @PostMapping("/spot/buy")
    public String spotBuy(@RequestParam String symbol, @RequestParam String quantity, @RequestParam(required = false) String price) {
        return binanceTradingService.spotBuy(symbol, quantity, price);
    }

    @PostMapping("/spot/sell")
    public String spotSell(@RequestParam String symbol, @RequestParam String quantity, @RequestParam(required = false) String price) {
        return binanceTradingService.spotSell(symbol, quantity, price);
    }

    // ================= FUTURES =================

    @PostMapping("/futures/open-long")
    public String openLong(@RequestParam String symbol, @RequestParam String quantity, @RequestParam(required = false) String price) {
        return binanceTradingService.openLong(symbol, quantity, price);
    }

    @PostMapping("/futures/close-long")
    public String closeLong(@RequestParam String symbol, @RequestParam String quantity, @RequestParam(required = false) String price) {
        return binanceTradingService.closeLong(symbol, quantity, price);
    }

    @PostMapping("/futures/open-short")
    public String openShort(@RequestParam String symbol, @RequestParam String quantity, @RequestParam(required = false) String price) {
        return binanceTradingService.openShort(symbol, quantity, price);
    }

    @PostMapping("/futures/close-short")
    public String closeShort(@RequestParam String symbol, @RequestParam String quantity, @RequestParam(required = false) String price) {
        return binanceTradingService.closeShort(symbol, quantity, price);
    }

    @PostMapping("/futures/leverage")
    public String setLeverage(@RequestParam String symbol, @RequestParam Integer leverage) {
        return binanceTradingService.setLeverage(symbol, leverage);
    }

    @PostMapping("/futures/margin-type")
    public String setMarginType(@RequestParam String symbol, @RequestParam String marginType) {
        return binanceTradingService.setMarginType(symbol, marginType);
    }
}
