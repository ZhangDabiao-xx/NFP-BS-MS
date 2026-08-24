package org.example.beamsearch.application;

import java.io.File;
import java.io.IOException;

/**
 * 批量运行 data/inputData2 下全部 12 个 case 的排样。
 * 每个 case 目录下需要有 material.csv 和 workpiece 两个文件。
 * 输出到 data/outputData2/{caseName}/ 目录下。
 */
public class InputData2BatchRunner {

    public static void main(String[] args) throws IOException {
        String baseInputDir = "data/inputData2";
        String baseOutputDir = "data/outputData2";

        File inputDir = new File(baseInputDir);
        if (!inputDir.exists() || !inputDir.isDirectory()) {
            System.err.println("Input directory not found: " + inputDir.getAbsolutePath());
            System.exit(1);
        }

        File[] cases = inputDir.listFiles(File::isDirectory);
        if (cases == null || cases.length == 0) {
            System.err.println("No case directories found under: " + inputDir.getAbsolutePath());
            System.exit(1);
        }

        for (File caseDir : cases) {
            String caseName = caseDir.getName();
            File materialFile = new File(caseDir, "material.csv");
            File workpieceFile = new File(caseDir, "workpiece");

            if (!materialFile.exists() || !workpieceFile.exists()) {
                System.err.println("Skipping " + caseName + ": missing material.csv or workpiece");
                continue;
            }

            File outDir = new File(baseOutputDir, caseName);
            outDir.mkdirs();

            System.out.println("========== Processing: " + caseName + " ==========");
            System.out.println("  Material:  " + materialFile.getAbsolutePath());
            System.out.println("  Workpiece: " + workpieceFile.getAbsolutePath());
            System.out.println("  Output:    " + outDir.getAbsolutePath());

            try {
                String[] result = LoadingTestRun.runWithImprove(
                        materialFile.getAbsolutePath(),
                        workpieceFile.getAbsolutePath(),
                        outDir.getAbsolutePath()
                );
                if (result != null) {
                    System.out.println("  Result: name=" + result[0]
                            + ", workpieces=" + result[1]
                            + ", boards=" + result[2]
                            + ", utilization=" + result[3]
                            + ", time=" + result[4]);
                }
            } catch (Exception e) {
                System.err.println("  ERROR processing " + caseName + ": " + e.getMessage());
                e.printStackTrace();
            }
            System.out.println();
        }

        System.out.println("Done. All results under: " + new File(baseOutputDir).getAbsolutePath());
    }
}