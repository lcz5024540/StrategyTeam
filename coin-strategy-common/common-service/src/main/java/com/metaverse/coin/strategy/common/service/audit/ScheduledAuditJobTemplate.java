package com.metaverse.coin.strategy.common.service.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Tier B (第二十批 · 2026-05-16): 通用「定时审计 Job 模板」.
 *
 * <p>跟单 5 个 audit Job 共享同一套样板:
 * <pre>
 * &#64;Scheduled(cron / fixedDelay) 入口
 *   ↓
 * 1) 检查 properties.enabled 等开关 → 关则直接 return
 *   ↓
 * 2) AtomicBoolean running.compareAndSet(false, true) 防并发 → false 时打 info 并 return
 *   ↓
 * 3) try { ... 业务调用 (通常 service.runOnce / buildReport) ... }
 *    catch (RuntimeException) { log.error 但不抛回 Spring scheduler }
 *    finally { running.set(false) }
 * </pre>
 *
 * <p>本接口 + 默认实现 ({@code AbstractScheduledAuditJob}) 把这个样板抽出来,
 * 让未来天气 / BTC 5min 策略的 audit Job 不必再重复一遍 try/catch + running guard
 * (5 个跟单 Job 已经写了 5 次相同的模板, 容易抄错; 抽接口后只需 implements `name()` + `runOnce()` + `enabled()`)</p>
 *
 * <p><b>设计原则</b>:
 * <ul>
 *   <li>只规范<b>调度边界</b> — cron 表达式 / fixedDelay 仍由实现类自行 @Scheduled 注解, 因为 Spring
 *     不支持把 cron 表达式动态化到接口默认方法上;</li>
 *   <li>不预设 metric / 告警 sink — sink 由各 Job 内部决定何时调用 {@link com.metaverse.coin.strategy.common.service.alert.StrategyAuditAlertSink};</li>
 *   <li>异常一律 swallow + log.error, 不让单次失败拖垮 scheduler;</li>
 *   <li>提供 helper {@link #runGuarded} 给不愿意 implements 完整接口的旧 Job 当工具使用 (零迁移成本)。</li>
 * </ul></p>
 *
 * <p><b>跟单已有 Job 是否回头接入</b>: 不必, 与第十六批 {@code SignalRiskGate} 接口预留同节奏 —
 * 5 个跟单 audit Job 都已工作良好, 重构有回归风险; 接口先备好, 未来 W6 真正实施时
 * 再统一接入。本批仅"抽接口 + helper" 让天气策略起步零代价。</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率影响</b>: ⚪ 无 — 仅声明类型 + 工具方法,
 * 跟单生产代码完全不引用; 即使整个接口删除, 跟单系统行为完全等价。</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第二十节 (Tier B).</p>
 */
public interface ScheduledAuditJobTemplate {

    /**
     * Job 名称, 仅用于日志区分; 建议与类 simpleName 一致.
     */
    String name();

    /**
     * 当前是否允许执行. 实现方应在此读取 properties.enabled 等开关.
     * @return true 允许; false 跳过本次调度
     */
    boolean enabled();

    /**
     * 单次业务执行. 实现方应在这里调用 service.runOnce / buildReport 等真正干活的方法.
     * 抛出的 RuntimeException 会被 {@link AbstractScheduledAuditJob#run} 捕获并 log.error,
     * 不会冒泡到 Spring scheduler。
     */
    void runOnce();

    /**
     * 公共 helper: 在不想 extends abstract 的场景下, 把 (enabled + running guard + try/catch) 三件套
     * 一行搞定。跟单老 Job 想接入时只需:
     * <pre>{@code
     * private final AtomicBoolean running = new AtomicBoolean(false);
     * &#64;Scheduled(cron = "...")
     * public void tick() {
     *     ScheduledAuditJobTemplate.runGuarded(
     *         "MyAuditJob", log,
     *         () -> properties.isEnabled() && properties.getAudit().isMyEnabled(),
     *         running, () -> service.runOnce());
     * }
     * }</pre>
     */
    static void runGuarded(String name, Logger logger,
                           BooleanSupplier enabledCheck,
                           AtomicBoolean running,
                           Runnable body) {
        Logger log = logger == null ? LoggerFactory.getLogger(ScheduledAuditJobTemplate.class) : logger;
        try {
            if (enabledCheck != null && !enabledCheck.getAsBoolean()) {
                return;
            }
        } catch (RuntimeException ex) {
            log.warn("[{}] enabled check failed, skipping: {}", name, ex.toString());
            return;
        }
        if (running != null && !running.compareAndSet(false, true)) {
            log.info("[{}] previous run still in progress, skipping this tick", name);
            return;
        }
        try {
            body.run();
        } catch (RuntimeException ex) {
            log.error("[{}] unexpected failure: {}", name, ex.toString(), ex);
        } finally {
            if (running != null) running.set(false);
        }
    }
}
