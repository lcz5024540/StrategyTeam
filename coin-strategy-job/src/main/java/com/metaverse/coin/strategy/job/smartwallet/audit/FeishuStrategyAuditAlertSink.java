package com.metaverse.coin.strategy.job.smartwallet.audit;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertLevel;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.engine.monitor.StrategyAlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * F-P1-9: 飞书 (Lark) 审计告警 Sink. 装饰在默认
 * {@link StrategyAlertServiceAuditAlertSink} 之上：先落 strategy_alert 表 + 日志，
 * 然后视开关 / 严重级别 / 去重窗口决定是否往飞书 webhook 推送一条卡片消息。
 *
 * <p>启用条件 (任意一个不满足都退化为仅走默认 sink)：
 * <ol>
 *   <li>{@code smart-wallet.audit.feishu-sink-enabled=true}</li>
 *   <li>{@code smart-wallet.audit.feishu-webhook-url} 非空</li>
 *   <li>alertType 的 defaultLevel 在 {@code feishu-allowed-severities} 列表内（默认 WARN,CRITICAL,FATAL）</li>
 *   <li>同 alertType 在 {@code feishu-dedupe-interval-ms} 内未推送过</li>
 * </ol>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无任何直接影响</b> —
 * 仅在审计 Job WARN 时被调用；webhook 失败 swallow，不阻塞主流程。
 *
 * <p>详见 docs/polymarket/minimal-implementations-fix-plan.md §三 F-P1-9.
 */
@Slf4j
@Service
@Primary
public class FeishuStrategyAuditAlertSink implements SmartWalletAuditAlertSink {

    private static final String LOG_PREFIX = "[FeishuAuditSink]";

    private final SmartWalletProperties properties;
    private final StrategyAlertService strategyAlertService;
    private final RestTemplate restTemplate;

    /** alertType → 上次推送时间戳（毫秒），ConcurrentHashMap 保证 sink 在多线程下也安全。 */
    private final Map<String, Long> lastFeishuPush = new ConcurrentHashMap<>();

    @Autowired
    public FeishuStrategyAuditAlertSink(SmartWalletProperties properties,
                                        StrategyAlertService strategyAlertService) {
        this.properties = properties;
        this.strategyAlertService = strategyAlertService;
        int timeoutMs = properties == null || properties.getAudit() == null
                ? 3_000
                : Math.max(500, properties.getAudit().getFeishuWebhookTimeoutMs());
        this.restTemplate = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofMillis(Math.min(timeoutMs, 2_000)))
                .setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Override
    public void publish(AlertType alertType, String title, String message,
                        BigDecimal metricValue, BigDecimal threshold) {
        if (!isSinkEnabled()) {
            return;
        }
        if (alertType == null) {
            return;
        }
        // 1) 先落库 strategy_alert + 日志（保持与既有 sink 行为完全一致）
        int created = 0;
        try {
            created = strategyAlertService.fireAlert(
                    STRATEGY_ID,
                    alertType,
                    title == null ? alertType.getDescription() : title,
                    message,
                    metricValue,
                    threshold);
        } catch (RuntimeException e) {
            log.warn("{} fireAlert failed type={} title={} err={}", LOG_PREFIX, alertType, title, e.toString());
        }
        // 2) 再发飞书 (跳过 INFO 噪音 + dedupe + webhook 健康检查在 sendFeishuCard 内自检)
        if (created > 0 && shouldPushToFeishu(alertType)) {
            sendFeishuCard(alertType, title, message, metricValue, threshold);
        }
    }

    @Override
    public void publish(String strategyId, String alertType, String title, String message,
                        BigDecimal metricValue, BigDecimal threshold) {
        if (!isSinkEnabled()) return;
        if (strategyId == null || alertType == null) return;
        AlertType type;
        try {
            type = AlertType.valueOf(alertType);
        } catch (IllegalArgumentException notKnown) {
            // 跨策略未知 alertType: 仅落库, 不推飞书 (避免跨策略噪音)
            try {
                log.info("{} cross-strategy publish skipped feishu (alertType={} not in enum) strategyId={} title={}",
                        LOG_PREFIX, alertType, strategyId, title);
            } catch (RuntimeException ignored) { /* swallow */ }
            return;
        }
        if (STRATEGY_ID.equals(strategyId)) {
            publish(type, title, message, metricValue, threshold);
            return;
        }
        // 非跟单策略: 仅落库, 不推飞书 (本 sink 只为跟单审计设计)
        try {
            strategyAlertService.fireAlert(strategyId, type,
                    title == null ? type.getDescription() : title, message, metricValue, threshold);
        } catch (RuntimeException e) {
            log.warn("{} cross-strategy publish failed strategyId={} type={} err={}",
                    LOG_PREFIX, strategyId, type, e.toString());
        }
    }

    private boolean isSinkEnabled() {
        if (properties == null || !properties.isEnabled()) return false;
        SmartWalletProperties.Audit audit = properties.getAudit();
        return audit != null && audit.isAlertSinkEnabled();
    }

