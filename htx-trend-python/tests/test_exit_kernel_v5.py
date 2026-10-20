from dataclasses import replace
from decimal import Decimal as D
import json
from pathlib import Path

from htx_trend.exit_contract_v5 import (
    EntryThesisV5, EventKind, ExitActionV5, ExitEventV5, ExitPolicyV5,
    ExecutionState, ExitStateV5, ModelState, ProfitPhase, Side, canonical_hash, canonical_json,
)
from htx_trend.exit_kernel_v5 import decide_v5
from htx_trend.model_holding_v5 import evaluate_model_holding_v5
from htx_trend.fee_allocator_v5 import allocate_fee
from htx_trend.exit_observer_v5 import observe_v5
from htx_trend.exit_experiment_v5 import evaluate_experiment_evidence, require_economic_evidence
from htx_trend.strategy_admission import (DecisionSurface, ResearchStage, StageEvidence, StageStatus,
                                          build_artifact)


def thesis(**changes):
    values=dict(account_id=7,strategy_instance='python-primary',position_epoch=1,
        model_id='FIB_PULLBACK_CONTINUATION',structure_id='fib:btc:1',side=Side.LONG,
        timeframe_seconds=900,first_fill_time_ms=0,entry_price=D('100'),filled_contracts=6,
        contract_size=D('1'),immutable_initial_stop=D('95'),failure_zone=D('97'),
        target_price=D('110'),atr_at_entry=D('1'),entry_fee=D('.10'),
        policy_hash=canonical_hash(ExitPolicyV5()))
    values.update(changes); return EntryThesisV5(**values)


def bar(number, **changes):
    end=number*900_000
    values=dict(event_key=f'bar:{end}',kind=EventKind.CLOSED_BAR,event_time_ms=end,
        reference_price=D('101'),confirmed_remaining_contracts=6,bar_open_ms=end-900_000,
        bar_end_ms=end,close_price=D('101'),atr_at_close=D('1'),current_net_pnl=D('1'),
        round_trip_cost=D('.10'),model_state=ModelState.VALID)
    values.update(changes); return ExitEventV5(**values)


def admission(stage=ResearchStage.SIGNAL_STATE_MACHINE, surface=DecisionSurface.OBSERVE):
    digest='a'*64
    return build_artifact(strategy_id='htx-trend-exit',strategy_version='HTX_TREND_EXIT_V5',
        experiment_id='EXP-20261010-001',source_commit='test',policy_hash=digest,config_hash=digest,
        dataset_manifest_hash=digest,stages=tuple(StageEvidence(item,StageStatus.PASS,f'evidence/{item.name}',digest,'passed')
            for item in ResearchStage if item<=stage),allowed_surfaces=(surface,),
        failure_conditions=('negative net expectancy',),pause_conditions=('private state unknown',))


def test_t01_flat_is_diagnostic_and_does_not_invalidate_entry_thesis():
    event=bar(1,model_state=ModelState.VALID,evidence_groups=('DIAGNOSTIC_FLAT',))
    result=decide_v5(thesis(),ExitStateV5(),event)
    assert result.decision.action is ExitActionV5.HOLD
    assert result.decision.reason_codes==('SOFT_MATURITY_NOT_REACHED',)


def test_t12_tick_hard_stop_is_not_blocked_by_soft_maturity():
    event=ExitEventV5('tick:1',EventKind.TICK,1,D('95'),6,data_quality='COMPLETE')
    result=decide_v5(thesis(),ExitStateV5(),event)
    assert result.decision.action is ExitActionV5.FULL_CLOSE
    assert result.decision.reason_codes==('INITIAL_HARD_STOP',)


def test_t03_duplicate_and_entry_half_bar_do_not_advance_soft_clock():
    contract=thesis(first_fill_time_ms=500_000)
    half=bar(1,bar_open_ms=0)
    first=decide_v5(contract,ExitStateV5(),half)
    assert first.state.eligible_bars==0
    complete=bar(2,bar_open_ms=900_000)
    second=decide_v5(contract,first.state,complete)
    duplicate=decide_v5(contract,second.state,complete)
    assert second.state.eligible_bars==duplicate.state.eligible_bars==1
    assert duplicate.state.revision==second.state.revision


