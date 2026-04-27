package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSelectionSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SmartWalletSelectionService {

    private final SmartWalletProfileStoreService profileStoreService;

    public SmartWalletSelectionSummary refreshQualifiedWallets() {
        int qualifiedCount = 0;
        int executableCount = 0;
        int rejectedCount = 0;
        List<String> notes = new ArrayList<>();

        for (SmartWalletProfileEntity entity : profileStoreService.listAll()) {
            profileStoreService.applyQualificationState(entity);
            entity.setLastEvaluatedAt(new Date());
            profileStoreService.update(entity);

            if (Boolean.TRUE.equals(entity.getQualified())) {
                qualifiedCount++;
                if (Boolean.TRUE.equals(entity.getWatchEnabled())) {
                    executableCount++;
                }
            } else {
                rejectedCount++;
            }
        }

        notes.add("Qualified wallets are selected from Polymarket source metrics.");
        notes.add("Executable watchlist is further gated by local followability archetype and fit score.");
        return SmartWalletSelectionSummary.builder()
                .qualifiedCount(qualifiedCount)
                .executableCount(executableCount)
                .rejectedCount(rejectedCount)
                .notes(notes)
                .build();
    }
}