    private boolean shouldPushToFeishu(AlertType alertType) {
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null || !audit.isFeishuSinkEnabled()) {
            return false;
        }
        if (!StringUtils.hasText(audit.getFeishuWebhookUrl())) {
            return false;
        }
        // severity 过滤
        Set<String> allowed = parseAllowedSeverities(audit.getFeishuAllowedSeverities());
        if (!allowed.isEmpty() && !allowed.contains(alertType.getDefaultLevel().name())) {
            return false;
        }
        // 去重
        long now = System.currentTimeMillis();
        long interval = Math.max(1_000L, audit.getFeishuDedupeIntervalMs());
        Long last = lastFeishuPush.get(alertType.name());
        if (last != null && (now - last) < interval) {
            return false;
        }
        lastFeishuPush.put(alertType.name(), now);
        return true;
    }

    private Set<String> parseAllowedSeverities(String csv) {
        if (!StringUtils.hasText(csv)) {
            // 默认: 仅 WARN/CRITICAL/FATAL 上飞书 (INFO 走库 + 日志即可)
            return new HashSet<>(Arrays.asList(
                    AlertLevel.WARN.name(),
                    AlertLevel.CRITICAL.name(),
                    AlertLevel.FATAL.name()));
        }
        Set<String> result = new HashSet<>();
        for (String token : csv.split(",")) {
            String trimmed = token == null ? "" : token.trim().toUpperCase();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private void sendFeishuCard(AlertType alertType, String title, String message,
                                BigDecimal metricValue, BigDecimal threshold) {
        SmartWalletProperties.Audit audit = properties.getAudit();
        String url = audit.getFeishuWebhookUrl();
        try {
            Map<String, Object> payload = buildCardPayload(alertType, title, message, metricValue, threshold,
                    audit.getFeishuWebhookSecret());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(payload, headers);
            ResponseEntity<String> resp = restTemplate.postForEntity(url, req, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("{} webhook non-2xx type={} status={} body={}",
                        LOG_PREFIX, alertType, resp.getStatusCode(), truncate(resp.getBody()));
                return;
            }
            // 飞书自带业务级 code 字段, 200 + code!=0 也算失败 — 简单 string 包含判断（避免再引 Jackson 解析）
            String body = resp.getBody() == null ? "" : resp.getBody();
            if (body.contains("\"code\":0") || body.contains("\"StatusCode\":0")) {
                if (log.isDebugEnabled()) {
                    log.debug("{} webhook ok type={} title={}", LOG_PREFIX, alertType, title);
                }
            } else {
                log.warn("{} webhook business-error type={} body={}",
                        LOG_PREFIX, alertType, truncate(body));
            }
        } catch (RuntimeException e) {
            log.warn("{} webhook failed (swallowed) type={} title={} err={}",
                    LOG_PREFIX, alertType, title, e.toString());
        }
    }

    /**
     * 构造飞书富文本卡片消息 payload。
     * <p>使用最稳定的 "interactive" 卡片消息类型，所有飞书机器人版本都支持。</p>
     */
    private Map<String, Object> buildCardPayload(AlertType alertType, String title, String message,
                                                 BigDecimal metricValue, BigDecimal threshold,
                                                 String secret) {
        AlertLevel level = alertType.getDefaultLevel();
        String headerColor = switch (level) {
            case FATAL, CRITICAL -> "red";
            case WARN -> "orange";
            default -> "blue";
        };
        StringBuilder content = new StringBuilder();
        content.append("**告警类型**: ").append(alertType.name()).append("\n");
        content.append("**等级**: ").append(level.name()).append("\n");
        if (StringUtils.hasText(message)) {
            content.append("**详情**: ").append(message).append("\n");
        }
        if (metricValue != null) {
            content.append("**当前值**: ").append(metricValue.toPlainString()).append("\n");
        }
        if (threshold != null) {
            content.append("**阈值**: ").append(threshold.toPlainString()).append("\n");
        }
        content.append("**策略**: SMART_WALLET_AUDIT");

        Map<String, Object> headerTitle = new LinkedHashMap<>();
        headerTitle.put("tag", "plain_text");
        headerTitle.put("content", title == null ? alertType.getDescription() : title);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("template", headerColor);
        header.put("title", headerTitle);

        Map<String, Object> textElement = new LinkedHashMap<>();
        textElement.put("tag", "markdown");
        textElement.put("content", content.toString());

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("header", header);
        card.put("elements", java.util.List.of(textElement));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "interactive");
        payload.put("card", card);

        // 签名：飞书机器人启用签名校验时必填
        if (StringUtils.hasText(secret)) {
            long ts = System.currentTimeMillis() / 1000;
            String sign = computeFeishuSign(ts, secret);
            if (sign != null) {
                payload.put("timestamp", String.valueOf(ts));
                payload.put("sign", sign);
            }
        }
        return payload;
    }

    /** 飞书签名算法：HmacSHA256(secret_key, "timestamp\nsecret") → base64. */
    private String computeFeishuSign(long ts, String secret) {
        try {
            String stringToSign = ts + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(new byte[]{});
            return java.util.Base64.getEncoder().encodeToString(signData);
        } catch (Exception e) {
            log.warn("{} feishu sign compute failed err={}", LOG_PREFIX, e.toString());
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 256 ? s.substring(0, 256) + "..." : s;
    }
}
