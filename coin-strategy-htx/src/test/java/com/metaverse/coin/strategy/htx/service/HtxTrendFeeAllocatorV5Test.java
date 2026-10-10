package com.metaverse.coin.strategy.htx.service;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
class HtxTrendFeeAllocatorV5Test {
    @Test void finalFillAbsorbsRemainderExactly(){List<BigDecimal> values=HtxTrendFeeAllocatorV5.allocate(new BigDecimal("0.1"),List.of(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE));assertEquals(0,new BigDecimal("0.1").compareTo(values.stream().reduce(BigDecimal.ZERO,BigDecimal::add)));assertEquals(0,new BigDecimal("0.033333333333333334").compareTo(values.get(2)));}
}
