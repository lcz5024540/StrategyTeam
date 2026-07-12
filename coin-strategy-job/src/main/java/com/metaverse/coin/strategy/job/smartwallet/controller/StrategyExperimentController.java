package com.metaverse.coin.strategy.job.smartwallet.controller;

import com.metaverse.coin.strategy.job.smartwallet.model.StrategyExperimentResult;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyExperimentSpec;
import com.metaverse.coin.strategy.job.smartwallet.service.StrategyExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/smart-wallet/strategy-experiments")
public class StrategyExperimentController {

    private final StrategyExperimentService experimentService;

    @PostMapping
    public StrategyExperimentResult execute(@RequestBody StrategyExperimentSpec spec) {
        return experimentService.execute(spec);
    }

    @GetMapping("/{experimentId}")
    public ResponseEntity<StrategyExperimentResult> get(@PathVariable String experimentId) {
        StrategyExperimentResult result = experimentService.get(experimentId);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }
}
