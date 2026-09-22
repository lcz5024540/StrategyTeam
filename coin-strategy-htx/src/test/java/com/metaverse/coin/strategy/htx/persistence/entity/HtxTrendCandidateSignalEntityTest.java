package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtxTrendCandidateSignalEntityTest {

    @Test
    void fibonacciExtensionsUseTheMigratedUnderscoreColumnNames() throws NoSuchFieldException {
        assertEquals("fib_extension_1272", HtxTrendCandidateSignalEntity.class
                .getDeclaredField("fibExtension1272").getAnnotation(TableField.class).value());
        assertEquals("fib_extension_1618", HtxTrendCandidateSignalEntity.class
                .getDeclaredField("fibExtension1618").getAnnotation(TableField.class).value());
    }
}
