package com.metaverse.coin.strategy.common.api.service;

import com.metaverse.coin.strategy.common.api.model.UnifiedOrder;
import com.metaverse.coin.strategy.common.api.model.Side;
import com.metaverse.coin.strategy.common.api.model.OrderType;
import java.math.BigDecimal;
import java.util.List;

public interface ITradeService {
    
    UnifiedOrder placeOrder(String symbol, Side side, OrderType type, BigDecimal quantity, BigDecimal price);
    
    UnifiedOrder getOrder(String symbol, String orderId);
    
    UnifiedOrder cancelOrder(String symbol, String orderId);
    
    List<UnifiedOrder> getOpenOrders(String symbol);
}
