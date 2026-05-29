package com.metaverse.coin.strategy.job.smartwallet.controller;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.service.PositionPriceMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 持仓价格监控 dashboard 接口。
 *
 * <p>暴露 {@link PositionPriceMonitorService} 内存 ring buffer 里最近的空 bid 告警快照,
 * 供前端运维面板回看 (DB 落表通道是 strategy_alert, 但 ring buffer 提供"开机至今 N 条"的
 * 实时无 SQL 视图)。</p>
 *
 * <p>所有端点 read-only, 不需要鉴权之外的额外保护; 不会触发 IO / 下单, 也不修改任何状态。</p>
 */
@RestController
@RequestMapping("/smart-wallet/position-monitor")
@RequiredArgsConstructor
@Tag(name = "持仓价格监控", description = "PositionPriceMonitorService 的运维查询接口 — 空 bid 告警快照、配置概览。")
public class PositionPriceMonitorController {

    private final PositionPriceMonitorService positionPriceMonitorService;
    private final SmartWalletProperties properties;

    @Operation(summary = "最近空 bid 告警快照",
            description = "返回 PositionPriceMonitorService 内存 ring buffer 中最近的空盘口告警, 默认按时间倒序"
                    + " (新→旧)。limit 上限受 ring buffer 大小约束 (默认 200)。")
    @GetMapping("/recent-alerts")
    public Map<String, Object> recentAlerts(@RequestParam(defaultValue = "50") int limit) {
        List<PositionPriceMonitorService.AlertObservation> all = positionPriceMonitorService.recentAlerts();
        int capped = Math.max(1, Math.min(limit, all.size()));
        // ring buffer 内是 旧→新, 这里翻转后再截断 limit 条
        List<PositionPriceMonitorService.AlertObservation> reversed = all.subList(
                Math.max(0, all.size() - capped), all.size());
        // 翻转: 用新建 list 反序
        List<PositionPriceMonitorService.AlertObservation> ordered = new java.util.ArrayList<>(reversed);
        java.util.Collections.reverse(ordered);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalInBuffer", all.size());
        body.put("returned", ordered.size());
        body.put("limit", limit);
        body.put("alerts", ordered);
        return body;
    }

    @Operation(summary = "监控配置概览",
            description = "返回当前生效的 PositionPriceMonitor 配置,便于运维 / 前端快速核对 tier 阈值、节奏、自动减仓开关。")
    @GetMapping("/config")
    public SmartWalletProperties.PositionPriceMonitor config() {
        return properties.getPositionPriceMonitor();
    }

    @Operation(summary = "运行计数器",
            description = "返回 PositionPriceMonitorService 进程内累计计数: 扫描轮次、空 bid 告警次数、"
                    + "各 AutoExitOutcome 计数, 以及当前卡在重试 / 长 cooldown 的仓位失败 streak。"
                    + "无 Micrometer 依赖, 进程重启计数归零。")
    @GetMapping("/counters")
    public Map<String, Object> counters() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scanRoundCount", positionPriceMonitorService.scanRoundCount());
        body.put("emptyBidAlertCount", positionPriceMonitorService.emptyBidAlertCount());
        body.put("autoExitOutcomes", positionPriceMonitorService.autoExitOutcomeSnapshot());
        body.put("activeFailureStreaks", positionPriceMonitorService.activeFailureStreaks());
        return body;
    }
}
