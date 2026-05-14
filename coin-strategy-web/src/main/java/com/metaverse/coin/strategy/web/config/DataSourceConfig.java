package com.metaverse.coin.strategy.web.config;

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
 * <p>web 与 job 共享 deploy/linux/env/shared/secrets.env，DB_PASSWORD 可能是 ENC(...) 密文。
 * 这里保持和 job 模块一致：密文先解密，明文直接透传。
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
