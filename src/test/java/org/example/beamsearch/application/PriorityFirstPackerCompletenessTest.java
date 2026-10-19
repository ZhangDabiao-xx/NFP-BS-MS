package org.example.beamsearch.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriorityFirstPackerCompletenessTest {

    @TempDir
    Path temporary;

    @Test
    void mixedPackingUsesBoundedInitialSearchAndPlacesPriorityAndOrdinaryWorkpieces() throws Exception {
        Path material = temporary.resolve("material.csv");
        Path workpieces = temporary.resolve("workpiece.csv");
        Path output = temporary.resolve("output");
        Files.createDirectories(output);

        Files.writeString(material, "Color,Length,Width,Grain\n0,80,80,0\n1,80,80,0\n");
        Files.writeString(workpieces, String.join("\n",
                "BatchNo,UPI,Qty,Color,Length,Width,IsSpecial,Rotatable,ActualWorkpieceArea",
                "batch,priority-1,1,1,40,40,0,0,1600",
                "batch,priority-2,1,1,40,40,0,0,1600",
                "batch,priority-3,1,1,40,40,0,0,1600",
                "batch,priority-4,1,1,40,40,0,0,1600",
                "batch,ordinary-1,1,0,40,20,0,0,800",
                "batch,ordinary-2,1,0,40,20,0,0,800",
                "batch,ordinary-3,1,0,40,20,0,0,800",
                ""));

        String previousBudget = System.getProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY);
        System.setProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY, "1");
        try {
            LoadingTestRun.runWithImprove(material.toString(), workpieces.toString(), output.toString());
        } finally {
            if (previousBudget == null) {
                System.clearProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY);
            } else {
                System.setProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY, previousBudget);
            }
        }

        List<String> rows = Files.readAllLines(output.resolve("optimized.csv"));
        assertEquals(8, rows.size(), "header plus every priority and ordinary workpiece");
        assertEquals(4, rows.stream().skip(1).filter(row -> row.split(",")[2].equals("1")).count());
        assertEquals(3, rows.stream().skip(1).filter(row -> row.split(",")[2].equals("0")).count());

        String log = Files.readString(output.resolve("optimized.log"));
        assertTrue(log.contains("Initial-packing policy"));
        assertTrue(log.contains("Ordinary insertion budget=10000ms."));
    }
}
