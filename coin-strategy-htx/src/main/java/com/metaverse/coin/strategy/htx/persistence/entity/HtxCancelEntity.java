package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("htx_cancel")
public class HtxCancelEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long htxOrderId;
    private String reason;
    private Boolean cancelOk;
    private String postCancelState;
    private BigDecimal postCancelFilled;
    private LocalDateTime createdAt;
}
