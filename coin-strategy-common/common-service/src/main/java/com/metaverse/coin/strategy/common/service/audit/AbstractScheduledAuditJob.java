package com.metaverse.coin.strategy.common.service.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tier B (第二十批 · 2026-05-16): {@link ScheduledAuditJobTemplate} 的抽象基类.
 *
 * <p>子类只需:
 * <pre>{@code
 * @Component
 * public class MyWeatherAuditJob extends AbstractScheduledAuditJob {
 *     private final MyService service;
 *     private final SmartWalletProperties properties;
 *
 *     public MyWeatherAuditJob(MyService service, SmartWalletProperties properties) {
 *         this.service = service;
 *         this.properties = properties;
 *     }
 *
 *     @Override public String name() { return "MyWeatherAuditJob"; }
 *     @Override public boolean enabled() { return properties.isEnabled(); }
 *     @Override public void runOnce() { service.runOnce(); }
 *
 *     @Scheduled(cron = "${weather.my-audit-cron:0 0 3 * * *}", zone = "Asia/Shanghai")
 *     public void tick() { run(); }
 * }
 * }</pre>
 *
 * <p>关键点:
 * <ul>
 *   <li>{@link #run} 调用顺序: enabled() → running.compareAndSet → runOnce() → finally release;</li>
 *   <li>异常 swallow + log.error, 不会冒泡到 Spring scheduler;</li>
 *   <li>logger 走子类 SimpleName, 方便 grep 查 specific job 日志;</li>
 *   <li>不预设 {@code @Scheduled} — Spring 不支持把 cron 注解放到抽象方法上, 子类自己加。</li>
 * </ul></p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率影响</b>: ⚪ 无 — 仅声明基类, 跟单
 * 生产代码完全不引用 (5 个跟单 audit Job 沿用既有内联模板, 未迁移)。</p>
 */
public abstract class AbstractScheduledAuditJob implements ScheduledAuditJobTemplate {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Logger logger;

    protected AbstractScheduledAuditJob() {
        this.logger = LoggerFactory.getLogger(getClass());
    }

    /**
     * 子类的 {@code @Scheduled} 方法应直接调用本方法.
     */
    public final void run() {
        ScheduledAuditJobTemplate.runGuarded(name(), logger, this::enabled, running, this::runOnce);
    }

    /**
     * 测试 / 调试入口: 检查 enabled + running 当前状态.
     */
    public final boolean isRunning() {
        return running.get();
    }
}
