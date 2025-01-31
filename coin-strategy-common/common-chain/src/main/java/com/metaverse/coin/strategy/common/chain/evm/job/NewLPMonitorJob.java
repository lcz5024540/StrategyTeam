package com.metaverse.coin.strategy.common.chain.evm.job;

import com.metaverse.coin.strategy.common.chain.evm.config.ChainConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 新创建LP监控
 * 1、新创建的地址，持有代币的地址数统计
 * 2、新创建地址，大量买入的地址数统计
 * 3、新创建地址，注入流动性的地址数统计（可能存在老鼠仓、大户做仓地址）
 */

@Component
@Slf4j
public class NewLPMonitorJob {

    @Autowired
    private ChainConfig chainConfig;
    
    @Scheduled(cron ="0 0 12 * * ?", zone = "UTC")
    public void lottery() {
        log.info("=====大额转账监控=====");

    }
}
