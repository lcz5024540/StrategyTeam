package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletFillView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletOrderView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletPositionView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletProfileOverrideView;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletProfileReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletReadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletExportController {

    private static final DateTimeFormatter EXPORT_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final SmartWalletReadService smartWalletReadService;
    private final SmartWalletProfileReadService smartWalletProfileReadService;

    public SmartWalletExportController(SmartWalletReadService smartWalletReadService,
                                       SmartWalletProfileReadService smartWalletProfileReadService) {
        this.smartWalletReadService = smartWalletReadService;
        this.smartWalletProfileReadService = smartWalletProfileReadService;
    }

    @GetMapping(value = "/orders/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportOrders(@RequestParam(defaultValue = "5000") Integer limit,
                                                              @RequestParam(required = false) String mode) {
        List<SmartWalletOrderView> rows = smartWalletReadService.orders(limit, mode);
        return csvResponse(
                List.of("id", "executionAddress", "marketId", "question", "outcome", "side", "orderType", "status",
                        "notionalUsdc", "costBasisUsdc", "feeUsdc", "tradingFeeUsdc", "gasFeeUsdc", "feePol",
                        "realizedPnlUsdc", "exitReason", "placedAt", "filledAt"),
                rows.stream().map(row -> Arrays.<Object>asList(
                        row.id(), row.executionAddress(), row.marketId(), row.question(), row.outcome(),
                        row.side(), row.orderType(), row.status(), row.notionalUsdc(), row.costBasisUsdc(),
                        row.feeUsdc(), row.tradingFeeUsdc(), row.gasFeeUsdc(), row.feePol(),
                        row.realizedPnlUsdc(), row.exitReason(), row.placedAt(), row.filledAt()
                )).toList(),
                "smart_wallet_orders", mode);
    }

    @GetMapping(value = "/fills/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportFills(@RequestParam(defaultValue = "5000") Integer limit,
                                                             @RequestParam(required = false) String mode) {
        List<SmartWalletFillView> rows = smartWalletReadService.fills(limit, mode);
        return csvResponse(
                List.of("id", "orderId", "executionAddress", "marketId", "question", "outcome", "side", "price", "size",
                        "notionalUsdc", "feeUsdc", "tradingFeeUsdc", "gasFeeUsdc", "feePol", "realizedPnlUsdc", "filledAt"),
                rows.stream().map(row -> Arrays.<Object>asList(
                        row.id(), row.orderId(), row.executionAddress(), row.marketId(), row.question(),
                        row.outcome(), row.side(), row.price(), row.size(), row.notionalUsdc(),
                        row.feeUsdc(), row.tradingFeeUsdc(), row.gasFeeUsdc(), row.feePol(),
                        row.realizedPnlUsdc(), row.filledAt()
                )).toList(),
                "smart_wallet_fills", mode);
    }

    @GetMapping(value = "/positions/live/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportLivePositions(@RequestParam(required = false) String mode) {
        List<SmartWalletPositionView> rows = smartWalletReadService.livePositions(mode);
        return csvResponse(
                List.of("id", "executionAddress", "marketId", "question", "outcome", "status", "quantity",
                        "averageEntryPrice", "costBasisUsdc", "originalCostBasisUsdc", "feePolTotal",
                        "realizedPnlUsdc", "unrealizedPnlUsdc", "pendingSettlementNetPnlUsdc",
                        "claimableProceedsUsdc", "marketEndAtBjt", "openedAt", "lastFilledAt", "closedAt"),
                rows.stream().map(this::positionRow).toList(),
                "smart_wallet_live_positions", mode);
    }

    @GetMapping(value = "/positions/next-settlements/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportNextSettlements(@RequestParam(defaultValue = "5000") Integer limit,
                                                                       @RequestParam(required = false) String mode) {
        List<SmartWalletPositionView> rows = smartWalletReadService.nextSettlements(limit, mode);
        return csvResponse(
                List.of("id", "executionAddress", "marketId", "question", "outcome", "status", "quantity",
                        "costBasisUsdc", "feePolTotal", "pendingSettlementNetPnlUsdc", "claimableProceedsUsdc",
                        "marketEndAtBjt", "openedAt", "lastFilledAt", "closedAt"),
                rows.stream().map(row -> Arrays.<Object>asList(
                        row.id(), row.executionAddress(), row.marketId(), row.question(), row.outcome(),
                        row.status(), row.quantity(), row.costBasisUsdc(), row.feePolTotal(),
                        row.pendingSettlementNetPnlUsdc(), row.claimableProceedsUsdc(),
                        row.marketEndAtBjt(), row.openedAt(), row.lastFilledAt(), row.closedAt()
                )).toList(),
                "smart_wallet_next_settlements", mode);
    }

    @GetMapping(value = "/positions/history/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportPositionHistory(@RequestParam(defaultValue = "false") boolean closedOnly,
                                                                       @RequestParam(required = false) String mode) {
        List<SmartWalletPositionView> rows = smartWalletReadService.allPositions(closedOnly, mode);
        return csvResponse(
                List.of("id", "executionAddress", "marketId", "question", "outcome", "status", "quantity",
                        "averageEntryPrice", "costBasisUsdc", "originalCostBasisUsdc", "feePolTotal",
                        "realizedPnlUsdc", "unrealizedPnlUsdc", "pendingSettlementNetPnlUsdc",
                        "claimableProceedsUsdc", "marketEndAtBjt", "openedAt", "lastFilledAt", "closedAt"),
                rows.stream().map(this::positionRow).toList(),
                closedOnly ? "smart_wallet_closed_positions" : "smart_wallet_position_history", mode);
    }

    @GetMapping(value = "/profiles/overrides/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportProfileOverrides(@RequestParam(defaultValue = "500") Integer limit) {
        List<SmartWalletProfileOverrideView> rows = smartWalletProfileReadService.listManualOverrides(limit);
        return csvResponse(
                List.of("walletAddress", "alias", "qualified", "watchEnabled", "manualWatchEnabled",
                        "manualRecommendedExitLane", "manualOverrideNote", "manualOverrideUpdatedAt"),
                rows.stream().map(row -> Arrays.<Object>asList(
                        row.walletAddress(), row.alias(), row.qualified(), row.watchEnabled(),
                        row.manualWatchEnabled(), row.manualRecommendedExitLane(),
                        row.manualOverrideNote(), row.manualOverrideUpdatedAt()
                )).toList(),
                "smart_wallet_profile_overrides", "manual");
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    private List<Object> positionRow(SmartWalletPositionView row) {
        return Arrays.asList(
                row.id(), row.executionAddress(), row.marketId(), row.question(), row.outcome(),
                row.status(), row.quantity(), row.averageEntryPrice(), row.costBasisUsdc(),
                row.originalCostBasisUsdc(), row.feePolTotal(), row.realizedPnlUsdc(),
                row.unrealizedPnlUsdc(), row.pendingSettlementNetPnlUsdc(), row.claimableProceedsUsdc(),
                row.marketEndAtBjt(), row.openedAt(), row.lastFilledAt(), row.closedAt());
    }

    private ResponseEntity<StreamingResponseBody> csvResponse(List<String> headers,
                                                               List<? extends List<?>> rows,
                                                               String prefix, String mode) {
        String suffix = mode == null || mode.isBlank() ? "all" : mode.trim().toLowerCase();
        String filename = prefix + "_" + suffix + "_" + LocalDateTime.now().format(EXPORT_TS) + ".csv";
        StreamingResponseBody body = outputStream -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            writer.write('\uFEFF');
            writer.write(String.join(",", headers));
            writer.newLine();
            for (List<?> row : rows) {
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) writer.write(',');
                    writer.write(csvCell(row.get(i)));
                }
                writer.newLine();
            }
            writer.flush();
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv"))
                .body(body);
    }

    private String csvCell(Object value) {
        if (value == null) return "";
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }
}
