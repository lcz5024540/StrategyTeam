package com.metaverse.coin.strategy.common.chain.evm.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.metaverse.coin.strategy.common.chain.evm.config.ChainConfig;
import com.metaverse.coin.strategy.common.chain.evm.utils.Web3jChainUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.web3j.protocol.core.methods.response.EthBlock;

import java.math.BigInteger;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class EthBlockCache {
    @Autowired
    private Executor simpleRamTaskExecutor;

    private static Cache<BigInteger, EthBlock> CACHE = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    public void pull(BigInteger start, BigInteger end){
        for(BigInteger i = start; i.compareTo(end) <= 0; i = i.add(BigInteger.ONE)) {
            BigInteger blockNumber = i;
            simpleRamTaskExecutor.execute(() -> {
                put(blockNumber);
            });
        }
    }

    public EthBlock get(BigInteger blockNumber){
        try {
            return CACHE.get(blockNumber, () -> put(blockNumber));
        } catch (ExecutionException e) {
            log.error("EthBlockCache get error!blockNumber={}",blockNumber,e);
        }
        return null;
    }

    private EthBlock put(BigInteger blockNumber){
        EthBlock result = Web3jChainUtils.getBlockByBlockNumber(ChainConfig.chain, blockNumber);
        if(!Objects.isNull(result)){
            CACHE.put(blockNumber, result);
        }
        return result;
    }
}