def test_t06_t07_boundary_is_staged_and_cannot_exit_on_arming_event():
    contract,state=thesis(),ExitStateV5()
    first=decide_v5(contract,state,bar(1))
    armed=decide_v5(contract,first.state,bar(2,reference_price=D('102'),close_price=D('102')))
    assert armed.decision.action is ExitActionV5.UPDATE_SOFT_BOUNDARY
    assert armed.state.phase is ProfitPhase.ESTABLISHED
    assert armed.state.active_soft_boundary is None
    assert armed.state.pending_soft_boundary==D('101')


def test_t09_t10_flat_never_supplies_independent_failure_vote():
    contract=thesis(); state=ExitStateV5()
    state=decide_v5(contract,state,bar(1)).state
    state=decide_v5(contract,state,bar(2,reference_price=D('102'),close_price=D('102'))).state
    state=decide_v5(contract,state,bar(3,reference_price=D('100.5'),close_price=D('100.5'),evidence_groups=('DIAGNOSTIC_FLAT',))).state
    watch=decide_v5(contract,state,bar(4,reference_price=D('100.5'),close_price=D('100.5'),evidence_groups=('DIAGNOSTIC_FLAT',)))
    assert watch.decision.action is ExitActionV5.HOLD
    assert watch.decision.reason_codes==('EXIT_WATCH_INSUFFICIENT_INDEPENDENT_EVIDENCE',)
    confirmed=decide_v5(contract,watch.state,bar(5,reference_price=D('100.5'),close_price=D('100.5'),evidence_groups=('DIAGNOSTIC_FLAT','E-H','E-H')))
    assert confirmed.decision.action is ExitActionV5.FULL_CLOSE
    assert confirmed.decision.evidence_groups==('DIAGNOSTIC_FLAT','E-H')


def test_t11_structure_failure_requires_closed_bar_confirmation_but_not_profit():
    contract=thesis(); state=ExitStateV5()
    one=decide_v5(contract,state,bar(1,reference_price=D('98'),close_price=D('98'),current_net_pnl=D('-2'),model_state=ModelState.INVALID))
    two=decide_v5(contract,one.state,bar(2,reference_price=D('98'),close_price=D('98'),current_net_pnl=D('-2'),model_state=ModelState.INVALID))
    assert one.decision.action is ExitActionV5.HOLD
    assert two.decision.action is ExitActionV5.FULL_CLOSE
    assert two.decision.reason_codes==('MODEL_INVALIDATION_CONFIRMED',)


def test_t16_single_contract_partial_never_becomes_full_close():
    contract=thesis(filled_contracts=1); state=ExitStateV5(eligible_bars=2)
    event=bar(3,confirmed_remaining_contracts=1,target_reached=True,partial_net_pnl=D('1'),evidence_groups=('E-H',))
    result=decide_v5(contract,state,event)
    assert result.decision.action is ExitActionV5.HOLD
    assert result.decision.target_remaining_contracts==1
    assert result.decision.reason_codes==('PARTIAL_CLOSE_NOT_ECONOMIC_OR_NOT_DIVISIBLE',)


def test_short_side_is_symmetric_at_equal_boundaries():
    contract=thesis(side=Side.SHORT,immutable_initial_stop=D('105'),failure_zone=D('103'))
    hard=ExitEventV5('tick',EventKind.TICK,1,D('105'),6)
    assert decide_v5(contract,ExitStateV5(),hard).decision.action is ExitActionV5.FULL_CLOSE


def test_unknown_private_state_requires_reconciliation_without_exit_guess():
    event=ExitEventV5('tick',EventKind.TICK,1,D('100'),6,data_quality='PRIVATE_QUERY_ERROR',ownership_confirmed=False)
    result=decide_v5(thesis(),ExitStateV5(),event)
    assert result.decision.action is ExitActionV5.RECONCILE_REQUIRED


