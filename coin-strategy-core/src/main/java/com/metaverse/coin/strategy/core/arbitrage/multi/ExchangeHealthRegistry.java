package com.metaverse.coin.strategy.core.arbitrage.multi;
import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import java.time.Instant; import java.util.*; import java.util.concurrent.ConcurrentHashMap; import java.util.concurrent.atomic.AtomicLong;
public class ExchangeHealthRegistry {
 public record Health(Exchange exchange,boolean healthy,long successCount,long failureCount,long rateLimitCount,long lastLatencyMs,Instant lastSuccess,Instant lastFailure,String lastError){}
 private static final class State{final AtomicLong ok=new AtomicLong(),fail=new AtomicLong(),rateLimit=new AtomicLong();volatile long latencyMs;volatile Instant lastOk,lastFail;volatile String error;}
 private final Map<Exchange,State> states=new ConcurrentHashMap<>();
 public void success(Exchange e,Instant now){success(e,now,0L);}
 public void success(Exchange e,Instant now,long latencyMs){State s=states.computeIfAbsent(e,k->new State());s.ok.incrementAndGet();s.latencyMs=Math.max(0L,latencyMs);s.lastOk=now;s.error=null;}
 public void failure(Exchange e,Instant now,Throwable t){failure(e,now,t,0L);}
 public void failure(Exchange e,Instant now,Throwable t,long latencyMs){State s=states.computeIfAbsent(e,k->new State());s.fail.incrementAndGet();s.latencyMs=Math.max(0L,latencyMs);if(isRateLimit(t))s.rateLimit.incrementAndGet();s.lastFail=now;s.error=t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());}
 public Map<Exchange,Health> snapshot(){Map<Exchange,Health> r=new EnumMap<>(Exchange.class);for(Exchange e:Exchange.values()){State s=states.computeIfAbsent(e,k->new State());boolean healthy=s.lastOk!=null&&(s.lastFail==null||s.lastOk.isAfter(s.lastFail));r.put(e,new Health(e,healthy,s.ok.get(),s.fail.get(),s.rateLimit.get(),s.latencyMs,s.lastOk,s.lastFail,s.error));}return Map.copyOf(r);}
 private boolean isRateLimit(Throwable throwable){for(Throwable current=throwable;current!=null;current=current.getCause())if(current instanceof PerpetualMarketDataClient.RateLimitException)return true;return false;}
}
