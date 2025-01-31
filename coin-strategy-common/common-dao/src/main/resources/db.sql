DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user` (
    `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_name` int NOT NULL DEFAULT '0' COMMENT '用户名',
    `cex` varchar(30) NOT NULL DEFAULT '0' COMMENT '用户选择的cex',
    `api_key` varchar(255) NOT NULL DEFAULT '0' COMMENT '用户上传的apikey',
    `secret_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '用户上传的secretkey',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '账户状态，0: 表示暂停使用，1：表示可以使用策略',
    `create_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建日期',
    `modify_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改日期',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_username_cex` (`user_name`,`cex`) USING BTREE,
    KEY `idx_user_name` (`user_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户cex信息表';

DROP TABLE IF EXISTS `t_user_account`;
CREATE TABLE `t_user_account` (
      `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
      `user_name` int NOT NULL DEFAULT '0' COMMENT '用户名',
      `chain` varchar(30) NOT NULL DEFAULT '0' COMMENT '用户选择的链',
      `private_key` varchar(255) NOT NULL DEFAULT '0' COMMENT '私钥',
      `status` tinyint NOT NULL DEFAULT '0' COMMENT '账户状态，0: 表示暂停使用，1：表示可以使用策略',
      `create_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建日期',
      `modify_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改日期',
      PRIMARY KEY (`id`),
      UNIQUE KEY `uniq_username_chain` (`user_name`,`chain`) USING BTREE,
      KEY `idx_user_name` (`user_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='用户chain信息表';

-- 每个链一个job服务，不能共用，这样逻辑清晰，表结构清晰。作用也比较清晰，不会影响其他服务


drop table if exists `t_block`;
CREATE TABLE `t_block` (
         `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
         `block_height` bigint NOT NULL default 0 COMMENT '区块高度' ,
         `block_hash` varchar(255) COMMENT '区块hash',
         `previous_hash` varchar(255) COMMENT '父hash',
         `status` TINYINT NOT NULL default 0 COMMENT '处理状态',
         `create_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建日期',
         `modify_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改日期',
         PRIMARY KEY (`id`),
         UNIQUE KEY `uniq_block_height` (`block_height`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COMMENT='区块表';