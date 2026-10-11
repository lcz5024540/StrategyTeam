package com.metaverse.coin.strategy.htx.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import static com.metaverse.coin.strategy.htx.service.HtxTrendExitContractV5.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HtxTrendExitKernelV5Test {
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void sharedGoldenFixtureMatchesEveryDecisionAndStateField() throws IOException{
        JsonNode fixture=JSON.readTree(Files.readString(fixturePath()));
        EntryThesis contract=JSON.treeToValue(fixture.get("contract"),EntryThesis.class);
        Policy policy=JSON.treeToValue(fixture.get("policy"),Policy.class);
        assertEquals(contract.policy_hash(),canonicalHash(policy));
        for(JsonNode c:fixture.get("cases")){
            Transition result=HtxTrendExitKernelV5.decide(contract,JSON.treeToValue(c.get("state"),State.class),JSON.treeToValue(c.get("event"),Event.class),policy);
            assertEquals(c.get("expected_decision"),JSON.readTree(canonicalJson(result.decision())),c.get("name").asText());
            assertEquals(c.get("expected_state"),JSON.readTree(canonicalJson(result.state())),c.get("name").asText());
        }
    }
    @Test void allSupportedModelAdaptersUseFrozenFailureZone() throws IOException{
        EntryThesis base=base();
        for(String model:new String[]{"FIB_PULLBACK_CONTINUATION","DONCHIAN_BREAKOUT","EMA_VWAP_MOMENTUM_CONTINUATION"}){
            EntryThesis c=withModel(base,model);assertEquals(ModelState.VALID,HtxTrendModelHoldingV5.evaluate(c,new BigDecimal("98"),true).state());
            assertEquals(ModelState.INVALID,HtxTrendModelHoldingV5.evaluate(c,new BigDecimal("97"),true).state());
        }
    }
    @Test void unknownModelOrSourceFailsClosed() throws IOException{
        EntryThesis base=base();assertEquals(ModelState.UNKNOWN,HtxTrendModelHoldingV5.evaluate(withModel(base,"UNKNOWN"),BigDecimal.TEN,true).state());
        assertEquals(ModelState.UNKNOWN,HtxTrendModelHoldingV5.evaluate(base,null,true).state());
    }
    private static EntryThesis base()throws IOException{return JSON.treeToValue(JSON.readTree(Files.readString(fixturePath())).get("contract"),EntryThesis.class);}
    private static EntryThesis withModel(EntryThesis v,String model){return new EntryThesis(v.account_id(),v.strategy_instance(),v.position_epoch(),model,v.structure_id(),v.side(),v.timeframe_seconds(),v.first_fill_time_ms(),v.entry_price(),v.filled_contracts(),v.contract_size(),v.immutable_initial_stop(),v.failure_zone(),v.target_price(),v.atr_at_entry(),v.entry_fee(),v.engine_version(),v.contract_schema(),v.model_adapter_version(),v.policy_hash());}
    private static Path fixturePath(){Path p=Path.of("").toAbsolutePath();for(int i=0;i<4&&p!=null;i++,p=p.getParent()){Path c=p.resolve("fixtures/trend-exit-v5/golden.json");if(Files.isRegularFile(c))return c;}throw new IllegalStateException("golden fixture missing");}
}
