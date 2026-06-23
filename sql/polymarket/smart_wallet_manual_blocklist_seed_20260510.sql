CREATE TABLE IF NOT EXISTS `smart_wallet_manual_blocklist` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '阻断钱包地址，小写归一化',
    `source` VARCHAR(32) NOT NULL DEFAULT 'POLYMARKET' COMMENT '来源',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `reason` VARCHAR(255) DEFAULT NULL COMMENT '阻断原因',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_sw_manual_blocklist_wallet_source` (`wallet_address`, `source`),
    KEY `idx_sw_manual_blocklist_status` (`status`),
    KEY `idx_sw_manual_blocklist_updated` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='smart wallet 手工地址阻断名单';

INSERT INTO `smart_wallet_manual_allowlist` (
    `wallet_address`, `source`, `status`, `allowed_execution_modes`, `stake_multiplier`,
    `max_live_orders_per_day`, `max_live_orders_per_run`, `expires_at`, `reason`, `created_by`
)
SELECT LOWER(p.`wallet_address`), 'POLYMARKET', 'ACTIVE', 'PAPER', 0.5000,
       5, 2, DATE_ADD(NOW(), INTERVAL 30 DAY), 'MANUAL_ALLOWLIST_REQUEST_20260510', 'codex'
FROM `smart_wallet_profile` p
WHERE LOWER(p.`alias`) IN ('timetraveler', 'surfandturf', 'xae12archangel', 'rn1')
ON DUPLICATE KEY UPDATE
    `status` = VALUES(`status`),
    `allowed_execution_modes` = VALUES(`allowed_execution_modes`),
    `stake_multiplier` = VALUES(`stake_multiplier`),
    `max_live_orders_per_day` = VALUES(`max_live_orders_per_day`),
    `max_live_orders_per_run` = VALUES(`max_live_orders_per_run`),
    `expires_at` = VALUES(`expires_at`),
    `reason` = VALUES(`reason`),
    `created_by` = VALUES(`created_by`),
    `updated_at` = CURRENT_TIMESTAMP;

INSERT INTO `smart_wallet_manual_blocklist` (`wallet_address`, `source`, `status`, `reason`, `created_by`)
SELECT DISTINCT LOWER(p.`wallet_address`), 'POLYMARKET', 'ACTIVE', 'INACTIVE_GT_35D_BLOCKLIST', 'codex'
FROM `smart_wallet_profile` p
WHERE LOWER(p.`alias`) IN (
    'gmanas', 'walletmobile', 'majorexploiter', 'randomgenius-190', 'zxgngl',
    'ilovecircle', 'seriouslysirius', 'blackwall', 'keytransporter', 'fengdubiying',
    '432614799197', 'cryptowhiten',
    'featherleather', 'reachingthesky', 'jenzigo', 'len9311238', 'mikatrade77',
    'reptrump', 'babatrump', 'bettom42', 'horizonsplendidview', 'theo4', 'tazcot',
    'alexmulti', 'princesscaro', 'primm', 'sovereign2013', 'michie', 'cemeterysun',
    'gfjoigfsjoigsjoi', 'trezorisbest', 'fredi9999',
    'influenz.eth', 'justcrazy', 'gcottrell93', 'gatorr', 'anon', 'babygroot',
    'kch123', 'beachboy4'
)
ON DUPLICATE KEY UPDATE
    `status` = 'ACTIVE',
    `reason` = VALUES(`reason`),
    `created_by` = VALUES(`created_by`),
    `updated_at` = CURRENT_TIMESTAMP;

UPDATE `smart_wallet_manual_blocklist` b
JOIN `smart_wallet_profile` p
  ON LOWER(b.`wallet_address`) COLLATE utf8mb4_unicode_ci = LOWER(p.`wallet_address`) COLLATE utf8mb4_unicode_ci
SET b.`reason` = 'MANUAL_BLOCKLIST',
    b.`created_by` = 'codex',
    b.`updated_at` = CURRENT_TIMESTAMP
WHERE LOWER(p.`alias`) IN ('c0o0oli0o03', 'pizzabillgates');

UPDATE `smart_wallet_manual_blocklist` b
JOIN `smart_wallet_profile` p
  ON LOWER(b.`wallet_address`) COLLATE utf8mb4_unicode_ci = LOWER(p.`wallet_address`) COLLATE utf8mb4_unicode_ci
SET b.`reason` = 'MANUAL_BLOCKLIST',
    b.`created_by` = 'codex',
    b.`updated_at` = CURRENT_TIMESTAMP
WHERE LOWER(p.`alias`) IN ('c0o0oli0o03', 'pizzabillgates');

UPDATE `smart_wallet_manual_blocklist` b
JOIN `smart_wallet_profile` p
  ON LOWER(b.`wallet_address`) COLLATE utf8mb4_unicode_ci = LOWER(p.`wallet_address`) COLLATE utf8mb4_unicode_ci
SET b.`reason` = 'MANUAL_BLOCKLIST',
    b.`created_by` = 'codex',
    b.`updated_at` = CURRENT_TIMESTAMP
WHERE LOWER(p.`alias`) IN ('c0o0oli0o03', 'pizzabillgates');

INSERT INTO `smart_wallet_manual_blocklist` (`wallet_address`, `source`, `status`, `reason`, `created_by`)
VALUES
    ('0x7fb7ad0d194d7123e711e7db6c9d418fac14e33d', 'POLYMARKET', 'ACTIVE', 'INACTIVE_GT_35D_BLOCKLIST', 'codex'),
    ('0x2a2c53bd278c04da9962fcf96490e17f3dfb9bc1', 'POLYMARKET', 'ACTIVE', 'INACTIVE_GT_35D_BLOCKLIST', 'codex')
ON DUPLICATE KEY UPDATE
    `status` = 'ACTIVE',
    `reason` = VALUES(`reason`),
    `created_by` = VALUES(`created_by`),
    `updated_at` = CURRENT_TIMESTAMP;

INSERT INTO `smart_wallet_manual_blocklist` (`wallet_address`, `source`, `status`, `reason`, `created_by`)
SELECT DISTINCT LOWER(p.`wallet_address`), 'POLYMARKET', 'ACTIVE', 'MANUAL_BLOCKLIST', 'codex'
FROM `smart_wallet_profile` p
WHERE LOWER(p.`alias`) IN ('c0o0oli0o03', 'pizzabillgates')
ON DUPLICATE KEY UPDATE
    `status` = 'ACTIVE',
    `reason` = VALUES(`reason`),
    `created_by` = VALUES(`created_by`),
    `updated_at` = CURRENT_TIMESTAMP;

INSERT INTO `smart_wallet_manual_blocklist` (`wallet_address`, `source`, `status`, `reason`, `created_by`)
SELECT DISTINCT LOWER(p.`wallet_address`), 'POLYMARKET', 'ACTIVE', 'INACTIVE_GT_35D_BLOCKLIST', 'system-inactivity-gate'
FROM `smart_wallet_profile` p
LEFT JOIN (
    SELECT LOWER(`wallet_address`) AS wallet_address, MAX(`last_trade_at`) AS last_trusted_closed_at
    FROM `smart_wallet_leader_trade_outcome`
    WHERE `outcome_status` IN ('CLOSED_WIN', 'CLOSED_LOSS', 'CLOSED_BREAKEVEN')
      AND `source_confidence` >= 0.5500
    GROUP BY LOWER(`wallet_address`)
) o ON o.wallet_address COLLATE utf8mb4_unicode_ci = LOWER(p.`wallet_address`) COLLATE utf8mb4_unicode_ci
WHERE (p.`last_trade_at` IS NOT NULL AND p.`last_trade_at` < DATE_SUB(NOW(), INTERVAL 35 DAY))
   OR (COALESCE(p.`recent_trade_count_30d`, 0) = 0
       AND o.last_trusted_closed_at IS NOT NULL
       AND o.last_trusted_closed_at < DATE_SUB(NOW(), INTERVAL 35 DAY))
ON DUPLICATE KEY UPDATE
    `status` = 'ACTIVE',
    `reason` = VALUES(`reason`),
    `created_by` = VALUES(`created_by`),
    `updated_at` = CURRENT_TIMESTAMP;

UPDATE `smart_wallet_manual_blocklist` b
JOIN `smart_wallet_profile` p
  ON LOWER(b.`wallet_address`) COLLATE utf8mb4_unicode_ci = LOWER(p.`wallet_address`) COLLATE utf8mb4_unicode_ci
SET b.`reason` = 'MANUAL_BLOCKLIST',
    b.`created_by` = 'codex',
    b.`updated_at` = CURRENT_TIMESTAMP
WHERE LOWER(p.`alias`) IN ('c0o0oli0o03', 'pizzabillgates');
