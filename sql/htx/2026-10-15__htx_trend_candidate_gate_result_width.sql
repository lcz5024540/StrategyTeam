-- HTX trend candidate trace: allow diagnostic gate codes such as
-- POLICY_NOT_MATCHED to be persisted without truncation or write failure.
-- Apply manually before running the shadow worker; the worker never executes DDL.

DROP PROCEDURE IF EXISTS widen_htx_trend_candidate_gate_result;

DELIMITER $$
CREATE PROCEDURE widen_htx_trend_candidate_gate_result()
BEGIN
    ALTER TABLE htx_trend_candidate_signal
        MODIFY COLUMN gate_result VARCHAR(32) NULL;
END$$
DELIMITER ;

CALL widen_htx_trend_candidate_gate_result();
DROP PROCEDURE IF EXISTS widen_htx_trend_candidate_gate_result;
