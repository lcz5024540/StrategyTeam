package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 策略手动触发命令实体（异步命令队列）。
 *
 * <p>web 端写入 status=PENDING，job 端 consumer 轮询消费：</p>
 * <ol>
 *   <li>CAS update PENDING → RUNNING</li>
 *   <li>执行 action 对应方法</li>
 *   <li>更新 status=COMPLETED/FAILED + success + message</li>
 * </ol>
 */
@Data
@Accessors(chain = true)
@TableName("strategy_manual_trigger")
public class StrategyManualTriggerEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略 ID（全局命令使用 _GLOBAL_） */
    private String strategyId;

    /** 动作: RUN_CYCLE / EXIT_SCAN / ALERT_CHECK / SNAPSHOT_COLLECT */
    private String action;

    /** 状态: PENDING / RUNNING / COMPLETED / FAILED */
    private String status;

    /** 可选 JSON 负载（FUNDING_ARB_EXECUTE 等需要参数的命令使用） */
    private String payload;

    /** 请求时间 */
    private Date requestedAt;

    /** 请求方 */
    private String requestedBy;

    /** consumer 开始处理时间 */
    private Date processedAt;

    /** 完成时间 */
    private Date completedAt;

    /** 是否成功 */
    private Boolean success;

    /** 执行结果或错误信息 */
    private String message;

    /** 结构化结果 JSON（承载复杂返回值，如统计字典 / 列表 / 嵌套对象）。message 为人类可读摘要。 */
    private String resultJson;
}
