package com.metaverse.coin.strategy.core.arbitrage.multi;
import java.time.*; import java.util.*; import java.util.concurrent.ConcurrentHashMap;
/** Requires the same directional pair to remain qualified for a configured duration. */
public class CrossExchangeOpportunityStabilityGate {
 private final Duration duration; private final Map<String,Instant> firstSeen=new ConcurrentHashMap<>();
 public CrossExchangeOpportunityStabilityGate(Duration duration){this.duration=duration;}
 public boolean stable(CrossExchangeOpportunity o,Instant now){if(!o.executable()){firstSeen.remove(key(o));return false;}Instant first=firstSeen.computeIfAbsent(key(o),k->now);return !first.plus(duration).isAfter(now);}
 private String key(CrossExchangeOpportunity o){return o.symbol()+":"+o.longQuote().exchange()+":"+o.shortQuote().exchange();}
}
