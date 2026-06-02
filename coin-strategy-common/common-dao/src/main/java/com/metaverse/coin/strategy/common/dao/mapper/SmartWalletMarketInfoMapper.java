package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SmartWalletMarketInfoMapper extends BaseMapper<SmartWalletMarketInfoEntity> {

    /**
     * 按唯一键 (market_id, outcome) 写入或更新元数据。
     * <p>
     * 2026-05-29 ray@cowork: 修复 {@code MarketMetadataRefreshJob} 与
     * {@code LeaderTradeOutcomeRebuildService} 等并发场景下重复触发
     * {@code Duplicate entry … uk_market_outcome} 异常的问题
     * （5-29 单日 job-current.log 中出现 422 次，详 docs/problem/问题记录事项-待解决-2026-05-29.md P0-2）。
     * <p>
     * 与 MyBatis-Plus 的 {@code insert(...)} 行为差异：
     * <ul>
     *   <li>命中唯一键时不抛 {@link java.sql.SQLIntegrityConstraintViolationException}，
     *       而是用 {@code COALESCE(VALUES(...), ...)} 增量补齐当前 NULL 的字段，
     *       已有非 NULL 值的字段保留（避免误覆盖业务侧手工修正过的值）；</li>
     *   <li>{@code market_end_at} 仅在 incoming 非空时覆盖（与 Service 层 5-17 修复一致：让 Polymarket 最新值生效）；</li>
     *   <li>不回写 {@code id} 字段——调用方必须二次 {@code SELECT} 拿到主键
     *       （见 {@code SmartWalletMarketInfoService.getOrCreate} 中 upsert 后调用
     *       {@code getByMarketIdAndOutcome} 的实现）。这里**故意不加** {@code @Options(useGeneratedKeys=true)}：
     *       因为本方法用多个 {@code @Param} 参数而非单一 entity 对象，MyBatis 找不到
     *       可写入 {@code id} 的目标对象，会抛
     *       {@code "Could not determine which parameter to assign generated keys to"}
     *       （2026-05-30 LeaderTradeOutcomeRebuildService 凌晨 00:56 已现场触发过）。</li>
     * </ul>
     *
     * @return 受影响行数（INSERT=1，UPDATE 命中=2，UPDATE 未变化=0）
     */
    @Insert("INSERT INTO smart_wallet_market_info "
            + "(market_id, question, outcome, token_id, index_set, market_end_at) "
            + "VALUES (#{marketId}, #{question}, #{outcome}, #{tokenId}, #{indexSet}, #{marketEndAt}) "
            + "ON DUPLICATE KEY UPDATE "
            + "question      = COALESCE(question,      VALUES(question)), "
            + "token_id      = COALESCE(token_id,      VALUES(token_id)), "
            + "index_set     = COALESCE(index_set,     VALUES(index_set)), "
            + "market_end_at = COALESCE(VALUES(market_end_at), market_end_at), "
            + "updated_at    = CURRENT_TIMESTAMP")
    int upsertByMarketOutcome(@Param("marketId") String marketId,
                              @Param("outcome") String outcome,
                              @Param("question") String question,
                              @Param("tokenId") String tokenId,
                              @Param("indexSet") Integer indexSet,
                              @Param("marketEndAt") Long marketEndAt);
}
