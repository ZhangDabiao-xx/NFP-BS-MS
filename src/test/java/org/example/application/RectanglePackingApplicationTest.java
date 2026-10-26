package org.example.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.beamsearch.application.LoadingTestRun;
import org.example.beamsearch.application.PackingRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        JsonObject report = readReport(caseOutput);
        assertEquals(1, report.getAsJsonObject("rectangleLowerBound").get("lowerBound").getAsInt());
        assertTrue(report.getAsJsonObject("rectangleLowerBound").get("allowRotation").getAsBoolean());
        assertFalse(report.getAsJsonObject("repackMonitoring").getAsJsonObject("priority")
                .get("executed").getAsBoolean());
        assertEquals("lower_bound_reached_before_optimization",
                report.getAsJsonObject("rectangleLowerBound").get("stopReason").getAsString());
    }

    @Test
    void rotationFlagSelectsTheBoundAndEachCaseGetsItsOwnCalculation() throws Exception {
        Path input = temporary.resolve("CLASSData");
        Files.createDirectories(input.resolve("07"));
        Files.writeString(input.resolve("material_07.csv"), "Color,Length,Width\n1,10,10\n");
        Files.writeString(input.resolve("07/A.txt"), "6 4\n6 4\n6 4\n6 4\n6 4\n");
        Files.writeString(input.resolve("07/B.txt"), "10 10\n10 10\n10 10\n10 10\n");
        for (boolean rotation : new boolean[]{false, true}) {
            Path output = temporary.resolve("results-" + rotation);
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> RectanglePackingApplication.run(input, output, rotation));
            for (String name : new String[]{"A", "B"}) {
                Path result = output.resolve("07").resolve(name);
                JsonObject report = readReport(result);
                JsonObject bound = report.getAsJsonObject("rectangleLowerBound");
                int expected = name.equals("B") ? 4 : rotation ? 2 : 3;
                assertEquals(expected, bound.get("lowerBound").getAsInt());
                assertEquals(expected, report.get("boardCount").getAsInt());
                assertEquals(rotation, bound.get("allowRotation").getAsBoolean());
                assertTrue(bound.get("reached").getAsBoolean());
                assertTrue(report.getAsJsonObject("verification").get("passed").getAsBoolean());
                assertEquals(1, Files.readAllLines(result.resolve("optimized.log")).stream()
                        .filter(line -> line.startsWith("Rectangle case lower bound=")).count());
                assertTrue(Files.readString(result.resolve("total.txt")).contains("Rectangle case lower bound: " + expected));
            }
        }
    }

    @Test
    void mixedEntryDisablesBoundsWhileRectangleEntriesKeepTheirOwnBoundsInTheSameProcess() throws Exception {
        Path input = temporary.resolve("CLASSData");
        Files.createDirectories(input.resolve("07"));
        Files.writeString(input.resolve("material_07.csv"), "Color,Length,Width\n1,10,10\n");
        Path source = input.resolve("07/A.txt");
        Files.writeString(source, "6 4\n6 4\n6 4\n6 4\n6 4\n");
        Path rectangleOutput = temporary.resolve("rectangle");
        RectanglePackingApplication.run(source, rectangleOutput, false);

        Path genericOutput = temporary.resolve("generic");
        Files.createDirectories(genericOutput);
        Path bridge = rectangleOutput.resolve("A/input");
        String oldBudget = System.getProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY);
        System.setProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY, "1");
        try {
            LoadingTestRun.runWithImprove(bridge.resolve("material.csv").toString(),
                    bridge.resolve("workpiece.csv").toString(), genericOutput.toString());
        } finally {
            if (oldBudget == null) {
                System.clearProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY);
            } else {
                System.setProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY, oldBudget);
            }
        }
        assertFalse(readReport(genericOutput).has("rectangleLowerBound"));
        assertFalse(Files.readString(genericOutput.resolve("optimized.log")).contains("Rectangle case lower bound="));
        JsonObject priority = readReport(genericOutput).getAsJsonObject("repackMonitoring").getAsJsonObject("priority");
        // 同一进程切换到混排时不计算阶段下界，也不能继承前一个矩形案例的停止条件。
        assertFalse(priority.has("stageLowerBound"));
        assertFalse(priority.get("stopReason").getAsString().startsWith("lower_bound_reached"));
        assertFalse(Files.readString(genericOutput.resolve("optimized.log")).contains("[Stage lower bound]"));
        assertFalse(readReport(rectangleOutput.resolve("A")).getAsJsonObject("repackMonitoring")
                .getAsJsonObject("priority").has("stageLowerBound"));
        // 同一 JVM 回到可旋转纯矩形入口，仍应计算并使用该案例自己的下界。
        Path rectangleAgain = temporary.resolve("rectangle-after-mixed");
        RectanglePackingApplication.run(source, rectangleAgain, true);
        JsonObject again = readReport(rectangleAgain.resolve("A"));
        assertEquals(2, again.getAsJsonObject("rectangleLowerBound").get("lowerBound").getAsInt());
        assertTrue(again.getAsJsonObject("rectangleLowerBound").get("allowRotation").getAsBoolean());
        assertFalse(again.getAsJsonObject("repackMonitoring").getAsJsonObject("priority").has("stageLowerBound"));
    }

    @Test
    void filteredRectangleInputCannotBeCertifiedAsACompleteCase() throws Exception {
        Path input = temporary.resolve("CLASSData");
        Files.createDirectories(input.resolve("07"));
        Files.writeString(input.resolve("material_07.csv"), "Color,Length,Width\n1,10,10\n");
        Path source = input.resolve("07/A.txt");
        Files.writeString(source, "20 20\n5 5\n");
        assertThrows(java.io.IOException.class,
                () -> RectanglePackingApplication.run(source, temporary.resolve("result"), false));
    }

    private static JsonObject readReport(Path output) throws Exception {
        return JsonParser.parseString(Files.readString(output.resolve("llm/run-report.json"))).getAsJsonObject();
    }
}
