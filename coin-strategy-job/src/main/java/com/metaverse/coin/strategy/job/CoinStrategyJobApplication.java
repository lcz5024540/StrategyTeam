package com.metaverse.coin.strategy.job;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication(scanBasePackages = "com.metaverse.coin.strategy")
@MapperScan("com.metaverse.coin.strategy.common.dao.mapper")
@EnableConfigurationProperties({SmartWalletProperties.class, WeatherArbitrageProperties.class})
@EnableScheduling
@Slf4j
public class CoinStrategyJobApplication {

    public static void main(String[] args) {
        // 全局统一 JVM 默认时区为北京时间 (东八区)。影响 Date.toString()、无显式时区的
        // SimpleDateFormat / LocalDate.now() / LocalDateTime.now() 以及 logback %d 日志时间戳。
        // 数据库连接已通过 JDBC serverTimezone=Asia/Shanghai 显式声明, 不受此设置影响。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(CoinStrategyJobApplication.class, args);
        log.info("================CoinStrategyJobApplication start success!================");
    }

}
