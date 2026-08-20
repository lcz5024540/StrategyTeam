package com.metaverse.coin.strategy.core.arbitrage.multi;
import com.fasterxml.jackson.databind.JsonNode; import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant; import java.util.*;
@Component public final class BinanceFundingRateGateway extends ReadOnlyFundingRateGateway implements PerpetualExchangeGateway {
 public Exchange exchange(){return Exchange.BINANCE;}
 protected URI fundingUri(String s){return URI.create("https://fapi.binance.com/fapi/v1/premiumIndex?symbol="+s);}
 protected BigDecimal fundingRate(JsonNode n){return n.path("lastFundingRate").decimalValue();}
 protected BigDecimal markPrice(JsonNode n){return n.path("markPrice").decimalValue();}
 public List<String> supportedUsdtPerpetualSymbols(){List<String> r=new ArrayList<>();for(JsonNode n:getJson(URI.create("https://fapi.binance.com/fapi/v1/exchangeInfo")).path("symbols"))if("PERPETUAL".equals(n.path("contractType").asText())&&"USDT".equals(n.path("quoteAsset").asText())&&"TRADING".equals(n.path("status").asText()))r.add(n.path("symbol").asText());return r;}
 public List<PerpetualContract> listContracts(){List<PerpetualContract> result=new ArrayList<>();for(JsonNode n:getJson(URI.create("https://fapi.binance.com/fapi/v1/exchangeInfo")).path("symbols")){if(!"PERPETUAL".equals(n.path("contractType").asText())||!"USDT".equals(n.path("quoteAsset").asText())||!"TRADING".equals(n.path("status").asText()))continue;BigDecimal tick=filter(n,"PRICE_FILTER","tickSize");BigDecimal step=filter(n,"LOT_SIZE","stepSize");BigDecimal minimum=filter(n,"MIN_NOTIONAL","notional");if(tick.signum()>0&&step.signum()>0&&minimum.signum()>0)result.add(new PerpetualContract(n.path("symbol").asText(),n.path("symbol").asText(),scale(step),scale(tick),minimum));}return result;}
 public NormalizedFundingRateSnapshot fundingRate(String s){JsonNode n=getJson(fundingUri(s));return new NormalizedFundingRateSnapshot(exchange(),s,n.path("lastFundingRate").decimalValue(),8,Instant.ofEpochMilli(n.path("nextFundingTime").asLong()),n.path("markPrice").decimalValue(),n.path("indexPrice").decimalValue(),Instant.now());}
 public NormalizedOrderBookSnapshot orderBook(String s,int depth){JsonNode n=getJson(URI.create("https://fapi.binance.com/fapi/v1/depth?symbol="+s+"&limit="+Math.min(100,Math.max(5,depth))));BigDecimal bid=sum(n.path("bids")),ask=sum(n.path("asks"));return new NormalizedOrderBookSnapshot(exchange(),s,new BigDecimal(n.path("bids").path(0).path(0).asText()),new BigDecimal(n.path("asks").path(0).path(0).asText()),bid,ask,Instant.now());}
 private BigDecimal sum(JsonNode levels){BigDecimal x=BigDecimal.ZERO;for(JsonNode l:levels)x=x.add(new BigDecimal(l.path(0).asText()).multiply(new BigDecimal(l.path(1).asText())));return x;}
 private BigDecimal filter(JsonNode symbol,String type,String field){for(JsonNode f:symbol.path("filters"))if(type.equals(f.path("filterType").asText()))return new BigDecimal(f.path(field).asText("0"));return BigDecimal.ZERO;}
 private int scale(BigDecimal step){return Math.max(0,step.stripTrailingZeros().scale());}
}
