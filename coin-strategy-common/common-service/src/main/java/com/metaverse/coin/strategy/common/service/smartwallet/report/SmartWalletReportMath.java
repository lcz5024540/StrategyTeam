package com.metaverse.coin.strategy.common.service.smartwallet.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class SmartWalletReportMath {

    private static final DateTimeFormatter BJT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private SmartWalletReportMath() {
    }

    public static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static BigDecimal winRate(int wins, int losses) {
        int total = wins + losses;
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    public static BigDecimal average(BigDecimal total, int count) {
        if (count <= 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        return safe(total).divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
    }

    public static String formatEpochMillis(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return null;
        }
        return BJT.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String normalizeWalletAddress(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return "UNKNOWN";
        }
        return walletAddress.trim().toLowerCase();
    }
}
