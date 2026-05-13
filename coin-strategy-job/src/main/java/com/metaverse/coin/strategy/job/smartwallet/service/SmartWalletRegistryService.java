package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletProfile;
import com.metaverse.coin.strategy.job.smartwallet.model.UpsertWalletRequest;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SmartWalletRegistryService {

    private final SmartWalletProperties properties;
    private final Map<String, SmartWalletProfile> wallets = new ConcurrentHashMap<>();

    public SmartWalletRegistryService(SmartWalletProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void loadSeeds() {
        for (SmartWalletProperties.WalletSeed seed : properties.getWallets()) {
            SmartWalletProfile profile = SmartWalletProfile.builder()
                    .address(seed.getAddress())
                    .chain(seed.getChain())
                    .alias(seed.getAlias())
                    .winRate(seed.getWinRate())
                    .realizedPnlUsdc(seed.getRealizedPnlUsdc())
                    .totalTrades(seed.getTotalTrades())
                    .averageSizeUsdc(seed.getAverageSizeUsdc())
                    .maxDrawdownRate(seed.getMaxDrawdownRate())
                    .preferredOutcome(seed.getPreferredOutcome())
                    .tags(seed.getTags())
                    .build();
            wallets.put(profile.getAddress(), profile);
        }
    }

    public List<SmartWalletProfile> list() {
        List<SmartWalletProfile> result = new ArrayList<>(wallets.values());
        result.sort(Comparator.comparing(SmartWalletProfile::getAddress));
        return result;
    }

    public SmartWalletProfile upsert(UpsertWalletRequest request) {
        SmartWalletProfile profile = SmartWalletProfile.builder()
                .address(request.getAddress())
                .chain(request.getChain())
                .alias(request.getAlias())
                .winRate(request.getWinRate())
                .realizedPnlUsdc(request.getRealizedPnlUsdc())
                .totalTrades(request.getTotalTrades())
                .averageSizeUsdc(request.getAverageSizeUsdc())
                .maxDrawdownRate(request.getMaxDrawdownRate())
                .preferredOutcome(request.getPreferredOutcome())
                .tags(request.getTags())
                .build();
        wallets.put(profile.getAddress(), profile);
        return profile;
    }

    public int clearAll() {
        int size = wallets.size();
        wallets.clear();
        return size;
    }
}
