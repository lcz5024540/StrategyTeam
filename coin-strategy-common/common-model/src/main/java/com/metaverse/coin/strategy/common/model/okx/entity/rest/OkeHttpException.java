package com.metaverse.coin.strategy.common.model.okx.entity.rest;

public class OkeHttpException extends RuntimeException {

    private final OkxRestError okxRestError;

    public OkeHttpException(OkxRestError okxRestError, String message, Throwable cause) {
        super(message, cause);
        this.okxRestError = okxRestError;
    }

}
