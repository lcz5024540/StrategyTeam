package com.metaverse.coin.strategy.htx.config;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.service.HtxLockedPairExecutor;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires HTX SDK beans when both AK and SK are present.
 * <p>
 * Same conditional-wiring pattern as {@code coin-strategy-job/config/BinanceConfig}: when either
 * credential is empty the whole configuration is skipped, {@link HtxApiService} is not created,
 * and {@code HtxTradingService} (guarded by {@code @ConditionalOnBean}) also disappears — so
 * downstream {@code @Autowired} fails at wiring time rather than at first call.
 */
@Configuration
@ConditionalOnExpression("'${htx.api.access-key:}' != '' && '${htx.api.secret-key:}' != ''")
@EnableConfigurationProperties({HtxTradeProperties.class, HtxMarketMakerProperties.class, HtxFundingArbitrageProperties.class})
@RequiredArgsConstructor
public class HtxConfig {

    private final HtxTradeProperties props;

    @Bean
    public HtxApiService htxApiService() {
        return new HtxApiService(
                props.getApi().getAccessKey(),
                props.getApi().getSecretKey(),
                props.getApi().getBaseUrl(),
                props.getApi().getWsUrl());
    }

    @Bean
    public HtxLockedPairExecutor htxLockedPairExecutor(HtxApiService htxApiService,
                                                       HtxTradeRecorder recorder) {
        return new HtxLockedPairExecutor(htxApiService, props, recorder);
    }
}
