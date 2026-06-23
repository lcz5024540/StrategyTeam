package com.metaverse.coin.strategy.job.smartwallet.config;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.service.ExitSignalProvider;
import com.metaverse.coin.strategy.job.smartwallet.service.ScoredExitSignalProvider;
import com.metaverse.coin.strategy.job.smartwallet.service.TimeAwareStopLossService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects which ExitSignalProvider implementation to use based on
 * smart-wallet.signal-risk.exit-signal-provider-mode:
 *   RULES   → TimeAwareStopLossService (rule-based, default)
 *   SCORING → ScoredExitSignalProvider (Method C feature scoring)
 */
@Configuration
public class ExitSignalProviderConfig {

    @Bean
    @Primary
    public ExitSignalProvider exitSignalProvider(
            SmartWalletProperties properties,
            TimeAwareStopLossService timeAwareStopLossService,
            ScoredExitSignalProvider scoredExitSignalProvider) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        String mode = (risk == null || risk.getExitSignalProviderMode() == null)
                ? "RULES" : risk.getExitSignalProviderMode().toUpperCase();
        return "SCORING".equals(mode) ? scoredExitSignalProvider : timeAwareStopLossService;
    }
}
