package com.metaverse.coin.strategy.common.base.constants;

import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * 响应码枚举
 */
public enum ErrorConstantEnum {

    /**
     * 系统响应码
     */
    SUCCESS(200, "请求成功", "The request is successful."),
    FAILURE(500,"系统繁忙，请稍后再试", "System busy, please try again later."),
    REQUEST_FREQUENTLY(501, "请求频繁", "The request is frequent."),

    REQUEST_PARAM_EXCEPTION(10000, "请求参数异常", "Request parameter exception."),


    USER_EXISTS(20000, "用户已存在", "User exists."),
    USER_NOT_EXISTS(20001, "用户不存在", "User not exists."),
    USER_ERROR(20002, "用户名或密码错误", "The user name or password is incorrect."),
    USER_NOT_LOGIN(20003, "用户未登录", "User not logged in."),
    USER_ILLEGAL(20004, "非法的用户信息", "Illegal user information."),


    WEB3J_CONFIG_ERROR(30000, "链配置错误", "Unknown chain web3j config."),
    WEB3J_EXECUTE_ERROR(30001, "web3j执行异常", "Web3j execute error."),
    PRIVATE_KEY_ERROR(30002, "私钥错误", "Private key error."),
    DUPLICATE_KEY_EXCEPTION(30003, "请等待进行中的链上操作结束后再发起新的操作", "Please wait for the ongoing on-chain operation to finish before initiating a new one."),


    USER_CLAIM_ERROR(40000, "用户领取错误", "user can't claim"),
    UPLOAD_FILE_EMPTY_ERROR(40001, "上传文件不能为空", "upload file can not be null"),
    UPLOAD_FILE_FAILED_ERROR(40002, "上传文件失败", "upload file failed"),
    INVITE_CODE_INVALID(40003, "邀请码无效", "invite code invalid"),
    USER_ALREADY_CLAIM_ERROR(40004, "用户已领取", "user already claim"),
    NOT_CLAIM_TIME_ERROR(40005, "不在mint时间内", "not in claim time"),
    NOT_SHOP_LOTTERY_TIME_ERROR(40006, "不在购买彩票时间内", "not in shop lottery time"),
    ;


    private Integer errCode;
    /**
     * 中文提示信息
     */
    private String errMsg;
    /**
     * 英文提示信息
     */
    private String enErrMsg;
    private Predicate predicate;


    ErrorConstantEnum(Integer errCode, String errMsg) {
        this.errCode = errCode;
        this.errMsg = errMsg;
    }

    ErrorConstantEnum(Integer errCode, String errMsg, String enErrMsg) {
        this.errCode = errCode;
        this.errMsg = errMsg;
        this.enErrMsg = enErrMsg;
    }

    ErrorConstantEnum(Integer errCode, String errMsg, Predicate predicate) {
        this.errCode = errCode;
        this.errMsg = errMsg;
        this.predicate = predicate;

    }

    public Integer getErrCode() {
        return errCode;
    }

    public String getErrMsg() {
        return this.errMsg;
    }

    public String getEnErrMsg() {
        return this.enErrMsg;
    }

    public boolean verify(Object object) {
        return predicate.test(object);
    }

    public boolean isSuccess() {
        return ErrorConstantEnum.SUCCESS.getErrCode().equals(this.getErrCode());
    }

    public static ErrorConstantEnum getErrorByMsg(String msg, ErrorConstantEnum defaultErrorEnum) {
        if (StringUtils.isEmpty(msg)) {
            return defaultErrorEnum;
        }
        for (ErrorConstantEnum errorConstantEnum : ErrorConstantEnum.values()) {
            if (errorConstantEnum.getErrMsg().contains(msg)) {
                return errorConstantEnum;
            }
        }
        return defaultErrorEnum;
    }

    @Override
    public String toString() {
        return "ErrorConstantEnum{" + "errCode=" + errCode + ", errMsg='" + errMsg + '\'' + '}';
    }

    public static String info(ErrorConstantEnum errorConstantEnum) {
        return info(null, errorConstantEnum, null);
    }


    public static String info(ErrorConstantEnum errorConstantEnum, Object request) {
        return info(null, errorConstantEnum, request);
    }

    public static String info(String prefix, ErrorConstantEnum errorConstantEnum, Object request) {
        StringBuffer info = new StringBuffer();
        if (StringUtils.isNotBlank(prefix)) {
            info.append(prefix).append(":");
        }
        if (null != errorConstantEnum) {
            info.append("errorCode=").append(errorConstantEnum.getErrCode()).append(",errorMessage=").append(errorConstantEnum.getErrMsg()).append(";");
        }
        if (!Objects.isNull(request)) {
            info.append("request=").append(JSONObject.toJSONString(request));
        }
        return info.toString();
    }

    public static ErrorConstantEnum getByCode(Integer code){
        for (ErrorConstantEnum value : ErrorConstantEnum.values()) {
            if(value.getErrCode().equals(code)){
                return value;
            }
        }
        return null;
    }
}
