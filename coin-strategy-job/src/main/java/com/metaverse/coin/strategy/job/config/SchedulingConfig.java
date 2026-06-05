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

    /**
     * 池大小下限。设为 24：实测在 2 核机上重型批处理任务
     * （ClosedPositionSync 并发子任务、OrderAnalysis、LeaderTradeOutcomeRebuild、metadata 刷新等）
     * 会同时占满线程，pool=8 时 30s 高频事件同步触发抢不到线程 → 摄取停摆 → 不开单。
     * 这些任务绝大多数 I/O 密集（HTTP/DB 等待，线程多在 park），线程数应按「并发阻塞任务数」而非核数定，
     * 故下限取 24（≈重型任务并发数 + 高频触发余量）；24 个线程栈约 12MB，对 4G 内存可忽略。
     * 配合 PolymarketApiClient 的 callTimeout（约束单次调用总时长）共同根治「长时间不开单」。
     */
    public static final int MIN_POOL_SIZE = 24;
    /** 池大小上限（防止误配置开过多线程）。 */
    public static final int MAX_POOL_SIZE = 48;
    /** 兼容保留：历史固定默认值；实际默认改为核感知 {@link #resolveDefaultPoolSize()}。 */
    public static final int DEFAULT_POOL_SIZE = 8;

    /** Spring 识别为全局 {@code @Scheduled} 调度器的固定 Bean 名。 */
    public static final String SCHEDULER_BEAN_NAME = "taskScheduler";

    /**
     * 核感知默认池大小：{@code cores * 4}，钳到 {@code [MIN_POOL_SIZE, MAX_POOL_SIZE]}。
     * <p>这些 @Scheduled 任务绝大多数为 I/O 密集（HTTP/DB 等待），线程多数时间 park，
     * 故线程数可适度高于核数。按核数线性伸缩：2 核→8、4 核→16、8 核→32，服务器升配后自动放大，
     * 无需改代码；仍可用 {@code spring.task.scheduling.pool.size} 显式覆盖。</p>
     */
    public static int resolveDefaultPoolSize() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.min(MAX_POOL_SIZE, Math.max(MIN_POOL_SIZE, cores * 4));
    }

    @Bean(name = SCHEDULER_BEAN_NAME, destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:-1}") int poolSize) {
        int effectivePoolSize = poolSize > 0 ? poolSize : resolveDefaultPoolSize();

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
