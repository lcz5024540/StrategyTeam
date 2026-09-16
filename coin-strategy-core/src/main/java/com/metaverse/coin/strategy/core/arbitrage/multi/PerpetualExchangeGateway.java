package com.metaverse.coin.strategy.core.arbitrage.multi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
/** Unified connector contract. V1 implementations expose public market data only. */
public interface PerpetualExchangeGateway extends CrossExchangeMarketDataProvider {
 record PerpetualContract(String canonicalSymbol,String exchangeSymbol,int quantityScale,int priceScale,BigDecimal minNotional){
  public PerpetualContract {
   if(canonicalSymbol==null||canonicalSymbol.isBlank()||exchangeSymbol==null||exchangeSymbol.isBlank()
     ||quantityScale<0||priceScale<0||minNotional==null||minNotional.signum()<=0){
    throw new IllegalArgumentException("complete positive contract metadata is required");
   }
  }
 }
 record AccountSnapshot(Exchange exchange,BigDecimal equity,BigDecimal availableMargin){}
 record PositionSnapshot(Exchange exchange,String symbol,BigDecimal longQuantity,BigDecimal shortQuantity){}
 record ContractTicker(Exchange exchange,String canonicalSymbol,BigDecimal lastPrice,
   BigDecimal markPrice,BigDecimal indexPrice,Instant receivedAt){}
 record OrderCommand(String clientOrderId,String symbol,HedgeSide side,BigDecimal quantity,boolean reduceOnly){}
 record OrderResult(String clientOrderId,String exchangeOrderId,String status,BigDecimal filledQuantity,BigDecimal averagePrice){}
 record CancelResult(String clientOrderId,boolean cancelled,String message){}
 List<PerpetualContract> listContracts();
 default NormalizedFundingRateSnapshot getFundingRate(String symbol){return fundingRate(symbol);}
 default NormalizedOrderBookSnapshot getOrderBook(String symbol,int depth){return orderBook(symbol,depth);}
 default ContractTicker getTicker(String symbol){throw new UnsupportedOperationException("ticker is not implemented for "+exchange()+"/"+symbol);}
 default AccountSnapshot getAccount(){throw privateApiDisabled();}
 default PositionSnapshot getPosition(String symbol){throw privateApiDisabled();}
 default OrderResult placeOrder(OrderCommand command){throw privateApiDisabled();}
 default OrderResult getOrder(String symbol,String clientOrderId){throw privateApiDisabled();}
 default CancelResult cancelOrder(String symbol,String clientOrderId){throw privateApiDisabled();}
 private UnsupportedOperationException privateApiDisabled(){return new UnsupportedOperationException("private perpetual API is disabled in V1 DRY_RUN for "+exchange());}
}
