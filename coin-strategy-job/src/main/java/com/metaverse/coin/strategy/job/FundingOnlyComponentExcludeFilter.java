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

    private static final List<String> EXCLUDED_PACKAGE_PREFIXES = List.of(
            "com.metaverse.coin.strategy.job.smartwallet.",
            "com.metaverse.coin.strategy.job.btcminute.",
            "com.metaverse.coin.strategy.job.execution.",
            "com.metaverse.coin.strategy.job.marketdata.",
            "com.metaverse.coin.strategy.job.engine.control.",
            "com.metaverse.coin.strategy.job.grid.",
            "com.metaverse.coin.strategy.job.analytics.",
            "com.metaverse.coin.strategy.job.follow.",
            "com.metaverse.coin.strategy.job.settlement.",
            "com.metaverse.coin.strategy.job.risk.",
            "com.metaverse.coin.strategy.job.weather.",
            "com.metaverse.coin.strategy.common.service.smartwallet."
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
        return EXCLUDED_PACKAGE_PREFIXES.stream().anyMatch(className::startsWith);
    }
}
