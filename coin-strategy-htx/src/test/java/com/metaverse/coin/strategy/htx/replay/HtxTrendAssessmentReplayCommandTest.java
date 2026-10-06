package com.metaverse.coin.strategy.htx.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HtxTrendAssessmentReplayCommandTest {

    @TempDir
    Path directory;

    @Test
    void rejectsAnInputWithoutAnObservationArray() throws Exception {
        Path input = directory.resolve("projection.json");
        Files.writeString(input, "{\"dataIntegrity\":\"COMPLETE\"}");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> HtxTrendAssessmentReplayCommand.main(new String[]{
                        "--input=" + input, "--output=" + directory.resolve("report.json")}));

        assertEquals("input observations must be a JSON array", exception.getMessage());
    }
}
