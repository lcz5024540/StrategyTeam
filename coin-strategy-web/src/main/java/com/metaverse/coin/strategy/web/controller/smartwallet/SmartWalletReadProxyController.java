package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.metaverse.coin.strategy.web.service.JobApiForwardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class SmartWalletReadProxyController {

    private final JobApiForwardService jobApiForwardService;

    public SmartWalletReadProxyController(JobApiForwardService jobApiForwardService) {
        this.jobApiForwardService = jobApiForwardService;
    }

    @GetMapping("/smart-wallet/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException, InterruptedException {
        return jobApiForwardService.forward(request, new byte[0]);
    }
}