def test_unknown_owner_takes_priority_over_apparent_hard_stop():
    event=ExitEventV5('tick',EventKind.TICK,1,D('95'),6,ownership_confirmed=False)
    result=decide_v5(thesis(),ExitStateV5(),event)
    assert result.decision.action is ExitActionV5.RECONCILE_REQUIRED
    assert result.decision.reason_codes==('OWNERSHIP_UNCERTAIN',)


def test_each_supported_model_uses_its_frozen_failure_zone_and_not_flat():
    for model in ('FIB_PULLBACK_CONTINUATION','DONCHIAN_BREAKOUT','EMA_MOMENTUM_CONTINUATION'):
        valid=evaluate_model_holding_v5(thesis(model_id=model),D('98'))
        failed=evaluate_model_holding_v5(thesis(model_id=model),D('97'))
        assert valid.state is ModelState.VALID and valid.evidence_groups==()
        assert failed.state is ModelState.INVALID and failed.evidence_groups==('E-S',)


def test_model_adapter_fails_closed_when_contract_or_source_is_unknown():
    assert evaluate_model_holding_v5(thesis(model_id='UNKNOWN'),D('100')).state is ModelState.UNKNOWN
    assert evaluate_model_holding_v5(thesis(),None).state is ModelState.UNKNOWN
    assert evaluate_model_holding_v5(thesis(failure_zone=D('95')),D('95')).state is ModelState.UNKNOWN


def test_entry_policy_hash_rejects_runtime_policy_drift():
    try:
        decide_v5(thesis(),ExitStateV5(),bar(1),ExitPolicyV5(maturity_bars=3))
    except ValueError as error:
        assert 'policy hash' in str(error)
    else:
        raise AssertionError('policy drift must fail closed')


def test_cost_and_atr_arm_thresholds_are_both_required():
    state=decide_v5(thesis(),ExitStateV5(),bar(1)).state
    insufficient_move=decide_v5(thesis(),state,bar(2,reference_price=D('100.5'),close_price=D('100.5')))
    assert insufficient_move.decision.action is ExitActionV5.HOLD
    insufficient_net=decide_v5(thesis(),state,bar(2,reference_price=D('102'),close_price=D('102'),
        current_net_pnl=D('0.19')))
    assert insufficient_net.decision.action is ExitActionV5.HOLD
    equality=decide_v5(thesis(),state,bar(2,reference_price=D('101'),close_price=D('101'),
        current_net_pnl=D('0.20')))
    assert equality.decision.action is ExitActionV5.UPDATE_SOFT_BOUNDARY


def test_intrabar_wick_does_not_advance_or_breach_soft_boundary():
    state=ExitStateV5(phase=ProfitPhase.ESTABLISHED,active_soft_boundary=D('101'),
        active_boundary_id='b1',eligible_bars=2,best_close=D('102'),extension_achieved=True)
    event=ExitEventV5('tick:wick',EventKind.TICK,2_000_000,D('100.5'),6,
        current_net_pnl=D('0.4'),round_trip_cost=D('0.1'))
    result=decide_v5(thesis(),state,event)
    assert result.decision.action is ExitActionV5.HOLD
    assert result.state.soft_breach_bars==0
    assert result.state.eligible_bars==2


def test_soft_boundary_recovery_resets_confirmation_count():
    state=ExitStateV5(revision=3,phase=ProfitPhase.ESTABLISHED,last_bar_end_ms=2_700_000,
        eligible_bars=3,best_close=D('102'),active_soft_boundary=D('101'),active_boundary_id='b1',
        soft_breach_bars=1,armed_bar_end_ms=1_800_000,extension_achieved=True)
    result=decide_v5(thesis(),state,bar(4,reference_price=D('101.5'),close_price=D('101.5')))
    assert result.decision.action in (ExitActionV5.HOLD,ExitActionV5.UPDATE_SOFT_BOUNDARY)
    assert result.state.soft_breach_bars==0


