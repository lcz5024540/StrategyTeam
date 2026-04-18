ALTER TABLE `smart_wallet_order`
    ADD COLUMN `realized_pnl_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '该订单已实现收益' AFTER `fee_usdc`,
    ADD COLUMN `exit_reason` VARCHAR(64) DEFAULT NULL COMMENT '提前退出原因' AFTER `realized_pnl_usdc`;
