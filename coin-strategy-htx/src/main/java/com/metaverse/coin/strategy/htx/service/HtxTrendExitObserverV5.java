package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.HtxTrendExitStateStoreV5;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static com.metaverse.coin.strategy.htx.persistence.HtxTrendExitStateStoreV5.*;
import static com.metaverse.coin.strategy.htx.service.HtxTrendExitContractV5.*;

/** Offline/observe/paper adapter. It deliberately persists no executable intent. */
@Service
public class HtxTrendExitObserverV5 {
    private final HtxTrendExitStateStoreV5 store;
    public HtxTrendExitObserverV5(HtxTrendExitStateStoreV5 store){this.store=store;}
    public record ObservationRequest(String mode,long positionId,long positionEpoch,long expectedRevision,long ownerToken,String accountLabel,String contractCode,BigDecimal confirmedQty,EntryThesis contract,State prior,Event event,Policy policy,LocalDateTime observedAt){}
    public record ObservationResult(Transition transition,AppendStatus persistenceStatus){}
    public ObservationResult observe(ObservationRequest request){if(!"OBSERVE".equals(request.mode())&&!"PAPER".equals(request.mode()))throw new IllegalArgumentException("trend exit v5 observer permits only OBSERVE or PAPER");Transition transition=HtxTrendExitKernelV5.decide(request.contract(),request.prior(),request.event(),request.policy());String key=decisionKey(request.positionId(),request.positionEpoch(),request.event().event_key(),request.contract().policy_hash());AppendStatus status=store.compareAndAppend(new AppendRequest(request.positionId(),request.positionEpoch(),request.expectedRevision(),request.ownerToken(),request.event().event_key(),key,request.accountLabel(),request.contractCode(),request.confirmedQty(),request.contract(),request.policy(),request.prior(),request.event(),transition,false,request.observedAt()));return new ObservationResult(transition,status);}
}
