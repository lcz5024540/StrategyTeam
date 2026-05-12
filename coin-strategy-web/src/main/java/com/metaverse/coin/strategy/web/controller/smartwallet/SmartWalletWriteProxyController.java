package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.metaverse.coin.strategy.web.service.JobApiForwardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class SmartWalletWriteProxyController {

    private final JobApiForwardService jobApiForwardService;

    public SmartWalletWriteProxyController(JobApiForwardService jobApiForwardService) {
        this.jobApiForwardService = jobApiForwardService;
    }

    @RequestMapping(
            path = "/smart-wallet/**",
            method = {
                    org.springframework.web.bind.annotation.RequestMethod.POST,
                    org.springframework.web.bind.annotation.RequestMethod.PUT,
                    org.springframework.web.bind.annotation.RequestMethod.PATCH,
                    org.springframework.web.bind.annotation.RequestMethod.DELETE
            }
    )
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) throws IOException, InterruptedException {
        return jobApiForwardService.forward(request, body);
    }
}
