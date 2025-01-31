package com.metaverse.coin.strategy.common.chain.evm.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.metaverse.coin.strategy.common.model.other.block.LockerBo;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class BlockCache {

    //需要10把锁
    private static int LOCKER_COUNT = 10;

    private static Map<Integer, LockerBo> LOCKER_MAP = new HashMap<Integer,LockerBo>(){{
        for (int i = 0; i < LOCKER_COUNT; i++) {
            put(i, new LockerBo(i));
        }
    }};

    private static Cache<BigInteger, Boolean> CACHE = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    public static boolean isExist(BigInteger blockNum, boolean isSave) {
        try {
            LockerBo locker = switchLocker(blockNum);
            log.info("blockNum={},locker.value={},locker.hashCode={},", blockNum,locker.getValue(), locker.hashCode());
            synchronized (locker){
                Boolean flag = CACHE.get(blockNum, () -> false);
                if(!flag && isSave){
                    CACHE.put(blockNum, true);
                }
                return flag;
            }
        } catch (Exception e) {
            log.error("block isExist error",e);
            return false;
        }
    }

    private static LockerBo switchLocker(BigInteger blockNum){
        int i = blockNum.intValue() % LOCKER_COUNT;
        return LOCKER_MAP.get(i);
    }
}
