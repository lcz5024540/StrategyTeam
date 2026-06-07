-- Beta后验/Wilson 可信胜率（2026-06-05 方案，docs/polymarket/方案-Beta后验与Wilson胜率-2026-06-05.md）
-- smart_wallet_profile 新增 3 列：决断样本数 / Wilson 下界 / Beta 后验均值。
--
-- ⚠️⚠️ 部署顺序（必须）：先执行本 SQL、再部署新 jar。
--   实体 SmartWalletProfileEntity 已新增对应字段，MyBatis-Plus 的 INSERT/UPDATE 会带上这些列，
--   DB 缺列会导致 profile 写入全部报错（Unknown column）。
--
-- 回滚：列可保留不影响旧版 jar（旧实体不感知新列）；如需彻底回滚再执行文件底部的 DROP。

ALTER TABLE smart_wallet_profile
    ADD COLUMN decisive_trades INT NULL COMMENT 'Beta/Wilson方案: 决断样本数 wins+losses(剔除平局), 真值刷新维护',
    ADD COLUMN credible_win_rate DECIMAL(8, 4) NULL COMMENT 'Beta/Wilson方案: Wilson单侧95%下界可信胜率(门槛用)',
    ADD COLUMN shrunk_win_rate DECIMAL(8, 4) NULL COMMENT 'Beta/Wilson方案: Beta(m=8,p0=0.5)后验均值收缩胜率(排序用)';

-- 历史回填：列默认 NULL；可信值由 SmartWalletProfileTruthRefreshService 周期刷新自动补齐
--（消费侧对 NULL 有回退点估计的兜底，无需手工 backfill；如需立即全量回填，触发一次
--  /strategy 管理端的 profile truth refresh 即可）。

-- 彻底回滚（仅在回退旧版 jar 之后执行）：
-- ALTER TABLE smart_wallet_profile
--     DROP COLUMN decisive_trades,
--     DROP COLUMN credible_win_rate,
--     DROP COLUMN shrunk_win_rate;
