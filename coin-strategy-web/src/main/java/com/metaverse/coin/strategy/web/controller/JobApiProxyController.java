package com.metaverse.coin.strategy.web.controller;

import com.metaverse.coin.strategy.web.config.DashboardProperties;
import com.metaverse.coin.strategy.web.service.JobApiForwardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class JobApiProxyController {

    private final JobApiForwardService jobApiForwardService;

    public JobApiProxyController(JobApiForwardService jobApiForwardService) {
        this.jobApiForwardService = jobApiForwardService;
    }

    @RequestMapping({
            "/strategy-engine/**",
            "/binance/**",
            "/sunwk/**",
            "/api/weather/**"
    })
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @org.springframework.web.bind.annotation.RequestBody(required = false) byte[] body)
            throws IOException, InterruptedException {
        return jobApiForwardService.forward(request, body);
    }
}
