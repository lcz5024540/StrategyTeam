package com.metaverse.coin.strategy.job.settlement.application;

import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRetryReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRetryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRunView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncStatusView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncSummary;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSettlementSyncService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SettlementApplicationService {

    private final SmartWalletSettlementSyncService smartWalletSettlementSyncService;

    public SettlementApplicationService(SmartWalletSettlementSyncService smartWalletSettlementSyncService) {
        this.smartWalletSettlementSyncService = smartWalletSettlementSyncService;
    }

    public SettlementSyncSummary runNow(int positionLimit, int marketLimit, boolean refreshCache) {
        return smartWalletSettlementSyncService.runNow(positionLimit, marketLimit, refreshCache);
    }

    public SettlementSyncStatusView status() {
        return smartWalletSettlementSyncService.status();
    }

    public List<SettlementRunView> listRuns(int limit) {
        return smartWalletSettlementSyncService.listRuns(limit);
    }

    public List<SettlementRetryView> listRetries(String status, int limit) {
        return smartWalletSettlementSyncService.listRetries(status, limit);
    }

    public SettlementRetryReportView retryReport() {
        return smartWalletSettlementSyncService.retryReport();
    }
}
