package com.metaverse.coin.strategy.common.model.okx.entity.ws.request;

import com.metaverse.coin.strategy.common.model.okx.enums.ws.Channel;

/**
 * The interface of websocket channel argument
 */
public interface WsChannelRequestArg extends WsRequestArg {

    Channel getChannel();

}
