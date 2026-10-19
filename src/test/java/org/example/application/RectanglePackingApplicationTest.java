package org.example.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RectanglePackingApplicationTest {

    @TempDir
    Path temporary;

    @Test
    void rectangleEntryUsesBoundedInitialSearchAndPlacesEveryWorkpiece() throws Exception {
        Path inputRoot = temporary.resolve("CLASSData");
        Path groupDirectory = inputRoot.resolve("7");
        Path caseFile = groupDirectory.resolve("Class_7_4_1.txt");
        Path outputRoot = temporary.resolve("result");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Files.createDirectories(groupDirectory);
            Files.writeString(inputRoot.resolve("material_7.csv"), "Color,Length,Width\n1,80,80\n");
            Files.writeString(caseFile, "40 40\n40 40\n40 40\n40 40\n");

            RectanglePackingApplication.run(caseFile, outputRoot, true);
        });

        Path caseOutput = outputRoot.resolve("Class_7_4_1");
        Path optimized = caseOutput.resolve("optimized.csv");
        assertTrue(Files.isRegularFile(optimized));
        assertEquals(5, Files.readAllLines(optimized).size(), "header plus all four rectangles");

        String log = Files.readString(caseOutput.resolve("optimized.log"));
        assertTrue(log.contains("Initial-packing policy"));
        assertTrue(log.contains("per-board search budget=1000ms"));
    }
}
