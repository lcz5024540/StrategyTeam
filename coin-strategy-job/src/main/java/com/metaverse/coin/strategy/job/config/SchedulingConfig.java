package com.metaverse.coin.strategy.job.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 全局 {@code @Scheduled} 调度线程池配置（问题 P0-1 修复）。
 *
 * <p><b>背景</b>：{@code CoinStrategyJobApplication} 上的 {@code @EnableScheduling} 在未自定义
 * {@link TaskScheduler} 时，Spring 默认使用<b>单线程</b>调度器，全部 {@code @Scheduled} 方法
 * 串行执行。coin-strategy-job 的 smartwallet 包下有 40+ 个定时任务，其中闭仓增量同步
 * （{@code ClosedPositionIncrementalSyncJob}）、市场发现等重任务单次运行耗时可达数分钟；
 * 它们一旦占满唯一的调度线程，30s 节拍的 HOT 事件同步
 * （{@code PolymarketWalletDiscoveryService.scheduledSyncWatchlistTrades}）就被饿死，
 * 导致事件摄取滞后约 70 分钟、信号入库即过期、100% 命中 {@code G5_SIGNAL_STALE_INGEST}、零开单。</p>
 *
 * <p><b>修复</b>：注册一个多线程 {@link ThreadPoolTaskScheduler} 作为全局调度器。Spring 的
 * {@code ScheduledAnnotationBeanPostProcessor} 会优先采用唯一的 {@link TaskScheduler} Bean
 * （此处再以固定 Bean 名 {@code taskScheduler} 双保险），从而让重任务与高频任务并行执行，
 * 重任务不再阻塞 HOT 事件同步。</p>
 *
 * <p><b>线程池大小</b>：默认 {@value #DEFAULT_POOL_SIZE}，可通过配置键
 * {@code spring.task.scheduling.pool.size} 覆盖。多数定时任务为 I/O 密集型（HTTP / DB），
 * 该规模足以让高频任务始终抢到线程；服务器升配后调大该值即可，无需改代码。</p>
 *
 * <p><b>回滚</b>：把 {@code spring.task.scheduling.pool.size} 设为 1 即退回单线程行为；
 * 或删除本类，由 Spring Boot 自动配置接管（其默认同样是单线程）。两种回滚均为配置/类级别，
 * 无任何数据副作用。</p>
 *
 * <p><b>并发安全须知</b>：原先 40+ 个 {@code @Scheduled} 任务为隐式串行，多线程化后若不同
 * 任务之间存在共享可变状态，可能出现并发问题——上线前需对高频任务 review。注意：同一个
 * {@code @Scheduled} 方法本身仍不会被并发重入（Spring 对单个 fixedDelay 任务串行调度），
 * 多线程化只让<b>不同</b>任务可以并行。详见
 * docs/problem/问题排查修复方案-2026-05-24.md 第五节补充建议 1。</p>
 */
@Slf4j
@Configuration
public class SchedulingConfig {

    /** 调度线程池默认大小。 */
    public static final int DEFAULT_POOL_SIZE = 8;

    /** Spring 识别为全局 {@code @Scheduled} 调度器的固定 Bean 名。 */
    public static final String SCHEDULER_BEAN_NAME = "taskScheduler";

    @Bean(name = SCHEDULER_BEAN_NAME, destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:8}") int poolSize) {
        int effectivePoolSize = poolSize > 0 ? poolSize : DEFAULT_POOL_SIZE;

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(effectivePoolSize);
        scheduler.setThreadNamePrefix("sched-");
        // 关闭时等待在途任务跑完，最多 30s，避免强杀导致定时任务中途中断。
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        // 被取消的延迟任务及时从队列移除，避免关闭阶段队列堆积。
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();

        log.info("[SchedulingConfig] global @Scheduled task scheduler ready, poolSize={} "
                + "(override via spring.task.scheduling.pool.size)", effectivePoolSize);
        return scheduler;
    }
}
