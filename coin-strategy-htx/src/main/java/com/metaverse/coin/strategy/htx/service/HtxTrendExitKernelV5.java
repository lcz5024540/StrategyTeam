package com.metaverse.coin.strategy.htx.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.metaverse.coin.strategy.htx.service.HtxTrendExitContractV5.*;

/** Pure v5 decision kernel. It cannot read a clock, database, network, or order API. */
public final class HtxTrendExitKernelV5 {
    private static final Set<String> INDEPENDENT=Set.of("E-H","E-B","E-F");
    private HtxTrendExitKernelV5() { }

    public static Transition decide(EntryThesis contract, State prior, Event event, Policy policy) {
        validate(contract,prior,event,policy);
        String hash=canonicalHash(Map.of("contract",contract,"state",prior,"event",event,"policy",policy));
        List<String> groups=new ArrayList<>(new LinkedHashSet<>(event.evidence_groups()==null?List.of():event.evidence_groups())); groups.sort(String::compareTo);
        if (!event.ownership_confirmed()) { Mutable s=new Mutable(prior);s.revision++;s.execution=ExecutionState.UNKNOWN;
            return result(s.freeze(),Action.RECONCILE_REQUIRED,event.confirmed_remaining_contracts(),List.of("OWNERSHIP_UNCERTAIN"),groups,hash); }
        if (hard(contract.side(),event.reference_price(),contract.immutable_initial_stop())) { Mutable s=new Mutable(prior);s.revision++;
            return result(s.freeze(),Action.FULL_CLOSE,0,List.of("INITIAL_HARD_STOP"),groups,hash); }
        if (!"COMPLETE".equals(event.data_quality())) { Mutable s=new Mutable(prior);s.revision++;s.execution=ExecutionState.UNKNOWN;
            return result(s.freeze(),Action.RECONCILE_REQUIRED,event.confirmed_remaining_contracts(),List.of("DATA_QUALITY_INCOMPLETE"),groups,hash); }
        if (event.kind()==EventKind.TICK) { Mutable s=new Mutable(prior);s.revision++;
            return result(s.freeze(),Action.HOLD,event.confirmed_remaining_contracts(),List.of("AWAIT_CLOSED_BAR"),groups,hash); }
        if (!eligible(contract,prior,event,policy)) return result(prior,Action.HOLD,event.confirmed_remaining_contracts(),
                List.of("INELIGIBLE_OR_DUPLICATE_BAR"),groups,hash);

        Mutable s=new Mutable(prior);s.revision++;
        if(prior.pending_boundary_id()!=null){s.active=prior.pending_soft_boundary();s.activeId=prior.pending_boundary_id();}
        s.pending=null;s.pendingId=null;s.lastBar=event.bar_end_ms();s.eligible++;
        s.failure=event.model_state()==ModelState.INVALID?s.failure+1:0;
        s.best=prior.best_close()==null?event.close_price():contract.side()==Side.LONG?prior.best_close().max(event.close_price()):prior.best_close().min(event.close_price());
        s.breach=crossed(contract.side(),event.close_price(),s.active)?s.breach+1:0;
        if(s.failure>=policy.structure_failure_confirm_bars()) return result(s.freeze(),Action.FULL_CLOSE,0,List.of("MODEL_INVALIDATION_CONFIRMED"),groups,hash);
        boolean mature=s.eligible>=policy.maturity_bars(), independent=groups.stream().anyMatch(INDEPENDENT::contains);
        if(s.active!=null&&s.breach>=policy.soft_breach_confirm_bars()) return independent
                ?result(s.freeze(),Action.FULL_CLOSE,0,List.of("CONFIRMED_TREND_FAILURE"),groups,hash)
                :result(s.freeze(),Action.HOLD,event.confirmed_remaining_contracts(),List.of("EXIT_WATCH_INSUFFICIENT_INDEPENDENT_EVIDENCE"),groups,hash);
        if(mature&&event.target_reached()&&independent&&s.partialStage<policy.max_partial_stages()){
            long close=BigDecimal.valueOf(event.confirmed_remaining_contracts()).multiply(policy.partial_fraction()).setScale(0,RoundingMode.DOWN).longValue();
            boolean economic=event.partial_net_pnl()!=null&&event.partial_net_pnl().compareTo(event.round_trip_cost().multiply(policy.partial_net_cost_multiple()))>=0;
            if(close>=1&&event.confirmed_remaining_contracts()-close>=1&&economic){s.phase=ProfitPhase.HARVEST;return result(s.freeze(),Action.PARTIAL_CLOSE,event.confirmed_remaining_contracts()-close,List.of("STRUCTURE_HARVEST"),groups,hash);}
            return result(s.freeze(),Action.HOLD,event.confirmed_remaining_contracts(),List.of("PARTIAL_CLOSE_NOT_ECONOMIC_OR_NOT_DIVISIBLE"),groups,hash);
        }
        BigDecimal favorable=contract.side()==Side.LONG?event.close_price().subtract(contract.entry_price()):contract.entry_price().subtract(event.close_price());
        BigDecimal unit=BigDecimal.valueOf(event.confirmed_remaining_contracts()).multiply(contract.contract_size());
        BigDecimal costDistance=unit.signum()>0?event.round_trip_cost().divide(unit,18,RoundingMode.DOWN):BigDecimal.ZERO;
        BigDecimal armDistance=contract.atr_at_entry().multiply(policy.arm_atr_multiplier()).max(costDistance.multiply(policy.arm_cost_distance_multiple()));
        boolean arm=mature&&favorable.compareTo(armDistance)>=0&&event.current_net_pnl().compareTo(event.round_trip_cost().multiply(policy.arm_net_cost_multiple()))>=0;
        boolean extension=s.extension||arm;
        if(s.eligible>=policy.no_extension_horizon_bars()&&!extension)return result(s.freeze(),Action.FULL_CLOSE,0,List.of("THESIS_TIMEOUT"),groups,hash);
        BigDecimal candidate=null;
        if(arm||EnumSet.of(ProfitPhase.ESTABLISHED,ProfitPhase.HARVEST,ProfitPhase.RUNNER).contains(s.phase)){
            BigDecimal atr=contract.atr_at_entry().max(event.atr_at_close()==null?BigDecimal.ZERO:event.atr_at_close());
            candidate=contract.side()==Side.LONG?s.best.subtract(atr.multiply(policy.noise_atr_multiplier())):s.best.add(atr.multiply(policy.noise_atr_multiplier()));
            if(event.close_price().subtract(candidate).abs().compareTo(atr.multiply(policy.noise_min_atr()))<0)candidate=null;
            else candidate=s.active==null?candidate:contract.side()==Side.LONG?s.active.max(candidate):s.active.min(candidate);
        }
        if(candidate!=null&&(s.active==null||candidate.compareTo(s.active)!=0)){
            s.phase=ProfitPhase.ESTABLISHED;s.extension=extension;if(s.armed==null)s.armed=event.bar_end_ms();s.pending=candidate;
            s.pendingId=canonicalHash(Map.of("barEnd",event.bar_end_ms(),"price",candidate)).substring(0,32);
            return result(s.freeze(),Action.UPDATE_SOFT_BOUNDARY,event.confirmed_remaining_contracts(),List.of("SOFT_BOUNDARY_STAGED_FOR_NEXT_BAR"),groups,hash);
        }
        s.extension=extension;if(extension)s.phase=ProfitPhase.ESTABLISHED;
        return result(s.freeze(),Action.HOLD,event.confirmed_remaining_contracts(),List.of(mature?"THESIS_VALID_HOLD":"SOFT_MATURITY_NOT_REACHED"),groups,hash);
    }
    private static boolean eligible(EntryThesis c,State s,Event e,Policy p){return e.bar_open_ms()!=null&&e.bar_end_ms()!=null&&e.close_price()!=null&&e.bar_open_ms()>=c.first_fill_time_ms()&&e.bar_end_ms()+p.close_grace_ms()<=e.event_time_ms()&&(s.last_bar_end_ms()==null||e.bar_end_ms()>s.last_bar_end_ms());}
    private static boolean hard(Side side,BigDecimal price,BigDecimal stop){return side==Side.LONG?price.compareTo(stop)<=0:price.compareTo(stop)>=0;}
    private static boolean crossed(Side side,BigDecimal price,BigDecimal line){return line!=null&&(side==Side.LONG?price.compareTo(line)<=0:price.compareTo(line)>=0);}
    private static Transition result(State state,Action action,long target,List<String> reasons,List<String> groups,String hash){return new Transition(new Decision(action,state.phase(),target,reasons,groups,hash,state.active_soft_boundary(),state.pending_soft_boundary(),state.eligible_bars(),state.soft_breach_bars()),state);}
    private static void validate(EntryThesis c,State s,Event e,Policy p){
        if(!VERSION.equals(c.engine_version())||c.policy_hash()==null||c.policy_hash().isBlank())throw new IllegalArgumentException("v5 entry thesis requires an engine version and policy hash");
        if(!"trend-entry-thesis-v5".equals(c.contract_schema())||!"trend-model-holding-v5".equals(c.model_adapter_version()))throw new IllegalArgumentException("unsupported v5 contract version");
        if(!c.policy_hash().equals(canonicalHash(p)))throw new IllegalArgumentException("v5 policy does not match the entry thesis policy hash");
        if(c.filled_contracts()<=0||c.contract_size().signum()<=0||c.entry_price().signum()<=0)throw new IllegalArgumentException("invalid v5 contract quantity");
        if(e.confirmed_remaining_contracts()<=0||e.confirmed_remaining_contracts()>c.filled_contracts())throw new IllegalArgumentException("event quantity outside entry contract");
        if(e.reference_price().signum()<=0||e.round_trip_cost().signum()<0)throw new IllegalArgumentException("invalid event pricing");
        if(p.maturity_bars()<1||p.soft_breach_confirm_bars()<1||p.structure_failure_confirm_bars()<1)throw new IllegalArgumentException("invalid confirmations");
        if(s.phase()==ProfitPhase.CLOSED)throw new IllegalArgumentException("closed state cannot be evaluated");
    }
    private static final class Mutable{
        long revision;ProfitPhase phase;ExecutionState execution;Long lastBar;int eligible,failure;BigDecimal best,active,pending;String activeId,pendingId;int breach;Long armed;int partialStage;boolean extension;
        Mutable(State s){revision=s.revision();phase=s.phase();execution=s.execution_state();lastBar=s.last_bar_end_ms();eligible=s.eligible_bars();failure=s.structure_failure_bars();best=s.best_close();active=s.active_soft_boundary();activeId=s.active_boundary_id();pending=s.pending_soft_boundary();pendingId=s.pending_boundary_id();breach=s.soft_breach_bars();armed=s.armed_bar_end_ms();partialStage=s.partial_stage();extension=s.extension_achieved();}
        State freeze(){return new State(revision,phase,execution,lastBar,eligible,failure,best,active,activeId,pending,pendingId,breach,armed,partialStage,extension);}
    }
}
