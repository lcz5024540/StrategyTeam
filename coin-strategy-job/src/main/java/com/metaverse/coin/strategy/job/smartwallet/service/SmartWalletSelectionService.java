package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSelectionSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SmartWalletSelectionService {

    private final SmartWalletProperties properties;
    private final SmartWalletProfileStoreService profileStoreService;

    public SmartWalletSelectionSummary refreshQualifiedWallets() {
        int qualifiedCount = 0;
        int rejectedCount = 0;
        List<String> notes = new ArrayList<>();

        for (SmartWalletProfileEntity entity : profileStoreService.listAll()) {
            boolean qualified = isQualified(entity);
            entity.setQualified(qualified);
            entity.setWatchEnabled(qualified);
            entity.setLastEvaluatedAt(new Date());
            profileStoreService.update(entity);

            if (qualified) {
                qualifiedCount++;
            } else {
                rejectedCount++;
            }
        }

        notes.add("Qualified wallets are selected from Polymarket metrics only.");
        notes.add("Watchlist is auto-synced to qualified wallets.");
        return SmartWalletSelectionSummary.builder()
                .qualifiedCount(qualifiedCount)
                .rejectedCount(rejectedCount)
                .notes(notes)
                .build();
    }

    private boolean isQualified(SmartWalletProfileEntity entity) {
        return safe(entity.getWinRate()).compareTo(properties.getMinWinRate()) >= 0
                && safe(entity.getWalletScore()).compareTo(properties.getMinWalletScore()) >= 0
                && (entity.getTotalTrades() == null ? 0 : entity.getTotalTrades()) >= properties.getMinTotalTrades()
                && safe(entity.getRealizedPnlUsdc()).compareTo(properties.getMinRealizedPnlUsdc()) >= 0
                && safe(entity.getMaxDrawdownRate()).compareTo(properties.getMaxDrawdownRate()) <= 0;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
