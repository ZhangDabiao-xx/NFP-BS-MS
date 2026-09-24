package org.example.application;

import org.example.agent.NfpStitchingAgentWorkflow;
import org.example.beamsearch.application.NFPToBeamSearchBridge;
import org.example.nfp.BatchBlockStitcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 项目的统一求解入口：依次完成 NFP 拼接、矩形化转换和优先级排样。
 *
 * <p>程序只需要案例路径。案例路径既可以是单个 JSON 文件，也可以是包含多个
 * JSON 案例的目录。NFP 结果目录和排样结果目录会按案例目录自动创建为同级目录。
 * 中间 {@code material.csv} 与 {@code workpiece} 文件统一写入
 * {@code <packingResultDirectory>/material/<caseName>/}。</p>
 */
public final class IntegratedPackingApplication {

    /** 未传入命令行参数时使用的默认案例目录；需要时只修改这一处路径即可。 */
    private static final Path DEFAULT_CASE_PATH = Path.of("data", "inputData");
    /** NFP 拼接结果目录名称。 */
    private static final String NFP_RESULT_DIRECTORY_NAME = "BA04_NFP";
    /** 矩形排样结果目录名称。 */
    private static final String PACKING_RESULT_DIRECTORY_NAME = "BA04_Packing";
    /** NFP 到排样的中间文件目录名称。 */
    private static final String BRIDGE_DIRECTORY_NAME = "material";

    private IntegratedPackingApplication() {
    }

    /**
     * 启动一体化求解流程。
     *
     * @param args 可选的单个参数：案例 JSON 文件或案例目录；未提供时使用 {@link #DEFAULT_CASE_PATH}。
     * @throws IOException 当案例、NFP 结果或排样结果无法读写时抛出。
     */
    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            printUsage();
            return;
        }
        Path casePath = args.length == 1 ? Path.of(args[0]) : DEFAULT_CASE_PATH;
        run(casePath);
    }

    /**
     * 对一个案例文件或案例目录执行完整流程，并根据案例路径自动创建两个结果目录。
     *
     * @param casePath 单个案例 JSON 文件，或包含多个案例 JSON 文件的目录。
     * @throws IOException 当案例输入不可用或自动创建的结果目录无法读写时抛出。
     */
    public static void run(Path casePath) throws IOException {
        OutputDirectories outputDirectories = resolveOutputDirectories(casePath);
        run(casePath, outputDirectories.nfpResultDirectory(), outputDirectories.packingResultDirectory());
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
        Files.createDirectories(nfpResultDirectory);
        Files.createDirectories(packingResultDirectory);
        List<Path> caseJsonFiles = resolveCaseJsonFiles(casePath);
        if (caseJsonFiles.isEmpty()) {
            throw new IOException("未生成任何 NFP 拼接结果。");
        }

        Path bridgeRootDirectory = packingResultDirectory.resolve(BRIDGE_DIRECTORY_NAME);
        for (Path caseJsonFile : caseJsonFiles) {
            String caseName = stripExtension(caseJsonFile.getFileName().toString());
            Path caseLlmDirectory = packingResultDirectory.resolve(caseName).resolve("llm");
            // 先由受限 Agent 选择策略，再交给确定性的 NFP 几何搜索执行。
            NfpStitchingAgentWorkflow.Selection selection = NfpStitchingAgentWorkflow.selectPolicy(
                    caseJsonFile, caseLlmDirectory);
            Path nfpResultFile = BatchBlockStitcher.stitchCase(
                    caseJsonFile, nfpResultDirectory, selection.policy());
            if (nfpResultFile == null || !Files.isRegularFile(nfpResultFile)) {
                throw new IOException("NFP 拼接结果不存在: " + nfpResultFile);
            }
            Path casePackingDirectory = NFPToBeamSearchBridge.packCase(
                    nfpResultFile,
                    caseJsonFile,
                    bridgeRootDirectory,
                    packingResultDirectory);
            // 排样已完成后，记录该 NFP 策略对应的最终质量，供下次同案例选择参考。
            NfpStitchingAgentWorkflow.recordPackingOutcome(caseLlmDirectory, selection);
            System.out.println("案例完成: " + caseJsonFile.getFileName() + " -> " + casePackingDirectory);
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

    private static String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
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

    /** 输出统一入口的命令行参数说明。 */
    private static void printUsage() {
        System.err.println("用法: IntegratedPackingApplication [caseJsonFileOrDirectory]");
        System.err.println("结果将自动写入案例目录同级的 "
                + NFP_RESULT_DIRECTORY_NAME + " 和 " + PACKING_RESULT_DIRECTORY_NAME + " 目录。");
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
