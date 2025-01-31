package com.metaverse.coin.strategy.common.base.exception;


import com.metaverse.coin.strategy.common.base.constants.ErrorConstantEnum;
import lombok.Data;

@Data
public class BusinessException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private ErrorConstantEnum errorConstantEnum;
    private String message;
    private Integer code;

    public BusinessException(ErrorConstantEnum errorConstantEnum, Throwable e) {
        super(errorConstantEnum.getErrMsg(), e);
        this.errorConstantEnum = errorConstantEnum;
        this.message = errorConstantEnum.getErrMsg();
        this.code = errorConstantEnum.getErrCode();
    }

    public BusinessException(ErrorConstantEnum errorConstantEnum, String message, Throwable e) {
        super(message, e);
        this.errorConstantEnum = errorConstantEnum;
        this.message = message;
        this.code = errorConstantEnum.getErrCode();
    }

    public BusinessException(ErrorConstantEnum errorConstantEnum, String message) {
        super(message);
        this.errorConstantEnum = errorConstantEnum;
        this.message = message;
        this.code = errorConstantEnum.getErrCode();
    }

    public BusinessException(ErrorConstantEnum errorConstantEnum) {
        super(errorConstantEnum.getErrMsg());
        this.errorConstantEnum = errorConstantEnum;
        this.message = errorConstantEnum.getErrMsg();
        this.code = errorConstantEnum.getErrCode();
    }
}
