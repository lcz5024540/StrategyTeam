-- Measured-move/Fibonacci target evidence for combination entries.
ALTER TABLE htx_trend_candidate_signal
    ADD COLUMN target_zone_low DECIMAL(36,18),
    ADD COLUMN target_zone_high DECIMAL(36,18),
    ADD COLUMN box_measured_move_target DECIMAL(36,18),
    ADD COLUMN fib_extension_1272 DECIMAL(36,18),
    ADD COLUMN fib_extension_1618 DECIMAL(36,18),
    ADD COLUMN expected_r_multiple DECIMAL(36,18),
    ADD COLUMN momentum_quality_score DECIMAL(36,18);

ALTER TABLE htx_trend_position
    ADD COLUMN target_zone_low DECIMAL(36,18),
    ADD COLUMN target_zone_high DECIMAL(36,18),
    ADD COLUMN expected_r_multiple DECIMAL(36,18),
    ADD COLUMN momentum_quality_score DECIMAL(36,18);
