package com.metaverse.coin.strategy.job.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 自定义 DataSource Bean，在构建连接池前解密数据库密码。
 *
 * <p>若 {@code spring.datasource.password} 的值以 {@code ENC(...)} 包裹，
 * 则由 {@link SecretDecryptor} 解密后再传给 HikariCP；明文值直接透传，不受影响。
 *
 * <p>{@code @ConfigurationProperties("spring.datasource.hikari")} 确保
 * {@code spring.datasource.hikari.*}（最大连接数、超时等）仍然正常生效。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataSourceConfig {

    private final DataSourceProperties dataSourceProperties;
    private final SecretDecryptor secretDecryptor;

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource() {
        String rawPassword = dataSourceProperties.getPassword();
        String decryptedPassword = secretDecryptor.decryptIfEncrypted(rawPassword);
        if (secretDecryptor.isEncrypted(rawPassword)) {
            log.info("[DataSourceConfig] DB password decrypted from ENC(...) format.");
        }
        return dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .password(decryptedPassword)
                .build();
    }
}
