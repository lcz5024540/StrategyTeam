package com.metaverse.coin.strategy.htx.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HtxTrendFrozenReplayConfigurationTest {

    @TempDir
    Path directory;

    @Test
    void loadsEveryCalculationParameterFromTheFrozenSnapshot() throws Exception {
        Path snapshot = write("INSUFFICIENT_EVIDENCE: code defaults are not historical deployment settings");

        HtxTrendFrozenReplayConfiguration.Configuration configuration = HtxTrendFrozenReplayConfiguration.open(snapshot);

        assertEquals("replay-config:test", configuration.configurationKey());
        assertEquals(180, configuration.properties().getKlineSize());
        assertEquals(32, configuration.boxWindowBars());
        assertEquals("test-v1", configuration.properties().getModelVersion());
    }

    @Test
    void rejectsCodeDefaultsThatPretendToBeHistoricalEvidence() throws Exception {
        Path snapshot = write("historical settings verified");

        assertThrows(IllegalArgumentException.class, () -> HtxTrendFrozenReplayConfiguration.open(snapshot));
    }

    private Path write(String evidence) throws Exception {
        Path snapshot = directory.resolve("configuration.json");
        Files.writeString(snapshot, """
                {"schema":"htx-trend-replay-configuration-v1","configurationKey":"replay-config:test",
                "sourceKind":"CODE_DEFAULTS","configurationEvidence":"%s","sourceReference":"test source","inputIntegrity":"COMPLETE",
                "boxWindowBars":32,"ensemble":{"klineSize":180,"candleCloseGraceSeconds":10,
                "fastEmaPeriod":8,"mediumEmaPeriod":21,"slowEmaPeriod":55,"breakoutLookback":20,
                "momentumLookback":8,"atrPeriod":5,"adxPeriod":5,"volumeLookback":20,"minAtrPct":"0.0001",
                "minAdx":"1","minMomentumPct":"0.001","minVolumeRatio":"1","entryScoreThreshold":"60",
                "minScoreGap":"10","modelVersion":"test-v1"}}
                """.formatted(evidence));
        return snapshot;
    }
}
