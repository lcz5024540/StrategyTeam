package com.metaverse.coin.strategy.common.dao.bean;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigInteger;
import java.util.Date;

@Data
@TableName("t_user")
public class User {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("user_name")
    private String userName;
    @TableField("cex")
    private String cex;
    @TableField("api_key")
    private String apiKey;
    @TableField("secret_key")
    private String secretKey;
    // status 0: 表示暂停使用，1：表示可以使用策略
    @TableField("status")
    private Integer status;
    @TableField("create_date")
    private Date createDate;
    @TableField("modify_date")
    private Date modifyDate;
}


