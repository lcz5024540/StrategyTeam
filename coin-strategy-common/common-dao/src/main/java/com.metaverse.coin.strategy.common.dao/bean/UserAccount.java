package com.metaverse.coin.strategy.common.dao.bean;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("t_user_account")
public class UserAccount {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("user_name")
    private String userName;
    // 私钥针对某一个链
    @TableField("chain")
    private String chain;
    @TableField("private_key")
    private String privateKey;
    // status 0: 表示暂停使用，1：表示可以使用策略
    private Integer status;
    @TableField("create_date")
    private Date createDate;
    @TableField("modify_date")
    private Date modifyDate;
}


