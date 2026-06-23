package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 策略注册状态快照。
 *
 * <p>由 job 端定期 UPSERT，web 端读取以提供 control/status、monitor/overview 等接口。</p>
 */
@Data
@Accessors(chain = true)
@TableName("strategy_registry_state")
public class StrategyRegistryStateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略 ID */
    private String strategyId;

    /** 策略显示名 */
    private String displayName;

    /** 是否启用 */
    private Boolean enabled;

    /** 运行模式: OFF / SHADOW / PAPER / LIVE */
    private String mode;

    /** 钱包 ID */
    private String walletId;

    /** 更新时间 */
    private Date updatedAt;
}
