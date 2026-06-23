package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.PmResolutionAuditMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PmResolutionAuditEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * W1-T2.2 把 {@link ResolutionAuditRecord} 写入 {@code pm_resolution_audit} 表。
 *
 * <p>调用方: {@code SettlementSourceAuditService} 或任何持有 {@link ResolutionAuditRecord}
 * 的代码。失败仅 WARN, 不抛出, 避免污染主链路.</p>
 *
 * <p>JSON 序列化采用手写紧凑实现, 避免引入额外依赖; 字段都是简单列表 / 映射, 不存在
 * 复杂转义需求.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十七节.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionAuditPersistenceService {

    private final PmResolutionAuditMapper auditMapper;

    /** 写一条审计记录. 返回 entity (含 id) 或 null (写失败/输入为空). */
    public PmResolutionAuditEntity persist(ResolutionAuditRecord record) {
        if (record == null) {
            return null;
        }
        try {
            Date now = new Date();
            PmResolutionAuditEntity row = new PmResolutionAuditEntity()
                    .setMarketId(record.getMarketId())
                    .setObservedAt(record.getObservedAt() == null ? now : record.getObservedAt())
                    .setResolutionStatus(record.getResolutionStatus())
                    .setResolutionSource(record.getResolutionSource())
                    .setWinningOutcome(record.getWinningOutcome())
                    .setWinningTokenId(record.getWinningTokenId())
                    .setManualCheckReason(record.getManualCheckReason())
                    .setClosed(record.isClosed())
                    .setAcceptingOrdersPresent(record.isAcceptingOrdersPresent())
                    .setAcceptingOrders(record.isAcceptingOrders())
                    .setUmaResolutionStatus(record.getUmaResolutionStatus())
                    .setUmaResolved(record.isUmaResolved())
                    // F-P1-12: UMA 争议标记 + 首次观察时间. DDL 上线前 entity 字段 @TableField(exist=false),
                    // 该 set 不会落库; 上线后只需删 exist=false 注解, 这里无需改动.
                    .setUmaDisputed(record.isUmaDisputed())
                    .setUmaDisputeObservedAt(record.getUmaDisputeObservedAt())
                    .setOutcomesJson(serializeList(record.getOutcomes()))
                    .setOutcomePricesJson(serializeList(record.getOutcomePrices()))
                    .setOutcomeTokenIdsJson(serializeMap(record.getOutcomeTokenIds()))
                    .setCreatedAt(now);
            auditMapper.insert(row);
            return row;
        } catch (RuntimeException ex) {
            log.warn("[ResolutionAuditPersistence] insert failed marketId={} status={} reason={}",
                    record.getMarketId(), record.getResolutionStatus(), ex.toString());
            return null;
        }
    }

    private String serializeList(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(160).append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(list.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private String serializeMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(160).append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append('"').append(':')
              .append('"').append(escape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        // 简易 JSON string escape — 仅处理双引号 / 反斜杠 / 控制字符常见情形
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
