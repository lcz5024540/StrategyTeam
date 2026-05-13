package com.metaverse.coin.strategy.web.controller;

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

    /**
     * 仅保留 Binance 相关路径的代理。
     * <p>
     * 历史上还代理过 {@code /api/weather/**}，但 weather 已收敛为 job 内网调度任务
     * （{@code WeatherMarketDataCollectorJob} 通过 {@link org.springframework.scheduling.annotation.Scheduled}
     * 触发），前端不再调用，故此处移除对外转发，不再经 web 暴露 weather 接口。
     * <p>
     * 后续 Binance 部分会作为单独的迁移项处理；目前按需求暂不改动。
     */
    @RequestMapping({
            "/binance/**"
    })
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @org.springframework.web.bind.annotation.RequestBody(required = false) byte[] body)
            throws IOException, InterruptedException {
        return jobApiForwardService.forward(request, body);
    }
}
