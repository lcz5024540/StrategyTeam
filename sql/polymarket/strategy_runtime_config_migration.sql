CREATE TABLE IF NOT EXISTS `strategy_runtime_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `strategy_code` VARCHAR(64) NOT NULL COMMENT '策略代码，例如 POLYMARKET_COPY',
  `config_key` VARCHAR(160) NOT NULL COMMENT '配置键，例如 smart-wallet.signal-risk.min-trigger-size-usdc',
  `config_value` TEXT NOT NULL COMMENT '配置值，按 value_type 解析',
  `value_type` VARCHAR(32) NOT NULL COMMENT 'BOOLEAN / INTEGER / DECIMAL / STRING / STRING_LIST / JSON',
  `group_name` VARCHAR(64) NOT NULL COMMENT '页面分组',
  `editable` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否允许页面编辑',
  `description` VARCHAR(512) DEFAULT NULL COMMENT '配置说明',
  `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '最后更新人',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_strategy_runtime_config_key` (`strategy_code`, `config_key`),
  KEY `idx_strategy_runtime_config_group` (`strategy_code`, `group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略运行时可编辑配置';
