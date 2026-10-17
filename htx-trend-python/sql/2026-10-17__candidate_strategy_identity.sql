-- Python-only trace identity for profile-versioned candidate evidence.
-- Apply explicitly before starting a worker built from this source.
ALTER TABLE htx_trend_candidate_signal
  ADD COLUMN strategy_version VARCHAR(80) NULL AFTER timeframe_profile,
  ADD COLUMN strategy_spec_hash CHAR(64) NULL AFTER strategy_version,
  ADD INDEX idx_htx_candidate_strategy_profile_time(strategy_version, timeframe_profile, bar_time);
