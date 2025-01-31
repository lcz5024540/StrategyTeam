package com.metaverse.coin.strategy.common.chain.evm.config;

import com.metaverse.coin.strategy.common.chain.evm.enums.Chain;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.Objects;


/**
 * @program: lottery-ticket->ChainAddressConfig
 * @description:
 * @author: Levi
 * @create: 2023-07-09 18:55
 * @version:1.0
 **/
@Data
@Configuration
@ConfigurationProperties(prefix = "chain")
public class ChainConfig {
    private String default_name;
    public Map<String, String> wallet;
    public Map<String, String> contract;

    public static Chain chain = null;

    @PostConstruct
    public void init(){
        Chain _chain = Chain.getByName(default_name.toUpperCase());
        if(!Objects.isNull(_chain)){
            chain = _chain;
        }
    }
}
