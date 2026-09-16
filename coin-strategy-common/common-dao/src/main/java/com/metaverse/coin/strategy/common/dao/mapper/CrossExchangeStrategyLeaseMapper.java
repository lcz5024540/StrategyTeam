package com.metaverse.coin.strategy.common.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

@Mapper
public interface CrossExchangeStrategyLeaseMapper {
    @Insert("INSERT INTO cross_exchange_strategy_lease (lease_key, owner_id, expires_at) VALUES "
            + "(#{leaseKey}, #{ownerId}, #{expiresAt}) ON DUPLICATE KEY UPDATE "
            + "owner_id = IF(expires_at < #{now}, VALUES(owner_id), owner_id), "
            + "expires_at = IF(expires_at < #{now}, VALUES(expires_at), expires_at)")
    int acquire(@Param("leaseKey") String leaseKey, @Param("ownerId") String ownerId,
            @Param("expiresAt") Date expiresAt, @Param("now") Date now);

    @Delete("DELETE FROM cross_exchange_strategy_lease WHERE lease_key = #{leaseKey} AND owner_id = #{ownerId}")
    int release(@Param("leaseKey") String leaseKey, @Param("ownerId") String ownerId);
}
