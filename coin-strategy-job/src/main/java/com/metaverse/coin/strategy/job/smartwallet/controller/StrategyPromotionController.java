package com.metaverse.coin.strategy.job.smartwallet.controller;

import com.metaverse.coin.strategy.job.smartwallet.model.StrategyPromotionEvaluationRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyPromotionView;
import com.metaverse.coin.strategy.job.smartwallet.service.StrategyPromotionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/smart-wallet/strategy-promotion")
public class StrategyPromotionController {
    private final StrategyPromotionService promotionService;

    @PostMapping("/evaluate")
    public StrategyPromotionView evaluate(@RequestBody StrategyPromotionEvaluationRequest request) {
        return promotionService.evaluate(request);
    }

    @GetMapping
    public ResponseEntity<StrategyPromotionView> get() {
        StrategyPromotionView view = promotionService.get();
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }
}
