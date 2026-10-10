package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.example.beamsearch.application.NFPToBeamSearchBridge;
import org.example.beamsearch.application.PackingRuntimeConfig;
import org.example.nfp.PolygonItem;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 分档排样、输入等价分组和指标比较；只由 NFP Agent 实验入口调用。 */
final class NfpExperimentEvaluator {
    record InputKey(String geometry, String withArea) { }
    record Measurement(Path directory, JsonObject quality, long budgetMs, long wallMs) { }
    record WeakBoards(Set<String> ids, JsonArray boards) { }
    private NfpExperimentEvaluator() { }

    /** 忽略 UPI 只用于分配试验预算，不复用不同成员方案的 Uagv 或验证结果。 */
    static InputKey inputKey(Path workpiece) throws IOException {
        List<String> geometry = new ArrayList<>(), withArea = new ArrayList<>();
        List<String> rows = Files.readAllLines(workpiece);
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            String[] columns = rows.get(i).split(",");
            if (columns.length != 9) throw new IOException("不支持的 workpiece 列数");
            String row = String.join(",", java.util.Arrays.copyOfRange(columns, 2, 8));
            geometry.add(row); withArea.add(row + "," + columns[8]);
        }
        geometry.sort(String::compareTo); withArea.sort(String::compareTo);
        return new InputKey(hash(String.join("\n", geometry)), hash(String.join("\n", withArea)));
    }

    /** 完整实验指纹包含案例、全部 Java 源码、构建配置、JVM 版本和评估预算。 */
    static String experimentKey(Path input, String settings) throws IOException {
        StringBuilder text = new StringBuilder(hash(Files.readAllBytes(input))).append(settings)
                .append(System.getProperty("java.version")).append("agent-local-v1");
        Path source = Path.of("src/main/java");
        if (Files.isDirectory(source)) {
            try (var paths = Files.walk(source)) {
                for (Path file : paths.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                    text.append(file).append(hash(Files.readAllBytes(file)));
                }
            }
            text.append(hash(Files.readAllBytes(Path.of("pom.xml"))));
        } else {
            // 无源码的分发包无法可靠识别全部算法版本，因此禁用跨运行 baseline 缓存复用。
            text.append(java.util.UUID.randomUUID());
        }
        return hash(text.toString());
    }

    /** 同档预算只在调用期间生效，失败时也恢复系统属性和排样器重定向过的 stdout。 */
    static Measurement measure(Path nfp, Path input, Path root, long budgetMs) throws IOException {
        long start = System.nanoTime();
        String previous = System.getProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY);
        PrintStream stdout = System.out;
        try {
            System.setProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY, Long.toString(budgetMs));
            Path output = NFPToBeamSearchBridge.packCase(nfp, input, root.resolve("material"), root.resolve("packing"));
            JsonObject report = AgentJsonFiles.readObject(output.resolve("llm/run-report.json"), "排样报告");
            if (!verified(report)) throw new IOException("排样未通过完整覆盖/几何验证: " + output);
            long wallMs = (System.nanoTime() - start) / 1_000_000;
            JsonObject envelope = new JsonObject();
            envelope.addProperty("budgetMs", budgetMs); envelope.addProperty("wallMs", wallMs);
            envelope.addProperty("planHash", hash(Files.readAllBytes(nfp)));
            envelope.addProperty("inputHash", hash(Files.readAllBytes(input)));
            envelope.addProperty("reportHash", hash(Files.readAllBytes(output.resolve("llm/run-report.json"))));
            envelope.addProperty("directory", output.toAbsolutePath().toString()); envelope.add("quality", report);
            AgentJsonFiles.writeObject(root.resolve("measurement.json"), envelope);
            return new Measurement(output, report, budgetMs, wallMs);
        } finally {
            System.setOut(stdout);
            if (previous == null) System.clearProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY);
            else System.setProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY, previous);
        }
    }

    /** 仅复用同案例、同代码、同预算且报告仍完整的 baseline；不会读取旧实验的 mtime。 */
    static Measurement baseline(Path nfp, Path input, Path root, long budgetMs) throws IOException {
        Path record = root.resolve("measurement.json");
        if (Files.isRegularFile(record)) {
            try {
                JsonObject saved = AgentJsonFiles.readObject(record, "baseline 缓存");
                Path directory = Path.of(saved.get("directory").getAsString());
                Path report = directory.resolve("llm/run-report.json");
                if (saved.get("budgetMs").getAsLong() == budgetMs
                        && saved.get("planHash").getAsString().equals(hash(Files.readAllBytes(nfp)))
                        && saved.get("inputHash").getAsString().equals(hash(Files.readAllBytes(input)))
                        && Files.isRegularFile(report) && Files.isRegularFile(directory.resolve("optimized.csv"))
                        && saved.get("reportHash").getAsString().equals(hash(Files.readAllBytes(report)))
                        && verified(saved.getAsJsonObject("quality"))) {
                    return new Measurement(directory, saved.getAsJsonObject("quality"), budgetMs, saved.get("wallMs").getAsLong());
                }
            } catch (RuntimeException | IOException stale) {
                System.out.println("baseline 缓存不完整，重新评估该档预算。");
            }
        }
        return measure(nfp, input, root, budgetMs);
    }

    static boolean verified(JsonObject report) {
        if (report == null || !report.has("verification")
                || !report.getAsJsonObject("verification").has("passed")
                || !report.getAsJsonObject("verification").get("passed").getAsBoolean()) return false;
        for (String field : List.of("priorityBoardCount", "ordinaryBoardCount", "boardCount", "equivalentContainerCount", "actualAverageUtilization")) {
            if (!report.has(field) || !Double.isFinite(report.get(field).getAsDouble()) || report.get(field).getAsDouble() < 0) return false;
        }
        return report.has("unplacedWorkpieceCount") && report.get("unplacedWorkpieceCount").getAsInt() == 0
                && report.has("inputWorkpieceCount") && report.get("inputWorkpieceCount").getAsInt() > 0
                && report.has("placedWorkpieceCount")
                && report.get("inputWorkpieceCount").getAsInt() == report.get("placedWorkpieceCount").getAsInt();
    }

    /** 负值代表左侧更好；只允许调用方比较相同档位、已通过验证的结果。 */
    static int compare(JsonObject left, JsonObject right) {
        if (!verified(left) || !verified(right)) throw new IllegalArgumentException("不能比较未验证排样");
        for (String field : List.of("priorityBoardCount", "ordinaryBoardCount", "boardCount", "equivalentContainerCount")) {
            double delta = left.get(field).getAsDouble() - right.get(field).getAsDouble();
            if (Math.abs(delta) > 1e-9) return delta < 0 ? -1 : 1;
        }
        double delta = left.get("actualAverageUtilization").getAsDouble() - right.get("actualAverageUtilization").getAsDouble();
        return Math.abs(delta) <= 1e-9 ? 0 : delta > 0 ? -1 : 1;
    }

    /** 使用真实多边形面积重算最弱板，不把矩形包围盒面积或 Uagv 当作单板利用率。 */
    static WeakBoards weakBoards(Measurement measurement, NfpLocalPlan plan, Map<String, PolygonItem> items,
                                 double plateArea) throws IOException {
        Map<String, Double> blockArea = new HashMap<>();
        // describe 有提示长度限制；此处成员由 UPI 文本映射之外的完整 NFP 成员表提供。
        plan.actualAreas(items).forEach(blockArea::put);
        Map<String, Double> areas = new HashMap<>(); Map<String, Set<String>> members = new HashMap<>();
        List<String> lines = Files.readAllLines(measurement.directory.resolve("optimized.csv"));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] row = lines.get(i).split(",");
            if (row.length < 4) throw new IOException("optimized.csv 列数不足");
            String board = row[2].strip() + ":" + row[1].strip(), upi = row[3].strip();
            if (!blockArea.containsKey(upi)) throw new IOException("弱板 UPI 无法映射到 NFP: " + upi);
            areas.merge(board, blockArea.get(upi), Double::sum);
            members.computeIfAbsent(board, ignored -> new HashSet<>()).add(upi);
        }
        JsonArray boards = new JsonArray(); Set<String> ids = new HashSet<>();
        for (String board : areas.keySet().stream().sorted(Comparator.comparingDouble(areas::get).thenComparing(Object::toString)).limit(3).toList()) {
            JsonObject value = new JsonObject(); value.addProperty("board", board);
            value.addProperty("trueAreaUtilization", areas.get(board) / plateArea);
            JsonArray blocks = new JsonArray(); members.get(board).stream().sorted().forEach(blocks::add);
            value.add("blockIds", blocks); boards.add(value); ids.addAll(members.get(board));
        }
        return new WeakBoards(Set.copyOf(ids), boards);
    }

    static String hash(String text) { return hash(text.getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
