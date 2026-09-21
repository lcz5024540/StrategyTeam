package com.metaverse.coin.strategy.job;

import org.springframework.core.env.Environment;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;

import java.io.IOException;
import java.util.List;

/**
 * Removes Polymarket-related components from the Job application when it is
 * deployed exclusively for cross-exchange funding arbitrage.
 *
 * <p>This is intentionally a component-scan boundary instead of a collection
 * of runtime boolean checks. A scheduled method without its own guard must not
 * be registered at all in {@code FUNDING_ONLY} mode.</p>
 */
public final class FundingOnlyComponentExcludeFilter implements TypeFilter {

    static final String DEPLOYMENT_MODE_PROPERTY = "strategy.deployment-mode";
    static final String FUNDING_ONLY_MODE = "FUNDING_ONLY";

    private static final List<String> FUNDING_ONLY_ALLOWED_CLASS_NAMES = List.of(
            "com.metaverse.coin.strategy.job.CoinStrategyJobApplication",
            "com.metaverse.coin.strategy.job.config.DataSourceConfig",
            "com.metaverse.coin.strategy.job.config.SecretDecryptor",
            "com.metaverse.coin.strategy.job.config.JacksonConfig",
            "com.metaverse.coin.strategy.job.config.MybatisPlusConfig",
            "com.metaverse.coin.strategy.job.config.OpenApiConfig",
            "com.metaverse.coin.strategy.job.config.SchedulingConfig",
            "com.metaverse.coin.strategy.job.funding.config.CrossExchangePaperConfiguration",
            "com.metaverse.coin.strategy.job.funding.controller.CrossExchangeFundingArbitrageController",
            "com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties"
    );

    private static final List<String> FUNDING_ONLY_ALLOWED_PACKAGE_PREFIXES = List.of(
            "com.metaverse.coin.strategy.job.funding.service.CrossExchange",
            "com.metaverse.coin.strategy.job.funding.service.FundingArb",
            "com.metaverse.coin.strategy.core.arbitrage.multi.",
            "com.metaverse.coin.strategy.core.arbitrage.spotperp."
    );

    private final boolean fundingOnly;

    public FundingOnlyComponentExcludeFilter(Environment environment) {
        this.fundingOnly = FUNDING_ONLY_MODE.equalsIgnoreCase(
                environment.getProperty(DEPLOYMENT_MODE_PROPERTY, "FULL").trim());
    }

    @Override
    public boolean match(MetadataReader metadataReader,
                         MetadataReaderFactory metadataReaderFactory) throws IOException {
        if (!fundingOnly) {
            return false;
        }
        String className = metadataReader.getClassMetadata().getClassName();
        return !isFundingOnlyComponentAllowed(className);
    }

    private boolean isFundingOnlyComponentAllowed(String className) {
        return FUNDING_ONLY_ALLOWED_CLASS_NAMES.contains(className)
                || FUNDING_ONLY_ALLOWED_PACKAGE_PREFIXES.stream().anyMatch(className::startsWith);
    }
}
