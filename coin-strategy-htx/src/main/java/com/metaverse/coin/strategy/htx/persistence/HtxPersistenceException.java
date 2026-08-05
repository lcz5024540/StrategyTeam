package com.metaverse.coin.strategy.htx.persistence;

/** 写前落库失败信号。触发 fail-closed：调用方必须放弃真实下单。 */
public class HtxPersistenceException extends RuntimeException {
    public HtxPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
