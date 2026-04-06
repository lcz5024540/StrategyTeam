package com.metaverse.coin.strategy.job.smartwallet.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class SmartWalletTimeFormatter {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BJT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String formatEpochMillisToBjt(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return null;
        }
        return Instant.ofEpochMilli(epochMillis)
                .atZone(BEIJING_ZONE)
                .format(BJT_FORMATTER);
    }
}
