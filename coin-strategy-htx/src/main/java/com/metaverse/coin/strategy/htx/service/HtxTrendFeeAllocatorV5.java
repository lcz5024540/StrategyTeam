package com.metaverse.coin.strategy.htx.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Exact proportional allocation; the final fill absorbs the decimal remainder once. */
public final class HtxTrendFeeAllocatorV5 {
    private HtxTrendFeeAllocatorV5() { }
    public static List<BigDecimal> allocate(BigDecimal total,List<BigDecimal> quantities) {
        if(total.signum()<0||quantities.isEmpty()||quantities.stream().anyMatch(q->q.signum()<=0))throw new IllegalArgumentException("non-negative total and positive fill quantities required");
        BigDecimal denominator=quantities.stream().reduce(BigDecimal.ZERO,BigDecimal::add),allocated=BigDecimal.ZERO;
        List<BigDecimal> out=new ArrayList<>();
        for(int i=0;i<quantities.size();i++){BigDecimal value=i==quantities.size()-1?total.subtract(allocated):total.multiply(quantities.get(i)).divide(denominator,18,RoundingMode.DOWN);out.add(value);allocated=allocated.add(value);}
        return List.copyOf(out);
    }
}
