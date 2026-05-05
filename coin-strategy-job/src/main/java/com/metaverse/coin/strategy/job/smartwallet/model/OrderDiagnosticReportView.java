package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OrderDiagnosticReportView {
    private List<Long> requestedOrderIds;
    private Long currentMinOrderId;
    private Long currentMaxOrderId;
    private Boolean archiveTablePresent;
    private List<OrderDiagnosticView> orders;
}