def test_thesis_timeout_only_fires_at_frozen_horizon():
    prior=ExitStateV5(revision=14,last_bar_end_ms=14*900_000,eligible_bars=14,best_close=D('100.2'))
    before=decide_v5(thesis(),prior,bar(15,reference_price=D('100.2'),close_price=D('100.2'),current_net_pnl=D('0')))
    assert before.decision.action is ExitActionV5.HOLD
    expired=decide_v5(thesis(),before.state,bar(16,reference_price=D('100.2'),close_price=D('100.2'),current_net_pnl=D('0')))
    assert expired.decision.action is ExitActionV5.FULL_CLOSE
    assert expired.decision.reason_codes==('THESIS_TIMEOUT',)


def test_partial_fill_fee_allocation_absorbs_remainder_once():
    values=allocate_fee(D('0.1'),(D('1'),D('1'),D('1')))
    assert sum(values,D('0'))==D('0.1')
    assert values[-1]==D('0.033333333333333334')


def test_observer_accepts_only_non_live_modes_even_for_full_close():
    event=ExitEventV5('tick',EventKind.TICK,1,D('95'),6)
    assert observe_v5('OBSERVE',thesis(),ExitStateV5(),event,admission=admission()).transition.decision.action is ExitActionV5.FULL_CLOSE
    try:
        observe_v5('LIVE',thesis(),ExitStateV5(),event,admission=admission())
    except ValueError as error:
        assert 'OBSERVE or PAPER' in str(error)
    else:
        raise AssertionError('observer must reject LIVE')


def test_economic_experiment_fails_closed_on_current_missing_execution_facts():
    result=evaluate_experiment_evidence({'source_commit':'2a479c04'},())
    assert result.status=='INSUFFICIENT_EVIDENCE' and result.level=='L1'
    try:
        require_economic_evidence({'source_commit':'2a479c04'},())
    except ValueError as error:
        assert str(error).startswith('INSUFFICIENT_EVIDENCE:')
    else:
        raise AssertionError('economic result must remain blocked')


def _from_fixture(cls, values, decimal_fields=(), enum_fields=None):
    values=dict(values)
    for field in decimal_fields:
        if values.get(field) is not None:
            values[field]=D(values[field])
    for field, enum_type in (enum_fields or {}).items():
        values[field]=enum_type(values[field])
    if cls is ExitEventV5:
        values['evidence_groups']=tuple(values['evidence_groups'])
    return cls(**values)


def test_shared_golden_fixture_matches_every_decision_and_state_field():
    fixture_path=Path(__file__).resolve().parents[2]/'fixtures/trend-exit-v5/golden.json'
    fixture=json.loads(fixture_path.read_text())
    contract=_from_fixture(EntryThesisV5,fixture['contract'],
        ('entry_price','contract_size','immutable_initial_stop','failure_zone','target_price','atr_at_entry','entry_fee'),
        {'side':Side})
    policy=_from_fixture(ExitPolicyV5,fixture['policy'],
        ('arm_atr_multiplier','arm_cost_distance_multiple','arm_net_cost_multiple','noise_atr_multiplier',
         'noise_min_atr','partial_fraction','partial_net_cost_multiple'))
    assert contract.policy_hash==canonical_hash(policy)
    for case in fixture['cases']:
        state=_from_fixture(ExitStateV5,case['state'],
            ('best_close','active_soft_boundary','pending_soft_boundary'),
            {'phase':ProfitPhase,'execution_state':ExecutionState})
        event=_from_fixture(ExitEventV5,case['event'],
            ('reference_price','close_price','atr_at_close','current_net_pnl','round_trip_cost','partial_net_pnl'),
            {'kind':EventKind,'model_state':ModelState})
        result=decide_v5(contract,state,event,policy)
        assert json.loads(canonical_json(result.decision))==case['expected_decision'],case['name']
        assert json.loads(canonical_json(result.state))==case['expected_state'],case['name']
