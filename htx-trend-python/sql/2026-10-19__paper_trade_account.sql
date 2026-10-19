-- Review artifact only. This task must NOT execute this SQL against any MySQL.
-- Disabled account: cannot be selected by LIVE account discovery.
INSERT IGNORE INTO htx_trade_account
(account_label, account_role, enabled, trend_enabled, mirror_enabled, sync_leverage, note)
VALUES ('paper', 'PAPER', 0, 0, 0, 0,
        'paper:htx-trend-paper-primary; simulated only');
