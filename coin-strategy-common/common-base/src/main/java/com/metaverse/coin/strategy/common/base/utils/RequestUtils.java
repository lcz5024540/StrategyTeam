package com.metaverse.coin.strategy.common.base.utils;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Objects;

public class RequestUtils {

    /**
     * 获取国际化标识
     *
     * @return {@link com.metaverse.coin.strategy.common.base.domain.InDTO} 字段名;默认返回中文
     */
    public static String getHeaderIn() {
        try {
            HttpServletRequest request = getRequest();
            if (Objects.isNull(request)) {
                return "zh";
            }
            String in = request.getHeader("in");
            return StringUtils.isBlank(in) ? "zh" : in;
        } catch (Exception e) {
            return "zh";
        }
    }

    @NotNull
    private static HttpServletRequest getRequest() {
        try {
            ServletRequestAttributes servletRequestAttributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return servletRequestAttributes.getRequest();
        } catch (Exception e) {
            return null;
        }
    }
}
