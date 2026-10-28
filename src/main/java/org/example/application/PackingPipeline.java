package org.example.application;

import org.example.beamsearch.application.NFPToBeamSearchBridge;
import org.example.beamsearch.application.PackingConvergenceRecorder;
import org.example.nfp.BatchBlockStitcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 确定性的基准求解编排：依次完成 NFP 拼接、矩形化转换和优先级排样。
 *
 * <p>程序只需要案例路径。案例路径既可以是单个 JSON 文件，也可以是包含多个
 * JSON 案例的目录。NFP 结果目录和排样结果目录会按案例目录自动创建为同级目录。
 * 中间 {@code material.csv} 与 {@code workpiece} 文件统一写入
 * {@code <packingResultDirectory>/material/<caseName>/}。</p>
 */
public final class PackingPipeline {
    /** NFP 拼接结果目录名称。 */
    private static final String NFP_RESULT_DIRECTORY_NAME = "BA06_NFP";
    /** 矩形排样结果目录名称。 */
    private static final String PACKING_RESULT_DIRECTORY_NAME = "BA06_Packing";
    /** NFP 到排样的中间文件目录名称。 */
    private static final String BRIDGE_DIRECTORY_NAME = "material";

    private PackingPipeline() {
    }

    /**
     * 对一个案例文件或案例目录执行完整流程，并根据案例路径自动创建两个结果目录。
     *
     * @param casePath 单个案例 JSON 文件，或包含多个案例 JSON 文件的目录。
     * @throws IOException 当案例输入不可用或自动创建的结果目录无法读写时抛出。
     */
    public static void run(Path casePath) throws IOException {
        run(casePath, null);
    }

    /** 只有混合入口显式传入启用配置时，才为每个案例采集收敛数据。 */
    public static void run(Path casePath, PackingConvergenceRecorder.Config statistics) throws IOException {
        OutputDirectories outputDirectories = resolveOutputDirectories(casePath);
        run(casePath, outputDirectories.nfpResultDirectory(), outputDirectories.packingResultDirectory(), statistics);
    }

    /**
     * 对一个案例文件或案例目录执行完整的 NFP 拼接和矩形排样流程。
     *
     * @param casePath 单个案例 JSON 文件，或包含多个案例 JSON 文件的目录。
     * @param nfpResultDirectory NFP 拼接结果目录；每个案例输出同名 {@code .txt} 文件。
     * @param packingResultDirectory 排样结果目录；每个案例输出同名子目录和中间 bridge 文件。
     * @throws IOException 当任一案例的输入、NFP 输出或排样输出不可用时抛出。
     */
    public static void run(Path casePath,
                           Path nfpResultDirectory,
                           Path packingResultDirectory) throws IOException {
        run(casePath, nfpResultDirectory, packingResultDirectory, null);
    }

    /** 保留原有无统计入口；统计文件独立写入配置指定目录，不改变排样输出路径。 */
    public static void run(Path casePath,
                           Path nfpResultDirectory,
                           Path packingResultDirectory,
                           PackingConvergenceRecorder.Config statistics) throws IOException {
        Files.createDirectories(nfpResultDirectory);
        Files.createDirectories(packingResultDirectory);
        List<Path> caseJsonFiles = resolveCaseJsonFiles(casePath);
        if (caseJsonFiles.isEmpty()) {
            throw new IOException("未生成任何 NFP 拼接结果。");
        }

        for (Path caseJsonFile : caseJsonFiles) {
            Path nfpResultFile = BatchBlockStitcher.stitchCase(caseJsonFile, nfpResultDirectory);
            if (nfpResultFile == null || !Files.isRegularFile(nfpResultFile)) {
                throw new IOException("NFP 拼接结果不存在: " + nfpResultFile);
            }
            String fileName = caseJsonFile.getFileName().toString();
            String caseName = fileName.substring(0, fileName.lastIndexOf('.'));
            // 作用域覆盖一次完整排样，退出后统一写盘；不会把 NFP 时间计入阶段曲线。
            try (PackingConvergenceRecorder ignored = PackingConvergenceRecorder.openCase(statistics, caseName)) {
                Path casePackingDirectory = NFPToBeamSearchBridge.packCase(
                        nfpResultFile,
                        caseJsonFile,
                        packingResultDirectory.resolve(BRIDGE_DIRECTORY_NAME),
                        packingResultDirectory);
                System.out.println("案例完成: " + caseJsonFile.getFileName() + " -> " + casePackingDirectory);
            }
        }
    }

    /** 将单文件或目录输入规范化成稳定排序的案例 JSON 列表。 */
    private static List<Path> resolveCaseJsonFiles(Path casePath) throws IOException {
        if (Files.isRegularFile(casePath)) {
            if (!casePath.getFileName().toString().toLowerCase().endsWith(".json")) {
                throw new IOException("案例文件必须是 .json: " + casePath);
            }
            return List.of(casePath);
        }
        if (!Files.isDirectory(casePath)) {
            throw new IOException("案例路径既不是文件也不是目录: " + casePath);
        }
        try (var files = Files.list(casePath)) {
            return files.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 根据输入案例文件或目录推导自动创建的 NFP 与排样结果目录。
     *
     * @param casePath 单个 JSON 文件，或包含 JSON 案例的目录。
     * @return 自动推导出的 NFP 和排样结果目录。
     * @throws IOException 当案例路径不存在，或无法确定案例目录时抛出。
     */
    private static OutputDirectories resolveOutputDirectories(Path casePath) throws IOException {
        if (casePath == null || !Files.exists(casePath)) {
            throw new IOException("案例路径不存在: " + casePath);
        }
        Path caseDirectory = Files.isDirectory(casePath) ? casePath : casePath.getParent();
        if (caseDirectory == null) {
            throw new IOException("无法根据案例路径确定输出目录: " + casePath);
        }
        Path outputParentDirectory = caseDirectory.getParent() == null ? caseDirectory : caseDirectory.getParent();
        return new OutputDirectories(
                outputParentDirectory.resolve(NFP_RESULT_DIRECTORY_NAME),
                outputParentDirectory.resolve(PACKING_RESULT_DIRECTORY_NAME));
    }

    /**
     * 自动推导出的两个结果目录。
     *
     * @param nfpResultDirectory NFP 拼接文本结果目录。
     * @param packingResultDirectory 矩形排样结果目录。
     */
    private record OutputDirectories(Path nfpResultDirectory, Path packingResultDirectory) {
    }
}
