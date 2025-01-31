package com.metaverse.coin.strategy.common.dao.chain.bean;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigInteger;
import java.util.Date;

@Data
@TableName("t_block")
public class Block {
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("block_height")
    private BigInteger blockHeight;
    @TableField("block_hash")
    private String blockHash;
    @TableField("previous_hash")
    private String previousHash;
    private Integer status;
    @TableField("create_date")
    private Date createDate;
    @TableField("modify_date")
    private Date modifyDate;
}


