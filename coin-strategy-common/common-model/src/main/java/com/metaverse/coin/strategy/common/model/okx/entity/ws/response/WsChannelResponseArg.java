package com.metaverse.coin.strategy.common.model.okx.entity.ws.response;

import com.metaverse.coin.strategy.common.model.okx.enums.ws.Channel;

/**
 * The interface of websocket channel argument
 */
public interface WsChannelResponseArg extends WsResponseArg {

    Channel getChannel();

}
