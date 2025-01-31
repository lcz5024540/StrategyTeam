package com.metaverse.coin.strategy.common.chain.evm.job;

import com.metaverse.coin.strategy.common.chain.evm.config.ChainConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.List;

/**
 * 大额监控包含以下内容
 * 1.监控转帐地址余额
 * 2.大户地址监控
 * 3.金额折算超过一定数额监控
 */

@Component
@Slf4j
public class BigAmountMonitorJob {

    @Autowired
    private ChainConfig chainConfig;
    
    @Scheduled(cron ="0 0 12 * * ?", zone = "UTC")
    public void lottery() {
        log.info("=====大额转账监控=====");

    }
}
