package com.metaverse.coin.strategy.job.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>注册 {@link OptimisticLockerInnerInterceptor} 以支持实体上的 {@code @Version} 乐观锁注解。
 * 当多个定时任务（PositionSyncService / SettlementSyncService / ClaimService / OrderPollingService）
 * 并发更新同一行数据时，���观锁会自动在 UPDATE 语句中追加 {@code WHERE version=?}
 * 并在更新成功时执行 {@code SET version=version+1}，从而防止丢失更新（lost update）。
 *
 * <p>如果 updateById 返回影响行数为 0（即 version 已变化），调用方应该：
 * <ul>
 *   <li>重新读取最新数据</li>
 *   <li>重新判断是否仍需执行状态变更</li>
 *   <li>重试更新（或跳过）</li>
 * </ul>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
