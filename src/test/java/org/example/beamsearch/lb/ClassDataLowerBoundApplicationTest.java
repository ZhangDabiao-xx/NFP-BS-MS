package org.example.beamsearch.lb;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.beamsearch.lb.validation.ClassDataLowerBoundApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClassDataLowerBoundApplicationTest {
    @TempDir
    Path temporary;

    @Test
    void verifiesGeometryBeforeUsingExistingPackingsAsUpperBounds() throws Exception {
        Path input = temporary.resolve("CLASSData");
        Files.createDirectories(input.resolve("01"));
        Files.writeString(input.resolve("material_01.csv"), "Color,Length,Width\n1,10,10\n");
        Files.writeString(input.resolve("01/example.txt"), "6 4\n6 4\n6 4\n6 4\n6 4\n");
        Path fixed = temporary.resolve("fixed/01/example");
        Path rotated = temporary.resolve("rotated/01/example");
        Files.createDirectories(fixed);
        Files.createDirectories(rotated);
        String header = "BatchNo,BoardNo,Color,UPI,X,Y,R,L,W,Area,Type,SubCode\n";
        String fixedRows = header
                + row(1, 1, 0, 0, 0) + row(2, 1, 0, 40, 0)
                + row(3, 2, 0, 0, 0) + row(4, 2, 0, 40, 0) + row(5, 3, 0, 0, 0);
        String rotatedRows = header
                + row(1, 1, 0, 0, 0) + row(2, 1, 0, 40, 0) + row(3, 1, 60, 0, 90)
                + row(4, 2, 0, 0, 0) + row(5, 2, 0, 40, 0);
        Files.writeString(fixed.resolve("optimized.csv"), fixedRows);
        Files.writeString(rotated.resolve("optimized.csv"), rotatedRows);
        Path output = temporary.resolve("report");
        ClassDataLowerBoundApplication.run(input, output, temporary.resolve("fixed"),
                temporary.resolve("rotated"), true);
        JsonObject summary = JsonParser.parseString(Files.readString(output.resolve("summary.json")))
                .getAsJsonObject();
        assertEquals(3, summary.getAsJsonObject("fixed").get("lowerBoundSum").getAsInt());
        assertEquals(2, summary.getAsJsonObject("rotation").get("lowerBoundSum").getAsInt());
        assertEquals(1, group(summary, "rotation").get("upperBoundsVerified").getAsInt());
        assertEquals(1, group(summary, "rotation").get("lowerBoundsEqualUpperBound").getAsInt());
        assertFalse(summary.getAsJsonObject("fixed").get("standardGroupCounts").getAsBoolean());
        assertEquals(7207, summary.getAsJsonObject("fixed").get("referenceLowerBoundSum").getAsInt());
        assertEquals(7208, summary.getAsJsonObject("fixed").get("referencePrintedTotal").getAsInt());
        assertFalse(summary.getAsJsonObject("fixed").get("referenceRowsMatchPrintedTotal").getAsBoolean());

        Files.writeString(rotated.resolve("optimized.csv"), rotatedRows.replace(
                row(3, 1, 60, 0, 90), row(3, 1, 0, 0, 90)));
        ClassDataLowerBoundApplication.run(input, output, temporary.resolve("fixed"),
                temporary.resolve("rotated"), false);
        summary = JsonParser.parseString(Files.readString(output.resolve("summary.json"))).getAsJsonObject();
        assertEquals(0, group(summary, "rotation").get("upperBoundsVerified").getAsInt());
        assertEquals(1, group(summary, "rotation").get("invalidExistingPackings").getAsInt());
    }

    private static JsonObject group(JsonObject summary, String mode) {
        return summary.getAsJsonObject(mode).getAsJsonObject("groups").getAsJsonObject("5");
    }

    private static String row(int id, int board, int x, int y, int rotation) {
        return "example," + board + ",1,example-" + String.format("%04d", id) + "," + x + "," + y
                + "," + rotation + ",6,4,2400,0,0\n";
    }
}
