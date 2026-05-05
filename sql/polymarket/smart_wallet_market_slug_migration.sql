-- 为 smart_wallet_market_info 表添加 market_slug 字段
-- 用于存储 Polymarket 市场 URL slug，支持前端精准跳转到对应市场页面

ALTER TABLE `smart_wallet_market_info`
    ADD COLUMN `market_slug` VARCHAR(512) DEFAULT NULL COMMENT 'Polymarket market URL slug'
    AFTER `market_id`;
