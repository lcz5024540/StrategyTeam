package com.metaverse.coin.strategy.web.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.TimeZone;

/**
 * 全局 Jackson 时间序列化配置。
 *
 * <p>把所有 HTTP 出参里的 {@link java.util.Date} 字段统一序列化为北京时间
 * (Asia/Shanghai) 的 {@code yyyy-MM-dd HH:mm:ss} 字符串, 与 {@code marketEndAtBjt}、
 * 日志时间戳风格保持一致, 避免出现"开单时间按 UTC 显示、结算时间按北京时间显示"
 * 这类同一条记录里时区不一致的问题。
 *
 * <p>本配置只覆盖<b>序列化</b>(出参), 不动<b>反序列化</b>(入参): 入参仍走 Jackson
 * 默认解析器, 兼容历史调用方传 epoch 毫秒或 ISO-8601, 不会破坏既有
 * {@code @RequestBody} 接口 (例如 allowlist / trade-events ingest)。
 */
@Configuration
public class JacksonConfig {

    /** 北京时间 (东八区)。 */
    public static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter BJT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BEIJING_ZONE);

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer beijingTimeJacksonCustomizer() {
        return builder -> {
            builder.timeZone(TimeZone.getTimeZone(BEIJING_ZONE));
            builder.serializerByType(Date.class, new BeijingDateSerializer());
        };
    }

    /** 把 {@link java.util.Date} 按北京时间格式化为 {@code yyyy-MM-dd HH:mm:ss}。 */
    static final class BeijingDateSerializer extends JsonSerializer<Date> {
        @Override
        public void serialize(Date value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeString(BJT_FORMATTER.format(value.toInstant()));
            }
        }
    }
}
