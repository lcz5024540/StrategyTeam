package com.metaverse.coin.strategy.htx.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HtxTrendExitLiveReadinessV5Test {
    @Test void missingEvidenceFailsClosed(){var result=HtxTrendExitLiveReadinessV5.evaluate(new HtxTrendExitLiveReadinessV5.Evidence(false,false,false,false,false,false,false,false));assertFalse(result.liveAllowed());assertEquals(8,result.blockers().size());}
    @Test void everyGateMustPass(){assertTrue(HtxTrendExitLiveReadinessV5.evaluate(new HtxTrendExitLiveReadinessV5.Evidence(true,true,true,true,true,true,true,true)).liveAllowed());}
}
