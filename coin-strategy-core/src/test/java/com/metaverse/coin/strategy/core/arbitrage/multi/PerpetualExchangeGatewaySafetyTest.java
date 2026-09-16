package com.metaverse.coin.strategy.core.arbitrage.multi;
import org.junit.jupiter.api.Test; import java.math.BigDecimal; import static org.junit.jupiter.api.Assertions.*;
class PerpetualExchangeGatewaySafetyTest {
 @Test void liveOrderIsUnavailableInV1(){PerpetualExchangeGateway gateway=new BinanceFundingRateGateway();var command=new PerpetualExchangeGateway.OrderCommand("id","BTCUSDT",HedgeSide.LONG,BigDecimal.ONE,false);assertThrows(UnsupportedOperationException.class,()->gateway.placeOrder(command));assertThrows(UnsupportedOperationException.class,gateway::getAccount);}
 @Test void hyperliquidHasNoWalletOrOrderPathInPaperMode(){PerpetualExchangeGateway gateway=new HyperliquidFundingRateGateway();var command=new PerpetualExchangeGateway.OrderCommand("id","BTCUSDT",HedgeSide.LONG,BigDecimal.ONE,false);assertThrows(UnsupportedOperationException.class,()->gateway.placeOrder(command));assertThrows(UnsupportedOperationException.class,()->gateway.getPosition("BTCUSDT"));}
 @Test void tickerDoesNotFabricateLastPriceFromMarkPrice(){PerpetualExchangeGateway gateway=new BinanceFundingRateGateway();assertThrows(UnsupportedOperationException.class,()->gateway.getTicker("BTCUSDT"));}
}
