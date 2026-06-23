package com.metaverse.coin.strategy.web;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;

/**
 * Web 应用入口。
 *
 * <p>{@code scanBasePackages} 扩到 {@code com.metaverse.coin.strategy}，
 * 使下沉到 {@code common-service} 的 SmartWallet 相关 service 也能被 web 扫描到。
 * 同时 {@link SmartWalletProperties} 在 common-service，用 {@link EnableConfigurationProperties}
 * 显式启用其绑定。</p>
 *
 * <p>注意：新架构下 web 不再通过 HTTP 调用 job，所有 fall-through proxy controller
 * （JobApiForwardService / JobApiProxyController / SmartWalletReadProxyController /
 * SmartWalletWriteProxyController）以及配套的 {@code DashboardProperties} 已删除。
 * 任何之前依赖 proxy 透传到 job 的接口，必须在 web 端落地本地实现，否则会 404。</p>
 */
@SpringBootApplication(scanBasePackages = "com.metaverse.coin.strategy")
@MapperScan("com.metaverse.coin.strategy.common.dao.mapper")
@EnableConfigurationProperties({SmartWalletProperties.class})
public class CoinStrategyWebApplication {

    public static void main(String[] args) {
        // 全局统一 JVM 默认时区为北京时间 (东八区)。影响 Date.toString()、无显式时区的
        // SimpleDateFormat / LocalDate.now() / LocalDateTime.now() 以及 logback %d 日志时间戳。
        // 数据库连接已通过 JDBC serverTimezone=Asia/Shanghai 显式声明, 不受此设置影响。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(CoinStrategyWebApplication.class, args);
    }

}
